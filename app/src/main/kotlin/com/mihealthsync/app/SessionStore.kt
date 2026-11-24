package com.mihealthsync.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.mihealthsync.cloud.MiCloud
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class SessionStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "mi-cloud-session"))
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Synchronized fun load(): MiCloud.Session? {
        if (!file.baseFile.exists()) return null
        return runCatching {
            val bytes = file.readFully()
            require(bytes.size > IV_SIZE + 16)
            val storedKey = keyStore.getKey(KEY_ALIAS, null) as SecretKey
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, storedKey, GCMParameterSpec(128, bytes.copyOfRange(0, IV_SIZE)))
            val plaintext = cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE)
            try { MiCloud.Session.decode(plaintext) } finally { plaintext.fill(0) }
        }.getOrElse {
            file.delete()
            null
        }
    }

    @Synchronized fun save(session: MiCloud.Session) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        check(cipher.iv.size == IV_SIZE)
        val plaintext = session.encode()
        val encrypted = try { cipher.doFinal(plaintext) } finally { plaintext.fill(0) }
        val stream = file.startWrite()
        try {
            stream.write(cipher.iv)
            stream.write(encrypted)
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    @Synchronized fun clear() {
        file.delete()
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
    }
}
