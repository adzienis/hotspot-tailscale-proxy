package io.proxyt.hotspot

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ProxyConfig(
    val port: Int = 8080,
    val advertisedBaseUrl: String = "",
    val selectedLocalAddress: String = "",
    val debug: Boolean = false,
)

enum class ProxyRuntimeState {
    Idle,
    Starting,
    Running,
    Stopping,
    Failed,
}

enum class ProxyErrorCategory {
    NONE,
    MISSING_BINARY,
    INVALID_CONFIG,
    PORT_IN_USE,
    PROXY_EXIT,
    PERMISSION_REQUIRED,
    STARTUP_FAILURE,
}

data class ProxyErrorInfo(
    val category: ProxyErrorCategory,
    val title: String,
    val detail: String,
    val recommendedAction: String,
)

data class ProxyDiagnostics(
    val currentPid: Long? = null,
    val selectedInterface: String = "",
    val selectedInterfaceKind: String = "",
    val selectedIp: String = "",
    val portBindResult: String = "",
    val lastProbeStatus: String = "",
    val lastProbeTarget: String = "",
    val lastProbeDetail: String = "",
    val hotspotActive: Boolean? = null,
)

data class ProxyStatus(
    val desiredRunning: Boolean = false,
    val state: ProxyRuntimeState = ProxyRuntimeState.Idle,
    val activeUrl: String = "",
    val lastExitCode: Int? = null,
    val message: String = "Proxy idle",
    val lastFailureReason: String = "",
    val startTimestampMs: Long? = null,
    val lastSuccessfulStartTimestampMs: Long? = null,
    val error: ProxyErrorInfo? = null,
    val diagnostics: ProxyDiagnostics = ProxyDiagnostics(),
) {
    val isActive: Boolean
        get() = state == ProxyRuntimeState.Starting || state == ProxyRuntimeState.Running || state == ProxyRuntimeState.Stopping
}

object ProxyPreferences {
    private const val PREFS_VERSION = 2
    private const val NAME = "proxy_preferences"
    private const val KEY_PREFS_VERSION = "prefs_version"
    private const val KEY_PORT = "port"
    private const val KEY_ADVERTISED_BASE_URL = "advertised_base_url"
    private const val KEY_SELECTED_LOCAL_ADDRESS = "selected_local_address"
    private const val KEY_DEBUG = "debug"
    private const val KEY_RUNNING = "running"
    private const val KEY_DESIRED_RUNNING = "desired_running"
    private const val KEY_STATE = "state"
    private const val KEY_ACTIVE_URL = "active_url"
    private const val KEY_LAST_EXIT_CODE = "last_exit_code"
    private const val KEY_MESSAGE = "message"
    private const val KEY_LAST_FAILURE_REASON = "last_failure_reason"
    private const val KEY_START_TIMESTAMP = "start_timestamp"
    private const val KEY_LAST_SUCCESSFUL_START_TIMESTAMP = "last_successful_start_timestamp"
    private const val KEY_ERROR_CATEGORY = "error_category"
    private const val KEY_ERROR_TITLE = "error_title"
    private const val KEY_ERROR_DETAIL = "error_detail"
    private const val KEY_ERROR_ACTION = "error_action"
    private const val KEY_CURRENT_PID = "current_pid"
    private const val KEY_SELECTED_INTERFACE = "selected_interface"
    private const val KEY_SELECTED_INTERFACE_KIND = "selected_interface_kind"
    private const val KEY_SELECTED_IP = "selected_ip"
    private const val KEY_PORT_BIND_RESULT = "port_bind_result"
    private const val KEY_LAST_PROBE_STATUS = "last_probe_status"
    private const val KEY_LAST_PROBE_TARGET = "last_probe_target"
    private const val KEY_LAST_PROBE_DETAIL = "last_probe_detail"
    private const val KEY_HOTSPOT_ACTIVE = "hotspot_active"
    private const val LOG_MAX_BYTES = 256_000L
    private const val LOG_SHARE_MAX_CHARS = 48_000
    private const val LOG_ARCHIVE_LIMIT = 4
    private const val STARTUP_EVENT_DEFAULT = "No recent startup events."
    private val LEGACY_KEYS = setOf(KEY_RUNNING)
    private val STATUS_KEYS = setOf(
        KEY_DESIRED_RUNNING,
        KEY_STATE,
        KEY_ACTIVE_URL,
        KEY_LAST_EXIT_CODE,
        KEY_MESSAGE,
        KEY_LAST_FAILURE_REASON,
        KEY_START_TIMESTAMP,
        KEY_LAST_SUCCESSFUL_START_TIMESTAMP,
        KEY_ERROR_CATEGORY,
        KEY_ERROR_TITLE,
        KEY_ERROR_DETAIL,
        KEY_ERROR_ACTION,
        KEY_CURRENT_PID,
        KEY_SELECTED_INTERFACE,
        KEY_SELECTED_INTERFACE_KIND,
        KEY_SELECTED_IP,
        KEY_PORT_BIND_RESULT,
        KEY_LAST_PROBE_STATUS,
        KEY_LAST_PROBE_TARGET,
        KEY_LAST_PROBE_DETAIL,
        KEY_HOTSPOT_ACTIVE,
    )

