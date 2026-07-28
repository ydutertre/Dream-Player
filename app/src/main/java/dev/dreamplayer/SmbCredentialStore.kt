package dev.dreamplayer

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts remembered SMB credentials with an app-private Android Keystore key. */
class SmbCredentialStore(context: Context) {
    private val preferences = context.getSharedPreferences("smb_credentials", Context.MODE_PRIVATE)

    fun isRemembered(): Boolean = preferences.contains(CIPHERTEXT)

    fun save(credentials: SmbCredentials) {
        val plain = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeUTF(credentials.host)
                output.writeUTF(credentials.username)
                output.writeUTF(credentials.password)
                output.writeUTF(credentials.domain)
                output.writeUTF(credentials.connectionHost)
            }
            bytes.toByteArray()
        }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, getOrCreateKey()) }
        preferences.edit()
            .putString(IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(CIPHERTEXT, Base64.encodeToString(cipher.doFinal(plain), Base64.NO_WRAP))
            .apply()
    }

    fun load(): SmbCredentials? = runCatching {
        val iv = Base64.decode(preferences.getString(IV, null), Base64.NO_WRAP)
        val encrypted = Base64.decode(preferences.getString(CIPHERTEXT, null), Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        }
        DataInputStream(ByteArrayInputStream(cipher.doFinal(encrypted))).use { input ->
            val host = input.readUTF()
            val username = input.readUTF()
            val password = input.readUTF()
            val domain = input.readUTF()
            val connectionHost = if (input.available() > 0) input.readUTF() else host
            SmbCredentials(host, "", username, password, domain, connectionHost)
        }
    }.getOrNull()

    fun clear() {
        preferences.edit().clear().apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }

    companion object {
        private const val KEY_ALIAS = "dream_player_smb_credentials"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV = "iv"
        private const val CIPHERTEXT = "ciphertext"
    }
}
