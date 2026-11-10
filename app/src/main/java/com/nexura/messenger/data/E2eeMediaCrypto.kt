package com.nexura.messenger.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.math.roundToInt
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class EncryptedMediaFile(
    val file: File,
    val mimeType: String,
    val fileName: String,
    val plainSize: Long,
    val keyBase64: String,
    val ivBase64: String,
    val cipherSha256: String,
    val cipherSize: Long
)

/**
 * Client-side AES-256-GCM file encryption. Only ciphertext is uploaded to Storage;
 * the key and IV are carried inside the Olm-encrypted message body.
 */
object E2eeMediaCrypto {
    const val MAX_PLAINTEXT_BYTES = 24L * 1024L * 1024L
    private const val BUFFER_SIZE = 64 * 1024

    fun encryptUri(
        resolver: ContentResolver,
        uri: Uri,
        cacheDir: File
    ): EncryptedMediaFile {
        val fileUri = uri.scheme == "file"
        val localFile = if (fileUri) uri.path?.let(::File) else null
        val mime = resolver.getType(uri)?.lowercase()
            ?: if (localFile?.extension?.lowercase() == "m4a" || localFile?.extension?.lowercase() == "aac") "audio/mp4"
            else throw IOException("Не удалось определить формат файла")
        val isImage = mime.startsWith("image/")
        val isAudio = mime.startsWith("audio/")
        if (!isImage && mime != "video/mp4" && mime != "audio/mp4") {
            throw IOException("Поддерживаются изображения, видео MP4 и аудио M4A")
        }
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }?.takeIf { it.isNotBlank() }?.replace(Regex("[^A-Za-z0-9._ -]"), "_")?.take(100)
            ?: localFile?.name?.take(100)
            ?: when { mime == "video/mp4" -> "video.mp4"; isAudio -> "voice.m4a"; else -> "photo" }