    fun loadConfig(context: Context): ProxyConfig {
        val preferences = preferences(context).also(::migrateIfNeeded)
        return ProxyConfig(
            port = preferences.getInt(KEY_PORT, 8080),
            advertisedBaseUrl = preferences.getString(KEY_ADVERTISED_BASE_URL, "").orEmpty(),
            selectedLocalAddress = preferences.getString(KEY_SELECTED_LOCAL_ADDRESS, "").orEmpty(),
            debug = preferences.getBoolean(KEY_DEBUG, false),
        )
    }

    fun saveConfig(context: Context, config: ProxyConfig) {
        preferences(context).also(::migrateIfNeeded).edit()
            .putInt(KEY_PORT, config.port)
            .putString(KEY_ADVERTISED_BASE_URL, config.advertisedBaseUrl)
            .putString(KEY_SELECTED_LOCAL_ADDRESS, config.selectedLocalAddress)
            .putBoolean(KEY_DEBUG, config.debug)
            .apply()
    }

    fun readStatus(context: Context): ProxyStatus {
        val preferences = preferences(context).also(::migrateIfNeeded)
        return ProxyStatus(
            desiredRunning = preferences.getBoolean(KEY_DESIRED_RUNNING, false),
            state = preferences.getString(KEY_STATE, ProxyRuntimeState.Idle.name)
                ?.let { raw -> ProxyRuntimeState.entries.firstOrNull { it.name == raw } }
                ?: ProxyRuntimeState.Idle,
            activeUrl = preferences.getString(KEY_ACTIVE_URL, "").orEmpty(),
            lastExitCode = if (preferences.contains(KEY_LAST_EXIT_CODE)) {
                preferences.getInt(KEY_LAST_EXIT_CODE, 0)
            } else {
                null
            },
            message = preferences.getString(KEY_MESSAGE, "Proxy idle").orEmpty(),
            lastFailureReason = preferences.getString(KEY_LAST_FAILURE_REASON, "").orEmpty(),
            startTimestampMs = if (preferences.contains(KEY_START_TIMESTAMP)) {
                preferences.getLong(KEY_START_TIMESTAMP, 0L)
            } else {
                null
            },
            lastSuccessfulStartTimestampMs = if (preferences.contains(KEY_LAST_SUCCESSFUL_START_TIMESTAMP)) {
                preferences.getLong(KEY_LAST_SUCCESSFUL_START_TIMESTAMP, 0L)
            } else {
                null
            },
            error = readError(preferences),
            diagnostics = readDiagnostics(preferences),
        )
    }

