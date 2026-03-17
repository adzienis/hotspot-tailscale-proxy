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
) {
    val isActive: Boolean
        get() = state == ProxyRuntimeState.Starting || state == ProxyRuntimeState.Running || state == ProxyRuntimeState.Stopping
}

object ProxyPreferences {
    private const val NAME = "proxy_preferences"
    private const val KEY_PORT = "port"
    private const val KEY_ADVERTISED_BASE_URL = "advertised_base_url"
    private const val KEY_SELECTED_LOCAL_ADDRESS = "selected_local_address"
    private const val KEY_DEBUG = "debug"
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
    private const val LOG_MAX_BYTES = 256_000L
    private const val LOG_KEEP_BYTES = 192_000
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
    )

    fun loadConfig(context: Context): ProxyConfig {
        val preferences = preferences(context)
        return ProxyConfig(
            port = preferences.getInt(KEY_PORT, 8080),
            advertisedBaseUrl = preferences.getString(KEY_ADVERTISED_BASE_URL, "").orEmpty(),
            selectedLocalAddress = preferences.getString(KEY_SELECTED_LOCAL_ADDRESS, "").orEmpty(),
            debug = preferences.getBoolean(KEY_DEBUG, false),
        )
    }

    fun saveConfig(context: Context, config: ProxyConfig) {
        preferences(context).edit()
            .putInt(KEY_PORT, config.port)
            .putString(KEY_ADVERTISED_BASE_URL, config.advertisedBaseUrl)
            .putString(KEY_SELECTED_LOCAL_ADDRESS, config.selectedLocalAddress)
            .putBoolean(KEY_DEBUG, config.debug)
            .apply()
    }

    fun readStatus(context: Context): ProxyStatus {
        val preferences = preferences(context)
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
        )
    }

    fun setStatus(context: Context, status: ProxyStatus) {
        val editor = preferences(context).edit()
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

        editor.apply()
    }

    fun reconcileStatus(context: Context): ProxyStatus {
        val status = readStatus(context)
        if (isProxyServiceRunning(context) || !status.isActive) {
            return status
        }

        val reconciled = if (status.desiredRunning) {
            val detail = status.lastFailureReason.ifBlank { "Android is no longer running the proxy service." }
            status.copy(
                state = ProxyRuntimeState.Failed,
                message = "Proxy stopped unexpectedly",
                lastFailureReason = detail,
                error = status.error ?: ProxyErrorInfo(
                    category = ProxyErrorCategory.STARTUP_FAILURE,
                    title = "Proxy stopped unexpectedly",
                    detail = detail,
                    recommendedAction = "Try starting the proxy again. If it keeps stopping, copy the last error and inspect the logs.",
                ),
            )
        } else {
            status.copy(
                state = ProxyRuntimeState.Idle,
                message = "Proxy idle",
                error = null,
            )
        }
        setStatus(context, reconciled)
        return reconciled
    }

    fun logFile(context: Context): File = File(context.filesDir, "proxyt.log")

    fun readLogTail(context: Context, maxChars: Int = 12_000): String {
        val logFile = logFile(context)
        if (!logFile.exists()) {
            return "No logs yet."
        }

        val contents = logFile.readText()
        return if (contents.length <= maxChars) contents else contents.takeLast(maxChars)
    }

    fun clearLogs(context: Context) {
        logFile(context).writeText("")
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
        rotateLogsIfNeeded(logFile)
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        logFile.appendText("[$timestamp] $message\n")
    }

    private fun rotateLogsIfNeeded(logFile: File) {
        if (!logFile.exists() || logFile.length() <= LOG_MAX_BYTES) {
            return
        }
        val contents = logFile.readText()
        val trimmed = if (contents.length <= LOG_KEEP_BYTES) contents else contents.takeLast(LOG_KEEP_BYTES)
        logFile.writeText(trimmed)
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

    private fun isProxyServiceRunning(context: Context): Boolean {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        return manager.getRunningServices(Int.MAX_VALUE)
            .any { service -> service.service.className == ProxyService::class.java.name }
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
}
