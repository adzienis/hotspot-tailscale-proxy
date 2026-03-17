package io.proxyt.hotspot

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class MainActivityFlowsTest {
    private lateinit var testRuntime: RecordingAppRuntime

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        ProxyPreferences.saveConfig(context, ProxyConfig())
        ProxyPreferences.setStatus(context, ProxyStatus())
        ProxyPreferences.clearLogs(context)
        testRuntime = RecordingAppRuntime()
        AppRuntimeHooks.delegate = testRuntime
    }

    @After
    fun tearDown() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        ProxyPreferences.saveConfig(context, ProxyConfig())
        ProxyPreferences.setStatus(context, ProxyStatus())
        ProxyPreferences.clearLogs(context)
        AppRuntimeHooks.reset()
    }

    @Test
    fun invalidConfigShowsValidationErrorWithoutStartingService() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(tabWithText(R.string.tab_advanced_diagnostics)).perform(click())
            onView(withId(R.id.portEdit)).perform(replaceText("70000"), closeSoftKeyboard())
            onView(withId(R.id.startButton)).perform(click())

            onView(withText("Enter a valid port between 1 and 65535")).check(matches(isDisplayed()))
            assertEquals(0, testRuntime.startCount.get())
        }
    }

    @Test
    fun successfulStartRendersRunningState() {
        testRuntime.permissionGranted = true
        testRuntime.onStart = { activity, config ->
            ProxyPreferences.setStatus(
                activity,
                ProxyStatus(
                    desiredRunning = true,
                    state = ProxyRuntimeState.Running,
                    activeUrl = if (config.advertisedBaseUrl.isBlank()) {
                        "http://192.168.43.1:${config.port}"
                    } else {
                        config.advertisedBaseUrl
                    },
                    message = "Serving on ${config.advertisedBaseUrl}",
                ),
            )
        }

        ActivityScenario.launch(MainActivity::class.java).use {
            onView(tabWithText(R.string.tab_advanced_diagnostics)).perform(click())
            onView(withId(R.id.baseUrlEdit)).perform(replaceText("http://192.168.43.1:8080"), closeSoftKeyboard())
            onView(withId(R.id.startButton)).perform(click())
            assertEquals(1, testRuntime.startCount.get())
            assertEquals("http://192.168.43.1:8080", ProxyPreferences.loadConfig(
                ApplicationProvider.getApplicationContext(),
            ).advertisedBaseUrl)
        }
    }

    private fun tabWithText(textRes: Int) = allOf(withText(textRes), isDescendantOfA(withId(R.id.mainTabLayout)))

    private class RecordingAppRuntime : AppRuntime {
        val startCount = AtomicInteger(0)
        var permissionGranted: Boolean = true
        var onStart: ((MainActivity, ProxyConfig) -> Unit)? = null

        override fun hasNotificationPermission(activity: androidx.appcompat.app.AppCompatActivity): Boolean =
            permissionGranted

        override fun startProxyService(activity: androidx.appcompat.app.AppCompatActivity, config: ProxyConfig) {
            startCount.incrementAndGet()
            onStart?.invoke(activity as MainActivity, config)
        }

        override fun stopProxyService(activity: androidx.appcompat.app.AppCompatActivity) = Unit

        override fun isIgnoringBatteryOptimizations(activity: androidx.appcompat.app.AppCompatActivity): Boolean = true
    }
}
