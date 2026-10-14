// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.benchmark

import android.content.Intent
import android.os.SystemClock
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class SplitBoxMacrobenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()
    private val iterations: Int
        get() = InstrumentationRegistry.getArguments().getString("splitbox.iterations", "5")
            .toInt().also { require(it > 0) { "Число повторов должно быть положительным" } }
    private val compilationName: String
        get() = InstrumentationRegistry.getArguments().getString("splitbox.compilation", "None")
            .lowercase(Locale.ROOT).also {
                require(it in setOf("none", "partial")) { "Режим компиляции должен быть None или Partial" }
            }
    private val compilationMode: CompilationMode
        get() = when (compilationName) {
            "none" -> CompilationMode.None()
            else -> CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require, warmupIterations = 0)
        }

    // Очистка до compilation позволяет одинаково проверять None и установленный Baseline Profile.
    private fun freshTarget(measure: () -> Unit) { clearTargetData(); measure() }

    @Test fun coldStart() = freshTarget {
        benchmark.measureRepeated(
            packageName = TARGET, metrics = listOf(StartupTimingMetric()), iterations = iterations,
            compilationMode = compilationMode, startupMode = StartupMode.COLD,
            setupBlock = { pressHome() },
        ) { launchMain() }
    }

    @Test fun settingsHistoryBack() = freshTarget {
        benchmark.measureRepeated(
            packageName = TARGET, metrics = listOf(FrameTimingMetric()), iterations = iterations,
            compilationMode = compilationMode,
            setupBlock = { pressHome(); launchMain() },
        ) { navigateSettingsHistoryBack() }
    }

    @Test fun importApk() = freshTarget {
        lateinit var source: Intent
        val observations = ImportObservations(compilationName, iterations)
        var completed = false
        var testFailure: Throwable? = null
        try {
            benchmark.measureRepeated(
                packageName = TARGET, metrics = listOf(StartupTimingMetric(), FrameTimingMetric()), iterations = iterations,
                compilationMode = compilationMode, startupMode = StartupMode.COLD,
                setupBlock = {
                    pressHome()
                    source = importIntent("application-${checkNotNull(iteration)}.apk")
                },
            ) {
                val sampleIteration = checkNotNull(iteration)
                // COLD force-stop после setupBlock отзывает grant; обновляем его от UID владельца файла.
                grantFixtureReadAccess(source)
                // Часы runner охватывают launch, ожидание первого кадра и наблюдение настоящего READY.
                val startedNs = SystemClock.elapsedRealtimeNanos()
                try {
                    importFixture(source)
                    observations.record(sampleIteration, SystemClock.elapsedRealtimeNanos() - startedNs, true)
                } catch (failure: Throwable) {
                    observations.record(sampleIteration, SystemClock.elapsedRealtimeNanos() - startedNs, false,
                        failure.message ?: failure.javaClass.simpleName)
                    throw failure
                }
            }
            completed = true
        } catch (failure: Throwable) {
            testFailure = failure
            throw failure
        } finally {
            // Запись артефакта вне измеряемых блоков; неудача не превращается в time-to-READY.
            try { observations.write(completed) } catch (outputFailure: Throwable) {
                val original = testFailure
                if (original != null) original.addSuppressed(outputFailure) else throw outputFailure
            }
        }
    }

    @Test fun splitList50() = splitList(50)
    @Test fun splitList500() = splitList(500)
    @Test fun splitList4096() = splitList(4096)

    private fun splitList(count: Int) = freshTarget {
        benchmark.measureRepeated(
            packageName = TARGET, metrics = listOf(FrameTimingMetric()), iterations = iterations,
            compilationMode = compilationMode,
            setupBlock = { pressHome(); launchSplitList(count) },
        ) { scrollAndToggleSplits(count) }
    }
}
