package io.lawallet.wallet.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until

/** The app under test (".bench" build); passed by the baseline-profile Gradle plugin. */
val targetPackage: String
    get() = InstrumentationRegistry.getArguments().getString("targetAppId")
        ?: error("targetAppId not passed as an instrumentation argument")

private fun MacrobenchmarkScope.tap(tag: String, timeoutMs: Long = 15_000) {
    device.wait(Until.hasObject(By.res(tag)), timeoutMs)
    device.findObject(By.res(tag))?.click() ?: error("missing $tag")
    device.waitForIdle()
}

private fun MacrobenchmarkScope.await(tag: String, timeoutMs: Long = 15_000) {
    check(device.wait(Until.hasObject(By.res(tag)), timeoutMs)) { "timed out waiting for $tag" }
}

/** Cold start into the logged-out landing (a fresh .bench install has no session). */
fun MacrobenchmarkScope.startToLanding() {
    // A fresh install asks for notifications on first launch; that dialog would hide the UI.
    device.executeShellCommand("pm grant $packageName android.permission.POST_NOTIFICATIONS")
    pressHome()
    startActivityAndWait()
    await("landing-create", 30_000)
}

/**
 * The onboarding paths every new user walks: create account (key generation + backup screen)
 * and login with a Nostr key. Pure UI — nothing is sent to an instance.
 */
fun MacrobenchmarkScope.onboardingJourney() {
    tap("landing-create")
    tap("signup-random")
    await("create-continue")
    tap("confirm-saved")
    device.pressBack()
    await("signup-random")
    device.pressBack()
    tap("landing-login")
    tap("login-nostr")
    await("nsec-input")
    device.pressBack()
    device.pressBack()
    await("landing-create")
}
