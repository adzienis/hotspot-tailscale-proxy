package io.proxyt.hotspot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ProxyService : Service() {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile
    private var process: Process? = null

    @Volatile
    private var stopRequested = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopProxy()
            else -> startProxy(intent)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        process?.destroy()
        executor.shutdown()
        super.onDestroy()
    }

    private fun startProxy(intent: Intent?) {
        if (process != null) {
            refreshNotification("Proxy already running")
            sendStatusBroadcast()
            return
        }

        val logFile = ProxyPreferences.logFile(this)
        logFile.writeText("")
        var resolvedBaseUrl = ""

        try {
            val config = parseConfig(intent)
            resolvedBaseUrl = resolveAdvertisedBaseUrl(config)
            val binary = File(applicationInfo.nativeLibraryDir, NATIVE_BINARY)
            binary.setExecutable(true, true)
            if (!binary.exists()) {
                ProxyPreferences.setStatus(
                    context = this,
                    running = false,
                    activeUrl = resolvedBaseUrl,
                    lastExitCode = null,
                    message = "Missing bundled binary: ${binary.absolutePath}",
                )
                refreshNotification("Bundled proxy binary missing")
                sendStatusBroadcast()
                stopSelf()
                return
            }

            val command = buildList {
                add(binary.absolutePath)
                add("serve")
                add("--http-only")
                add("--bind")
                add("0.0.0.0")
                add("--port")
                add(config.port.toString())
                add("--base-url")
                add(resolvedBaseUrl)
                if (config.debug) {
                    add("--debug")
                }
            }

            startForeground(NOTIFICATION_ID, notification("Starting $resolvedBaseUrl"))
            stopRequested = false

            val startedProcess = ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
                .start()

            process = startedProcess
            ProxyPreferences.saveConfig(this, config)
            ProxyPreferences.setStatus(
                context = this,
                running = true,
                activeUrl = resolvedBaseUrl,
                lastExitCode = null,
                message = "Serving on $resolvedBaseUrl",
            )
            refreshNotification("Serving $resolvedBaseUrl")
            sendStatusBroadcast()

            executor.execute {
                val exitCode = startedProcess.waitFor()
                if (process === startedProcess) {
                    process = null
                }

                val message = if (stopRequested) {
                    "Proxy stopped"
                } else {
                    "Proxy exited with code $exitCode"
                }
                ProxyPreferences.setStatus(
                    context = this,
                    running = false,
                    activeUrl = resolvedBaseUrl,
                    lastExitCode = exitCode,
                    message = message,
                )
                refreshNotification(message)
                sendStatusBroadcast()
                stopSelf()
            }
        } catch (exception: Exception) {
            process = null
            ProxyPreferences.setStatus(
                context = this,
                running = false,
                activeUrl = resolvedBaseUrl,
                lastExitCode = null,
                message = "Failed to start proxy: ${exception.message ?: "unknown error"}",
            )
            refreshNotification("Failed to start proxy")
            sendStatusBroadcast()
            stopSelf()
        }
    }

    private fun stopProxy() {
        val runningProcess = process ?: run {
            stopSelf()
            return
        }

        stopRequested = true
        refreshNotification("Stopping proxy")
        runningProcess.destroy()
        executor.execute {
            if (!runningProcess.waitFor(2, TimeUnit.SECONDS)) {
                runningProcess.destroyForcibly()
            }
        }
    }

    private fun parseConfig(intent: Intent?): ProxyConfig {
        val fallback = ProxyPreferences.loadConfig(this)
        return ProxyConfig(
            port = intent?.getIntExtra(EXTRA_PORT, fallback.port) ?: fallback.port,
            advertisedBaseUrl = intent?.getStringExtra(EXTRA_ADVERTISED_BASE_URL).orEmpty()
                .ifBlank { fallback.advertisedBaseUrl },
            selectedLocalAddress = intent?.getStringExtra(EXTRA_SELECTED_LOCAL_ADDRESS).orEmpty()
                .ifBlank { fallback.selectedLocalAddress },
            debug = intent?.getBooleanExtra(EXTRA_DEBUG, fallback.debug) ?: fallback.debug,
        )
    }

    private fun resolveAdvertisedBaseUrl(config: ProxyConfig): String {
        if (config.port !in 1..65535) {
            throw IllegalArgumentException("Listen port must be between 1 and 65535")
        }
        return ProxyConfigValidator.resolveEffectiveUrl(config, HotspotAddressDetector.detectCandidates())
    }

    private fun sendStatusBroadcast() {
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName))
    }

    private fun refreshNotification(status: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification(status))
    }

    private fun notification(status: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(status)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentIntent(pendingIntent)
            .setOngoing(process != null)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Proxy status",
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "io.proxyt.hotspot.action.START"
        const val ACTION_STATUS = "io.proxyt.hotspot.action.STATUS"
        const val ACTION_STOP = "io.proxyt.hotspot.action.STOP"

        const val EXTRA_PORT = "extra_port"
        const val EXTRA_ADVERTISED_BASE_URL = "extra_advertised_base_url"
        const val EXTRA_SELECTED_LOCAL_ADDRESS = "extra_selected_local_address"
        const val EXTRA_DEBUG = "extra_debug"

        private const val CHANNEL_ID = "proxy_status"
        private const val NOTIFICATION_ID = 4001
        private const val NATIVE_BINARY = "libproxyt.so"
    }
}
