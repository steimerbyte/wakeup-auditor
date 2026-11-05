package com.steimerbyte.wakeupauditor

/** Eine App-Zeile der Rangliste. Alle Werte stammen aus rein lesenden dumpsys-Aufrufen. */
internal data class AppRow(
    val pkg: String,
    val uid: Int?,
    val jobs: Int,
    val alarms: Int,
    val fgMs: Long,
    val bgMs: Long,
    val fgsMs: Long,
    val cachedMs: Long,
    /** Anzeigename aus dem PackageManager; null bei Pseudo-Paketen wie `uid 1000`. */
    val label: String? = null,
    /** Häufigster Standby-Bucket der Jobs dieses Pakets; null ohne Job. */
    val bucket: String? = null
) {
    val backgroundMs: Long get() = bgMs + cachedMs
    val minutes: Long get() = backgroundMs / 60_000
    /** Ein Job zählt 3, ein Alarm 2, eine Minute Hintergrundzeit 1 Punkt. */
    val score: Int get() = jobs * 3 + alarms * 2 + minutes.toInt()
}

/** Sortierachse der Rangliste. */
internal enum class Sort(val label: String) {
    POINTS("PUNKTE"),
    JOBS("JOBS"),
    ALARME("ALARME"),
    MINUTEN("MINUTEN")
}

/** Absteigend nach der gewählten Achse, bei Gleichstand alphabetisch nach Paket. */
internal fun List<AppRow>.rankedBy(axis: Sort): List<AppRow> {
    val key: (AppRow) -> Long = when (axis) {
        Sort.POINTS -> { r -> r.score.toLong() }
        Sort.JOBS -> { r -> r.jobs.toLong() }
        Sort.ALARME -> { r -> r.alarms.toLong() }
        Sort.MINUTEN -> { r -> r.minutes }
    }
    return sortedWith(compareByDescending(key).thenBy { it.pkg })
}

internal data class Audit(
    val jobsTotal: Int,
    val alarmsTotal: Int,
    val dozeCount: Int,
    val rows: List<AppRow>
)

private class Acc {
    var uid: Int? = null
    var jobs = 0
    var alarms = 0
    var bucket: String? = null
    var fgMs = 0L
    var bgMs = 0L
    var fgsMs = 0L
    var cachedMs = 0L
}

// "ms" steht vor "m": sonst wird aus "161ms" ein Wert von 161 Minuten.
private val DURATION = Regex("(\\d+)(ms|h|m|s)")
private val SOURCE_PKG = Regex("pkg=(\\S+)")
private val ALARM_UID = Regex("^  (?:u\\d+a\\d+|\\d+):(\\S+) \\+\\S+ running, \\d+ wakeups:")
private val ALARM_EVENT = Regex("^    \\+\\S+ \\d+ wakes (\\d+) alarms")
private val PACKAGE_UID = Regex("^package:(\\S+) uid:([\\d,]+)")

/** Parst "1h 29m 52s 161ms", "27m 9s 202ms", "136ms" zu Millisekunden. */
internal fun parseDuration(text: String): Long {
    var total = 0L
    for (m in DURATION.findAll(text)) {
        val v = m.groupValues[1].toLongOrNull() ?: continue
        total += when (m.groupValues[2]) {
            "h" -> v * 3_600_000
            "m" -> v * 60_000
            "s" -> v * 1_000
            else -> v
        }
    }
    return total
}

private fun uidOf(raw: String): Int? = when {
    raw.startsWith("u0a") -> raw.removePrefix("u0a").toIntOrNull()?.let { 10_000 + it }
    raw.all { it.isDigit() } -> raw.toIntOrNull()
    else -> null
}

/**
 * Zählt Jobs je Paket. Der Paketname steht im "Source:"-Kopf des Blocks; fehlt er,
 * wird er aus der JOB-Zeile gelesen ("@namespace@pkg/class" → pkg). Nebenbei fällt
 * der Standby-Bucket des Jobs ab: jedes Paket kann über seine Jobs verschiedene
 * Buckets tragen, gespeichert wird die Häufigkeit je Wert.
 */
