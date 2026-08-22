package com.bomaload.app

import android.content.Context

object Ledger {
    val vault = mutableSetOf<String>()
    val history = mutableListOf<String>()
    var avgSafe = 4.0
    var avgFast = 3.0
    private var ctx: Context? = null

    fun init(c: Context) {
        ctx = c
        val sp = c.getSharedPreferences("boma", Context.MODE_PRIVATE)
        vault.clear()
        vault.addAll(sp.getStringSet("vault", emptySet()) ?: emptySet())
        history.clear()
        history.addAll((sp.getString("history", "") ?: "").split("\n").filter { it.isNotBlank() })
        avgSafe = sp.getFloat("avgSafe", 4f).toDouble()
        avgFast = sp.getFloat("avgFast", 3f).toDouble()
    }

    fun save() {
        val sp = ctx?.getSharedPreferences("boma", Context.MODE_PRIVATE) ?: return
        sp.edit()
            .putStringSet("vault", vault.toSet())
            .putString("history", history.joinToString("\n"))
            .putFloat("avgSafe", avgSafe.toFloat())
            .putFloat("avgFast", avgFast.toFloat())
            .apply()
    }

    fun inVault(p: String) = vault.contains(p)
    fun vaultAdd(p: String) { vault.add(p) }
    fun vaultRemove(p: String) { vault.remove(p); save() }
    fun vaultClear() { vault.clear(); save() }
    fun vaultList(): List<String> = vault.toList().sorted()

    fun dayStart(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    fun todayLoaded(): Int {
        val t0 = dayStart()
        return history.count { l ->
            val p = l.split("|")
            p.size >= 4 && p[3] == "SUCCESS" && (p[0].toLongOrNull() ?: 0L) >= t0
        }
    }

    fun todayFails(): Int {
        val t0 = dayStart()
        return history.count { l ->
            val p = l.split("|")
            p.size >= 4 && p[3] == "FAILED" && (p[0].toLongOrNull() ?: 0L) >= t0
        }
    }

    fun todayStats(): Pair<Int, Int> {
        val s = todayLoaded()
        return s to (s + todayFails())
    }

    fun add(line: String) { history.add(line) }
    fun historyList(): List<String> = history.reversed()
    fun clearHistory() { history.clear(); save() }

    fun learn(fast: Boolean, s: Double) {
        if (fast) avgFast = avgFast * 0.7 + s * 0.3 else avgSafe = avgSafe * 0.7 + s * 0.3
    }

    fun estimateSec(pending: Int, fastMode: Boolean): Long {
        val per = (if (fastMode) avgFast else avgSafe) + (if (fastMode) 1.5 else 2.0)
        val cool = if (fastMode) 0 else (pending / 10) * 10
        return (pending * per).toLong() + cool
    }
}
