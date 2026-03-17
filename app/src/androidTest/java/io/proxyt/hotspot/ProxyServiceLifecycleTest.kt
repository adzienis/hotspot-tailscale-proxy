package io.proxyt.hotspot

import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProxyServiceLifecycleTest {
    @Test
    fun startThenStopUpdatesPersistedRuntimeStateWithFakeDependencies() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val runtime = installFakeRuntimeDependencies(context)

        ContextCompat.startForegroundService(
            context,
            Intent(context, ProxyService::class.java).apply {
                action = ProxyService.ACTION_START
                putExtra(ProxyService.EXTRA_PORT, 8080)
                putExtra(ProxyService.EXTRA_SELECTED_LOCAL_ADDRESS, "192.168.43.1")
            },
        )

        val runningStatus = runtime.statusStore.awaitStatus {
            it.state == ProxyRuntimeState.Running
        }
        assertEquals(ProxyRuntimeState.Running, runningStatus.state)
        assertTrue(runtime.launcher.launchedRequests.isNotEmpty())

        context.startService(
            Intent(context, ProxyService::class.java).apply {
                action = ProxyService.ACTION_STOP
            },
        )

        val stoppedStatus = runtime.statusStore.awaitStatus {
            it.state == ProxyRuntimeState.Idle
        }
        assertEquals(ProxyRuntimeState.Idle, stoppedStatus.state)
    }
}
