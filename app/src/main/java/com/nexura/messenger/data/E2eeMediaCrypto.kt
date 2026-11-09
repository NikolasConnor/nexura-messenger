package com.nexura.messenger.data

import android.content.ContentResolver
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
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
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
        val mime = resolver.getType(uri)?.lowercase()
            ?: throw IOException("Не удалось определить формат файла")
        val allowed = mime in setOf("image/jpeg", "image/png", "image/webp", "video/mp4")
        if (!allowed) throw IOException("Поддерживаются JPG, PNG, WebP и MP4")
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }?.takeIf { it.isNotBlank() }?.replace(Regex("[^A-Za-z0-9._ -]"), "_")?.take(100)
            ?: if (mime == "video/mp4") "video.mp4" else "photo"

        val key = ByteArray(32).also(SecureRandom()::nextBytes)
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val encrypted = File.createTempFile("nexura-media-", ".enc", cacheDir)
        var total = 0L
        try {
            val input = resolver.openInputStream(uri) ?: throw IOException("Не удалось открыть файл")
            input.use { source ->
                CipherOutputStream(FileOutputStream(encrypted), cipher).use { target ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_PLAINTEXT_BYTES) {
                            throw IOException("Файл слишком большой. Максимум 24 МБ.")
                        }
                        target.write(buffer, 0, read)
                    }
                }
            }
            if (total == 0L) throw IOException("Файл пустой")
            return EncryptedMediaFile(
                encrypted, mime, name, total,
                Base64.encodeToString(key, Base64.NO_WRAP),
                Base64.encodeToString(iv, Base64.NO_WRAP),
                sha256(encrypted), encrypted.length()
            )
        } catch (error: Exception) {
            encrypted.delete()
            throw error
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
            CipherInputStream(FileInputStream(encryptedFile), cipher).use { source ->
                FileOutputStream(outputFile).use { target ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_PLAINTEXT_BYTES) throw IOException("Расшифрованный файл превышает лимит")
                        target.write(buffer, 0, read)
                    }
                }
            }
            if (outputFile.length() == 0L) throw IOException("Расшифрованный файл пустой")
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
        require(path.matches(Regex("^[0-9a-fA-F-]{36}/[0-9a-fA-F-]{36}/[0-9a-fA-F-]{36}\\.enc$")))
        require(mime in setOf("image/jpeg", "image/png", "image/webp", "video/mp4"))
        require(kind == if (mime.startsWith("video/")) "video" else "image")
        require(key.isNotBlank() && iv.isNotBlank() && hash.matches(Regex("^[0-9a-f]{64}$")))
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
