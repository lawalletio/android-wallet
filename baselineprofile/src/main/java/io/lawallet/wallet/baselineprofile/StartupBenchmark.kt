package io.lawallet.wallet.baselineprofile

import androidx.benchmark.macro.ArtMetric
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold start and navigation smoothness, with and without the baseline profile:
 *   ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest
 * Compare `timeToInitialDisplayMs` and `frameDurationCpuMs` P50/P90 between the two modes. `ArtMetric` counts the JIT
 * compiles and class verifications the profile should remove: unlike timings, emulator load doesn't skew them.
 */
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
@LargeTest
class StartupBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()
    @get:Rule val instance = FakeInstance()

    @Test fun startupNoCompilation() = startup(CompilationMode.None())

    @Test fun startupBaselineProfile() = startup(CompilationMode.Partial(BaselineProfileMode.Require))

    @Test fun onboardingFramesBaselineProfile() = rule.measureRepeated(
        packageName = targetPackage,
        metrics = listOf(FrameTimingMetric(), ArtMetric()),
        compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
        iterations = 5,
        startupMode = StartupMode.COLD,
        setupBlock = { pressHome() },
    ) {
        startToLanding()
        onboardingJourney()
    }

    @Test fun loggedInFramesBaselineProfile() = rule.measureRepeated(
        packageName = targetPackage,
        metrics = listOf(FrameTimingMetric(), ArtMetric()),
        compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
        iterations = 5,
        startupMode = StartupMode.COLD,
        setupBlock = { pressHome() },
    ) {
        startToLanding()
        loggedInJourney()
    }

    private fun startup(mode: CompilationMode) = rule.measureRepeated(
        packageName = targetPackage,
        metrics = listOf(StartupTimingMetric(), ArtMetric()),
        compilationMode = mode,
        iterations = 8,
        startupMode = StartupMode.COLD,
    ) {
        startToLanding()
    }
}
