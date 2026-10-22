package com.example.turbo

import android.content.Context
import com.topjohnwu.superuser.Shell

data class Target(val kind: String, val id: String, val title: String, val note: String, val danger: Boolean = false)

/** Everything here is SESSION ONLY: force-stop, init `stop`, logcat buffer resize.
 *  The one exception is `settings put` (vibration), whose originals are saved and
 *  restored by BootReceiver / the RESTORE button. */
object Engine {
    // Edit these lists freely. Only items that are installed AND running are offered.
    private val PKGS = listOf(
        Triple("com.sec.android.diagmonagent", "Samsung Diagnostic Monitor", "Diagnostic data to Samsung"),
        Triple("com.samsung.android.dqagent", "Device Quality Agent", "Quality/usage telemetry"),
        Triple("com.samsung.android.rubin.app", "Customization Service", "Usage profiling for suggestions"),
        Triple("com.samsung.android.bixby.agent", "Bixby Voice", "Voice assistant"),
        Triple("com.samsung.android.bixby.service", "Bixby Service", "Bixby background service"),
        Triple("com.samsung.android.app.spage", "Samsung Free / Bixby Home", "Feed, background refresh"),
        Triple("com.samsung.android.visionintelligence", "Bixby Vision", "Camera AI"),
        Triple("com.samsung.android.game.gamehome", "Game Launcher", "Game hub, background tracking"),
        Triple("com.samsung.android.app.tips", "Tips", "Tips app"),
        Triple("com.samsung.android.arzone", "AR Zone", "AR features"),
        Triple("com.samsung.android.aremoji", "AR Emoji", "AR emoji engine"),
        Triple("com.samsung.android.da.daagent", "DA Agent", "Samsung analytics helper"),
        Triple("com.samsung.android.mobileservice", "Samsung Mobile Services", "Samsung account sync (Medium risk)"),
        Triple("com.samsung.android.app.sharelive", "Quick Share", "Nearby sharing scanner"),
        Triple("com.sec.android.easyMover.Agent", "Smart Switch Agent", "Phone transfer helper"),
        Triple("com.facebook.appmanager", "Facebook App Manager", "Preinstalled Facebook helper"),
        Triple("com.facebook.services", "Facebook Services", "Preinstalled Facebook helper"),
        Triple("com.facebook.system", "Facebook Installer", "Preinstalled Facebook helper"),
        Triple("com.microsoft.appmanager", "Link to Windows", "Phone Link background service"),
    )
    private val SVCS = listOf(
        Triple("statsd", "Stats daemon", "Metrics collection"),
        Triple("traced", "Perfetto tracing", "System tracing"),
        Triple("traced_probes", "Perfetto probes", "System tracing probes"),
        Triple("tombstoned", "Crash dump daemon", "Native crash dumps"),
        Triple("sec-vibrator-2-2", "Samsung vibrator HAL", "Vibration motor service (vibration stops until reboot)"),
    )
    // DANGER: manual-only, never included in STOP ALL
    private val DANGER = listOf(
        Triple("logd", "Log daemon", "Kills logcat/system logging until reboot"),
    )
    // Runtime tweaks: (id, title, note, apply command, "needs applying" check)
    private class Tweak(val id: String, val title: String, val note: String, val cmd: String, val needed: () -> Boolean)
    private val TWEAKS = listOf(
        Tweak("klog", "Kernel log (printk)", "Silence kernel console messages + clear dmesg",
            "echo '0 0 0 0' > /proc/sys/kernel/printk; dmesg -c > /dev/null",
            { sh("cut -f1 /proc/sys/kernel/printk") != "0" }),
        Tweak("kmsg", "Kernel log writes (/dev/kmsg)", "Ignore messages userspace writes into the kernel log",
            "echo off > /proc/sys/kernel/printk_devkmsg",
            { sh("cat /proc/sys/kernel/printk_devkmsg").let { it.isNotEmpty() && !it.contains("off") } }),
        Tweak("logtag", "Log level (log.tag)", "Suppress debug/verbose/noisy app logging globally",
            "setprop log.tag S",
            { sh("getprop log.tag") != "S" }),
    )
    private val VIB_KEYS = listOf("vibrat", "haptic")

    private fun running(s: String) = sh("getprop init.svc.$s") == "running" || sh("pidof $s").isNotEmpty()

    private fun procsOf(p: String) =
        sh("ps -A -o PID,NAME | grep -F $p").lines().filter { it.isNotBlank() && !it.contains("grep") }

    fun sh(c: String) = Shell.cmd(c).exec().out.joinToString("\n").trim()

