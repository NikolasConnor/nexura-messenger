package com.nexura.messenger.media

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
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
            }
            if (!audioFile.isFile || audioFile.length() <= 0L) {
                throw IOException("Файл голосового сообщения пуст или недоступен")
            }
            if (audioFile.length() > MAX_AUDIO_BYTES) {
                throw IOException("Голосовое сообщение превышает лимит расшифровки 25 МБ")
            }
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
