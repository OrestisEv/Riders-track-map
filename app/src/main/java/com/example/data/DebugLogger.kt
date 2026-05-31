package com.example.data

import android.content.Context
import android.content.Intent
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogEntry(
    val timestamp: String,
    val level: String,      // "INFO", "WARN", "ERROR", "SYS"
    val tag: String,        // "GPS", "SENSORS", "DATABASE", "SYSTEM", "MAP"
    val message: String,
    val exception: String? = null
) {
    fun toFormattedString(): String {
        val exc = if (exception != null) "\n$exception" else ""
        return "[$timestamp] $level/$tag: $message$exc"
    }
}

object DebugLogger {
    private const val LOG_FILE_NAME = "riders_track_debug.log"
    private const val MAX_IN_MEMORY_LOGS = 1500
    private const val MAX_LOG_FILE_SIZE_BYTES = 512 * 1024 // 512 KB

    private val _logsFlow = MutableStateFlow<List<LogEntry>>(emptyList())
    val logsFlow: StateFlow<List<LogEntry>> = _logsFlow.asStateFlow()

    private var logFile: File? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /**
     * Initializes the logger, reads existing logs from the persistent file into memory.
     */
    fun init(context: Context) {
        if (logFile != null) return // Already initialized
        val appContext = context.applicationContext
        logFile = File(appContext.filesDir, LOG_FILE_NAME)

        scope.launch {
            try {
                val file = logFile ?: return@launch
                if (file.exists()) {
                    // Check file size limit and prune if needed
                    if (file.length() > MAX_LOG_FILE_SIZE_BYTES) {
                        pruneLogFile(file)
                    }

                    val loadedLogs = mutableListOf<LogEntry>()
                    file.forEachLine { line ->
                        parseLogLine(line)?.let { loadedLogs.add(it) }
                    }

                    // Keep only latest MAX_IN_MEMORY_LOGS
                    val trimmed = if (loadedLogs.size > MAX_IN_MEMORY_LOGS) {
                        loadedLogs.takeLast(MAX_IN_MEMORY_LOGS)
                    } else {
                        loadedLogs
                    }

                    _logsFlow.value = trimmed
                    log("SYS", "LOGGER", "Loaded ${trimmed.size} existing debug logs from disk.")
                } else {
                    file.createNewFile()
                    log("SYS", "LOGGER", "Initialized new debug log storage.")
                }
            } catch (e: Exception) {
                // Fail-safe: we don't want logger crashes to crash the app
                e.printStackTrace()
            }
        }
    }

    /**
     * Parse a formatted log line helper
     */
    private fun parseLogLine(line: String): LogEntry? {
        try {
            // Pattern format: [2026-05-30 19:35:00.000] LEVEL/TAG: MESSAGE
            if (!line.startsWith("[")) return null
            val closingBracketIdx = line.indexOf("]")
            if (closingBracketIdx == -1) return null
            val timestamp = line.substring(1, closingBracketIdx)
            
            val rest = line.substring(closingBracketIdx + 2).trim()
            val slashIdx = rest.indexOf("/")
            if (slashIdx == -1) return null
            val level = rest.substring(0, slashIdx)
            
            val colonIdx = rest.indexOf(":")
            if (colonIdx == -1) return null
            val tag = rest.substring(slashIdx + 1, colonIdx)
            val message = rest.substring(colonIdx + 1).trim()
            
            return LogEntry(timestamp, level, tag, message)
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Prune log file to protect storage space
     */
    private fun pruneLogFile(file: File) {
        try {
            if (!file.exists()) return
            val lines = file.readLines()
            if (lines.size > 500) {
                val keptLines = lines.takeLast(500)
                file.writeText(keptLines.joinToString("\n") + "\n")
            } else {
                file.writeText("[System Notice] Logs cleared due to file size threshold exceeded.\n")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Logs an entry. Safe to call from any thread.
     */
    fun log(level: String, tag: String, message: String, throwable: Throwable? = null) {
        val timestamp = dateFormat.format(Date())
        val exceptionStr = throwable?.let {
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            it.printStackTrace(pw)
            sw.toString()
        }

        val entry = LogEntry(timestamp, level, tag, message, exceptionStr)
        
        // Append in memory
        _logsFlow.update { current ->
            val updated = current + entry
            if (updated.size > MAX_IN_MEMORY_LOGS) {
                updated.takeLast(MAX_IN_MEMORY_LOGS)
            } else {
                updated
            }
        }

        // Print to logcat so developers can still see in AS
        val logcatMsg = "$tag: $message"
        when (level) {
            "WARN" -> android.util.Log.w(tag, logcatMsg, throwable)
            "ERROR" -> android.util.Log.e(tag, logcatMsg, throwable)
            else -> android.util.Log.i(tag, logcatMsg, throwable)
        }

        // Write to raw file asynchronously to prevent frame dropping
        scope.launch {
            try {
                val file = logFile ?: return@launch
                file.appendText(entry.toFormattedString() + "\n")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // Helper functions for easy logging
    fun i(tag: String, message: String) = log("INFO", tag, message)
    fun w(tag: String, message: String) = log("WARN", tag, message)
    fun e(tag: String, message: String, throwable: Throwable? = null) = log("ERROR", tag, message, throwable)
    fun sys(tag: String, message: String) = log("SYS", tag, message)

    /**
     * Clears all log entries in memory and truncates the file.
     */
    fun clear() {
        _logsFlow.value = emptyList()
        scope.launch {
            try {
                val file = logFile
                if (file != null && file.exists()) {
                    file.writeText("") // Clear contents
                    log("SYS", "LOGGER", "All diagnostic logs cleared by user request.")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Exports the log logs to a standard Android Share Sheet as raw intent text
     */
    fun shareLogs(context: Context) {
        scope.launch {
            val allLogsStr = _logsFlow.value.joinToString("\n") { it.toFormattedString() }
            withContext(Dispatchers.Main) {
                if (allLogsStr.isEmpty()) {
                    Toast.makeText(context, "No debug logs available to share.", Toast.LENGTH_SHORT).show()
                    return@withContext
                }

                val sendIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_TEXT, allLogsStr)
                    putExtra(Intent.EXTRA_TITLE, "Riders Track Map Diagnostics")
                    type = "text/plain"
                }
                val shareIntent = Intent.createChooser(sendIntent, "Send Riders Track Logs via:")
                shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(shareIntent)
            }
        }
    }
}