    fun scan(log: (String) -> Unit): List<Target> {
        val out = mutableListOf<Target>()
        log("$ id"); log(sh("id"))
        log("[SCAN] packages...")
        val installed = sh("pm list packages").lines().map { it.removePrefix("package:") }.toSet()
        log("[SCAN] ${installed.size} packages installed, checking ${PKGS.size} known bloat entries")
        for ((p, name, note) in PKGS) {
            if (p !in installed) { log("[ -- ] $name | $p | not installed"); continue }
            val procs = procsOf(p)
            if (procs.isEmpty()) { log("[ -- ] $name | $p | installed, not running"); continue }
            log("[FOUND] $name | $p | ${procs.size} process(es) | $note")
            procs.forEach { log("         pid ${it.trim()}") }
            out += Target("pkg", p, name, note)
        }
        log("[SCAN] init services...")
        for ((s, name, note) in SVCS) {
            if (running(s)) {
                log("[FOUND] $name | svc:$s | pid=${sh("pidof $s")} | state=${sh("getprop init.svc.$s")} | $note"); out += Target("svc", s, name, note)
            } else log("[ -- ] $name | svc:$s | not running")
        }
        log("[SCAN] core services (DANGER, manual only)...")
        for ((s, name, note) in DANGER) {
            if (running(s)) {
                log("[DANGER] $name | svc:$s | pid=${sh("pidof $s")} | state=${sh("getprop init.svc.$s")} | $note"); out += Target("svc", s, name, note, danger = true)
            } else log("[ -- ] $name ($s) not found (init.svc='${sh("getprop init.svc.$s")}', pid='${sh("pidof $s")}')")
        }
        log("[SCAN] vibration / haptics...")
        for (ns in listOf("system", "secure", "global")) {
            sh("settings list $ns").lines().forEach { line ->
                val k = line.substringBefore("=", ""); val v = line.substringAfter("=", "")
                if (k.isNotEmpty() && VIB_KEYS.any { k.contains(it, true) } && v != "0" && v.toIntOrNull() != null) {
                    log("[FOUND] $ns:$k = $v - vibration/haptic setting")
                    out += Target("set", "$ns:$k", "$ns:$k", "Vibration (restored on reboot)")
                }
            }
        }
        log("[SCAN] kernel / log noise...")
        for (t in TWEAKS) if (t.needed()) {
            log("[FOUND] ${t.title} - ${t.note}"); out += Target("cmd", t.id, t.title, t.note)
        }
        log("[FOUND] logcat buffers - shrink and clear (session only)")
        out += Target("logbuf", "logcat", "Logcat buffers", "Shrink + clear")
        log("[DONE] ${out.size} stoppable items.")
        return out
    }

    fun stop(ctx: Context, t: Target): String = when (t.kind) {
        "pkg" -> { val before = procsOf(t.id).size; Shell.cmd("am force-stop ${t.id}").exec()
            val after = procsOf(t.id).size
            if (after == 0) "[ OK ] ${t.title} | ${t.id} | killed $before process(es)" else "[WARN] ${t.title} | ${t.id} | respawned ($after running)" }
        "svc" -> { val pid = sh("pidof ${t.id}"); Shell.cmd("stop ${t.id}").exec()
            if (sh("getprop init.svc.${t.id}").let { it == "stopped" || (it.isEmpty() && sh("pidof ${t.id}").isEmpty()) }) "[ OK ] ${t.title} | svc:${t.id} | was pid $pid, now stopped" else "[FAIL] ${t.title} | svc:${t.id} | still running" }
        "set" -> {
            val (ns, k) = t.id.split(":", limit = 2)
            val prefs = ctx.getSharedPreferences("orig", Context.MODE_PRIVATE)
            if (!prefs.contains(t.id)) prefs.edit().putString(t.id, sh("settings get $ns $k")).apply()
            Shell.cmd("settings put $ns $k 0").exec()
            if (sh("settings get $ns $k") == "0") "[ OK ] ${t.id} | was ${prefs.getString(t.id, "?")} -> 0" else "[FAIL] ${t.id} | could not change"
        }
        "cmd" -> { val tw = TWEAKS.first { it.id == t.id }; Shell.cmd(tw.cmd).exec()
            if (!tw.needed()) "[ OK ] ${t.title} | ran: ${tw.cmd}" else "[FAIL] ${t.title} | blocked by kernel/ROM | tried: ${tw.cmd}" }
        "logbuf" -> { Shell.cmd("logcat -G 64K", "logcat -c").exec(); "[ OK ] log buffers shrunk and cleared" }
        else -> "[FAIL] unknown target"
    }

    fun restore(ctx: Context): Int {
        val prefs = ctx.getSharedPreferences("orig", Context.MODE_PRIVATE)
        val all = prefs.all
        for ((id, v) in all) {
            val (ns, k) = id.split(":", limit = 2)
            Shell.cmd("settings put $ns $k $v").exec()
        }
        prefs.edit().clear().apply()
        return all.size
    }
}
