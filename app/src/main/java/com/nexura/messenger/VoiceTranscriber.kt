package com.nexura.messenger

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * On-device Russian voice-message transcription.
 *
 * The acoustic model is downloaded once into the app's private files directory.
 * Audio is decrypted locally by the chat layer and is never uploaded by this class.
 */
internal object VoiceTranscriber {
    private const val MODEL_NAME = "vosk-model-small-ru-0.22"
    private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip"
    private const val MAX_MODEL_ARCHIVE_BYTES = 90L * 1024L * 1024L
    private const val MAX_AUDIO_BYTES = 24L * 1024L * 1024L
    private const val MAX_AUDIO_DURATION_MS = 15L * 60L * 1000L

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.MINUTES)
            .callTimeout(4, TimeUnit.MINUTES)
            .build()
    }

    suspend fun transcribe(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val audioFile = resolveAudioFile(context, uri)
        try {
            if (!audioFile.isFile || audioFile.length() !in 1..MAX_AUDIO_BYTES) {
                throw IOException("Голосовое сообщение отсутствует или превышает лимит 24 МБ")
            }

            val modelDirectory = ensureModel(context)
            val model = Model(modelDirectory.absolutePath)
            try {
                transcribeFile(audioFile, model)
            } finally {
                model.close()
            }
        } finally {
            if (audioFile.parentFile == context.cacheDir && audioFile.name.startsWith("nexura-transcribe-")) {
                audioFile.delete()
            }
        }
    }

    private fun resolveAudioFile(context: Context, uri: Uri): File {
        if (uri.scheme == "file") {
            return File(uri.path ?: throw IOException("Не найден локальный аудиофайл"))
        }
        val temporary = File.createTempFile("nexura-transcribe-", ".audio", context.cacheDir)
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Не удалось открыть голосовое сообщение")
            input.use { source ->
                FileOutputStream(temporary).use { target ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_AUDIO_BYTES) throw IOException("Голосовое сообщение превышает лимит 24 МБ")
                        target.write(buffer, 0, count)
                    }
                    target.fd.sync()
                }
            }
            return temporary
        } catch (error: Exception) {
            temporary.delete()
            throw error
        }
    }

    private fun ensureModel(context: Context): File {
        val modelDirectory = File(context.filesDir, MODEL_NAME)
        if (File(modelDirectory, "conf/model.conf").isFile &&
            File(modelDirectory, "am/final.mdl").isFile) return modelDirectory

        val stagingDirectory = File(context.filesDir, "$MODEL_NAME.installing")
        stagingDirectory.deleteRecursively()
        if (!stagingDirectory.mkdirs()) throw IOException("Не удалось подготовить модель распознавания")

        val request = Request.Builder().url(MODEL_URL).get().build()
        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            stagingDirectory.deleteRecursively()
            throw IOException("Не удалось скачать модель распознавания речи (HTTP ${response.code})")
        }

        try {
            val body = response.body ?: throw IOException("Сервер не передал модель распознавания")
            val contentLength = body.contentLength()
            if (contentLength > MAX_MODEL_ARCHIVE_BYTES) throw IOException("Архив модели неожиданно большой")

            ZipInputStream(body.byteStream()).use { zip ->
                var entry = zip.nextEntry
                var extractedBytes = 0L
                while (entry != null) {
                    val normalized = entry.name.replace('\\\\', '/')
                    val prefix = "$MODEL_NAME/"
                    if (normalized.startsWith(prefix)) {
                        val relative = normalized.removePrefix(prefix)
                        if (relative.isNotBlank()) {
                            val destination = File(stagingDirectory, relative)
                            val rootPath = stagingDirectory.canonicalPath + File.separator
                            if (!destination.canonicalPath.startsWith(rootPath)) {
                                throw IOException("Архив модели содержит недопустимый путь")
                            }
                            if (entry.isDirectory) {
                                destination.mkdirs()
                            } else {
                                destination.parentFile?.mkdirs()
                                FileOutputStream(destination).use { output ->
                                    val buffer = ByteArray(64 * 1024)
                                    while (true) {
                                        val count = zip.read(buffer)
                                        if (count < 0) break
                                        extractedBytes += count
                                        if (extractedBytes > 180L * 1024L * 1024L) {
                                            throw IOException("Распакованная модель превышает допустимый размер")
                                        }
                                        output.write(buffer, 0, count)
                                    }
                                }
                            }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } catch (error: Exception) {
            stagingDirectory.deleteRecursively()
            throw if (error is IOException) error else IOException("Не удалось распаковать модель распознавания", error)
        } finally {
            response.close()
        }

        if (!File(stagingDirectory, "conf/model.conf").isFile ||
            !File(stagingDirectory, "am/final.mdl").isFile) {
            stagingDirectory.deleteRecursively()
            throw IOException("Архив модели распознавания повреждён")
        }
        modelDirectory.deleteRecursively()
        if (!stagingDirectory.renameTo(modelDirectory)) {
            stagingDirectory.deleteRecursively()
            throw IOException("Не удалось установить модель распознавания")
        }
        return modelDirectory
    }

    private fun transcribeFile(audioFile: File, model: Model): String {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var recognizer: Recognizer? = null
        try {
            extractor.setDataSource(audioFile.absolutePath)
            var audioTrack = -1
            var inputFormat: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTrack = index
                    inputFormat = format
                    break
                }
            }
            if (audioTrack < 0 || inputFormat == null) throw IOException("В файле не найдена аудиодорожка")

            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: throw IOException("Не удалось определить формат голосового сообщения")
            val inputSampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            if (inputSampleRate !in 8_000..96_000) throw IOException("Частота дискретизации аудио не поддерживается")

            extractor.selectTrack(audioTrack)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()
            recognizer = Recognizer(model, inputSampleRate.toFloat())

            val bufferInfo = MediaCodec.BufferInfo()
            var inputFinished = false
            var outputFinished = false
            var outputSampleRate = inputSampleRate
            var outputChannels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var decodedBytes = 0L

            while (!outputFinished) {
                if (!inputFinished) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                            ?: throw IOException("Декодер не предоставил входной буфер")
                        inputBuffer.clear()
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputFinished = true
                        } else {
                            val presentationTime = extractor.sampleTime.coerceAtLeast(0L)
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, presentationTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = codec.outputFormat
                        if (outputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            outputSampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (outputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            outputChannels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            pcmEncoding = outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                        if (outputSampleRate != inputSampleRate) {
                            throw IOException("Изменение частоты дискретизации аудио не поддерживается")
                        }
                    }
                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    outputIndex >= 0 -> {
                        if (bufferInfo.size > 0) {
                            val outputBuffer = codec.getOutputBuffer(outputIndex)
                                ?: throw IOException("Декодер не предоставил аудиоданные")
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            val decoded = ByteArray(bufferInfo.size)
                            outputBuffer.get(decoded)
                            val monoPcm = toMonoPcm16(decoded, outputChannels, pcmEncoding)
                            decodedBytes += monoPcm.size
                            val maxDecoded = outputSampleRate.toLong() * 2L * (MAX_AUDIO_DURATION_MS / 1000L)
                            if (decodedBytes > maxDecoded) throw IOException("Голосовое сообщение длиннее 15 минут")
                            recognizer.acceptWaveForm(monoPcm, monoPcm.size)
                        }
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputFinished = true
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }

            val text = JSONObject(recognizer.finalResult).optString("text").trim()
            if (text.isBlank()) throw IOException("В этом голосовом сообщении не удалось распознать речь")
            return text
        } catch (error: Exception) {
            throw if (error is IOException) error else IOException("Не удалось расшифровать голосовое сообщение", error)
        } finally {
            runCatching { recognizer?.close() }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun toMonoPcm16(bytes: ByteArray, channels: Int, encoding: Int): ByteArray {
        val channelCount = channels.coerceIn(1, 8)
        return when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> {
                val samples = bytes.size / 2
                if (channelCount == 1) return bytes
                val frames = samples / channelCount
                val output = ByteArray(frames * 2)
                for (frame in 0 until frames) {
                    var sum = 0L
                    for (channel in 0 until channelCount) {
                        val offset = (frame * channelCount + channel) * 2
                        val sample = ((bytes[offset].toInt() and 0xff) or (bytes[offset + 1].toInt() shl 8)).toShort().toInt()
                        sum += sample
                    }
                    val mono = (sum / channelCount).toShort().toInt()
                    output[frame * 2] = mono.toByte()
                    output[frame * 2 + 1] = (mono shr 8).toByte()
                }
                output
            }
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val floats = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                val frames = floats.limit() / channelCount
                val output = ByteArray(frames * 2)
                for (frame in 0 until frames) {
                    var sum = 0.0
                    for (channel in 0 until channelCount) sum += floats.get(frame * channelCount + channel).toDouble()
                    val sample = ((sum / channelCount).coerceIn(-1.0, 1.0) * 32767.0).toInt()
                    output[frame * 2] = sample.toByte()
                    output[frame * 2 + 1] = (sample shr 8).toByte()
                }
                output
            }
            AudioFormat.ENCODING_PCM_8BIT -> {
                val samples = bytes.size
                if (channelCount == 1) {
                    ByteArray(samples * 2).also { output ->
                        for (index in bytes.indices) {
                            val sample = ((bytes[index].toInt() and 0xff) - 128) shl 8
                            output[index * 2] = sample.toByte()
                            output[index * 2 + 1] = (sample shr 8).toByte()
                        }
                    }
                } else {
                    val frames = samples / channelCount
                    ByteArray(frames * 2).also { output ->
                        for (frame in 0 until frames) {
                            var sum = 0L
                            for (channel in 0 until channelCount) {
                                sum += (bytes[frame * channelCount + channel].toInt() and 0xff) - 128
                            }
                            val sample = ((sum / channelCount) shl 8).toInt()
                            output[frame * 2] = sample.toByte()
                            output[frame * 2 + 1] = (sample shr 8).toByte()
                        }
                    }
                }
            }
            else -> throw IOException("Декодер вернул неподдерживаемый формат PCM")
        }
    }
}
