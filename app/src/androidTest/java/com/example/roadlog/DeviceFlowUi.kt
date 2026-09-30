package com.example.roadlog

import androidx.test.platform.app.InstrumentationRegistry
import android.os.SystemClock
import android.view.View
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.NoMatchingViewException
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matcher
import org.hamcrest.Description
import org.hamcrest.TypeSafeMatcher
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

/** Bounded accessibility-state waits; avoids fixed sleeps for coroutine-driven Activity updates. */
internal object DeviceFlowUi {
    private const val DEFAULT_TIMEOUT_MS = 7_000L

    fun awaitViewLaidOut(viewId: Int, timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
        val laidOut = object : TypeSafeMatcher<View>() {
            override fun describeTo(description: Description) {
                description.appendText("view with completed layout and nonzero dimensions")
            }

            override fun matchesSafely(view: View): Boolean =
                view.isLaidOut && !view.isLayoutRequested && view.width > 0 && view.height > 0
        }
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            try {
                onView(withId(viewId)).check(matches(laidOut))
                return
            } catch (error: AssertionError) {
                if (SystemClock.uptimeMillis() >= deadline) throw error
            } catch (error: NoMatchingViewException) {
                if (SystemClock.uptimeMillis() >= deadline) throw error
            }
            SystemClock.sleep(50L)
        }
    }

    /** Checks full View text, including content outside the accessibility viewport. */
    fun awaitViewTextContains(viewId: Int, text: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
        awaitMatchingText(withId(viewId), text, timeoutMs)
    }

    fun awaitTextInView(text: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
        awaitMatchingText(withText(containsString(text)), text, timeoutMs)
    }

    private fun awaitMatchingText(matcher: Matcher<View>, text: String, timeoutMs: Long) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            try {
                onView(matcher).check(matches(withText(containsString(text))))
                return
            } catch (error: AssertionError) {
                if (SystemClock.uptimeMillis() >= deadline) throw error
            } catch (error: NoMatchingViewException) {
                if (SystemClock.uptimeMillis() >= deadline) throw error
            }
            SystemClock.sleep(50L)
        }
    }

    fun awaitTextContains(text: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        check(device.wait(Until.hasObject(By.textContains(text)), timeoutMs)) {
            "Timed out waiting for UI text containing: $text"
        }
    }

    fun awaitTextGone(text: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        check(device.wait(Until.gone(By.textContains(text)), timeoutMs)) {
            "Timed out waiting for UI text to disappear: $text"
        }
    }

    fun clickTextContains(text: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
        awaitTextContains(text, timeoutMs)
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            .findObject(By.textContains(text))
            .click()
    }

    fun pressBack() {
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
    }

    fun clickTextIfVisible(text: String, timeoutMs: Long = 1_000L): Boolean {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val target = device.wait(Until.findObject(By.textContains(text)), timeoutMs) ?: return false
        target.click()
        return true
    }

    fun clickPermissionDenyIfVisible(timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val denyButton = By.text(
            Pattern.compile("(?i)(don't allow|don’t allow|deny)")
        )
        val target = device.wait(Until.findObject(denyButton), timeoutMs) ?: return false
        target.click()
        return true
    }
}
