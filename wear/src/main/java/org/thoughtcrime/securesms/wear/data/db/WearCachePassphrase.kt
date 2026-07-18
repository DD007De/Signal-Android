package org.thoughtcrime.securesms.wear.data.db

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

/**
 * Generates and persists the SQLCipher passphrase used by [WearCacheDatabase].
 *
 * The passphrase is a random 32-byte key, generated once, then stored base64-encoded inside
 * an [EncryptedSharedPreferences] file that is itself protected by an Android Keystore-backed
 * master key. Nothing about the plaintext database contents is ever written outside SQLCipher.
 */
internal object WearCachePassphrase {
  private const val PREFS_FILE_NAME = "wear_cache_secrets"
  private const val KEY_PASSPHRASE = "db_passphrase"
  private const val PASSPHRASE_LENGTH_BYTES = 32

  @Synchronized
  fun getOrCreate(context: Context): ByteArray {
    val prefs = encryptedPrefs(context)
    val existing = prefs.getString(KEY_PASSPHRASE, null)
    if (existing != null) {
      return Base64.decode(existing, Base64.NO_WRAP)
    }

    val generated = ByteArray(PASSPHRASE_LENGTH_BYTES)
    SecureRandom().nextBytes(generated)

    prefs.edit()
      .putString(KEY_PASSPHRASE, Base64.encodeToString(generated, Base64.NO_WRAP))
      .apply()

    return generated
  }

  private fun encryptedPrefs(context: Context): SharedPreferences {
    val masterKey = MasterKey.Builder(context)
      .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
      .build()

    return EncryptedSharedPreferences.create(
      context,
      PREFS_FILE_NAME,
      masterKey,
      EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
      EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
  }
}
