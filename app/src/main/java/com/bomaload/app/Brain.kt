package com.bomaload.app

object Brain {

    fun ask(q: String): String {
        val s = q.lowercase()
        return when {
            s.contains("requeue") || s.contains("retry skipped") -> {
                val n = Engine.requeueUnloaded()
                "Done. Re-queued $n pin(s). Press START to load them."
            }
            s.contains("remove") && (s.contains("ghost") || s.contains("dup") || s.contains("unreal")) -> {
                val n = Engine.purgeGhosts()
                "Purged $n ghost/duplicate pin(s). Queue now matches your uploads."
            }
            s.contains("ghost") || s.contains("dup") || s.contains("unreal") -> {
                val g = Engine.ghostList()
                if (g.isEmpty()) "No ghosts. Every queued pin is real, unique and not yet loaded. Queue = exactly what you uploaded."
                else "Found ${g.size} ghost pin(s) (already loaded or duplicate): " +
                        g.joinToString(", ") { "••••${it.pin.takeLast(4)}" } +
                        ". Say \"remove ghosts\" and I'll purge them."
            }
            s.contains("skip") -> {
                val sk = Engine.todaySkips()
                if (sk.isEmpty()) "No skips today. Every pin either loaded or died honestly."
                else "Skipped today: ${sk.size} pin(s): " + sk.joinToString(", ") { "••••${it.takeLast(4)}" } +
                        ". Say \"requeue\" to reload them."
            }
            s.contains("problem") || s.contains("audit") || s.contains("check") || s.contains("wrong") -> audit()
            s.contains("speed") || s.contains("fast") || s.contains("time") -> {
                "Learned speed: SAFE ~${Engine.estimateSec(1, false)}s/pin, FAST ~${Engine.estimateSec(1, true)}s/pin. " +
                        (if (Engine.lastBatchCount > 0) "Last batch: ${Engine.lastBatchCount} pins in ${Engine.lastBatchSecs}s." else "No batch yet today.")
            }
            s.contains("balance") -> if (Engine.balance.isEmpty()) "No balance yet - tap CHECK BALANCE." else "Last balance: Ksh ${Engine.balance}"
            s.contains("clear review") -> { Engine.clearReview(); "Review cleared." }
            s.contains("today") || s.contains("report") || s.contains("summary") -> today()
            s.contains("help") -> help()
            else -> today() + "\n\n" + help()
        }
    }

    fun greet(): String = audit()

    private fun today(): String {
        val (s, tot) = Engine.todayStats()
        val rate = if (tot > 0) s * 100 / tot else 100
        val per = Engine.targets.joinToString(" · ") { "${it.label}: ${it.today}" }
        return "TODAY: $s loaded of $tot attempts ($rate%).\nPer line → $per.\n" +
                (if (Engine.lastBatchCount > 0) "Last batch: ${Engine.lastBatchCount} pins in ${Engine.lastBatchSecs}s." else "")
    }

    private fun audit(): String {
        val problems = mutableListOf<String>()
        if (Engine.service == null) problems.add("READER ASLEEP - toggle accessibility OFF/ON")
        val ghosts = Engine.ghostList().size
        if (ghosts > 0) problems.add("$ghosts ghost pin(s) in queue - say \"remove ghosts\"")
        if (Engine.review.isNotEmpty()) problems.add("${Engine.review.size} unclear pin(s) in REVIEW - keep or drop them")
        if (Engine.downgrades > 0) problems.add("${Engine.downgrades} connection-after-accept event(s) caught & retried today")
        val sk = Engine.todaySkips().size
        if (sk > 0) problems.add("$sk skipped pin(s) - say \"requeue\"")
        val (s, tot) = Engine.todayStats()
        val head = "BRAIN AUDIT → " + if (problems.isEmpty()) "ALL CLEAN. No ghosts, no skips, reader alive. " else ""
        return head + problems.joinToString("\n- ", "- ", "") + "\n" + today()
    }

    private fun help(): String =
        "I can: \"today\" report · \"skips\" list · \"requeue\" reload skips · \"ghosts\" detect · " +
                "\"remove ghosts\" purge · \"problems\" full audit · \"speed\" stats · \"balance\" · \"clear review\"."
}
