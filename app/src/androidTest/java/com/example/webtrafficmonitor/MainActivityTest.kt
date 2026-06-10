package com.example.webtrafficmonitor

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test — runs on the connected phone. Confirms the main screen launches
 * and shows its controls. The monitoring services need user-granted permissions,
 * so those are verified by hand on the device.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    @Test
    fun mainScreen_showsControls() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.btn_capture)).check(matches(isDisplayed()))
            onView(withId(R.id.btn_accessibility)).check(matches(isDisplayed()))
            onView(withId(R.id.list)).check(matches(isDisplayed()))
        }
    }
}