        val key = ByteArray(32).also(SecureRandom()::nextBytes)
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val optimizedImage = if (mime.startsWith("image/")) optimizeImage(resolver, uri, cacheDir, mime) else null
        val uploadMime = optimizedImage?.second ?: mime
        val uploadName = if (optimizedImage != null) {
            name.substringBeforeLast(".", name) + when (uploadMime) { "image/png" -> ".png"; "image/webp" -> ".webp"; else -> ".jpg" }
        } else name
        val encrypted = File.createTempFile("nexura-media-", ".enc", cacheDir)
        var total = 0L
        try {
            val input = if (optimizedImage != null) FileInputStream(optimizedImage.first)
                else if (localFile != null) FileInputStream(localFile)
                else resolver.openInputStream(uri) ?: throw IOException("Не удалось открыть файл")
            input.use { source ->
                CipherOutputStream(FileOutputStream(encrypted), cipher).use { target ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_PLAINTEXT_BYTES) throw IOException("Файл слишком большой. Максимум 24 МБ.")
                        target.write(buffer, 0, read)
                    }
                }
            }
            if (total == 0L) throw IOException("Файл пустой")
            return EncryptedMediaFile(
                encrypted, uploadMime, uploadName, total,
                Base64.encodeToString(key, Base64.NO_WRAP),
                Base64.encodeToString(iv, Base64.NO_WRAP),
                sha256(encrypted), encrypted.length()
            )
        } catch (error: Exception) {
            encrypted.delete()
            throw error
        } finally {
            optimizedImage?.first?.delete()
        }
    }

    private fun optimizeImage(resolver: ContentResolver, uri: Uri, cacheDir: File, mime: String): Pair<File, String> {
        // ImageDecoder supports modern Android image codecs (including HEIC on supported versions).
        // Older Android versions use the BitmapFactory fallback.
        val bitmap = try {
            if (Build.VERSION.SDK_INT >= 28) {
                val source = ImageDecoder.createSource(resolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val width = info.size.width.coerceAtLeast(1)
                    val height = info.size.height.coerceAtLeast(1)
                    val scale = minOf(1f, 1920f / maxOf(width, height))
                    decoder.setTargetSize(
                        (width * scale).roundToInt().coerceAtLeast(1),
                        (height * scale).roundToInt().coerceAtLeast(1)
                    )
                }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                    ?: throw IOException("Галерея не предоставила доступ к изображению")
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    throw IOException("Формат изображения не поддерживается на этой версии Android")
                }
                val largest = maxOf(bounds.outWidth, bounds.outHeight)
                var sample = 1
                while (largest / (sample * 2) >= 1920) sample *= 2
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                    ?: throw IOException("Не удалось декодировать изображение из галереи")
            }
        } catch (error: Exception) {
            if (error is IOException) throw error
            throw IOException("Не удалось прочитать изображение из галереи: " + (error.message ?: error.javaClass.simpleName), error)
        }

        val outputMime = when (mime) {
            "image/png" -> "image/png"
            "image/webp" -> "image/webp"
            else -> "image/jpeg"
        }
        val extension = when (outputMime) {
            "image/png" -> ".png"
            "image/webp" -> ".webp"
            else -> ".jpg"
        }
        val output = File.createTempFile("nexura-image-", extension, cacheDir)
        try {
            val format = when (outputMime) {
                "image/png" -> Bitmap.CompressFormat.PNG
                "image/webp" -> if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
                else -> Bitmap.CompressFormat.JPEG
            }
            FileOutputStream(output).use { stream ->
                val quality = if (outputMime == "image/png") 100 else 82
                if (!bitmap.compress(format, quality, stream)) {
                    throw IOException("Не удалось подготовить изображение к отправке")
                }
            }
            return output to outputMime
        } catch (error: Exception) {
            output.delete()
            throw error
        } finally {
            bitmap.recycle()
        }
    }

    fun decryptFile(
        encryptedFile: File,
        outputFile: File,
        keyBase64: String,
        ivBase64: String,
        expectedSha256: String
    ) {
        if (!constantTimeEquals(sha256(encryptedFile), expectedSha256)) {
            throw IOException("Контрольная сумма медиа не совпадает")
        }
        val key = try { Base64.decode(keyBase64, Base64.NO_WRAP) } catch (_: Exception) {
            throw IOException("Некорректный ключ медиа")
        }
        val iv = try { Base64.decode(ivBase64, Base64.NO_WRAP) } catch (_: Exception) {
            throw IOException("Некорректный IV медиа")
        }
        if (key.size != 32 || iv.size != 12) throw IOException("Некорректные параметры AES-GCM")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        try {
            FileInputStream(encryptedFile).use { source ->
                FileOutputStream(outputFile).use { target ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        val plainChunk = cipher.update(buffer, 0, read)
                        if (plainChunk != null && plainChunk.isNotEmpty()) {
                            total += plainChunk.size
                            if (total > MAX_PLAINTEXT_BYTES) throw IOException("Расшифрованный файл превышает лимит")
                            target.write(plainChunk)
                        }
                    }
                    // GCM authentication is checked here. Never expose the file
                    // until doFinal succeeds and verifies the 128-bit tag.
                    val finalChunk = cipher.doFinal()
                    if (finalChunk.isNotEmpty()) {
                        total += finalChunk.size
                        if (total > MAX_PLAINTEXT_BYTES) throw IOException("Расшифрованный файл превышает лимит")
                        target.write(finalChunk)
                    }
                    target.fd.sync()
                    if (total == 0L) throw IOException("Расшифрованный файл пустой")
                }
            }
        } catch (error: Exception) {
            outputFile.delete()
            throw IOException("Не удалось проверить или расшифровать медиафайл", error)
        }
    }

    fun parsePayload(raw: String): JSONObject? = runCatching {
        val json = JSONObject(raw)
        if (json.optString("schema") != "nexura-media-v1") return null
        val path = json.optString("path")
        val mime = json.optString("mime")
        val kind = json.optString("media_type")
        val key = json.optString("key")
        val iv = json.optString("iv")
        val hash = json.optString("cipher_sha256")
        val uuid = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
        require(path.matches(Regex("^$uuid/$uuid/$uuid\\.enc$")))
        require(mime in setOf("image/jpeg", "image/png", "image/webp", "video/mp4", "audio/mp4"))
        require(kind == when { mime.startsWith("video/") -> "video"; mime.startsWith("audio/") -> "audio"; else -> "image" })
        require(key.isNotBlank() && iv.isNotBlank() && hash.matches(Regex("^[0-9a-f]{64}$")))
        require(Base64.decode(key, Base64.NO_WRAP).size == 32)
        require(Base64.decode(iv, Base64.NO_WRAP).size == 12)
        require(json.optLong("size", -1L) in 1..MAX_PLAINTEXT_BYTES)
        require(json.optString("name").isNotBlank() && json.optString("name").length <= 100)
        json
    }.getOrNull()

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun constantTimeEquals(left: String, right: String): Boolean {
        if (left.length != right.length) return false
        var diff = 0
        for (index in left.indices) diff = diff or (left[index].code xor right[index].code)
        return diff == 0
    }
}
