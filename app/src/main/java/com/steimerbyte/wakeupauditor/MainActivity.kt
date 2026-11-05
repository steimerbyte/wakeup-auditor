package com.steimerbyte.wakeupauditor

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku

internal val Accent = Color(0xFFD71921)
internal val Dim = Color(0xFF7A7A7A)
internal val Panel = Color(0xFF0D0D0D)
internal val Line = Color(0xFF242424)
internal val Good = Color(0xFF39D98A)
internal val Warn = Color(0xFFE0A32E)

private const val REQ_PERMISSION = 1001

internal enum class ShizukuState { UNKNOWN, DEAD, ALIVE, GRANTED, DENIED }

/** stdout und die erste stderr-Zeile eines Shell-Aufrufs. */
internal class ShellResult(val out: String, val err: String?) {
    /** stdout, oder Abbruch mit der ersten stderr-Zeile, wenn das Kommando nichts lieferte. */
    fun required(): String = out.ifBlank {
        throw IllegalStateException(err ?: "Kommando lieferte keine Ausgabe")
    }
}

/** Eine Probe aus /proc: CPU-Zeit und RAM-Belegung, in einem `cat` geholt. */
internal class ProcSample(val total: Long, val idle: Long, val used: Float, val text: String?)

/** Liest `cpu …` aus /proc/stat und MemTotal/MemAvailable aus /proc/meminfo. */
internal fun parseProcSample(out: String): ProcSample? {
    val cpu = out.lineSequence().firstOrNull { it.startsWith("cpu ") } ?: return null
    val nums = cpu.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
    if (nums.size < 5) return null
    val totalKb = Regex("MemTotal:\\s+(\\d+) kB").find(out)?.groupValues?.get(1)?.toLongOrNull()
    val availKb = Regex("MemAvailable:\\s+(\\d+) kB").find(out)?.groupValues?.get(1)?.toLongOrNull()
    if (totalKb == null || availKb == null || totalKb <= 0L) {
        return ProcSample(nums.sum(), nums[3] + nums[4], 0f, null)
    }
    val usedKb = totalKb - availKb
    val text = String.format(
        Locale.GERMANY, "%.1f/%.1f GiB",
        usedKb / 1_048_576.0, totalKb / 1_048_576.0
    )
    return ProcSample(nums.sum(), nums[3] + nums[4], usedKb.toFloat() / totalKb, text)
}

class MainActivity : ComponentActivity() {

    internal var shizukuState by mutableStateOf(ShizukuState.UNKNOWN)
    internal var statusLine by mutableStateOf("")
    internal var audit by mutableStateOf<Audit?>(null)
    internal var loading by mutableStateOf(false)
    internal var loadError by mutableStateOf<String?>(null)

    /** HINTERGRUND-Zustand je Paket: true = AN (allow), false = AUS (ignore), null = nicht lesbar. */
    internal var appOps by mutableStateOf<Map<String, Boolean?>>(emptyMap())
    internal var opsBusy by mutableStateOf<Set<String>>(emptySet())
    internal var opsProbed by mutableStateOf(0)
    internal var opsSize by mutableStateOf(0)
    /** Pakete, die einen Schalter bekommen; alles andere zeigt keinen. */
    internal var opsTargets by mutableStateOf<Set<String>>(emptySet())
    private var opsJob: Job? = null

    internal var cpuHistory by mutableStateOf<List<Float>>(emptyList())
    internal var ramHistory by mutableStateOf<List<Float>>(emptyList())
    internal var ramText by mutableStateOf<String?>(null)
    /** About-Sektion, geöffnet über die Kicker-Zeile im Kopf. */
    internal var showAbout by mutableStateOf(false)

    private val permListener = Shizuku.OnRequestPermissionResultListener { code, grantResult ->
        if (code == REQ_PERMISSION) {
            shizukuState = if (grantResult == PackageManager.PERMISSION_GRANTED) {
                ShizukuState.GRANTED
            } else {
                ShizukuState.DENIED
            }
            onShizukuReady()
        }
    }