private fun collectJobs(
    dump: String,
    jobs: MutableMap<String, Int>,
    buckets: MutableMap<String, MutableMap<String, Int>>
): Int {
    var total = 0
    var pkg: String? = null
    var bucket: String? = null
    fun flush() {
        val p = pkg
        val b = bucket
        pkg = null
        bucket = null
        if (p == null) return
        jobs[p] = (jobs[p] ?: 0) + 1
        if (b != null) {
            val counts = buckets.getOrPut(p) { HashMap() }
            counts[b] = (counts[b] ?: 0) + 1
        }
    }
    for (line in dump.lineSequence()) {
        when {
            line.startsWith("  JOB ") -> {
                flush()
                total++
                val parts = line.substringAfter(": ", "").split(' ')
                pkg = if (parts.size > 1) {
                    parts[1].substringBefore('/').substringAfterLast('@').ifBlank { null }
                } else null
            }
            pkg != null && line.startsWith("    Source: uid=") ->
                SOURCE_PKG.find(line)?.let { pkg = it.groupValues[1] }
            // Letzte Zeile des Blocks, die wir brauchen: danach ist der Job vollständig.
            pkg != null && line.startsWith("    Standby bucket: ") -> {
                bucket = line.removePrefix("    Standby bucket: ").trim().ifBlank { null }
                flush()
            }
        }
    }
    flush()
    return total
}

/** Summiert die geFeuerten Alarme je Paket aus dem Abschnitt "Alarm Stats:". */
private fun collectAlarms(dump: String, alarms: MutableMap<String, Int>): Int {
    var total = 0
    var current: String? = null
    for (line in dump.lineSequence()) {
        val head = ALARM_UID.find(line)
        if (head != null) {
            current = head.groupValues[1]
            continue
        }
        val pkg = current ?: continue
        val event = ALARM_EVENT.find(line) ?: continue
        val n = event.groupValues[1].toIntOrNull() ?: 0
        alarms[pkg] = (alarms[pkg] ?: 0) + n
        total += n
    }
    return total
}

private fun collectPackages(dump: String): Map<Int, Set<String>> {
    val map = HashMap<Int, MutableSet<String>>()
    for (line in dump.lineSequence()) {
        val m = PACKAGE_UID.find(line.trim()) ?: continue
        val pkg = m.groupValues[1]
        for (u in m.groupValues[2].split(',')) {
            u.toIntOrNull()?.let { map.getOrPut(it) { HashSet() }.add(pkg) }
        }
    }
    return map
}

private val BATTERY_KEYS = listOf("fg", "bg", "fgs", "cached")

/** Liest die UID-Zeilen der Batteriestatistik: `UID u0aNNN: 12.3 bg: 0.4 (1h 2m 3s 4ms) ...`. */
private fun collectBattery(dump: String): Map<Int, LongArray> {
    val map = HashMap<Int, LongArray>()
    for (line in dump.lineSequence()) {
        val t = line.trimStart()
        if (!t.startsWith("UID ")) continue
        val colon = t.indexOf(": ")
        if (colon < 0) continue
        val uid = uidOf(t.substring(4, colon).trim()) ?: continue
        val rest = t.substring(colon + 2)
        val values = LongArray(BATTERY_KEYS.size)
        for (i in BATTERY_KEYS.indices) {
            // Ohne Klammer (z. B. "bg: 36.8") gibt dumpsys keine Dauer aus → 0.
            val m = Regex("(?:^|\\s)" + BATTERY_KEYS[i] + ": [\\d.]+ \\((.*?)\\)").find(rest)
            values[i] = m?.let { parseDuration(it.groupValues[1]) } ?: 0L
        }
        map[uid] = values
    }
    return map
}

