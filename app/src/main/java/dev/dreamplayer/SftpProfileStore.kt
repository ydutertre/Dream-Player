package dev.dreamplayer

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores SFTP profiles as one authenticated, encrypted document. Passwords are
 * omitted for profiles whose "remember password" option is disabled.
 */
class SftpProfileStore(context: Context) {
    private val preferences =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): List<SftpProfile> = runCatching {
        val ivText = preferences.getString(IV, null) ?: return emptyList()
        val ciphertextText =
            preferences.getString(CIPHERTEXT, null) ?: return emptyList()
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, Base64.decode(ivText, Base64.NO_WRAP)),
            )
        }
        val json = cipher.doFinal(
            Base64.decode(ciphertextText, Base64.NO_WRAP)
        ).toString(Charsets.UTF_8)
        val array = JSONArray(json)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    SftpProfile(
                        id = item.optString("id").ifBlank {
                            java.util.UUID.randomUUID().toString()
                        },
                        name = item.optString("name"),
                        host = item.optString("host"),
                        port = item.optInt("port", SftpProfile.DEFAULT_PORT),
                        username = item.optString("username"),
                        password = item.optString("password"),
                        startPath = item.optString("startPath", "."),
                        hostKeyFingerprint =
                            item.optString("hostKeyFingerprint"),
                        rememberPassword =
                            item.optBoolean("rememberPassword", true),
                        protocol = RemoteProtocol.fromStored(
                            item.optString(
                                "protocol",
                                RemoteProtocol.SFTP.name,
                            )
                        ),
                        allowInsecureFtp =
                            item.optBoolean("allowInsecureFtp", false),
                    ).normalized()
                )
            }
        }
    }.getOrElse { emptyList() }

    fun save(profiles: List<SftpProfile>) {
        val array = JSONArray()
        profiles.forEach { raw ->
            val profile = raw.normalized()
            array.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("name", profile.name)
                    .put("host", profile.host)
                    .put("port", profile.port)
                    .put("username", profile.username)
                    .put(
                        "password",
                        profile.password.takeIf { profile.rememberPassword }
                            .orEmpty(),
                    )
                    .put("startPath", profile.startPath)
                    .put(
                        "hostKeyFingerprint",
                        profile.hostKeyFingerprint,
                    )
                    .put("rememberPassword", profile.rememberPassword)
                    .put("protocol", profile.protocol.name)
                    .put("allowInsecureFtp", profile.allowInsecureFtp)
            )
        }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        }
        preferences.edit()
            .putString(IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(
                CIPHERTEXT,
                Base64.encodeToString(
                    cipher.doFinal(array.toString().toByteArray(Charsets.UTF_8)),
                    Base64.NO_WRAP,
                ),
            )
            .apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
        }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore",
        ).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or
                        KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }

    companion object {
        private const val PREFERENCES = "sftp_profiles"
        private const val KEY_ALIAS = "dream_player_sftp_profiles"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV = "iv"
        private const val CIPHERTEXT = "ciphertext"
    }
}
