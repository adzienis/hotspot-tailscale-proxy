package io.proxyt.hotspot

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityValidationTest {
    @Test
    fun invalidPortShowsValidationErrorAndDoesNotStartService() {
        val runtime = installFakeRuntimeDependencies()

        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.portEdit)).perform(replaceText("70000"), closeSoftKeyboard())
            onView(withId(R.id.startButton)).perform(click())

            onView(withText("Enter a valid port between 1 and 65535")).check(matches(isDisplayed()))
            assertTrue(runtime.launcher.launchedRequests.isEmpty())
        }
    }
}