    fun setStatus(context: Context, status: ProxyStatus) {
        val editor = preferences(context).also(::migrateIfNeeded).edit()
            .putBoolean(KEY_DESIRED_RUNNING, status.desiredRunning)
            .putString(KEY_STATE, status.state.name)
            .putString(KEY_ACTIVE_URL, status.activeUrl)
            .putString(KEY_MESSAGE, status.message)
            .putString(KEY_LAST_FAILURE_REASON, status.lastFailureReason)

        if (status.lastExitCode == null) {
            editor.remove(KEY_LAST_EXIT_CODE)
        } else {
            editor.putInt(KEY_LAST_EXIT_CODE, status.lastExitCode)
        }

        if (status.startTimestampMs == null) {
            editor.remove(KEY_START_TIMESTAMP)
        } else {
            editor.putLong(KEY_START_TIMESTAMP, status.startTimestampMs)
        }

        if (status.lastSuccessfulStartTimestampMs == null) {
            editor.remove(KEY_LAST_SUCCESSFUL_START_TIMESTAMP)
        } else {
            editor.putLong(KEY_LAST_SUCCESSFUL_START_TIMESTAMP, status.lastSuccessfulStartTimestampMs)
        }

        if (status.error == null || status.error.category == ProxyErrorCategory.NONE) {
            editor.remove(KEY_ERROR_CATEGORY)
            editor.remove(KEY_ERROR_TITLE)
            editor.remove(KEY_ERROR_DETAIL)
            editor.remove(KEY_ERROR_ACTION)
        } else {
            editor.putString(KEY_ERROR_CATEGORY, status.error.category.name)
            editor.putString(KEY_ERROR_TITLE, status.error.title)
            editor.putString(KEY_ERROR_DETAIL, status.error.detail)
            editor.putString(KEY_ERROR_ACTION, status.error.recommendedAction)
        }

        if (status.diagnostics.currentPid == null) {
            editor.remove(KEY_CURRENT_PID)
        } else {
            editor.putLong(KEY_CURRENT_PID, status.diagnostics.currentPid)
        }
        editor.putString(KEY_SELECTED_INTERFACE, status.diagnostics.selectedInterface)
        editor.putString(KEY_SELECTED_INTERFACE_KIND, status.diagnostics.selectedInterfaceKind)
        editor.putString(KEY_SELECTED_IP, status.diagnostics.selectedIp)
        editor.putString(KEY_PORT_BIND_RESULT, status.diagnostics.portBindResult)
        editor.putString(KEY_LAST_PROBE_STATUS, status.diagnostics.lastProbeStatus)
        editor.putString(KEY_LAST_PROBE_TARGET, status.diagnostics.lastProbeTarget)
        editor.putString(KEY_LAST_PROBE_DETAIL, status.diagnostics.lastProbeDetail)
        if (status.diagnostics.hotspotActive == null) {
            editor.remove(KEY_HOTSPOT_ACTIVE)
        } else {
            editor.putBoolean(KEY_HOTSPOT_ACTIVE, status.diagnostics.hotspotActive)
        }

        editor.apply()
    }

    fun reconcileStatus(context: Context): ProxyStatus {
        val status = readStatus(context)
        val reconciled = ProxyStatusReducer.reconcilePersistedStatus(
            status = status,
            isServiceRunning = isProxyServiceRunning(context),
        )
        if (reconciled == status) {
            return status
        }
        setStatus(context, reconciled)
        return reconciled
    }

    fun logFile(context: Context): File = File(context.filesDir, "proxyt.log")

    fun startNewLogSession(context: Context) {
        val currentLog = logFile(context)
        if (currentLog.exists() && currentLog.length() > 0L) {
            archiveLog(context, currentLog)
        }
        currentLog.parentFile?.mkdirs()
        currentLog.writeText("")
    }

