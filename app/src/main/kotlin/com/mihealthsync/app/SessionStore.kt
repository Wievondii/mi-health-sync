package com.mihealthsync.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.mihealthsync.cloud.MiCloud
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal sealed interface StoredSession {
    data object Missing : StoredSession
    data object SignedOut : StoredSession
    data class Connected(val session: MiCloud.Session) : StoredSession
    data class Expired(val notified: Boolean) : StoredSession
}

internal class SessionStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "mi-cloud-session"))
    private val keyStore by lazy { KeyStore.getInstance("AndroidKeyStore").apply { load(null) } }

    @Synchronized fun load(): StoredSession {
        val bytes = try { file.readFully() } catch (_: FileNotFoundException) { return StoredSession.Missing }
        if (bytes.size == 1) {
            when (bytes[0]) {
                SIGNED_OUT -> return StoredSession.SignedOut
                EXPIRED -> return StoredSession.Expired(notified = false)
                EXPIRED_NOTIFIED -> return StoredSession.Expired(notified = true)
            }
        }
        return runCatching {
            require(bytes.size > IV_SIZE + 16)
            val storedKey = keyStore.getKey(KEY_ALIAS, null) as SecretKey
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, storedKey, GCMParameterSpec(128, bytes.copyOfRange(0, IV_SIZE)))
            val plaintext = cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE)
            try { StoredSession.Connected(MiCloud.Session.decode(plaintext)) } finally { plaintext.fill(0) }
        }.getOrElse {
            markExpired()
            StoredSession.Expired(notified = false)
        }
    }

    @Synchronized fun save(session: MiCloud.Session) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        check(cipher.iv.size == IV_SIZE)
        val plaintext = session.encode()
        val encrypted = try { cipher.doFinal(plaintext) } finally { plaintext.fill(0) }
        write(cipher.iv + encrypted)
    }

    // 原子替换会话文件，避免退出进程时丢失过期原因或重新使用失效凭据。
    @Synchronized fun markExpired(notified: Boolean = false) {
        write(byteArrayOf(if (notified) EXPIRED_NOTIFIED else EXPIRED))
    }

    private fun write(bytes: ByteArray) {
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    @Synchronized fun clear() {
        write(byteArrayOf(SIGNED_OUT))
        keyStore.deleteEntry(KEY_ALIAS)
    }

    private fun key(): SecretKey {
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
            generateKey()
        }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_ALIAS = "mi-health-sync-session"
        const val IV_SIZE = 12
        const val SIGNED_OUT: Byte = 0
        const val EXPIRED: Byte = 1
        const val EXPIRED_NOTIFIED: Byte = 2
    }
}
