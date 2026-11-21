package io.lawallet.wallet.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates app/src/release/generated/baselineProfiles/baseline-prof.txt:
 *   ./gradlew :app:generateReleaseBaselineProfile
 * Ahead-of-time compiles startup, onboarding and the logged-in screens so first uses don't run in the
 * interpreter/JIT (less jank, faster cold start). Library profiles (Compose etc.) merge in too.
 * The login goes to [FakeInstance] with a throwaway key: no real instance or account is involved.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()
    @get:Rule val instance = FakeInstance()

    @Test
    fun generate() = rule.collect(packageName = targetPackage, includeInStartupProfile = true) {
        startToLanding()
        onboardingJourney()
        loggedInJourney()
    }
}
