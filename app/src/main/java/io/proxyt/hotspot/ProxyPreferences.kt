package io.proxyt.hotspot

import android.content.Context
import java.io.File

data class ProxyConfig(
    val port: Int = 8080,
    val advertisedBaseUrl: String = "",
    val debug: Boolean = false,
)

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
    val running: Boolean,
    val activeUrl: String,
    val lastExitCode: Int?,
    val message: String,
    val error: ProxyErrorInfo?,
)

object ProxyPreferences {
    private const val NAME = "proxy_preferences"
    private const val KEY_PORT = "port"
    private const val KEY_ADVERTISED_BASE_URL = "advertised_base_url"
    private const val KEY_DEBUG = "debug"
    private const val KEY_RUNNING = "running"
    private const val KEY_ACTIVE_URL = "active_url"
    private const val KEY_LAST_EXIT_CODE = "last_exit_code"
    private const val KEY_MESSAGE = "message"
    private const val KEY_ERROR_CATEGORY = "error_category"
    private const val KEY_ERROR_TITLE = "error_title"
    private const val KEY_ERROR_DETAIL = "error_detail"
    private const val KEY_ERROR_ACTION = "error_action"

    fun loadConfig(context: Context): ProxyConfig {
        val preferences = preferences(context)
        return ProxyConfig(
            port = preferences.getInt(KEY_PORT, 8080),
            advertisedBaseUrl = preferences.getString(KEY_ADVERTISED_BASE_URL, "").orEmpty(),
            debug = preferences.getBoolean(KEY_DEBUG, false),
        )
    }

    fun saveConfig(context: Context, config: ProxyConfig) {
        preferences(context).edit()
            .putInt(KEY_PORT, config.port)
            .putString(KEY_ADVERTISED_BASE_URL, config.advertisedBaseUrl)
            .putBoolean(KEY_DEBUG, config.debug)
            .apply()
    }

    fun readStatus(context: Context): ProxyStatus {
        val preferences = preferences(context)
        return ProxyStatus(
            running = preferences.getBoolean(KEY_RUNNING, false),
            activeUrl = preferences.getString(KEY_ACTIVE_URL, "").orEmpty(),
            lastExitCode = if (preferences.contains(KEY_LAST_EXIT_CODE)) {
                preferences.getInt(KEY_LAST_EXIT_CODE, 0)
            } else {
                null
            },
            message = preferences.getString(KEY_MESSAGE, "Proxy idle").orEmpty(),
            error = readError(preferences),
        )
    }

    fun setStatus(
        context: Context,
        running: Boolean,
        activeUrl: String,
        lastExitCode: Int?,
        message: String,
        error: ProxyErrorInfo? = null,
    ) {
        val editor = preferences(context).edit()
            .putBoolean(KEY_RUNNING, running)
            .putString(KEY_ACTIVE_URL, activeUrl)
            .putString(KEY_MESSAGE, message)

        if (lastExitCode == null) {
            editor.remove(KEY_LAST_EXIT_CODE)
        } else {
            editor.putInt(KEY_LAST_EXIT_CODE, lastExitCode)
        }

        if (error == null || error.category == ProxyErrorCategory.NONE) {
            editor.remove(KEY_ERROR_CATEGORY)
            editor.remove(KEY_ERROR_TITLE)
            editor.remove(KEY_ERROR_DETAIL)
            editor.remove(KEY_ERROR_ACTION)
        } else {
            editor.putString(KEY_ERROR_CATEGORY, error.category.name)
            editor.putString(KEY_ERROR_TITLE, error.title)
            editor.putString(KEY_ERROR_DETAIL, error.detail)
            editor.putString(KEY_ERROR_ACTION, error.recommendedAction)
        }

        editor.apply()
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

    private fun readError(preferences: android.content.SharedPreferences): ProxyErrorInfo? {
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

    private fun preferences(context: Context) =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
}
