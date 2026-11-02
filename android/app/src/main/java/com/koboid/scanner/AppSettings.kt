package com.koboid.scanner

import android.content.Context
import java.io.File

object AppSettings {
    private const val PREFS_NAME = "koboid_settings"
    private const val KEY_RETAIN_CAPTURED_PHOTOS = "retain_captured_photos"
    private const val KEY_DEFAULT_MRZ_STANDARD = "default_mrz_standard"

    private const val DEFAULT_MRZ_STANDARD = "TD3"
    private const val TEMP_CAPTURE_MAX_AGE_MS = 30L * 60L * 1000L

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun shouldRetainCapturedPhotos(context: Context): Boolean =
        prefs(context).getBoolean(KEY_RETAIN_CAPTURED_PHOTOS, false)

    fun setRetainCapturedPhotos(context: Context, retain: Boolean) {
        prefs(context).edit().putBoolean(KEY_RETAIN_CAPTURED_PHOTOS, retain).apply()
    }

    fun defaultMrzStandard(context: Context): String =
        prefs(context).getString(KEY_DEFAULT_MRZ_STANDARD, DEFAULT_MRZ_STANDARD) ?: DEFAULT_MRZ_STANDARD

    fun setDefaultMrzStandard(context: Context, standard: String) {
        prefs(context).edit().putString(KEY_DEFAULT_MRZ_STANDARD, standard).apply()
    }

    fun captureDirectory(context: Context): File {
        val root = if (shouldRetainCapturedPhotos(context)) context.filesDir else context.cacheDir
        return File(root, "captures").apply { mkdirs() }
    }

    fun cleanupTemporaryCaptures(context: Context) {
        if (shouldRetainCapturedPhotos(context)) return
        cleanupDirectory(File(context.cacheDir, "captures"), TEMP_CAPTURE_MAX_AGE_MS)
    }

    private fun cleanupDirectory(directory: File, maxAgeMs: Long) {
        if (!directory.exists()) return
        val cutoff = System.currentTimeMillis() - maxAgeMs
        directory.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) {
                file.delete()
            }
        }
    }
}
