package com.prabu.voicelock.data.prefs

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

private val Context.enrollmentDataStore by preferencesDataStore(name = "enrollment")

/** Languages the passphrase check must support. Decided in CLAUDE.md, 2026-08-26. */
enum class PassphraseLanguage(val label: String, val tag: String) {
    INDONESIAN("Bahasa Indonesia", "id"),
    ENGLISH("English", "en"),
}

/**
 * The enrolled voiceprint, passphrase and language.
 *
 * Encrypted with an AES-GCM key generated inside the Android Keystore, which
 * cannot be exported, so the stored blob is useless if lifted off the device. The
 * same reasoning as [PinStore], and for the same reason it does not use
 * EncryptedSharedPreferences: that API is deprecated for main-thread stalls and
 * OEM keyset corruption.
 *
 * The centroid is derived data, not audio. Recordings are discarded as soon as
 * their embedding is computed and are never written anywhere.
 *
 * Changing language invalidates the passphrase, so [save] always replaces the
 * whole record: it is a re-enrollment, not an editable setting.
 */
@Singleton
class EnrollmentStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    data class Enrollment(
        val language: PassphraseLanguage,
        val passphrase: String,
        val centroid: FloatArray,
        val sampleCount: Int,
        val consistency: Float,
        val createdAtMillis: Long,
    )

    val isEnrolled: Flow<Boolean> =
        context.enrollmentDataStore.data.map { it[Keys.RECORD] != null }

    suspend fun save(enrollment: Enrollment) = withContext(Dispatchers.Default) {
        val plaintext = encode(enrollment)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            // No caller-supplied IV: Keystore requires randomized encryption, so the
            // IV is generated per encryption and stored alongside the ciphertext.
            init(Cipher.ENCRYPT_MODE, enrollmentKey())
        }
        val ciphertext = cipher.doFinal(plaintext)
        val blob = cipher.iv + ciphertext
        context.enrollmentDataStore.edit {
            it[Keys.RECORD] = Base64.getEncoder().encodeToString(blob)
        }
    }

    /** @return the enrollment, or null if none is stored or it cannot be decrypted. */
    suspend fun load(): Enrollment? = withContext(Dispatchers.Default) {
        val stored = context.enrollmentDataStore.data.first()[Keys.RECORD] ?: return@withContext null
        try {
            val blob = Base64.getDecoder().decode(stored)
            val iv = blob.copyOfRange(0, IV_BYTES)
            val ciphertext = blob.copyOfRange(IV_BYTES, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, enrollmentKey(), GCMParameterSpec(TAG_BITS, iv))
            }
            decode(cipher.doFinal(ciphertext))
        } catch (e: GeneralSecurityException) {
            // A wiped or invalidated Keystore key makes the blob unrecoverable. Report
            // "not enrolled" so the user can re-enroll rather than being locked out.
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    suspend fun clear() {
        context.enrollmentDataStore.edit { it.remove(Keys.RECORD) }
    }

    private fun encode(enrollment: Enrollment): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeByte(FORMAT_VERSION)
            out.writeUTF(enrollment.language.name)
            out.writeUTF(enrollment.passphrase)
            out.writeInt(enrollment.sampleCount)
            out.writeFloat(enrollment.consistency)
            out.writeLong(enrollment.createdAtMillis)
            out.writeInt(enrollment.centroid.size)
            for (value in enrollment.centroid) out.writeFloat(value)
        }
        return bytes.toByteArray()
    }

    private fun decode(bytes: ByteArray): Enrollment? {
        DataInputStream(bytes.inputStream()).use { input ->
            val version = input.readByte().toInt()
            if (version != FORMAT_VERSION) return null
            val language = runCatching { PassphraseLanguage.valueOf(input.readUTF()) }
                .getOrNull() ?: return null
            val passphrase = input.readUTF()
            val sampleCount = input.readInt()
            val consistency = input.readFloat()
            val createdAt = input.readLong()
            val size = input.readInt()
            if (size <= 0 || size > MAX_EMBEDDING_SIZE) return null
            val centroid = FloatArray(size) { input.readFloat() }
            return Enrollment(
                language = language,
                passphrase = passphrase,
                centroid = centroid,
                sampleCount = sampleCount,
                consistency = consistency,
                createdAtMillis = createdAt,
            )
        }
    }

    private fun enrollmentKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)
            ?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private object Keys {
        val RECORD = stringPreferencesKey("enrollment_record")
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "voicelock_enrollment_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val FORMAT_VERSION = 1
        const val MAX_EMBEDDING_SIZE = 4096
    }
}
