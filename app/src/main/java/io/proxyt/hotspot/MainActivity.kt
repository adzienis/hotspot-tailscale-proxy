package io.proxyt.hotspot

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class MainActivity : AppCompatActivity() {
    private lateinit var stateView: TextView
    private lateinit var statusMessageView: TextView
    private lateinit var statusUrlView: TextView
    private lateinit var errorCategoryView: TextView
    private lateinit var lastFailureView: TextView
    private lateinit var lastExitCodeView: TextView
    private lateinit var nextActionView: TextView
    private lateinit var detectedAddressView: TextView
    private lateinit var logView: TextView
    private lateinit var portLayout: TextInputLayout
    private lateinit var portEdit: TextInputEditText
    private lateinit var baseUrlLayout: TextInputLayout
    private lateinit var baseUrlEdit: TextInputEditText
    private lateinit var localAddressLayout: TextInputLayout
    private lateinit var localAddressPicker: MaterialAutoCompleteTextView
    private lateinit var debugSwitch: MaterialCheckBox
    private lateinit var startButton: MaterialButton
    private lateinit var stopButton: MaterialButton
    private lateinit var copyLastErrorButton: MaterialButton
    private lateinit var copyUrlButton: MaterialButton

    private var latestStatus: ProxyStatus? = null
    private var pendingStartConfig: ProxyConfig? = null
    private lateinit var statusPreferenceListener: SharedPreferences.OnSharedPreferenceChangeListener
    private var localCandidates: List<HotspotAddressCandidate> = emptyList()
    private var suppressValidationCallbacks = false

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val configToStart = pendingStartConfig
        pendingStartConfig = null
        if (granted && configToStart != null) {
            startProxyService(configToStart)
            return@registerForActivityResult
        }

        if (configToStart != null) {
            persistNotificationPermissionFailure(configToStart)
            showNotificationPermissionDeniedDialog()
        }
    }

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            renderLogs()
            refreshHandler.postDelayed(this, 2_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        stateView = findViewById(R.id.stateValueText)
        statusMessageView = findViewById(R.id.statusMessageText)
        statusUrlView = findViewById(R.id.statusUrlText)
        errorCategoryView = findViewById(R.id.errorCategoryText)
        lastFailureView = findViewById(R.id.lastFailureText)
        lastExitCodeView = findViewById(R.id.lastExitCodeText)
        nextActionView = findViewById(R.id.nextActionText)
        detectedAddressView = findViewById(R.id.detectedAddressText)
        logView = findViewById(R.id.logText)
        portLayout = findViewById(R.id.portLayout)
        portEdit = findViewById(R.id.portEdit)
        baseUrlLayout = findViewById(R.id.baseUrlLayout)
        baseUrlEdit = findViewById(R.id.baseUrlEdit)
        localAddressLayout = findViewById(R.id.localAddressLayout)
        localAddressPicker = findViewById(R.id.localAddressPicker)
        debugSwitch = findViewById(R.id.debugSwitch)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        copyLastErrorButton = findViewById(R.id.copyLastErrorButton)
        copyUrlButton = findViewById(R.id.copyUrlButton)

        ProxyPreferences.reconcileStatus(this)
        statusPreferenceListener = ProxyPreferences.registerStatusListener(this) {
            runOnUiThread {
                renderStatus()
            }
        }

        val config = ProxyPreferences.loadConfig(this)
        portEdit.setText(getString(R.string.port_value, config.port))
        baseUrlEdit.setText(config.advertisedBaseUrl)
        localAddressPicker.setText(config.selectedLocalAddress, false)
        debugSwitch.isChecked = config.debug

        refreshLocalAddressOptions()
        renderValidation()

        findViewById<MaterialButton>(R.id.detectButton).setOnClickListener {
            refreshLocalAddressOptions()
            renderStatus()
        }

        findViewById<MaterialButton>(R.id.refreshLogsButton).setOnClickListener {
            renderLogs()
        }

        findViewById<MaterialButton>(R.id.clearLogsButton).setOnClickListener {
            ProxyPreferences.clearLogs(this)
            renderLogs()
            Toast.makeText(this, "Logs cleared", Toast.LENGTH_SHORT).show()
        }

        copyLastErrorButton.setOnClickListener {
            val clipboardText = buildClipboardError(latestStatus) ?: return@setOnClickListener
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("Proxy last error", clipboardText))
            Toast.makeText(this, R.string.last_error_copied, Toast.LENGTH_SHORT).show()
        }

        copyUrlButton.setOnClickListener {
            val effectiveUrl = validateUi(showErrors = false).effectiveUrl
            if (effectiveUrl.isBlank()) {
                return@setOnClickListener
            }
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("Proxy control URL", effectiveUrl))
            Toast.makeText(this, "Copied control URL", Toast.LENGTH_SHORT).show()
        }

        portEdit.doAfterTextChanged {
            if (!suppressValidationCallbacks) {
                renderValidation()
                renderStatus()
            }
        }
        baseUrlEdit.doAfterTextChanged {
            if (!suppressValidationCallbacks) {
                renderValidation()
                renderStatus()
            }
        }
        localAddressPicker.doAfterTextChanged {
            if (!suppressValidationCallbacks) {
                renderValidation()
                renderStatus()
            }
        }

        startButton.setOnClickListener {
            val validation = validateUi(showErrors = true)
            val configToStart = validation.config ?: return@setOnClickListener
            ProxyPreferences.saveConfig(this, configToStart)
            requestNotificationPermissionThenStart(configToStart)
        }

        stopButton.setOnClickListener {
            startService(
                Intent(this, ProxyService::class.java).apply {
                    action = ProxyService.ACTION_STOP
                },
            )
        }

        renderStatus()
        renderLogs()
    }

    override fun onStart() {
        super.onStart()
        renderStatus()
        refreshHandler.post(refreshRunnable)
    }

    override fun onStop() {
        refreshHandler.removeCallbacks(refreshRunnable)
        super.onStop()
    }

    override fun onDestroy() {
        ProxyPreferences.unregisterStatusListener(this, statusPreferenceListener)
        super.onDestroy()
    }

    private fun requestNotificationPermissionThenStart(config: ProxyConfig) {
        if (hasNotificationPermission()) {
            startProxyService(config)
            return
        }

        pendingStartConfig = config
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.notification_permission_required_title)
            .setMessage(R.string.notification_permission_required_message)
            .setCancelable(false)
            .setPositiveButton(R.string.notification_permission_continue) { _, _ ->
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                pendingStartConfig = null
                persistNotificationPermissionFailure(config)
            }
            .show()
    }

    private fun startProxyService(config: ProxyConfig) {
        ContextCompat.startForegroundService(
            this,
            Intent(this, ProxyService::class.java).apply {
                action = ProxyService.ACTION_START
                putExtra(ProxyService.EXTRA_PORT, config.port)
                putExtra(ProxyService.EXTRA_ADVERTISED_BASE_URL, config.advertisedBaseUrl)
                putExtra(ProxyService.EXTRA_SELECTED_LOCAL_ADDRESS, config.selectedLocalAddress)
                putExtra(ProxyService.EXTRA_DEBUG, config.debug)
            },
        )
    }

    private fun persistNotificationPermissionFailure(config: ProxyConfig) {
        val resolvedBaseUrl = resolveAdvertisedBaseUrl(config)
        val error = ProxyErrorInfo(
            category = ProxyErrorCategory.PERMISSION_REQUIRED,
            title = getString(R.string.error_state_permission),
            detail = getString(R.string.notification_permission_required_message),
            recommendedAction = getString(R.string.notification_permission_settings_message),
        )
        ProxyPreferences.setStatus(
            this,
            ProxyStatus(
                desiredRunning = true,
                state = ProxyRuntimeState.Failed,
                activeUrl = resolvedBaseUrl,
                lastExitCode = null,
                message = getString(R.string.notification_permission_required_short),
                lastFailureReason = error.detail,
                error = error,
            ),
        )
        renderStatus()
    }

    private fun showNotificationPermissionDeniedDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.notification_permission_required_title)
            .setMessage(R.string.notification_permission_settings_message)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null),
                    ),
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun refreshLocalAddressOptions() {
        localCandidates = HotspotAddressDetector.detectCandidates()
        val savedSelection = localAddressPicker.text?.toString()?.trim().orEmpty()
        val options = buildList {
            localCandidates.forEach { candidate ->
                add(candidate.address)
            }
            if (savedSelection.isNotBlank() && localCandidates.none { it.address == savedSelection }) {
                add(savedSelection)
            }
        }
        localAddressPicker.setAdapter(
            ArrayAdapter(
                this,
                android.R.layout.simple_dropdown_item_1line,
                options,
            ),
        )
        if (savedSelection.isNotBlank()) {
            suppressValidationCallbacks = true
            localAddressPicker.setText(savedSelection, false)
            suppressValidationCallbacks = false
        } else if (localCandidates.isNotEmpty()) {
            suppressValidationCallbacks = true
            localAddressPicker.setText(localCandidates.first().address, false)
            suppressValidationCallbacks = false
        }
        renderValidation()
    }

    private fun validateUi(showErrors: Boolean): ProxyConfigValidationResult {
        val validation = ProxyConfigValidator.validate(
            portInput = portEdit.text?.toString().orEmpty(),
            advertisedBaseUrlInput = baseUrlEdit.text?.toString().orEmpty(),
            selectedLocalAddressInput = localAddressPicker.text?.toString().orEmpty(),
            debug = debugSwitch.isChecked,
            localCandidates = localCandidates,
        )
        if (showErrors) {
            portLayout.error = validation.portError
            baseUrlLayout.error = validation.baseUrlError
            localAddressLayout.error = validation.localAddressError
        } else {
            portLayout.error = null
            baseUrlLayout.error = null
            localAddressLayout.error = null
        }
        baseUrlLayout.helperText = validation.baseUrlWarning
            ?: getString(R.string.base_url_helper_text)
        localAddressLayout.helperText = validation.localAddressWarning
            ?: getString(R.string.local_address_helper_text)
        copyUrlButton.isEnabled = validation.effectiveUrl.isNotBlank()
        return validation
    }

    private fun renderValidation() {
        validateUi(showErrors = false)
    }

    private fun resolveAdvertisedBaseUrl(config: ProxyConfig): String =
        ProxyConfigValidator.resolveEffectiveUrl(config, localCandidates)

    private fun renderStatus() {
        val validation = validateUi(showErrors = false)
        val candidateSummary = if (localCandidates.isEmpty()) {
            getString(R.string.detected_address_missing)
        } else {
            localCandidates.joinToString(separator = "\n") { candidate ->
                "- ${candidate.address} (${candidate.interfaceName}, ${candidate.kind.lowercase()})"
            }
        }
        detectedAddressView.text = buildString {
            append(getString(R.string.effective_url_value, validation.effectiveUrl))
            append("\n\n")
            append(getString(R.string.detected_address_candidates_label))
            append("\n")
            append(candidateSummary)
        }

        val status = ProxyPreferences.reconcileStatus(this)
        latestStatus = status

        stateView.text = stateLabel(status)
        statusMessageView.text = status.message
        statusUrlView.text = status.activeUrl.ifBlank { getString(R.string.no_active_url) }

        val visibleError = status.error
        errorCategoryView.text = visibleError?.let(::errorLabel) ?: getString(R.string.error_state_healthy)
        errorCategoryView.setTextColor(ContextCompat.getColor(this, errorColor(visibleError?.category)))
        lastFailureView.text = visibleError?.detail ?: status.lastFailureReason.ifBlank { getString(R.string.no_failure_recorded) }
        lastExitCodeView.text = status.lastExitCode?.toString() ?: getString(R.string.no_exit_code)
        nextActionView.text = visibleError?.recommendedAction ?: defaultRecommendedAction(status)
        copyLastErrorButton.visibility = if (buildClipboardError(status) == null) View.GONE else View.VISIBLE

        startButton.isEnabled = status.state != ProxyRuntimeState.Starting && status.state != ProxyRuntimeState.Running
        stopButton.isEnabled = status.desiredRunning || status.isActive
    }

    private fun renderLogs() {
        logView.text = ProxyPreferences.readLogTail(this)
    }

    private fun stateLabel(status: ProxyStatus): String =
        when (status.state) {
            ProxyRuntimeState.Idle -> getString(R.string.state_stopped)
            ProxyRuntimeState.Starting -> "Starting"
            ProxyRuntimeState.Running -> getString(R.string.state_running)
            ProxyRuntimeState.Stopping -> "Stopping"
            ProxyRuntimeState.Failed -> "Failed"
        }

    private fun errorLabel(error: ProxyErrorInfo): String =
        when (error.category) {
            ProxyErrorCategory.MISSING_BINARY -> getString(R.string.error_state_missing_binary)
            ProxyErrorCategory.INVALID_CONFIG -> getString(R.string.error_state_invalid_config)
            ProxyErrorCategory.PORT_IN_USE -> getString(R.string.error_state_port_in_use)
            ProxyErrorCategory.PROXY_EXIT -> getString(R.string.error_state_proxy_exit)
            ProxyErrorCategory.PERMISSION_REQUIRED -> getString(R.string.error_state_permission)
            ProxyErrorCategory.STARTUP_FAILURE -> getString(R.string.error_state_startup_failure)
            ProxyErrorCategory.NONE -> getString(R.string.error_state_healthy)
        }

    private fun errorColor(category: ProxyErrorCategory?): Int =
        when (category) {
            null, ProxyErrorCategory.NONE -> R.color.proxy_success
            ProxyErrorCategory.INVALID_CONFIG -> R.color.proxy_warning
            ProxyErrorCategory.PERMISSION_REQUIRED -> R.color.proxy_warning
            ProxyErrorCategory.PORT_IN_USE -> R.color.proxy_error
            ProxyErrorCategory.MISSING_BINARY -> R.color.proxy_error
            ProxyErrorCategory.PROXY_EXIT -> R.color.proxy_error
            ProxyErrorCategory.STARTUP_FAILURE -> R.color.proxy_error
        }

    private fun defaultRecommendedAction(status: ProxyStatus): String =
        when {
            status.error != null -> status.error.recommendedAction
            !hasNotificationPermission() -> getString(R.string.notification_permission_required_message)
            status.state == ProxyRuntimeState.Running -> getString(R.string.next_action_running)
            else -> getString(R.string.next_action_idle)
        }

    private fun buildClipboardError(status: ProxyStatus?): String? {
        if (status == null) {
            return null
        }

        val error = status.error
        val detail = error?.detail ?: status.lastFailureReason
        if (detail.isBlank()) {
            return null
        }

        return buildString {
            append(error?.title ?: status.message)
            append('\n')
            append(detail)
            if (status.lastExitCode != null) {
                append("\nExit code: ")
                append(status.lastExitCode)
            }
            val action = error?.recommendedAction
            if (!action.isNullOrBlank()) {
                append("\nRecommended action: ")
                append(action)
            }
        }
    }
}