/** Gemeinsame UIDs haben mehrere Pakete; nimm das mit den meisten Jobs+Alarmen, sonst alphabetisch. */
private fun bestPackage(
    uid: Int,
    candidates: Map<Int, Set<String>>,
    jobs: Map<String, Int>,
    alarms: Map<String, Int>
): String? = candidates[uid]?.minWithOrNull(
    compareByDescending<String> { (jobs[it] ?: 0) + (alarms[it] ?: 0) }.thenBy { it }
)

internal fun analyze(
    jobsDump: String,
    alarmDump: String,
    batteryDump: String,
    whitelistDump: String,
    packageDump: String,
    /** Anzeigename zum Paket; im Test leer, am Gerät der PackageManager. */
    labelOf: (String) -> String? = { null }
): Audit {
    val jobs = HashMap<String, Int>()
    val buckets = HashMap<String, MutableMap<String, Int>>()
    val jobsTotal = collectJobs(jobsDump, jobs, buckets)

    val alarms = HashMap<String, Int>()
    val alarmsTotal = collectAlarms(alarmDump, alarms)

    val battery = collectBattery(batteryDump)
    val candidates = collectPackages(packageDump)

    val acc = HashMap<String, Acc>()
    fun acc(pkg: String) = acc.getOrPut(pkg) { Acc() }

    jobs.forEach { (pkg, n) ->
        acc(pkg).jobs = n
        // Job-Buckets eines Pakets können sich unterscheiden (z. B. WhatsApp ACTIVE und EXEMPTED):
        // der häufigste Wert, bei Gleichstand der alphabetisch erste.
        acc(pkg).bucket = buckets[pkg]?.entries?.minWithOrNull(
            compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }
        )?.key
    }
    alarms.forEach { (pkg, n) -> acc(pkg).alarms = n }
    battery.forEach { (uid, v) ->
        val r = acc(bestPackage(uid, candidates, jobs, alarms) ?: "uid $uid")
        r.uid = uid
        r.fgMs = v[0]
        r.bgMs = v[1]
        r.fgsMs = v[2]
        r.cachedMs = v[3]
    }

    val rows = acc.map { (pkg, a) ->
        AppRow(pkg, a.uid, a.jobs, a.alarms, a.fgMs, a.bgMs, a.fgsMs, a.cachedMs, labelOf(pkg), a.bucket)
    }
        .filter { it.jobs > 0 || it.alarms > 0 || it.fgMs > 0 || it.backgroundMs > 0 || it.fgsMs > 0 }
        .rankedBy(Sort.POINTS)

    return Audit(
        jobsTotal = jobsTotal,
        alarmsTotal = alarmsTotal,
        // Eine App steht oft in mehreren Listen (system-excidle, system, user) → je Paket einmal zählen.
        dozeCount = whitelistDump.lineSequence()
            .mapNotNull { it.split(',').getOrNull(1)?.trim()?.ifEmpty { null } }
            .distinct()
            .count(),
        rows = rows
    )
}

/**
 * Liest den Modus aus `cmd appops get <pkg> RUN_ANY_IN_BACKGROUND`.
 * true = AN (allow), false = AUS (ignore), null = unbekannte oder fehlende Antwort.
 */
internal fun parseBackgroundMode(out: String): Boolean? {
    val mode = Regex("RUN_ANY_IN_BACKGROUND:\\s*(\\w+)").find(out)?.groupValues?.get(1)
        ?: Regex("Default mode:\\s*(\\w+)").find(out)?.groupValues?.get(1)
        ?: return null
    return when (mode) {
        "allow", "default" -> true
        "ignore", "deny" -> false
        else -> null
    }
}

/** Millisekunden im dumpsys-Format: "1h 29m 52s 161ms". */
internal fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "0ms"
    val h = ms / 3_600_000
    val m = ms % 3_600_000 / 60_000
    val s = ms % 60_000 / 1_000
    val r = ms % 1_000
    return buildString {
        if (h > 0) append("${h}h ")
        if (h > 0 || m > 0) append("${m}m ")
        if (h > 0 || m > 0 || s > 0) append("${s}s ")
        append("${r}ms")
    }
}
