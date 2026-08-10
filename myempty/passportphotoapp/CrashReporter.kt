package com.example.myempty.passportphotoapp

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A custom [Thread.UncaughtExceptionHandler] that writes the full stack trace
 * of any uncaught exception to a timestamped `.txt` file in the app's external
 * Documents directory before handing control back to the system.
 *
 * Placement: [Context.getExternalFilesDir] with [Environment.DIRECTORY_DOCUMENTS]
 * is part of the app's own scoped storage — no `WRITE_EXTERNAL_STORAGE` permission
 * is required on any API level, and the files survive until the app is uninstalled.
 * They are visible to any file-manager app under:
 *   Android/data/<package>/files/Documents/
 *
 * @param context       Application context used to resolve the output directory.
 * @param defaultHandler The system handler that was in place before we installed
 *                        ourselves. We always call it at the end so Android can
 *                        display its standard crash dialog and terminate the process
 *                        normally.
 */
class CrashReporter(
    private val context: Context,
    private val defaultHandler: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        // Always call the default handler last — even if we crash ourselves —
        // so Android can terminate the process correctly.
        try {
            writeCrashLog(thread, throwable)
        } catch (_: Throwable) {
            // Swallow any secondary failure so the default handler always runs.
        } finally {
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrashLog(thread: Thread, throwable: Throwable) {
        // 1. Render the full stack trace to a String.
        val stackTrace = StringWriter().also { sw ->
            throwable.printStackTrace(PrintWriter(sw, true))
        }.toString()

        // 2. Build the report body.
        val timestamp = TIMESTAMP_FORMAT.format(Date())
        val report = buildString {
            appendLine("════════════════════════════════════════")
            appendLine("  PassportPhotoApp — Crash Report")
            appendLine("════════════════════════════════════════")
            appendLine("Timestamp : $timestamp")
            appendLine("Thread    : ${thread.name} (id=${thread.id})")
            appendLine("Exception : ${throwable.javaClass.name}")
            appendLine("Message   : ${throwable.message}")
            appendLine("────────────────────────────────────────")
            appendLine(stackTrace)
        }

        // 3. Resolve the output directory.
        //    getExternalFilesDir() is scoped storage — no permission required.
        val docsDir: File = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: context.filesDir   // Fallback to internal storage if external unavailable.

        if (!docsDir.exists()) docsDir.mkdirs()

        // 4. Write atomically to a uniquely named file.
        val filename = "crash_log_${timestamp.replace(":", "-")}.txt"
        File(docsDir, filename).writeText(report, Charsets.UTF_8)
    }

    companion object {
        private val TIMESTAMP_FORMAT = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
    }
}

