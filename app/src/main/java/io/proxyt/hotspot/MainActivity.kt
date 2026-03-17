package io.proxyt.hotspot

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class MainActivity : AppCompatActivity() {
    private lateinit var tabLayout: TabLayout
    private lateinit var quickStartTabContent: View
    private lateinit var advancedDiagnosticsTabContent: View
    private lateinit var stateView: TextView
    private lateinit var statusMessageView: TextView
    private lateinit var statusUrlView: TextView
    private lateinit var quickStartSummaryView: TextView
    private lateinit var readinessSummaryView: TextView
    private lateinit var notificationReadinessView: TextView
    private lateinit var hotspotReadinessView: TextView
    private lateinit var batteryReadinessView: TextView
    private lateinit var backgroundGuidanceView: TextView
    private lateinit var errorCategoryView: TextView
    private lateinit var lastFailureView: TextView
    private lateinit var lastExitCodeView: TextView
    private lateinit var nextActionView: TextView
    private lateinit var currentPidView: TextView
    private lateinit var selectedInterfaceView: TextView
    private lateinit var selectedIpView: TextView
    private lateinit var selectedRouteView: TextView
    private lateinit var portBindResultView: TextView
    private lateinit var lastProbeResultView: TextView
    private lateinit var probeTargetView: TextView
    private lateinit var probeDetailView: TextView
    private lateinit var hotspotActiveView: TextView
    private lateinit var startupEventsView: TextView
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
    private lateinit var shareDiagnosticsButton: MaterialButton
    private lateinit var shareLogsButton: MaterialButton
    private lateinit var batteryOptimizationButton: MaterialButton
    private lateinit var openSettingsButton: MaterialButton

    private val connectivityManager: ConnectivityManager? by lazy {
        getSystemService(ConnectivityManager::class.java)
    }

    private var latestStatus: ProxyStatus? = null
    private var pendingStartConfig: ProxyConfig? = null
    private lateinit var statusPreferenceListener: SharedPreferences.OnSharedPreferenceChangeListener
    private var localCandidates: List<HotspotAddressCandidate> = emptyList()
    private var suppressValidationCallbacks = false
    private var networkCallbackRegistered = false
    private var logObserver: FileObserver? = null
    private var selectedTabIndex = MainTab.QuickStart.ordinal

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

    private val batteryOptimizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        renderStatus()
    }

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val networkRefreshRunnable = Runnable {
        refreshLocalAddressOptions()
        renderStatus()
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scheduleLocalAddressRefresh()
        }

        override fun onLost(network: Network) {
            scheduleLocalAddressRefresh()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            scheduleLocalAddressRefresh()
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            scheduleLocalAddressRefresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        selectedTabIndex = savedInstanceState?.getInt(KEY_SELECTED_TAB) ?: MainTab.QuickStart.ordinal
        tabLayout = findViewById(R.id.mainTabLayout)
        quickStartTabContent = findViewById(R.id.quickStartTabContent)
        advancedDiagnosticsTabContent = findViewById(R.id.advancedDiagnosticsTabContent)
        stateView = findViewById(R.id.stateValueText)
        statusMessageView = findViewById(R.id.statusMessageText)
        statusUrlView = findViewById(R.id.statusUrlText)
        quickStartSummaryView = findViewById(R.id.quickStartSummaryText)
        readinessSummaryView = findViewById(R.id.readinessSummaryText)
        notificationReadinessView = findViewById(R.id.notificationReadinessText)
        hotspotReadinessView = findViewById(R.id.hotspotReadinessText)
        batteryReadinessView = findViewById(R.id.batteryReadinessText)
        backgroundGuidanceView = findViewById(R.id.backgroundGuidanceText)
        errorCategoryView = findViewById(R.id.errorCategoryText)
        lastFailureView = findViewById(R.id.lastFailureText)
        lastExitCodeView = findViewById(R.id.lastExitCodeText)
        nextActionView = findViewById(R.id.nextActionText)
        currentPidView = findViewById(R.id.currentPidText)
        selectedInterfaceView = findViewById(R.id.selectedInterfaceText)
        selectedIpView = findViewById(R.id.selectedIpText)
        selectedRouteView = findViewById(R.id.selectedRouteText)
        portBindResultView = findViewById(R.id.portBindResultText)
        lastProbeResultView = findViewById(R.id.lastProbeResultText)
        probeTargetView = findViewById(R.id.probeTargetText)
        probeDetailView = findViewById(R.id.probeDetailText)
        hotspotActiveView = findViewById(R.id.hotspotActiveText)
        startupEventsView = findViewById(R.id.startupEventsText)
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
        shareDiagnosticsButton = findViewById(R.id.shareDiagnosticsButton)
        shareLogsButton = findViewById(R.id.shareLogsButton)
        batteryOptimizationButton = findViewById(R.id.batteryOptimizationButton)
        openSettingsButton = findViewById(R.id.openSettingsButton)

        setupTabs()

        ProxyPreferences.reconcileStatus(this)
        statusPreferenceListener = ProxyPreferences.registerStatusListener(this) {
            runOnUiThread { renderStatus() }
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

        shareLogsButton.setOnClickListener {
            shareLogs()
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
            Toast.makeText(this, R.string.copied_control_url, Toast.LENGTH_SHORT).show()
        }

        shareDiagnosticsButton.setOnClickListener {
            shareDiagnostics()
        }

        batteryOptimizationButton.setOnClickListener {
            requestBatteryOptimizationExemption()
        }

        openSettingsButton.setOnClickListener {
            openAppSettings()
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
            appRuntime().stopProxyService(this)
        }

        renderStatus()
        renderLogs()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(KEY_SELECTED_TAB, selectedTabIndex)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        registerNetworkMonitoring()
        renderStatus()
        startLogObserver()
        renderLogs()
    }

    override fun onStop() {
        unregisterNetworkMonitoring()
        stopLogObserver()
        refreshHandler.removeCallbacks(networkRefreshRunnable)
        super.onStop()
    }

    override fun onDestroy() {
        ProxyPreferences.unregisterStatusListener(this, statusPreferenceListener)
        super.onDestroy()
    }

    private fun appRuntime(): AppRuntime = AppRuntimeHooks.delegate

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
        appRuntime().startProxyService(this, config)
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
        return appRuntime().hasNotificationPermission(this)
    }

    private fun setupTabs() {
        if (tabLayout.tabCount == 0) {
            MainTab.entries.forEach { tab ->
                tabLayout.addTab(tabLayout.newTab().setText(tab.titleRes))
            }
        }
        tabLayout.addOnTabSelectedListener(
            object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    selectTab(tab.position)
                }

                override fun onTabUnselected(tab: TabLayout.Tab) = Unit

                override fun onTabReselected(tab: TabLayout.Tab) = Unit
            },
        )
        val initialIndex = selectedTabIndex.coerceIn(MainTab.QuickStart.ordinal, MainTab.AdvancedDiagnostics.ordinal)
        tabLayout.getTabAt(initialIndex)?.select()
        selectTab(initialIndex)
    }

    private fun selectTab(index: Int) {
        selectedTabIndex = index.coerceIn(MainTab.QuickStart.ordinal, MainTab.AdvancedDiagnostics.ordinal)
        quickStartTabContent.visibility = if (selectedTabIndex == MainTab.QuickStart.ordinal) View.VISIBLE else View.GONE
        advancedDiagnosticsTabContent.visibility = if (selectedTabIndex == MainTab.AdvancedDiagnostics.ordinal) View.VISIBLE else View.GONE
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
        baseUrlLayout.helperText = validation.baseUrlWarning ?: getString(R.string.base_url_helper_text)
        localAddressLayout.helperText = validation.localAddressWarning ?: getString(R.string.local_address_helper_text)
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
        val routeSummary = buildSelectedRouteSummary(validation.effectiveUrl, status.diagnostics)

        stateView.text = stateLabel(status)
        statusMessageView.text = status.message
        statusUrlView.text = status.activeUrl.ifBlank { getString(R.string.no_active_url) }
        quickStartSummaryView.text = quickStartSummary(status, validation.effectiveUrl)
        readinessSummaryView.text = readinessSummaryText(status)
        notificationReadinessView.text = notificationReadinessText()
        hotspotReadinessView.text = hotspotReadinessText(status)
        batteryReadinessView.text = batteryReadinessText()
        backgroundGuidanceView.text = backgroundGuidanceText()
        selectedRouteView.text = routeSummary

        val visibleError = status.error
        errorCategoryView.text = visibleError?.let(::errorLabel) ?: getString(R.string.error_state_healthy)
        errorCategoryView.setTextColor(ContextCompat.getColor(this, errorColor(visibleError?.category)))
        lastFailureView.text = visibleError?.detail ?: status.lastFailureReason.ifBlank { getString(R.string.no_failure_recorded) }
        lastExitCodeView.text = status.lastExitCode?.toString() ?: getString(R.string.no_exit_code)
        nextActionView.text = visibleError?.recommendedAction ?: defaultRecommendedAction(status)
        currentPidView.text = status.diagnostics.currentPid?.toString() ?: getString(R.string.diagnostics_not_available)
        selectedInterfaceView.text = buildSelectedInterfaceLabel(status.diagnostics)
        selectedIpView.text = status.diagnostics.selectedIp.ifBlank { getString(R.string.diagnostics_not_available) }
        portBindResultView.text = status.diagnostics.portBindResult.ifBlank { getString(R.string.diagnostics_not_available) }
        lastProbeResultView.text = status.diagnostics.lastProbeStatus.ifBlank { getString(R.string.diagnostics_not_available) }
        probeTargetView.text = status.diagnostics.lastProbeTarget.ifBlank { getString(R.string.diagnostics_not_available) }
        probeDetailView.text = status.diagnostics.lastProbeDetail.ifBlank { getString(R.string.diagnostics_not_available) }
        hotspotActiveView.text = if (isHotspotCurrentlyActive()) {
            getString(R.string.hotspot_active_yes)
        } else {
            getString(R.string.hotspot_active_no)
        }
        copyLastErrorButton.visibility = if (buildClipboardError(status) == null) View.GONE else View.VISIBLE
        batteryOptimizationButton.visibility = if (isIgnoringBatteryOptimizations()) View.GONE else View.VISIBLE

        startButton.isEnabled = status.state != ProxyRuntimeState.Starting && status.state != ProxyRuntimeState.Running
        stopButton.isEnabled = status.desiredRunning || status.isActive
    }

    private fun renderLogs() {
        logView.text = ProxyPreferences.readLogTail(this)
        startupEventsView.text = ProxyPreferences.readStartupEventSummary(this)
    }

    private fun stateLabel(status: ProxyStatus): String =
        when (status.state) {
            ProxyRuntimeState.Idle -> getString(R.string.state_stopped)
            ProxyRuntimeState.Starting -> "Starting"
            ProxyRuntimeState.Running -> getString(R.string.state_running)
            ProxyRuntimeState.Stopping -> "Stopping"
            ProxyRuntimeState.Failed -> "Failed"
        }

    private fun buildSelectedRouteSummary(effectiveUrl: String, diagnostics: ProxyDiagnostics): String {
        if (diagnostics.selectedIp.isBlank() && diagnostics.selectedInterface.isBlank()) {
            return getString(R.string.selected_route_manual, effectiveUrl)
        }
        return getString(
            R.string.selected_route_value,
            diagnostics.selectedInterface.ifBlank { getString(R.string.diagnostics_not_available) },
            diagnostics.selectedInterfaceKind.ifBlank { getString(R.string.diagnostics_not_available) },
            diagnostics.selectedIp.ifBlank { getString(R.string.diagnostics_not_available) },
            effectiveUrl,
        )
    }

    private fun buildSelectedInterfaceLabel(diagnostics: ProxyDiagnostics): String {
        if (diagnostics.selectedInterface.isBlank()) {
            return getString(R.string.diagnostics_not_available)
        }
        return if (diagnostics.selectedInterfaceKind.isBlank()) {
            diagnostics.selectedInterface
        } else {
            "${diagnostics.selectedInterface} (${diagnostics.selectedInterfaceKind})"
        }
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
            !isIgnoringBatteryOptimizations() -> getString(R.string.battery_optimization_active)
            status.state == ProxyRuntimeState.Running -> getString(R.string.next_action_running)
            else -> getString(R.string.next_action_idle)
        }

    private fun quickStartSummary(status: ProxyStatus, effectiveUrl: String): String =
        when {
            status.state == ProxyRuntimeState.Running && status.activeUrl.isNotBlank() ->
                getString(R.string.quick_start_running_summary)
            status.error != null ->
                getString(R.string.quick_start_error_summary)
            !isHotspotCurrentlyActive() ->
                getString(R.string.quick_start_hotspot_disabled)
            effectiveUrl.isNotBlank() ->
                getString(R.string.quick_start_ready_to_start)
            else ->
                getString(R.string.detected_address_missing)
        }

    private fun readinessSummaryText(status: ProxyStatus): String =
        if (
            hasNotificationPermission() &&
            isIgnoringBatteryOptimizations() &&
            isHotspotCurrentlyActive()
        ) {
            getString(R.string.readiness_summary_ready)
        } else {
            getString(R.string.readiness_summary_action_needed)
        }

    private fun notificationReadinessText(): String =
        if (hasNotificationPermission()) {
            getString(R.string.readiness_notifications_ready)
        } else {
            getString(R.string.readiness_notifications_blocked)
        }

    private fun hotspotReadinessText(status: ProxyStatus): String =
        if (isHotspotCurrentlyActive()) getString(R.string.readiness_hotspot_ready)
        else getString(R.string.readiness_hotspot_disabled)

    private fun isHotspotCurrentlyActive(): Boolean =
        localCandidates.any { candidate ->
            candidate.kind == "Hotspot" || candidate.kind == "USB tethering"
        }

    private fun batteryReadinessText(): String =
        if (isIgnoringBatteryOptimizations()) {
            getString(R.string.readiness_battery_ready)
        } else {
            getString(R.string.readiness_battery_blocked)
        }

    private fun backgroundGuidanceText(): String =
        when {
            isIgnoringBatteryOptimizations() -> getString(R.string.battery_optimization_ignored)
            Build.MANUFACTURER.equals("motorola", ignoreCase = true) -> getString(R.string.battery_background_motorola)
            else -> getString(R.string.battery_background_generic)
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

    private fun registerNetworkMonitoring() {
        if (networkCallbackRegistered) {
            return
        }
        val manager = connectivityManager ?: return
        val request = NetworkRequest.Builder().build()
        runCatching {
            manager.registerNetworkCallback(request, networkCallback)
            networkCallbackRegistered = true
        }
    }

    private fun unregisterNetworkMonitoring() {
        if (!networkCallbackRegistered) {
            return
        }
        val manager = connectivityManager ?: return
        runCatching {
            manager.unregisterNetworkCallback(networkCallback)
        }
        networkCallbackRegistered = false
    }

    private fun scheduleLocalAddressRefresh() {
        refreshHandler.removeCallbacks(networkRefreshRunnable)
        refreshHandler.postDelayed(networkRefreshRunnable, 500)
    }

    private fun isIgnoringBatteryOptimizations(): Boolean =
        appRuntime().isIgnoringBatteryOptimizations(this)

    private fun requestBatteryOptimizationExemption() {
        val requestIntent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName"),
        )
        val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        val launchIntent = when {
            requestIntent.resolveActivity(packageManager) != null -> requestIntent
            fallbackIntent.resolveActivity(packageManager) != null -> fallbackIntent
            else -> return
        }
        batteryOptimizationLauncher.launch(launchIntent)
    }

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            ),
        )
    }

    private fun startLogObserver() {
        if (logObserver != null) {
            return
        }

        val logFileName = ProxyPreferences.logFile(this).name
        logObserver = object : FileObserver(filesDir.absolutePath, CREATE or MODIFY or CLOSE_WRITE or MOVED_TO) {
            override fun onEvent(event: Int, path: String?) {
                if (path != logFileName) {
                    return
                }
                runOnUiThread { renderLogs() }
            }
        }.also(FileObserver::startWatching)
    }

    private fun stopLogObserver() {
        logObserver?.stopWatching()
        logObserver = null
    }

    private fun shareLogs() {
        val logFile = ProxyPreferences.prepareShareLogFile(this)
        if (logFile == null || !logFile.exists()) {
            Toast.makeText(this, R.string.no_logs_to_share, Toast.LENGTH_SHORT).show()
            return
        }

        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", logFile)
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_logs_subject))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                getString(R.string.share_logs),
            ),
        )
    }

    private fun shareDiagnostics() {
        val report = buildDiagnosticsReport()
        val logFile = ProxyPreferences.logFile(this)
        val chooserIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_diagnostics_subject))
            putExtra(Intent.EXTRA_TEXT, report)
        }

        if (logFile.exists()) {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", logFile)
            chooserIntent.putExtra(Intent.EXTRA_STREAM, uri)
            chooserIntent.clipData = ClipData.newUri(contentResolver, getString(R.string.share_logs_subject), uri)
            chooserIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        startActivity(Intent.createChooser(chooserIntent, getString(R.string.share_diagnostics)))
        Toast.makeText(this, R.string.diagnostics_shared, Toast.LENGTH_SHORT).show()
    }

    private fun buildDiagnosticsReport(): String {
        val status = latestStatus ?: ProxyPreferences.reconcileStatus(this)
        val validation = validateUi(showErrors = false)
        return buildString {
            appendLine(getString(R.string.app_name))
            appendLine("State: ${stateLabel(status)}")
            appendLine("Message: ${status.message}")
            appendLine("Active URL: ${status.activeUrl.ifBlank { getString(R.string.no_active_url) }}")
            appendLine("Effective URL: ${validation.effectiveUrl.ifBlank { getString(R.string.no_active_url) }}")
            appendLine("Notifications allowed: ${if (hasNotificationPermission()) "yes" else "no"}")
            appendLine("Battery optimization exempt: ${if (isIgnoringBatteryOptimizations()) "yes" else "no"}")
            appendLine("Private hotspot/LAN IP detected: ${if (localCandidates.isNotEmpty()) "yes" else "no"}")
            appendLine("Manufacturer: ${Build.MANUFACTURER}")
            appendLine("Model: ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine()
            appendLine("Diagnostics")
            appendLine("Selected interface: ${status.diagnostics.selectedInterface.ifBlank { "n/a" }}")
            appendLine("Selected IP: ${status.diagnostics.selectedIp.ifBlank { "n/a" }}")
            appendLine("Current PID: ${status.diagnostics.currentPid?.toString() ?: "n/a"}")
            appendLine("Port bind result: ${status.diagnostics.portBindResult.ifBlank { "n/a" }}")
            appendLine("Last probe status: ${status.diagnostics.lastProbeStatus.ifBlank { "n/a" }}")
            appendLine("Last probe target: ${status.diagnostics.lastProbeTarget.ifBlank { "n/a" }}")
            appendLine("Last probe detail: ${status.diagnostics.lastProbeDetail.ifBlank { "n/a" }}")
            appendLine("Hotspot active: ${isHotspotCurrentlyActive()}")
            appendLine("Last exit code: ${status.lastExitCode?.toString() ?: "n/a"}")
            appendLine("Last failure: ${status.error?.detail ?: status.lastFailureReason.ifBlank { "n/a" }}")
            appendLine("Recommended action: ${status.error?.recommendedAction ?: defaultRecommendedAction(status)}")
        }
    }

    private enum class MainTab(val titleRes: Int) {
        QuickStart(R.string.tab_quick_start),
        AdvancedDiagnostics(R.string.tab_advanced_diagnostics),
    }

    companion object {
        private const val KEY_SELECTED_TAB = "selected_tab"
    }
}
