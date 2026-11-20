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

/**
 * Logs in with a throwaway key against [FakeInstance] and walks the logged-in screens: Home (both deck pages and
 * the wallet list), Receive, Send, Scan, Settings and Profile. Starts at the landing and logs out at the end, so
 * the next iteration starts there again.
 */
fun MacrobenchmarkScope.loggedInJourney() {
    device.executeShellCommand("pm grant $packageName android.permission.CAMERA")
    tap("landing-login")
    tap("login-nostr")
    await("nsec-input")
    device.findObject(By.res("nsec-input")).text = FakeInstance.throwawaySecret()
    tap("nsec-submit")
    await("home-root", 30_000)
    await("nav-receive")

    // Home's pages: Onchain is left of Lightning.
    swipe(0.2f, 0.45f, 0.8f, 0.45f, until = "home-onchain")
    swipe(0.8f, 0.45f, 0.2f, 0.45f, until = "home-lightning")
    // Swipe down: the wallet list (Home's "deck"); swipe up puts it away.
    swipe(0.5f, 0.42f, 0.5f, 0.79f, until = "home-wallets")
    swipe(0.5f, 0.71f, 0.5f, 0.25f, until = "home-wallet-pill")

    visit("nav-receive", "receive-address")
    visit("nav-send", "recipient-input")
    visit("nav-scan", null)
    visit("home-settings", "settings-language")

    tap("avatar")
    tap("profile-edit")
    await("profile-save")
    device.pressBack()
    tap("profile-logout")
    tap("modal-action")
    await("landing-create", 30_000)
}

/** Swipes between two points (fractions of the screen) until [until] shows; retried, as one that lands mid-animation is lost. */
private fun MacrobenchmarkScope.swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, until: String) {
    val (w, h) = device.displayWidth to device.displayHeight
    repeat(3) {
        device.swipe((w * fromX).toInt(), (h * fromY).toInt(), (w * toX).toInt(), (h * toY).toInt(), 60)
        if (device.wait(Until.hasObject(By.res(until)), 3_000)) return device.waitForIdle()
    }
    error("swipe never showed $until")
}

/** Opens a screen from Home, waits for [ready] (or a beat, for the camera), and goes back to Home. */
private fun MacrobenchmarkScope.visit(from: String, ready: String?) {
    tap(from)
    if (ready != null) await(ready) else Thread.sleep(2_000)
    // A focused field (Send's recipient) takes the first back.
    repeat(3) {
        device.pressBack()
        if (device.wait(Until.hasObject(By.res("home-root")), 2_000)) return device.waitForIdle()
    }
    error("no way back to home-root from $from")
}