    private val deadListener = Shizuku.OnBinderDeadListener { shizukuState = ShizukuState.DEAD }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(permListener)
        Shizuku.addBinderDeadListener(deadListener)
        setContent { AppTheme { Screen(); if (showAbout) About() } }
        refreshShizuku()
        onShizukuReady()
        // Erststart: Shizuku ist da, die Berechtigung fehlt → selbst fragen.
        if (shizukuState == ShizukuState.ALIVE) requestShizuku()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permListener)
        Shizuku.removeBinderDeadListener(deadListener)
        super.onDestroy()
    }

    // ---------- Shizuku ----------

    internal fun refreshShizuku() {
        shizukuState = try {
            when {
                !Shizuku.pingBinder() -> ShizukuState.DEAD
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> ShizukuState.GRANTED
                else -> ShizukuState.ALIVE
            }
        } catch (t: Throwable) {
            ShizukuState.DEAD
        }
    }

    internal fun requestShizuku() {
        if (shizukuState == ShizukuState.DEAD || shizukuState == ShizukuState.UNKNOWN) {
            statusLine = "Shizuku läuft nicht. Starte es über die Shizuku-App."
            return
        }
        runCatching { Shizuku.requestPermission(REQ_PERMISSION) }
            .onFailure { statusLine = "Anfrage fehlgeschlagen: ${it.message}" }
    }

    /** Startet ein Kommando mit UID 2000 (shell) über Shizuku und liefert stdout und stderr. */
    internal fun runShell(vararg cmd: String): ShellResult {
        val service = IShizukuService.Stub.asInterface(Shizuku.getBinder())
        val proc = service.newProcess(arrayOf(*cmd), null, null)
        val err = StringBuilder()
        val reader = Thread {
            runCatching {
                ParcelFileDescriptor.AutoCloseInputStream(proc.errorStream)
                    .bufferedReader().use { r -> r.forEachLine { err.appendLine(it) } }
            }
        }
        reader.start()
        val out = ParcelFileDescriptor.AutoCloseInputStream(proc.inputStream)
            .bufferedReader().use { it.readText() }
        proc.waitFor()
        reader.join(2000)
        return ShellResult(out, err.lineSequence().firstOrNull { it.isNotBlank() }?.trim())
    }

    /** Anzeigename zum Paket; ohne Treffer null statt einer Ausnahme. */
    internal fun labelOf(pkg: String): String? = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()

    /** Wird aufgerufen, sobald sich der Shizuku-Zustand ändert — hier die Daten laden. */
    internal fun onShizukuReady() {
        if (shizukuState == ShizukuState.GRANTED) load()
    }

    internal fun load() {
        if (shizukuState != ShizukuState.GRANTED || loading) return
        loading = true
        loadError = null
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    analyze(
                        runShell("dumpsys", "jobscheduler").required(),
                        runShell("dumpsys", "alarm").required(),
                        runShell("dumpsys", "batterystats", "--charged").required(),
                        runShell("cmd", "deviceidle", "whitelist").required(),
                        runShell("cmd", "package", "list", "packages", "-U").required(),
                        labelOf = this@MainActivity::labelOf
                    )
                }
            }
            result.onSuccess { a ->
                if (a.rows.isEmpty()) {
                    loadError = "Die Dumps enthielten keine auswertbaren Zeilen."
                } else {
                    audit = a
                    statusLine = android.text.format.DateFormat.format("HH:mm", System.currentTimeMillis()).toString()
                    startOpsQueue(a)
                }
            }.onFailure { loadError = it.message ?: it.toString() }
            loading = false
        }
    }

    // ---------- HINTERGRUND-Schalter (AppOps) ----------

    /** Drittanbieter-Paket, das geschaltet werden darf. Shizuku, `android` und diese App nie. */
    private fun isSwitchable(pkg: String): Boolean {
        if (pkg == packageName || pkg == "android") return false
        if (pkg.startsWith("moe.shizuku") || pkg.startsWith("rikka.shizuku")) return false
        if (pkg.startsWith("uid ")) return false
        val info = runCatching { packageManager.getApplicationInfo(pkg, 0) }.getOrNull() ?: return false
        return (info.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && info.uid % 100_000 >= 10_000
    }

    /** Liest `RUN_ANY_IN_BACKGROUND`; null, wenn die Antwort kein bekanntes Modus-Wort enthält. */
    private fun readAppOp(pkg: String): Boolean? {
        val out = runCatching {
            runShell("cmd", "appops", "get", pkg, "RUN_ANY_IN_BACKGROUND").out
        }.getOrNull() ?: return null
        return parseBackgroundMode(out)
    }

    /**
     * Fragt die Zustände einzeln nacheinander ab, damit der erste Bildaufbau nicht auf 150
     * Shell-Aufrufe wartet. Veröffentlicht in Fünferschritten, damit die Liste ruhig bleibt.
     */
    private fun startOpsQueue(a: Audit) {
        opsJob?.cancel()
        appOps = emptyMap()
        opsBusy = emptySet()
        opsProbed = 0
        opsSize = 0
        opsTargets = emptySet()
        opsJob = lifecycleScope.launch {
            val targets = withContext(Dispatchers.IO) { a.rows.map { it.pkg }.filter { isSwitchable(it) } }
            opsTargets = targets.toSet()
            opsSize = targets.size
            val batch = HashMap<String, Boolean?>()
            var done = 0
            for (pkg in targets) {
                batch[pkg] = withContext(Dispatchers.IO) { readAppOp(pkg) }
                done++
                if (done % 5 == 0 || done == targets.size) {
                    appOps = appOps + batch
                    batch.clear()
                    opsProbed = done
                }
            }
        }
    }

    /** Schreibt den neuen Zustand und liest ihn zurück; angezeigt wird nur die Antwort. */
    internal fun toggleBackground(pkg: String) {
        val current = appOps[pkg] ?: return
        if (pkg in opsBusy) return
        opsBusy = opsBusy + pkg
        lifecycleScope.launch {
            val next = withContext(Dispatchers.IO) {
                runCatching {
                    runShell("cmd", "appops", "set", pkg, "RUN_ANY_IN_BACKGROUND", if (current) "ignore" else "allow")
                    readAppOp(pkg)
                }.getOrNull()
            }
            appOps = appOps + (pkg to next)
            opsBusy = opsBusy - pkg
        }
    }

    // ---------- Live-Anzeige (CPU/RAM) ----------

    private fun readProcSample(): ProcSample? =
        parseProcSample(runShell("cat", "/proc/stat", "/proc/meminfo").out)

    /** Histogramme für CPU und RAM; die Werte kommen aus dem Takt unten. */
    @Composable
    private fun LivePanel(cpu: List<Float>, ram: List<Float>) {
        if (shizukuState == ShizukuState.GRANTED) {
            val owner = LocalLifecycleOwner.current
            // Nur bei sichtbarem Screen; die Coroutine stirbt mit dem Composable bzw. der Pause.
            LaunchedEffect(owner) {
                owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    var previous: ProcSample? = null
                    while (isActive) {
                        val sample = withContext(Dispatchers.IO) { runCatching { readProcSample() }.getOrNull() }
                        if (sample != null) {
                            previous?.let { before ->
                                val deltaTotal = sample.total - before.total
                                val deltaIdle = sample.idle - before.idle
                                if (deltaTotal > 0L) {
                                    val load = (1.0 - deltaIdle.toDouble() / deltaTotal).toFloat().coerceIn(0f, 1f)
                                    cpuHistory = (cpuHistory + load).takeLast(48)
                                }
                            }
                            if (sample.text != null) {
                                ramHistory = (ramHistory + sample.used).takeLast(48)
                                ramText = sample.text
                            }
                            previous = sample
                        }
                        delay(700)
                    }
                }
            }
        }
        OutlineBox {
            LiveRow("CPU", cpu, if (cpu.isEmpty()) null else "${(cpu.last() * 100).roundToInt()} %")
            Spacer(Modifier.height(6.dp))
            LiveRow("RAM", ram, ramText ?: if (ram.isEmpty()) null else "${(ram.last() * 100).roundToInt()} %")
        }
    }

    @Composable
    private fun LiveRow(label: String, history: List<Float>, value: String?) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Micro(label)
            Spacer(Modifier.width(8.dp))
            Histogram(history, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            if (value == null) Micro("KEINE DATEN", color = Warn) else Value(value)
        }
    }

    @Composable
    private fun Histogram(history: List<Float>, modifier: Modifier = Modifier) {
        Canvas(modifier.fillMaxWidth().height(46.dp)) {
            val slots = 48
            val slotWidth = size.width / slots
            val barWidth = slotWidth * 0.62f
            val shift = slots - history.size
            for (i in 0 until slots) {
                val index = i - shift
                val value = if (index >= 0) history[index].coerceIn(0f, 1f) else 0f
                val barHeight = if (value > 0f) (size.height * value).coerceAtLeast(2f) else 1f
                val x = i * slotWidth + (slotWidth - barWidth) / 2f
                drawRect(
                    color = if (value > 0f) Accent else Line,
                    topLeft = Offset(x, size.height - barHeight),
                    size = Size(barWidth, barHeight)
                )
            }
        }
    }

    // ---------- UI ----------

    @Composable
    private fun Screen() {
        var selectedPkg by rememberSaveable { mutableStateOf<String?>(null) }
        var onlyJobs by rememberSaveable { mutableStateOf(false) }
        var query by rememberSaveable { mutableStateOf("") }
        var sort by rememberSaveable { mutableStateOf(Sort.POINTS) }
        // Lebt oberhalb der Verzweigung, damit die Listenposition den Detailaufruf übersteht.
        val listState = rememberLazyListState()
        val current = audit
        val selected = selectedPkg?.let { p -> current?.rows?.firstOrNull { it.pkg == p } }

        BackHandler(enabled = selected != null) { selectedPkg = null }

        when {
            shizukuState != ShizukuState.GRANTED -> ScrollColumn(rememberScrollState()) {
                Header()
                PermissionCard()
            }

            loading && current == null -> ScrollColumn(rememberScrollState()) {
                Header()
                LoadingCard()
            }

            loadError != null && current == null -> ScrollColumn(rememberScrollState()) {
                Header()
                ErrorCard(loadError!!)
            }

            current != null && selected != null -> ScrollColumn(rememberScrollState()) {
                Header()
                Detail(selected) { selectedPkg = null }
            }

            current != null -> Overview(
                audit = current,
                query = query,
                sort = sort,
                onlyJobs = onlyJobs,
                setQuery = { query = it },
                setSort = { sort = it },
                setOnlyJobs = { onlyJobs = it },
                error = loadError,
                listState = listState,
                onSelect = { selectedPkg = it }
            )
        }
    }

    @Composable
    private fun ScrollColumn(state: androidx.compose.foundation.ScrollState, content: @Composable ColumnScope.() -> Unit) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .systemBarsPadding()
                .padding(horizontal = 16.dp)
                .verticalScroll(state),
            content = content
        )
    }

    /** Slot 1 des Layoutsystems: Kicker, Titel, Aktion rechts; die Live-Anzeige hängt darunter. */
    @Composable
    private fun Header() {
        Spacer(Modifier.height(10.dp))
        Osw(
            kicker = "SCHNAPPSCHUSS ${statusLine.ifBlank { "—" }} · NICHT AUTOMATISCH",
            title = "WAKEUP AUDITOR",
            action = if (shizukuState == ShizukuState.GRANTED) {
                { MiniButton(if (loading) "LÄDT…" else "NEU LADEN") { load() } }
            } else {
                null
            },
            onKickerClick = { showAbout = true }
        )
        Spacer(Modifier.height(6.dp))
        Micro("NUR LESEND · JOBS, ALARME, HINTERGRUNDZEIT")
        Spacer(Modifier.height(12.dp))
        LivePanel(cpuHistory, ramHistory)
        Spacer(Modifier.height(12.dp))
    }

    /** Inhalt der About-Sektion; erreichbar über die Kicker-Zeile im Kopf. */
    @Composable
    private fun About() {
        Dialog(onDismissRequest = { showAbout = false }) {
            Box(
                Modifier
                    .background(Color.Black)
                    .border(1.dp, Line, RoundedCornerShape(2.dp))
                    .padding(16.dp)
            ) {
                AboutPanel(
                    name = "WAKEUP AUDITOR",
                    version = BuildConfig.VERSION_NAME,
                    purpose = "Rangliste der Apps nach Jobs, Alarmen und Hintergrundzeit, mit AppOps-Schalter je App.",
                    commands = listOf(
                        "dumpsys jobscheduler",
                        "dumpsys alarm",
                        "dumpsys batterystats --charged",
                        "cmd deviceidle whitelist",
                        "cmd package list packages -U",
                        "cmd appops get <pkg> RUN_ANY_IN_BACKGROUND",
                        "cmd appops set <pkg> RUN_ANY_IN_BACKGROUND",
                        "cat /proc/stat /proc/meminfo"
                    )
                )
            }
        }
    }

    @Composable
    private fun PermissionCard() {
        Box(
            Modifier
                .fillMaxWidth()
                .border(1.dp, Accent, RoundedCornerShape(2.dp))
                .padding(12.dp)
        ) {
            Column {
                Micro("SHIZUKU · NICHT BEREIT", color = Accent)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Ohne laufenden Shizuku-Dienst und dessen Berechtigung kann diese App nichts tun.",
                    color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp
                )
                Spacer(Modifier.height(10.dp))
                WideButton("ERLAUBEN") { requestShizuku() }
            }
        }
    }

    @Composable
    private fun LoadingCard() {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Micro("LÄUFT · DUMPS LESEN", color = Warn)
            Spacer(Modifier.height(8.dp))
            Bar()
            Spacer(Modifier.height(8.dp))
            Micro("JOBSCHEDULER · ALARM · BATTERYSTATS · WHITELIST")
        }
    }

    @Composable
    private fun ErrorCard(message: String) {
        Box(
            Modifier
                .fillMaxWidth()
                .border(1.dp, Accent, RoundedCornerShape(2.dp))
                .padding(12.dp)
        ) {
            Column {
                Micro("FEHLER", color = Accent)
                Spacer(Modifier.height(8.dp))
                Text(message, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                WideButton("NEU LADEN") { loadError = null; load() }
            }
        }
    }

    @Composable
    private fun Overview(
        audit: Audit,
        query: String,
        sort: Sort,
        onlyJobs: Boolean,
        setQuery: (String) -> Unit,
        setSort: (Sort) -> Unit,
        setOnlyJobs: (Boolean) -> Unit,
        error: String?,
        listState: LazyListState,
        onSelect: (String) -> Unit
    ) {
        // Suche wirkt auf Paket und Label; die Trefferzahl unten ist die Schnittmenge mit dem Job-Filter.
        val needle = query.trim().lowercase()
        val shown = remember(audit, needle, sort, onlyJobs) {
            audit.rows
                .filter { !onlyJobs || it.jobs > 0 }
                .filter { needle.isEmpty() || it.pkg.lowercase().contains(needle) || it.label?.lowercase()?.contains(needle) == true }
                .rankedBy(sort)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .systemBarsPadding(),
            contentPadding = PaddingValues(horizontal = 16.dp)
        ) {
            item { Header() }
            item {
                SummaryPanel(
                    listOf(
                        "JOBS" to audit.jobsTotal.toString(),
                        "ALARME" to audit.alarmsTotal.toString(),
                        "DOZE-PAKETE" to audit.dozeCount.toString()
                    )
                )
                Spacer(Modifier.height(12.dp))
                SearchField(query, setQuery, shown.size)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Sort.entries.forEach { axis ->
                        Pill(axis.label, sort == axis, Modifier.weight(1f)) { setSort(axis) }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Kicker("PUNKTE = JOBS×3 + ALARME×2 + MINUTEN", Modifier.weight(1f))
                    Spacer(Modifier.width(6.dp))
                    Pill("NUR MIT JOBS", onlyJobs) { setOnlyJobs(!onlyJobs) }
                }
                Spacer(Modifier.height(6.dp))
                Mono("SORTIERT NACH ${sort.label} · ${shown.size} VON ${audit.rows.size} APPS")
                if (error != null) {
                    Spacer(Modifier.height(6.dp))
                    Micro("LETZTES LADEN FEHLGESCHLAGEN · $error", color = Accent)
                }
                if (opsProbed < opsSize) {
                    Spacer(Modifier.height(6.dp))
                    Micro("HINTERGRUND · PRÜFE $opsProbed / $opsSize", color = Warn)
                }
                Spacer(Modifier.height(6.dp))
                Micro("AUS = APP DARF NICHT IM HINTERGRUND LAUFEN.\nMESSENGER UND SYNC BRECHEN DANN AB.")
                Spacer(Modifier.height(8.dp))
            }
            itemsIndexed(shown, key = { _, row -> row.pkg }) { i, row ->
                RowItem(i + 1, row) { onSelect(row.pkg) }
            }
            if (shown.isEmpty()) {
                item {
                    Spacer(Modifier.height(12.dp))
                    Micro(
                        when {
                            needle.isNotEmpty() -> "KEIN TREFFER FÜR «${query.trim()}»"
                            onlyJobs -> "KEINE APP MIT JOBS"
                            else -> "KEINE APPS GEFUNDEN"
                        },
                        color = Warn
                    )
                }
            }
            item {
                Spacer(Modifier.height(10.dp))
                Mono("DUMPS NUR LESEND GELESEN")
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    @Composable
    private fun SearchField(query: String, setQuery: (String) -> Unit, hits: Int) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .border(1.dp, Line, RoundedCornerShape(2.dp))
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Micro("SUCHE")
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = query,
                onValueChange = setQuery,
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = TextStyle(
                    color = Color.White, fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace, letterSpacing = 0.3.sp
                ),
                cursorBrush = SolidColor(Accent),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) Micro("PACKAGE ODER LABEL")
                        inner()
                    }
                }
            )
            if (query.isNotEmpty()) {
                Text(
                    "×", color = Dim, fontSize = 16.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .clickable { setQuery("") }
                        .padding(horizontal = 8.dp)
                )
            }
            Spacer(Modifier.width(4.dp))
            Micro("$hits TREFFER")
        }
    }

    @Composable
    private fun RowItem(rank: Int, row: AppRow, onClick: () -> Unit) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp)
                .border(1.dp, Line, RoundedCornerShape(2.dp))
                .background(Panel)
                .clickable { onClick() }
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Mono(rank.toString().padStart(2, '0'))
                Spacer(Modifier.width(8.dp))
                Body(row.label ?: row.pkg, Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Value(row.score.toString(), color = Accent)
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Mono(row.pkg, Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Mono(
                    "JOB ${row.jobs} · ALARM ${row.alarms} · BG ${row.minutes}m",
                    color = if (row.jobs > 0 || row.alarms > 0 || row.minutes > 0) Color.White else Dim
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                BackgroundSwitch(row.pkg)
            }
        }
    }

    /** Zeigt AN/AUS aus der zuletzt gelesenen AppOps-Antwort; tippen schreibt und liest neu. */
    @Composable
    private fun BackgroundSwitch(pkg: String) {
        if (pkg !in opsTargets) return
        val known = appOps.containsKey(pkg)
        val state = appOps[pkg]
        val busy = pkg in opsBusy
        when {
            !known -> Micro("HINTERGRUND –")
            state == null -> Micro("HINTERGRUND ?", color = Warn)
            else -> Toggle(
                label = if (state) "HINTERGRUND AN" else "HINTERGRUND AUS",
                on = state
            ) { if (!busy) toggleBackground(pkg) }
        }
    }

    @Composable
    private fun Detail(row: AppRow, onBack: () -> Unit) {
        Column(Modifier.fillMaxWidth()) {
            Row { MiniButton("‹ ZURÜCK", onBack) }
            Spacer(Modifier.height(14.dp))
            Body(row.label ?: row.pkg, maxLines = 2, bold = true)
            Spacer(Modifier.height(4.dp))
            Mono(row.pkg)
            Spacer(Modifier.height(4.dp))
            Micro("UID ${row.uid ?: "?"}")
            Spacer(Modifier.height(12.dp))
            SummaryPanel(
                listOf(
                    "JOBS" to row.jobs.toString(),
                    "ALARME" to row.alarms.toString(),
                    "MINUTEN" to row.minutes.toString(),
                    "PUNKTE" to row.score.toString()
                )
            )
            Spacer(Modifier.height(12.dp))
            OutlineBox {
                Kv("BUCKET", row.bucket ?: "—")
                Kv("FG", formatDuration(row.fgMs))
                Kv("BG", formatDuration(row.bgMs))
                Kv("FGS", formatDuration(row.fgsMs))
                Kv("CACHED", formatDuration(row.cachedMs))
                Kv("BG + CACHED", formatDuration(row.backgroundMs))
                Kv("MINUTEN (BG+CACHED)", row.minutes.toString())
            }
            Spacer(Modifier.height(12.dp))
            Kicker("PUNKTE = JOBS×3 + ALARME×2 + MINUTEN")
            Spacer(Modifier.height(4.dp))
            Mono("JOBS ${row.jobs}×3 + ALARME ${row.alarms}×2 + ${row.minutes}m = ${row.score}", color = Dim)
        }
    }

    @Composable
    private fun Kv(label: String, value: String) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Micro(label)
            Spacer(Modifier.weight(1f))
            Text(value, color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
    }

    // ---------- Komponenten des Layoutsystems ----------

    /** Kopfleiste: Kicker 9 sp Dim, Titel 28 sp Bold, Aktion rechts. Der Kicker öffnet die About-Sektion. */
    @Composable
    internal fun Osw(
        kicker: String,
        title: String,
        action: (@Composable () -> Unit)? = null,
        onKickerClick: (() -> Unit)? = null
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    title, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp, fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(3.dp))
                Kicker(
                    kicker,
                    modifier = if (onKickerClick == null) Modifier else Modifier
                        .clickable { onKickerClick() }
                        .padding(vertical = 4.dp)
                )
            }
            if (action != null) {
                Spacer(Modifier.width(8.dp))
                action()
            }
        }
    }

    /** About-Sektion: Name, Version + Gerät, Leitspruch, Zweck, Kernkommandos. */
    @Composable
    internal fun AboutPanel(name: String, version: String, purpose: String, commands: List<String>) {
        Column {
            Text(
                name, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp, fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(6.dp))
            Mono("Version $version · ${android.os.Build.MODEL}")
            Spacer(Modifier.height(12.dp))
            Value("Function over form.", color = Accent)
            Spacer(Modifier.height(12.dp))
            Text(
                purpose, color = Color.White, fontSize = 12.sp,
                fontFamily = FontFamily.Monospace, letterSpacing = 0.3.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(12.dp))
            Mono("Kernkommandos:")
            commands.forEach { Mono(it) }
        }
    }

    /** Rahmen 1 dp in Line, Radius 2 dp, 10 dp Innenabstand. */
    @Composable
    internal fun OutlineBox(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
        Column(
            modifier
                .fillMaxWidth()
                .border(1.dp, Line, RoundedCornerShape(2.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            content = content
        )
    }

    /** Kennzahlen: Mikrolabel über dem Wert, 2–4 Spalten. */
    @Composable
    internal fun SummaryPanel(items: List<Pair<String, String>>) {
        OutlineBox {
            Row {
                items.forEachIndexed { i, (label, value) ->
                    if (i > 0) Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Micro(label)
                        Spacer(Modifier.height(4.dp))
                        Value(value)
                    }
                }
            }
        }
    }

    @Composable
    internal fun MiniButton(text: String, onClick: () -> Unit) {
        Box(
            Modifier
                .minimumInteractiveComponentSize()
                .height(36.dp)
                .border(1.dp, Line, RoundedCornerShape(2.dp))
                .clickable { onClick() }
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text, color = Color.White, fontSize = 11.sp,
                fontFamily = FontFamily.Monospace, letterSpacing = 1.4.sp, maxLines = 1
            )
        }
    }

    @Composable
    internal fun Pill(label: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
        Box(
            modifier
                .minimumInteractiveComponentSize()
                .height(32.dp)
                .border(1.dp, if (active) Accent else Line, RoundedCornerShape(2.dp))
                .background(if (active) Accent.copy(alpha = 0.14f) else Color.Transparent)
                .clickable { onClick() }
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label, color = if (active) Accent else Dim, fontSize = 11.sp,
                fontFamily = FontFamily.Monospace, letterSpacing = 1.4.sp, maxLines = 1
            )
        }
    }

    /** Zustandsanzeige mit zwei Werten: AN in Dim, AUS in Warn. */
    @Composable
    internal fun Toggle(label: String, on: Boolean, onChange: () -> Unit) {
        Box(
            Modifier
                .minimumInteractiveComponentSize()
                .height(24.dp)
                .border(1.dp, if (on) Line else Warn, RoundedCornerShape(2.dp))
                .clickable { onChange() }
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label, color = if (on) Dim else Warn, fontSize = 9.sp,
                fontFamily = FontFamily.Monospace, letterSpacing = 1.4.sp, maxLines = 1
            )
        }
    }

    @Composable
    internal fun Bar() {
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(Accent.copy(alpha = 0.5f))
        )
    }

    @Composable
    internal fun Kicker(text: String, modifier: Modifier = Modifier, color: Color = Dim) {
        Text(
            text, modifier = modifier, color = color, fontSize = 9.sp,
            fontFamily = FontFamily.Monospace, letterSpacing = 1.8.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }

    @Composable
    internal fun Micro(text: String, modifier: Modifier = Modifier, color: Color = Dim) {
        Text(
            text, modifier = modifier, color = color, fontSize = 9.sp,
            fontFamily = FontFamily.Monospace, letterSpacing = 1.4.sp
        )
    }

    /** Paketname oder Rohwert: 10 sp, Spacing 0.2, eine Zeile. */
    @Composable
    internal fun Mono(text: String, modifier: Modifier = Modifier, color: Color = Dim) {
        Text(
            text, modifier = modifier, color = color, fontSize = 10.sp,
            fontFamily = FontFamily.Monospace, letterSpacing = 0.2.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }

    @Composable
    internal fun Body(text: String, modifier: Modifier = Modifier, maxLines: Int = 1, bold: Boolean = false) {
        Text(
            text, modifier = modifier, color = Color.White, fontSize = 12.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            fontFamily = FontFamily.Monospace, letterSpacing = 0.3.sp,
            maxLines = maxLines, overflow = TextOverflow.Ellipsis
        )
    }

    @Composable
    internal fun Value(text: String, modifier: Modifier = Modifier, color: Color = Color.White) {
        Text(
            text, modifier = modifier, color = color, fontSize = 22.sp,
            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
        )
    }

    @Composable
    internal fun WideButton(label: String, onClick: () -> Unit) {
        Box(
            Modifier
                .fillMaxWidth()
                .border(1.dp, Line, RoundedCornerShape(2.dp))
                .clickable { onClick() }
                .padding(vertical = 11.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label, color = Color.White, fontSize = 11.sp,
                fontFamily = FontFamily.Monospace, letterSpacing = 1.4.sp
            )
        }
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            background = Color.Black,
            surface = Color.Black,
            onBackground = Color.White,
            onSurface = Color.White
        ),
        content = content
    )
}
