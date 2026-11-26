package com.nexura.messenger.media

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import dev.ffmpegkit.whisper.Whisper
import dev.ffmpegkit.whisper.WhisperConfig
import org.vosk.Model
import org.vosk.Recognizer
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

/**
 * Offline Russian voice-message transcription.
 *
 * The encrypted attachment is already decrypted into the app's private media cache before
 * this class is called. Audio decoding and recognition stay on-device; only the first-use
 * language-model download uses the network.
 */
object AudioTranscriber {
    private const val SAMPLE_RATE = 16_000
    private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip"
    private const val MODEL_FOLDER = "vosk-model-small-ru-0.22"
    private const val WHISPER_MODEL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/fb5ed9369c26eaf08392504d8446da431e0fffb9/ggml-base.bin"
    private const val WHISPER_MODEL_NAME = "ggml-base.bin"
    private const val WHISPER_MODEL_BYTES = 147_951_465L
    private const val WHISPER_MODEL_SHA256 = "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe"
    private const val MAX_WAV_DATA_BYTES = 512L * 1024L * 1024L
    private const val MAX_AUDIO_BYTES = 25L * 1024L * 1024L
    private const val MAX_ARCHIVE_BYTES = 100L * 1024L * 1024L
    private const val MAX_EXTRACTED_BYTES = 250L * 1024L * 1024L
    private val modelMutex = Mutex()
    private val recognitionMutex = Mutex()

