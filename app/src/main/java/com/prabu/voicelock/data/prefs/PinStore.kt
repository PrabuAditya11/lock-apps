package com.prabu.voicelock.data.prefs

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

private val Context.pinDataStore by preferencesDataStore(name = "pin")

/**
 * The fallback PIN, which is mandatory from the first audio milestone because
 * voice verification will produce false rejections.
 *
 * The PIN is never stored recoverably. What is persisted is
 * `HMAC_keystore(PBKDF2(pin, salt))`:
 *
 * - **PBKDF2** makes each guess expensive.
 * - **The HMAC pepper** uses a key generated inside the Android Keystore that
 *   cannot be exported, so copying this DataStore file off the device is not
 *   enough to attack it. That matters because a 4-6 digit PIN has so little
 *   entropy that PBKDF2 alone would fall to an offline sweep.
 *
 * EncryptedSharedPreferences is deliberately not used: its API is deprecated for
 * main-thread stalls and OEM keyset corruption, and it would store the PIN
 * recoverably rather than hashed.
 */
@Singleton
class PinStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    sealed interface VerifyResult {
        data object Success : VerifyResult
        data object NotSet : VerifyResult
        data class Wrong(val attemptsUntilLockout: Int) : VerifyResult
        data class LockedOut(val secondsRemaining: Long) : VerifyResult
    }

    val isPinSet: Flow<Boolean> =
        context.pinDataStore.data.map { it[Keys.DIGEST] != null }

    suspend fun setPin(pin: String) = withContext(Dispatchers.Default) {
        require(pin.length >= MIN_PIN_LENGTH) { "PIN must be at least $MIN_PIN_LENGTH digits" }
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val digest = peppered(pin, salt)
        context.pinDataStore.edit { preferences ->
            preferences[Keys.SALT] = encode(salt)
            preferences[Keys.DIGEST] = encode(digest)
            preferences[Keys.FAILURES] = 0
            preferences[Keys.LOCKED_UNTIL] = 0L
        }
    }

    suspend fun verify(pin: String): VerifyResult = withContext(Dispatchers.Default) {
        val preferences = context.pinDataStore.data.first()
        val storedDigest = preferences[Keys.DIGEST] ?: return@withContext VerifyResult.NotSet
        val storedSalt = preferences[Keys.SALT] ?: return@withContext VerifyResult.NotSet

        val now = System.currentTimeMillis()
        val lockedUntil = preferences[Keys.LOCKED_UNTIL] ?: 0L
        if (now < lockedUntil) {
            return@withContext VerifyResult.LockedOut((lockedUntil - now + 999) / 1000)
        }

        val candidate = peppered(pin, decode(storedSalt))
        // Constant-time comparison; a length-or-content early exit would leak.
        if (MessageDigest.isEqual(candidate, decode(storedDigest))) {
            context.pinDataStore.edit {
                it[Keys.FAILURES] = 0
                it[Keys.LOCKED_UNTIL] = 0L
            }
            return@withContext VerifyResult.Success
        }

        val failures = (preferences[Keys.FAILURES] ?: 0) + 1
        val lockoutMillis = lockoutMillisFor(failures)
        context.pinDataStore.edit {
            it[Keys.FAILURES] = failures
            it[Keys.LOCKED_UNTIL] = if (lockoutMillis > 0) now + lockoutMillis else 0L
        }
        if (lockoutMillis > 0) {
            VerifyResult.LockedOut((lockoutMillis + 999) / 1000)
        } else {
            VerifyResult.Wrong(FREE_ATTEMPTS - failures)
        }
    }

    /**
     * Backoff after [FREE_ATTEMPTS] wrong guesses, doubling and capped. Without it
     * a 4-digit PIN is a few minutes of tapping.
     */
    private fun lockoutMillisFor(failures: Int): Long {
        if (failures < FREE_ATTEMPTS) return 0L
        val step = failures - FREE_ATTEMPTS
        val scaled = BASE_LOCKOUT_MILLIS shl min(step, MAX_DOUBLINGS)
        return min(scaled, MAX_LOCKOUT_MILLIS)
    }

    private fun peppered(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_BITS)
        val derived = try {
            SecretKeyFactory.getInstance(PBKDF2_ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        return Mac.getInstance(HMAC_ALGORITHM).run {
            init(pepperKey())
            doFinal(derived)
        }
    }

    /** HMAC key held in the Keystore; created once, never exportable. */
    private fun pepperKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(PEPPER_ALIAS, null) as? KeyStore.SecretKeyEntry)
            ?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_HMAC_SHA256,
            ANDROID_KEYSTORE,
        )
        // No setUserAuthenticationRequired: this key must stay usable on a device
        // with no biometrics enrolled, and it is a pepper, not the secret itself.
        generator.init(
            KeyGenParameterSpec.Builder(PEPPER_ALIAS, KeyProperties.PURPOSE_SIGN).build(),
        )
        return generator.generateKey()
    }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decode(text: String): ByteArray = Base64.getDecoder().decode(text)

    private object Keys {
        val SALT = stringPreferencesKey("pin_salt")
        val DIGEST = stringPreferencesKey("pin_digest")
        val FAILURES = intPreferencesKey("pin_failures")
        val LOCKED_UNTIL = longPreferencesKey("pin_locked_until")
    }

    companion object {
        const val MIN_PIN_LENGTH = 4
        const val FREE_ATTEMPTS = 5

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val PEPPER_ALIAS = "voicelock_pin_pepper"
        private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val HMAC_ALGORITHM = "HmacSHA256"
        private const val SALT_BYTES = 16
        private const val ITERATIONS = 120_000
        private const val KEY_BITS = 256
        private const val BASE_LOCKOUT_MILLIS = 30_000L
        private const val MAX_DOUBLINGS = 5
        private const val MAX_LOCKOUT_MILLIS = 15 * 60_000L
    }
}