    fun readLogTail(context: Context, maxChars: Int = 12_000): String {
        val transcript = recentLogTranscript(context, maxFiles = LOG_ARCHIVE_LIMIT + 1)
        if (transcript.isBlank()) {
            return "No logs yet."
        }
        return if (transcript.length <= maxChars) transcript else transcript.takeLast(maxChars)
    }

    fun clearLogs(context: Context) {
        logFile(context).delete()
        logArchiveDirectory(context).deleteRecursively()
    }

    fun readStartupEventSummary(context: Context, maxEvents: Int = 5): String {
        val logText = recentLogTranscript(context, maxFiles = LOG_ARCHIVE_LIMIT + 1)
        if (logText.isBlank()) {
            return STARTUP_EVENT_DEFAULT
        }

        val events = extractStartupEvents(logText)
        if (events.isEmpty()) {
            return STARTUP_EVENT_DEFAULT
        }
        return events.takeLast(maxEvents).joinToString(separator = "\n")
    }

    fun registerStatusListener(
        context: Context,
        onStatusChanged: () -> Unit,
    ): SharedPreferences.OnSharedPreferenceChangeListener {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key in STATUS_KEYS) {
                onStatusChanged()
            }
        }
        preferences(context).registerOnSharedPreferenceChangeListener(listener)
        return listener
    }

    fun unregisterStatusListener(
        context: Context,
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        preferences(context).unregisterOnSharedPreferenceChangeListener(listener)
    }

    fun appendLog(context: Context, message: String) {
        val logFile = logFile(context)
        rotateLogsIfNeeded(context, logFile)
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        logFile.appendText("[$timestamp] $message\n")
    }

    fun prepareShareLogFile(context: Context): File? {
        val transcript = recentLogTranscript(context, maxFiles = LOG_ARCHIVE_LIMIT + 1)
        if (transcript.isBlank()) {
            return null
        }
        val shareFile = File(context.filesDir, "proxyt-share.txt")
        shareFile.writeText(
            if (transcript.length <= LOG_SHARE_MAX_CHARS) transcript else transcript.takeLast(LOG_SHARE_MAX_CHARS),
        )
        return shareFile
    }

    private fun rotateLogsIfNeeded(context: Context, logFile: File) {
        if (!logFile.exists() || logFile.length() <= LOG_MAX_BYTES) {
            return
        }
        archiveLog(context, logFile)
        logFile.writeText("")
    }

    private fun readError(preferences: SharedPreferences): ProxyErrorInfo? {
        val categoryName = preferences.getString(KEY_ERROR_CATEGORY, null) ?: return null
        val category = ProxyErrorCategory.entries.firstOrNull { it.name == categoryName }
            ?: ProxyErrorCategory.STARTUP_FAILURE
        if (category == ProxyErrorCategory.NONE) {
            return null
        }

        val title = preferences.getString(KEY_ERROR_TITLE, "").orEmpty()
        val detail = preferences.getString(KEY_ERROR_DETAIL, "").orEmpty()
        val recommendedAction = preferences.getString(KEY_ERROR_ACTION, "").orEmpty()
        if (title.isBlank() && detail.isBlank() && recommendedAction.isBlank()) {
            return null
        }

        return ProxyErrorInfo(
            category = category,
            title = title,
            detail = detail,
            recommendedAction = recommendedAction,
        )
    }

    private fun readDiagnostics(preferences: SharedPreferences): ProxyDiagnostics =
        ProxyDiagnostics(
            currentPid = if (preferences.contains(KEY_CURRENT_PID)) {
                preferences.getLong(KEY_CURRENT_PID, 0L)
            } else {
                null
            },
            selectedInterface = preferences.getString(KEY_SELECTED_INTERFACE, "").orEmpty(),
            selectedInterfaceKind = preferences.getString(KEY_SELECTED_INTERFACE_KIND, "").orEmpty(),
            selectedIp = preferences.getString(KEY_SELECTED_IP, "").orEmpty(),
            portBindResult = preferences.getString(KEY_PORT_BIND_RESULT, "").orEmpty(),
            lastProbeStatus = preferences.getString(KEY_LAST_PROBE_STATUS, "").orEmpty(),
            lastProbeTarget = preferences.getString(KEY_LAST_PROBE_TARGET, "").orEmpty(),
            lastProbeDetail = preferences.getString(KEY_LAST_PROBE_DETAIL, "").orEmpty(),
            hotspotActive = if (preferences.contains(KEY_HOTSPOT_ACTIVE)) {
                preferences.getBoolean(KEY_HOTSPOT_ACTIVE, false)
            } else {
                null
            },
        )

    private fun extractStartupEvents(logText: String): List<String> {
        return logText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filter { line ->
                val lowercase = line.lowercase(Locale.US)
                "starting proxy" in lowercase ||
                    "preflight" in lowercase ||
                    "probe" in lowercase ||
                    "serving on" in lowercase ||
                    "start blocked" in lowercase ||
                    "failed to start" in lowercase ||
                    "port " in lowercase
            }
            .toList()
    }

    private fun migrateIfNeeded(preferences: SharedPreferences) {
        val version = preferences.getInt(KEY_PREFS_VERSION, 0)
        if (version >= PREFS_VERSION) {
            return
        }

        val editor = preferences.edit()
        if (version < 1 && preferences.contains(KEY_RUNNING) && !preferences.contains(KEY_STATE)) {
            val wasRunning = preferences.getBoolean(KEY_RUNNING, false)
            editor.putBoolean(KEY_DESIRED_RUNNING, wasRunning)
            editor.putString(KEY_STATE, if (wasRunning) ProxyRuntimeState.Running.name else ProxyRuntimeState.Idle.name)
        }
        LEGACY_KEYS.forEach(editor::remove)
        editor.putInt(KEY_PREFS_VERSION, PREFS_VERSION)
        editor.apply()
    }

    private fun recentLogTranscript(context: Context, maxFiles: Int): String {
        val files = recentLogFiles(context, maxFiles)
        if (files.isEmpty()) {
            return ""
        }

        return buildString {
            files.forEachIndexed { index, file ->
                val label = if (file.name == LOG_FILE_NAME) {
                    "Current log"
                } else {
                    "Archived log: ${file.nameWithoutExtension}"
                }
                append("== ")
                append(label)
                append(" ==\n")
                append(file.readText())
                if (index != files.lastIndex) {
                    append("\n")
                }
            }
        }
    }

    private fun recentLogFiles(context: Context, maxFiles: Int): List<File> {
        val archived = logArchiveDirectory(context)
            .listFiles()
            ?.filter { it.isFile && it.extension == "log" }
            ?.sortedByDescending { it.name }
            .orEmpty()
            .take(maxFiles - 1)
            .reversed()

        val current = logFile(context).takeIf(File::exists)
        return buildList {
            addAll(archived)
            if (current != null) {
                add(current)
            }
        }
    }

    private fun archiveLog(context: Context, currentLog: File) {
        val archiveDir = logArchiveDirectory(context).apply { mkdirs() }
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val archiveFile = File(archiveDir, "proxyt-$timestamp.log")
        currentLog.copyTo(archiveFile, overwrite = true)
        currentLog.delete()
        pruneArchivedLogs(archiveDir)
    }

    private fun pruneArchivedLogs(archiveDir: File) {
        archiveDir.listFiles()
            ?.filter { it.isFile && it.extension == "log" }
            ?.sortedByDescending { it.name }
            ?.drop(LOG_ARCHIVE_LIMIT)
            ?.forEach(File::delete)
    }

    private fun logArchiveDirectory(context: Context): File = File(context.filesDir, "proxyt-log-archive")

    private fun isProxyServiceRunning(context: Context): Boolean {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        return manager.getRunningServices(Int.MAX_VALUE)
            .any { service -> service.service.className == ProxyService::class.java.name }
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    private const val LOG_FILE_NAME = "proxyt.log"
}