    suspend fun transcribe(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        var temporaryAudio: File? = null
        try {
            val audioFile = when (uri.scheme?.lowercase()) {
                "file" -> File(uri.path ?: throw IOException("Не найден локальный аудиофайл"))
                "content" -> {
                    val copied = File.createTempFile("nexura-transcribe-", ".audio", app.cacheDir)
                    temporaryAudio = copied
                    val input = app.contentResolver.openInputStream(uri)
                        ?: throw IOException("Не удалось открыть голосовое сообщение")
                    input.use { source ->
                        copied.outputStream().buffered().use { output ->
                            copyBounded(source, output, MAX_AUDIO_BYTES)
                        }
                    }
                    copied
                }
                "http", "https" -> {
                    val copied = File.createTempFile("nexura-transcribe-", ".audio", app.cacheDir)
                    temporaryAudio = copied
                    downloadAudio(uri, copied)
                    copied
                }
                else -> throw IOException("Источник аудио не поддерживается: ${uri.scheme ?: "без схемы"}")
            }

            if (!audioFile.isFile || audioFile.length() <= 0L) {
                throw IOException("Файл голосового сообщения пуст или недоступен")
            }
            if (audioFile.length() > MAX_AUDIO_BYTES) {
                throw IOException("Голосовое сообщение превышает лимит расшифровки 25 МБ")
            }
            // Whisper Base is multilingual and more accurate than the legacy small Russian-only Vosk model.
            if (Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }) {
                val modelFile = modelMutex.withLock { ensureWhisperModel(app) }
                return@withContext recognitionMutex.withLock {
                    val wavFile = File.createTempFile("nexura-whisper-", ".wav", app.cacheDir)
                    try {
                        decodeToWav(audioFile, wavFile)
                        val whisperModel = Whisper.loadModel(app, modelFile.absolutePath)
                        try {
                            val result = Whisper.transcribe(
                                whisperModel,
                                wavFile.absolutePath,
                                WhisperConfig(
                                    language = "auto",
                                    threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4),
                                    printTimestamps = false
                                )
                            )
                            result.text.trim().ifBlank {
                                throw IOException("Не удалось распознать речь в этом сообщении")
                            }
                        } finally {
                            Whisper.releaseModel(whisperModel)
                        }
                    } finally {
                        wavFile.delete()
                    }
                }
            }
            // Compatibility fallback for devices without arm64 support.
            val modelDirectory = modelMutex.withLock { ensureModel(app) }
            recognitionMutex.withLock {
                val model = Model(modelDirectory.absolutePath)
                try {
                    val recognizer = Recognizer(model, SAMPLE_RATE.toFloat())
                    try {
                        val transcript = decodeAndRecognize(audioFile, recognizer)
                        if (transcript.isBlank()) throw IOException("Не удалось распознать речь в этом сообщении")
                        transcript
                    } finally {
                        recognizer.close()
                    }
                } finally {
                    model.close()
                }
            }
        } finally {
            temporaryAudio?.delete()
        }
    }

    private fun downloadAudio(uri: Uri, destination: File) {
        val connection = (URL(uri.toString()).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Accept", "audio/*,application/octet-stream;q=0.9,*/*;q=0.5")
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                throw IOException("Не удалось получить аудио из хранилища (HTTP $status)")
            }
            if (connection.contentLengthLong > MAX_AUDIO_BYTES) {
                throw IOException("Голосовое сообщение превышает лимит расшифровки 25 МБ")
            }
            connection.inputStream.use { input ->
                destination.outputStream().buffered().use { output ->
                    copyBounded(input, output, MAX_AUDIO_BYTES)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun copyBounded(input: java.io.InputStream, output: java.io.OutputStream, limit: Long) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) {
                throw IOException("Голосовое сообщение превышает лимит расшифровки 25 МБ")
            }
            output.write(buffer, 0, read)
        }
    }

    private fun ensureModel(context: Context): File {
        val modelRoot = File(context.filesDir, "nexura-voice-models")
        if (!modelRoot.exists() && !modelRoot.mkdirs()) {
            throw IOException("Не удалось подготовить место для языковой модели")
        }
        val installed = File(modelRoot, MODEL_FOLDER)
        if (File(installed, "am/final.mdl").isFile && File(installed, "conf/model.conf").isFile) {
            return installed
        }
        installed.deleteRecursively()

        val archive = File(modelRoot, "ru-model.zip.part")
        val staging = File(modelRoot, "ru-model-extract")
        archive.delete()
        staging.deleteRecursively()
        if (!staging.mkdirs()) throw IOException("Не удалось подготовить распаковку модели")

        try {
            val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 25_000
                readTimeout = 90_000
                instanceFollowRedirects = true
            }
            try {
                val status = connection.responseCode
                if (status !in 200..299) throw IOException("Не удалось загрузить модель распознавания (HTTP $status)")
                if (connection.contentLengthLong > MAX_ARCHIVE_BYTES) {
                    throw IOException("Архив языковой модели имеет неожиданный размер")
                }
                connection.inputStream.use { input ->
                    BufferedInputStream(input).use { source ->
                        BufferedOutputStream(FileOutputStream(archive)).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                val read = source.read(buffer)
                                if (read < 0) break
                                total += read
                                if (total > MAX_ARCHIVE_BYTES) throw IOException("Архив языковой модели слишком большой")
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }

            var extractedBytes = 0L
            ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val destination = File(staging, entry.name).canonicalFile
                    val stagingPath = staging.canonicalPath + File.separator
                    if (!destination.canonicalPath.startsWith(stagingPath) && destination != staging) {
                        throw IOException("Архив модели содержит недопустимый путь")
                    }
                    if (entry.isDirectory) {
                        if (!destination.exists() && !destination.mkdirs()) {
                            throw IOException("Не удалось распаковать языковую модель")
                        }
                    } else {
                        destination.parentFile?.let { if (!it.exists() && !it.mkdirs()) {
                            throw IOException("Не удалось создать папку языковой модели")
                        } }
                        FileOutputStream(destination).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = zip.read(buffer)
                                if (read < 0) break
                                extractedBytes += read
                                if (extractedBytes > MAX_EXTRACTED_BYTES) {
                                    throw IOException("Распакованная языковая модель превышает допустимый размер")
                                }
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }

            val modelDirectory = staging.listFiles()?.firstOrNull {
                it.isDirectory && File(it, "am/final.mdl").isFile && File(it, "conf/model.conf").isFile
            } ?: throw IOException("В архиве отсутствует корректная русская языковая модель")

            if (!modelDirectory.renameTo(installed)) {
                throw IOException("Не удалось установить языковую модель")
            }
            return installed
        } catch (error: Exception) {
            installed.deleteRecursively()
            throw if (error is IOException) error else IOException("Ошибка подготовки распознавания: ${error.message}", error)
        } finally {
            archive.delete()
            staging.deleteRecursively()
        }
    }


    private fun ensureWhisperModel(context: Context): File {
        val root = File(context.filesDir, "nexura-voice-models")
        if (!root.exists() && !root.mkdirs()) throw IOException("Не удалось подготовить место для модели распознавания")
        val model = File(root, WHISPER_MODEL_NAME)
        if (model.isFile && model.length() == WHISPER_MODEL_BYTES && sha256(model) == WHISPER_MODEL_SHA256) return model
        model.delete()
        val partial = File(root, "$WHISPER_MODEL_NAME.part")
        partial.delete()
        val connection = (URL(WHISPER_MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 25_000
            readTimeout = 120_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Nexura-Android")
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("Не удалось загрузить модель Whisper (HTTP $status)")
            if (connection.contentLengthLong > WHISPER_MODEL_BYTES) throw IOException("Сервер вернул файл модели неожиданного размера")
            var total = 0L
            connection.inputStream.use { input ->
                partial.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > WHISPER_MODEL_BYTES) throw IOException("Файл модели Whisper превышает ожидаемый размер")
                        output.write(buffer, 0, count)
                    }
                }
            }
            if (total != WHISPER_MODEL_BYTES || sha256(partial) != WHISPER_MODEL_SHA256) {
                throw IOException("Проверка целостности модели Whisper не пройдена. Повторите загрузку")
            }
            if (!partial.renameTo(model)) throw IOException("Не удалось сохранить модель Whisper")
            return model
        } finally {
            connection.disconnect()
            partial.delete()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun decodeToWav(source: File, destination: File) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var sink: WavSink? = null
        var completed = false
        try {
            extractor.setDataSource(source.absolutePath)
            val audioTrack = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("В файле не найдена аудиодорожка")
            extractor.selectTrack(audioTrack)
            val sourceFormat = extractor.getTrackFormat(audioTrack)
            val mime = sourceFormat.getString(MediaFormat.KEY_MIME)
                ?: throw IOException("Не удалось определить формат голосового сообщения")
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(sourceFormat, null, null, 0)
            decoder.start()
            var inputEnded = false
            var outputEnded = false
            var outputFormat = sourceFormat
            val info = MediaCodec.BufferInfo()
            sink = WavSink(destination)
            while (!outputEnded) {
                if (!inputEnded) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputIndex)
                            ?: throw IOException("Не удалось получить входной аудиобуфер")
                        inputBuffer.clear()
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, extractor.sampleFlags)
                            extractor.advance()
                        }
                    }
                }
                when (val outputIndex = decoder.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat = decoder.outputFormat
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> if (outputIndex >= 0) {
                        val outputBuffer = decoder.getOutputBuffer(outputIndex)
                        if (info.size > 0 && outputBuffer != null) {
                            outputBuffer.position(info.offset)
                            outputBuffer.limit(info.offset + info.size)
                            sink.consume(outputBuffer.slice().order(ByteOrder.LITTLE_ENDIAN), outputFormat)
                        }
                        decoder.releaseOutputBuffer(outputIndex, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputEnded = true
                    }
                }
            }
            sink.finish()
            completed = true
        } catch (error: Exception) {
            throw if (error is IOException) error else IOException(
                "Не удалось подготовить аудио для Whisper: \${error.message}", error
            )
        } finally {
            if (!completed) sink?.abort()
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
        }
    }

    private class WavSink(private val file: File) {
        private val output = FileOutputStream(file)
        private var sampleCount = 0L
        private var inputIndex = -1L
        private var nextOutputPosition = 0.0
        private var previousSample = 0f
        private var closed = false

        init { output.write(ByteArray(44)) }

        fun consume(buffer: ByteBuffer, format: MediaFormat) {
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(1)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                format.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else AudioFormat.ENCODING_PCM_16BIT
            val bytesPerSample = when (encoding) {
                AudioFormat.ENCODING_PCM_FLOAT -> 4
                AudioFormat.ENCODING_PCM_8BIT -> 1
                else -> 2
            }
            val frames = buffer.remaining() / (bytesPerSample * channels)
            val step = sampleRate.toDouble() / SAMPLE_RATE.toDouble()
            repeat(frames) {
                var mixed = 0f
                repeat(channels) {
                    val sample = when (encoding) {
                        AudioFormat.ENCODING_PCM_FLOAT -> buffer.float
                        AudioFormat.ENCODING_PCM_8BIT -> ((buffer.get().toInt() and 0xFF) - 128) / 128f
                        else -> buffer.short / 32768f
                    }
                    mixed += sample
                }
                val mono = (mixed / channels).coerceIn(-1f, 1f)
                if (inputIndex < 0L) {
                    inputIndex = 0L
                    previousSample = mono
                    push(mono)
                    nextOutputPosition = step
                } else {
                    inputIndex++
                    while (nextOutputPosition <= inputIndex.toDouble()) {
                        val fraction = (nextOutputPosition - (inputIndex - 1).toDouble()).coerceIn(0.0, 1.0)
                        push(previousSample + (mono - previousSample) * fraction.toFloat())
                        nextOutputPosition += step
                    }
                    previousSample = mono
                }
            }
        }

        private fun push(value: Float) {
            if (sampleCount * 2L >= MAX_WAV_DATA_BYTES) throw IOException("Голосовое сообщение слишком длинное для обработки на устройстве")
            val sample = (value * 32767f).toInt().coerceIn(-32768, 32767)
            output.write(sample and 0xFF)
            output.write((sample shr 8) and 0xFF)
            sampleCount++
        }

        fun finish() {
            if (sampleCount == 0L) throw IOException("В голосовом сообщении нет аудиоданных")
            output.flush()
            output.close()
            closed = true
            val dataBytes = (sampleCount * 2L).toInt()
            RandomAccessFile(file, "rw").use { wav ->
                wav.seek(0)
                wav.write("RIFF".toByteArray(Charsets.US_ASCII))
                writeIntLe(wav, 36 + dataBytes)
                wav.write("WAVEfmt ".toByteArray(Charsets.US_ASCII))
                writeIntLe(wav, 16)
                writeShortLe(wav, 1)
                writeShortLe(wav, 1)
                writeIntLe(wav, SAMPLE_RATE)
                writeIntLe(wav, SAMPLE_RATE * 2)
                writeShortLe(wav, 2)
                writeShortLe(wav, 16)
                wav.write("data".toByteArray(Charsets.US_ASCII))
                writeIntLe(wav, dataBytes)
            }
        }

        fun abort() {
            if (!closed) runCatching { output.close() }
            file.delete()
        }

        private fun writeIntLe(file: RandomAccessFile, value: Int) {
            file.write(value and 0xFF)
            file.write((value ushr 8) and 0xFF)
            file.write((value ushr 16) and 0xFF)
            file.write((value ushr 24) and 0xFF)
        }

        private fun writeShortLe(file: RandomAccessFile, value: Int) {
            file.write(value and 0xFF)
            file.write((value ushr 8) and 0xFF)
        }
    }

    private fun decodeAndRecognize(file: File, recognizer: Recognizer): String {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val audioTrack = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("В файле не найдена аудиодорожка")
            extractor.selectTrack(audioTrack)
            val sourceFormat = extractor.getTrackFormat(audioTrack)
            val mime = sourceFormat.getString(MediaFormat.KEY_MIME)
                ?: throw IOException("Не удалось определить формат голосового сообщения")
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(sourceFormat, null, null, 0)
            decoder.start()

            var inputEnded = false
            var outputEnded = false
            var outputFormat = sourceFormat
            val info = MediaCodec.BufferInfo()
            val sink = PcmSink(recognizer)

            while (!outputEnded) {
                if (!inputEnded) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputIndex)
                            ?: throw IOException("Не удалось получить входной аудиобуфер")
                        inputBuffer.clear()
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(
                                inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputEnded = true
                        } else {
                            decoder.queueInputBuffer(
                                inputIndex, 0, sampleSize, extractor.sampleTime, extractor.sampleFlags
                            )
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = decoder.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat = decoder.outputFormat
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> if (outputIndex >= 0) {
                        val outputBuffer = decoder.getOutputBuffer(outputIndex)
                        if (info.size > 0 && outputBuffer != null) {
                            outputBuffer.position(info.offset)
                            outputBuffer.limit(info.offset + info.size)
                            sink.consume(outputBuffer.slice().order(ByteOrder.LITTLE_ENDIAN), outputFormat)
                        }
                        decoder.releaseOutputBuffer(outputIndex, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputEnded = true
                        }
                    }
                }
            }
            return sink.finish()
        } catch (error: Exception) {
            throw if (error is IOException) error else IOException(
                "Не удалось декодировать аудио для расшифровки: ${error.message}", error
            )
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
        }
    }

    private class PcmSink(private val recognizer: Recognizer) {
        private val chunk = ShortArray(4_096)
        private var chunkSize = 0
        private var resamplePhase = 0.0
        private val transcriptParts = StringBuilder()

        fun consume(buffer: ByteBuffer, format: MediaFormat) {
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(1)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                format.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else AudioFormat.ENCODING_PCM_16BIT
            val bytesPerSample = when (encoding) {
                AudioFormat.ENCODING_PCM_FLOAT -> 4
                AudioFormat.ENCODING_PCM_8BIT -> 1
                else -> 2
            }
            val frameBytes = bytesPerSample * channels
            val frames = buffer.remaining() / frameBytes
            repeat(frames) {
                var mixed = 0f
                repeat(channels) {
                    val sample = when (encoding) {
                        AudioFormat.ENCODING_PCM_FLOAT -> buffer.getFloat()
                        AudioFormat.ENCODING_PCM_8BIT ->
                            (((buffer.get().toInt() and 0xFF) - 128) / 128f)
                        else -> buffer.getShort() / 32768f
                    }
                    mixed += sample
                }
                val mono = (mixed / channels).coerceIn(-1f, 1f)
                resamplePhase += SAMPLE_RATE.toDouble() / sampleRate.toDouble()
                while (resamplePhase >= 1.0) {
                    push((mono * 32767f).toInt().coerceIn(-32768, 32767).toShort())
                    resamplePhase -= 1.0
                }
            }
        }

        private fun push(sample: Short) {
            chunk[chunkSize++] = sample
            if (chunkSize == chunk.size) flush()
        }

        private fun flush() {
            if (chunkSize == 0) return
            if (recognizer.acceptWaveForm(chunk, chunkSize)) {
                appendResult(recognizer.result)
            }
            chunkSize = 0
        }

        fun finish(): String {
            flush()
            appendResult(recognizer.finalResult)
            return transcriptParts.toString().trim().replace(Regex("\\s+"), " ")
        }

        private fun appendResult(json: String) {
            val text = runCatching { JSONObject(json).optString("text").trim() }.getOrDefault("")
            if (text.isNotBlank()) {
                if (transcriptParts.isNotEmpty()) transcriptParts.append(' ')
                transcriptParts.append(text)
            }
        }
    }
}
