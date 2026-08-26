package com.prabu.voicelock.audio

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Makes the packaged ECAPA-TDNN model available as a file on disk.
 *
 * ONNX Runtime is handed a path rather than a byte array on purpose: the model is
 * ~80 MB, and `createSession(ByteArray)` would hold all of it on the Java heap,
 * while the path overload lets the runtime map the file instead. Assets cannot be
 * opened as a path, so the model is copied out once on first use.
 *
 * The copy costs ~80 MB of app storage on top of the APK. That is the price of
 * not holding the model in heap; revisit if quantization (M6) shrinks it enough
 * to matter.
 */
@Singleton
class ModelProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val mutex = Mutex()

    /**
     * @return the extracted model file, copying it out of assets if needed.
     */
    suspend fun modelFile(): File = mutex.withLock {
        withContext(Dispatchers.IO) {
            val target = File(context.filesDir, EXTRACTED_NAME)
            val expectedSize = assetSize()

            // Size is the cheap integrity check that catches a copy interrupted by
            // process death or a full disk. A partial model would otherwise fail
            // later, inside session creation, where the cause is far less obvious.
            if (target.isFile && target.length() == expectedSize) {
                return@withContext target
            }

            Log.i(TAG, "extracting model ($expectedSize bytes) to ${target.name}")
            val staging = File(context.filesDir, "$EXTRACTED_NAME.part")
            staging.delete()
            context.assets.open(ASSET_PATH).use { input ->
                staging.outputStream().use { output -> input.copyTo(output) }
            }
            if (staging.length() != expectedSize) {
                staging.delete()
                error("model copy is ${staging.length()} bytes, expected $expectedSize")
            }
            // Rename last, so an interrupted copy never leaves a valid-looking file.
            if (!staging.renameTo(target)) {
                staging.delete()
                error("could not move model into place at ${target.path}")
            }
            target
        }
    }

    /**
     * Asset length in constant time.
     *
     * openFd only works because the model is declared noCompress in the build file;
     * for a compressed asset this throws, and the only alternative would be
     * streaming all 80 MB just to count the bytes.
     */
    private fun assetSize(): Long =
        context.assets.openFd(ASSET_PATH).use { descriptor -> descriptor.length }

    private companion object {
        const val TAG = "ModelProvider"
        const val ASSET_PATH = "models/ecapa_tdnn.onnx"
        const val EXTRACTED_NAME = "ecapa_tdnn.onnx"
    }
}
