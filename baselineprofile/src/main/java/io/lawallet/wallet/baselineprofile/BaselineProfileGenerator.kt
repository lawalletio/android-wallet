package io.lawallet.wallet.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates app/src/release/generated/baselineProfiles/baseline-prof.txt:
 *   ./gradlew :app:generateBaselineProfile
 * Ahead-of-time compiles the startup + onboarding code so first launches don't run in the
 * interpreter/JIT (less jank, faster cold start). Library profiles (Compose etc.) merge in too.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = targetPackage, includeInStartupProfile = true) {
        startToLanding()
        onboardingJourney()
    }
}
