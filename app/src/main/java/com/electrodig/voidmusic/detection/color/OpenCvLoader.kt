package com.electrodig.voidmusic.detection.color

import android.content.Context
import android.util.Log
import org.opencv.android.OpenCVLoader
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Initialises the OpenCV native library exactly once per process.
 *
 * With the modern `org.opencv:opencv` Maven artifact the native libraries are
 * packaged in the APK; [OpenCVLoader.initLocal] loads them without needing the
 * legacy OpenCV Manager app. Safe to call repeatedly.
 */
object OpenCvLoader {
    private val initialised = AtomicBoolean(false)

    fun ensureInitialised(context: Context): Boolean {
        if (initialised.get()) return true
        val ok = runCatching { OpenCVLoader.initLocal() }
            .getOrElse {
                Log.e(TAG, "OpenCV init threw", it)
                false
            }
        initialised.set(ok)
        if (ok) Log.i(TAG, "OpenCV loaded") else Log.e(TAG, "OpenCV failed to load")
        return ok
    }

    private const val TAG = "OpenCvLoader"
}
