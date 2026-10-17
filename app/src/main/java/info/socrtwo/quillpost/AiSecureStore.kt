package info.socrtwo.quillpost

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secret storage for the AI tab's API keys: each value is encrypted with AES-256-GCM under a key
 * that is generated inside the Android Keystore and never leaves it; only the IV and ciphertext
 * are kept, in this app's private SharedPreferences ("quillpost.ai.secure"). The web UI reaches it
 * through QuillPostAndroid.secureGet / secureSet / secureRemove (MainActivity.Bridge).
 *
 * If the Keystore key is lost (for example after a backup is restored to another device), old
 * values cannot be decrypted and read as absent; the user adds the key again.
 */
internal object AiSecureStore {
    private const val KEY_ALIAS = "quillpost.ai.secrets.v1"
    private const val PREFS = "quillpost.ai.secure"
    /** Only AI provider keys are stored here: "ai.key.<provider id>". */
    private val NAME = Regex("^ai\\.key\\.[a-z0-9_-]{1,40}$")

    fun validName(name: String) = NAME.matches(name)

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return kg.generateKey()
    }

    private fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun put(ctx: Context, name: String, value: String) {
        require(validName(name)) { "bad key name" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, secretKey())
        val ct = c.doFinal(value.toByteArray(Charsets.UTF_8))
        val stored = Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
        prefs(ctx).edit().putString(name, stored).apply()
    }

    fun get(ctx: Context, name: String): String? {
        if (!validName(name)) return null
        val stored = prefs(ctx).getString(name, null) ?: return null
        val sep = stored.indexOf(':')
        if (sep < 0) return null
        return runCatching {
            val iv = Base64.decode(stored.substring(0, sep), Base64.NO_WRAP)
            val ct = Base64.decode(stored.substring(sep + 1), Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(c.doFinal(ct), Charsets.UTF_8)
        }.getOrNull()
    }

    fun remove(ctx: Context, name: String) {
        if (validName(name)) prefs(ctx).edit().remove(name).apply()
    }
}
