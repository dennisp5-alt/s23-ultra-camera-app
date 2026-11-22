package com.dennisp.s23lab

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dennisp.s23lab.core.BenchProtocol
import com.dennisp.s23lab.core.MemoryReport
import com.dennisp.s23lab.core.ThermalPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val bg = Color(0xFF0D1422)
private val panel = Color(0xFF182334)
private val accent = Color(0xFF67E9D1)
private val muted = Color(0xFFAABCD0)
private val warn = Color(0xFFFFC46B)

/** UI only. All model state lives in LabViewModel, which survives rotation and other configuration changes. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = accent, onPrimary = bg, background = bg, onBackground = Color.White,
                surface = panel, onSurface = Color.White, onSurfaceVariant = Color(0xFFB8C8D9))) {
                LabScreen(viewModel(), ::saveText, ::shareText)
            }
        }
    }

    private fun saveText(uri: Uri, text: String) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } }
        }
    }

    private fun shareText(text: String) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "S23 Local AI Lab results")
            putExtra(Intent.EXTRA_TEXT, text)
        }, "Share results"))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LabScreen(vm: LabViewModel, save: (Uri, String) -> Unit, share: (String) -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val engine by vm.engineState.collectAsStateWithLifecycle()
    var prompt by rememberSaveable { mutableStateOf("convert 25 C to F") }
    var pendingExport by remember { mutableStateOf<String?>(null) }
    val busy = ui.working || engine.busy != null

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importModel(uri) }
    val saveJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val text = pendingExport
        if (uri != null && text != null) save(uri, text)
        pendingExport = null
    }
    val saveCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val text = pendingExport
        if (uri != null && text != null) save(uri, text)
        pendingExport = null
    }

    Column(
        Modifier.fillMaxSize().background(bg).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("S23 / LOCAL AI LAB", color = accent)
        Text("Intelligence, on device.", color = Color.White, style = MaterialTheme.typography.headlineMedium)
        Text("v" + BuildConfig.VERSION_NAME + " • PRIVATE • OFFLINE • CPU / GPU • measured, not estimated", color = muted, style = MaterialTheme.typography.bodySmall)

        if (ui.notice.isNotBlank()) Text(ui.notice, color = warn)
        if (busy) {
            Text((ui.task ?: engine.busy ?: ui.importing ?: "Working") + (if (ui.progress.isNotBlank()) " — " + ui.progress else ""), color = accent)
            OutlinedButton(onClick = vm::stop) { Text("Stop") }
        } else if (ui.progress.isNotBlank()) Text(ui.progress, color = muted)

        Panel("01 / Model") {
            val m = ui.manifest
            if (ui.hasModel && m != null) {
                Text(m.displayName + " · " + m.sizeBytes / 1048576 + " MB · SHA-256 " + m.shortHash + "…")
                Text("Imported " + m.importedUtc.take(16).replace('T', ' ') + " UTC · " + m.runtime, color = muted, style = MaterialTheme.typography.bodySmall)
                Text(m.checkText ?: "Model check not run yet.", color = when (m.checkPassed) { true -> accent; false -> warn; null -> muted })
            } else if (ui.hasModel) {
                Text("A model is installed but has no record (imported by an older version). Import it again to record its hash.", color = warn)
            } else Text("Choose a .litertlm model. For benchmarks use the no-think Qwen3 0.6B file (Qwen3-0.6B-int4).")
            ui.importing?.let { Text(it, color = accent) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !busy && !engine.loaded) { Text("Import model") }
                Button(onClick = vm::runModelCheck, enabled = !busy && engine.loaded) { Text("Run model check") }
            }
            if (!engine.loaded) Text("Load the model (02) to run the check: five factual questions, thinking off.", color = muted, style = MaterialTheme.typography.bodySmall)
            if (ui.checkVerdict.isNotBlank()) Text(ui.checkVerdict, color = if (ui.checkVerdict.startsWith("Passed")) accent else warn)
            for (o in ui.checkOutcomes) {
                Text((if (o.onTopic) "✓ " else "✗ ") + o.prompt, style = MaterialTheme.typography.bodySmall)
                Text("   " + (o.error ?: o.answer.take(120).ifBlank { "(empty reply)" }) + (if (o.thinkingSeen) "  [thinking text]" else ""),
                    color = muted, style = MaterialTheme.typography.bodySmall)
            }
        }

        Panel("02 / Engine") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (b in LabViewModel.Backend.entries) FilterChip(selected = ui.backend == b, onClick = { vm.setBackend(b) }, label = { Text(b.label) }, enabled = !busy)
            }
            Text("Context (maxNumTokens)", color = muted, style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (n in listOf(512, 1024, 1280, null)) FilterChip(selected = ui.maxNumTokens == n, onClick = { vm.setMaxNumTokens(n) },
                    label = { Text(n?.toString() ?: "Model default") }, enabled = !busy && !engine.loaded)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::load, enabled = !busy && ui.hasModel) { Text(if (engine.loaded) "Reload" else "Load") }
                OutlinedButton(onClick = vm::unload, enabled = !busy && engine.loaded) { Text("Unload") }
            }
            Text(engine.message, style = MaterialTheme.typography.bodySmall, color = if (engine.fellBackToCpu) warn else Color.Unspecified)
        }

        Panel("03 / Ask") {
            OutlinedTextField(value = prompt, onValueChange = { prompt = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Instruction or question") }, minLines = 2)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.ask(prompt) }, enabled = !busy) { Text("Run") }
            }
            if (ui.reply.isNotBlank()) Text(ui.reply)
            if (ui.replyMetrics.isNotBlank()) Text(ui.replyMetrics, color = accent, style = MaterialTheme.typography.bodySmall)
            Text("Validated calculations run first without AI: convert 25 C to F · 15% of 240 · dew point 21 C RH 81% · exposure +1 EV. " +
                "Each reply starts a fresh conversation (no history), greedy decoding, thinking off.", color = muted, style = MaterialTheme.typography.bodySmall)
        }

        Panel("04 / Benchmark") {
            Text("Synthetic runs use LiteRT-LM's own benchmark with exact prefill and decode token counts (fresh engine each run, so load time is measured too). " +
                "Prompt runs use real prompts with greedy decoding and a ${BenchProtocol.FULL.maxOutputTokens}-token cap. All token figures come from the runtime.",
                color = muted, style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val both = listOf(LabViewModel.Backend.CPU, LabViewModel.Backend.GPU)
                OutlinedButton(onClick = { vm.runBenchmark(BenchProtocol.QUICK, both) }, enabled = !busy && ui.hasModel) { Text("Quick (CPU + GPU)") }
                Button(onClick = { vm.runBenchmark(BenchProtocol.FULL, both) }, enabled = !busy && ui.hasModel) { Text("Full protocol (CPU + GPU)") }
            }
            Text("Full: prefill 64 / 256 / 1,024 tokens, 128 decode, 2 warm-ups + 10 measured runs each, then 2 real prompts × 5. Expect 20–40 minutes. " +
                "Unplug the charger and start cool.", color = muted, style = MaterialTheme.typography.bodySmall)
            val summaries = BenchProtocol.summarise(ui.benchRuns)
            if (summaries.isNotEmpty()) {
                HorizontalDivider()
                Text(BenchProtocol.summaryText(summaries), style = MaterialTheme.typography.bodySmall)
                val failed = ui.benchRuns.count { !it.ok }
                if (failed > 0) Text("$failed run(s) failed: " + ui.benchRuns.first { !it.ok }.error, color = warn, style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pendingExport = vm.benchmarkJson(); saveJson.launch("s23-lab-benchmark.json") }) { Text("Save JSON") }
                    OutlinedButton(onClick = { pendingExport = vm.benchmarkCsv(); saveCsv.launch("s23-lab-benchmark.csv") }) { Text("Save CSV") }
                    OutlinedButton(onClick = { share(vm.benchmarkSummary()) }) { Text("Share summary") }
                }
            }
        }

        Panel("05 / Memory") {
            Text("Measures memory by phase (idle, loaded, peak during a reply, after unload) with the backend and context chosen in 02, " +
                "then repeats load → reply → unload to look for leaks.", color = muted, style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.runMemoryProfile(5) }, enabled = !busy && ui.hasModel) { Text("Profile + 5 cycles") }
                Button(onClick = { vm.runMemoryProfile(20) }, enabled = !busy && ui.hasModel) { Text("Profile + 20 cycles") }
            }
            for (s in ui.memoryPhases) Text(MemoryReport.line(s), style = MaterialTheme.typography.bodySmall)
            if (ui.memoryCycles.isNotEmpty()) Text("Cycles after unload: " + ui.memoryCycles.joinToString(", ") { BenchProtocol.f0(it.totalPssMb) } + " MB",
                style = MaterialTheme.typography.bodySmall)
            ui.memoryVerdict?.let { Text(it.text, color = if (it.passed) accent else warn) }
            if (ui.memoryPhases.isNotEmpty())
                OutlinedButton(onClick = { pendingExport = vm.memoryJson(); saveJson.launch("s23-lab-memory.json") }) { Text("Save JSON") }
            Text("Graphics = GPU memory Android attributes to the app. Some driver memory may not show in app figures; " +
                "confirm with: adb shell dumpsys meminfo com.dennisp.s23lab", color = muted, style = MaterialTheme.typography.bodySmall)
        }

        Panel("06 / Device") {
            Text(ui.deviceLine.ifBlank { "Reading…" })
            Text("Runs refuse to start at thermal status 'severe' or a headroom forecast of 0.95 or more, and a reply is cancelled if the phone " +
                "reaches 'severe' while generating. Current status: " + ThermalPolicy.statusName(ui.thermalStatus) + ".",
                color = muted, style = MaterialTheme.typography.bodySmall)
        }

        Panel("07 / Snapdragon NPU") {
            Text("Not enabled. Google lists the Snapdragon 8 Gen 2 (SM8550) for LiteRT NPU, but the documented route for language models is a " +
                "command-line tool with Qualcomm's libraries, and nothing has been verified on this phone. No unsupported accelerator is installed.", color = muted)
        }

        Text("v" + BuildConfig.VERSION_NAME + " • installs alongside v0.3 • import the model once • no internet permission or telemetry.",
            color = muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Panel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = panel)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}
