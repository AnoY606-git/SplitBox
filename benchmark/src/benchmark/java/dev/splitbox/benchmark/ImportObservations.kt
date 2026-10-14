// Copyright (C) 2026 AnoY606-git and SplitBox contributors
// SPDX-License-Identifier: GPL-3.0-only
package dev.splitbox.benchmark

import android.os.Build
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Wall-clock до наблюдаемого READY, включая запуск и накладные расходы UIAutomator; не parser CPU. */
internal class ImportObservations(private val compilation: String, private val expectedIterations: Int) {
    private val samples = JSONArray()
    private val failures = JSONArray()
    private val fixtureSha256 = run {
        val digest = MessageDigest.getInstance("SHA-256")
        InstrumentationRegistry.getInstrumentation().context.assets.open("fixtures/application.apk").use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun record(iteration: Int, durationNs: Long, ready: Boolean, failure: String? = null) {
        if (ready) samples.put(JSONObject().put("iteration", iteration).put("durationNs", durationNs))
        else failures.put(JSONObject().put("iteration", iteration).put("failure", failure ?: "Неизвестная ошибка"))
    }

    @Suppress("DEPRECATION") // API34+: media dir доступен shell для выгрузки test artifacts.
    fun write(completed: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.context
        val configured = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val directory = configured?.let(::File) ?: checkNotNull(context.externalMediaDirs.firstOrNull()) {
            "Нет каталога для benchmark artifacts; задайте additionalTestOutputDir"
        }
        check(directory.isDirectory || directory.mkdirs()) { "Не удалось создать каталог benchmark artifacts" }
        val file = File(directory, "splitbox-import-observed-ready-$compilation-${System.currentTimeMillis()}.json")
        val json = JSONObject().put("schemaVersion", 1).put("metric", "uriDispatchToObservedReady")
            .put("clock", "elapsedRealtimeNanos").put("unit", "ns").put("targetPackage", TARGET)
            .put("compilation", compilation).put("warmupIterations", 0)
            .put("expectedIterations", expectedIterations).put("sampleCount", samples.length())
            .put("completed", completed && samples.length() == expectedIterations && failures.length() == 0)
            .put("fixture", "fixtures/application.apk").put("fixtureSha256", fixtureSha256)
            .put("sourceDisplayNamePattern", "application-{iteration}.apk")
            .put("firstIterationState", "empty").put("subsequentIterationState", "prior-ready-persisted")
            .put("cacheResetBetweenIterations", false)
            .put("fingerprint", Build.FINGERPRINT).put("model", Build.MODEL)
            .put("sdk", Build.VERSION.SDK_INT).put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("fontScale", context.resources.configuration.fontScale.toDouble())
            .put("includesUiAutomatorObservationOverhead", true).put("parserCpuTime", false)
            .put("samples", samples).put("failures", failures)
        file.writeText(json.toString(2))
        // Протокол additionalTestOutputFile используется AGP/AndroidX для выгрузки файла на host.
        instrumentation.sendStatus(2, Bundle().apply {
            putString("additionalTestOutputFile_splitboxImportObservedReady", file.absolutePath)
            putString("stream", "\nSplitBox import observation JSON: ${file.absolutePath}\n")
        })
    }
}
