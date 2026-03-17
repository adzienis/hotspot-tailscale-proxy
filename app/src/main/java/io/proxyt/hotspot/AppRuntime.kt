package io.proxyt.hotspot

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

interface AppRuntime {
    fun hasNotificationPermission(activity: AppCompatActivity): Boolean

    fun startProxyService(activity: AppCompatActivity, config: ProxyConfig)

    fun stopProxyService(activity: AppCompatActivity)

    fun isIgnoringBatteryOptimizations(activity: AppCompatActivity): Boolean
}

object AppRuntimeHooks {
    @Volatile
    var delegate: AppRuntime = DefaultAppRuntime

    fun reset() {
        delegate = DefaultAppRuntime
    }
}

object DefaultAppRuntime : AppRuntime {
    override fun hasNotificationPermission(activity: AppCompatActivity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }
        return ContextCompat.checkSelfPermission(
            activity,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun startProxyService(activity: AppCompatActivity, config: ProxyConfig) {
        ContextCompat.startForegroundService(
            activity,
            Intent(activity, ProxyService::class.java).apply {
                action = ProxyService.ACTION_START
                putExtra(ProxyService.EXTRA_PORT, config.port)
                putExtra(ProxyService.EXTRA_ADVERTISED_BASE_URL, config.advertisedBaseUrl)
                putExtra(ProxyService.EXTRA_SELECTED_LOCAL_ADDRESS, config.selectedLocalAddress)
                putExtra(ProxyService.EXTRA_DEBUG, config.debug)
            },
        )
    }

    override fun stopProxyService(activity: AppCompatActivity) {
        activity.startService(
            Intent(activity, ProxyService::class.java).apply {
                action = ProxyService.ACTION_STOP
            },
        )
    }

    override fun isIgnoringBatteryOptimizations(activity: AppCompatActivity): Boolean {
        val powerManager = activity.getSystemService(PowerManager::class.java)
        return powerManager?.isIgnoringBatteryOptimizations(activity.packageName) == true
    }
}
