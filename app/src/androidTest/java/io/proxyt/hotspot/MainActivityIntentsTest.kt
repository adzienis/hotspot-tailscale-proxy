package io.proxyt.hotspot

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityIntentsTest {
    @Before
    fun setUp() {
        Intents.init()
    }

    @After
    fun tearDown() {
        Intents.release()
    }

    @Test
    fun shareLogsFiresChooserSendIntent() {
        val runtime = installFakeRuntimeDependencies()
        runtime.statusStore.appendLog("Starting proxy")
        runtime.statusStore.appendLog("Serving on http://192.168.43.1:8080")

        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("Logs")).perform(click())
            onView(withId(R.id.shareLogsButton)).perform(click())

            intended(hasAction(Intent.ACTION_CHOOSER))
        }
    }
}
