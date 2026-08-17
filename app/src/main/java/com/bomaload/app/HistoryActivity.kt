package com.bomaload.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    private var mode = "ALL"
    private var selecting = false
    private val selected = mutableSetOf<Int>()
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        Engine.init(applicationContext)
        list = findViewById(R.id.llHistory)

        findViewById<Button>(R.id.bAll).setOnClickListener { mode = "ALL"; selected.clear(); render() }
        findViewById<Button>(R.id.bOk).setOnClickListener { mode = "OK"; selected.clear(); render() }
        findViewById<Button>(R.id.bFail).setOnClickListener { mode = "FAIL"; selected.clear(); render() }
        findViewById<Button>(R.id.bDead).setOnClickListener { mode = "DEAD"; selected.clear(); render() }
        findViewById<Button>(R.id.bVault).setOnClickListener { mode = "VAULT"; selected.clear(); render() }
        findViewById<Button>(R.id.bBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.bShare).setOnClickListener { share() }
        findViewById<Button>(R.id.bSelect).setOnClickListener {
            selecting = !selecting; selected.clear(); render()
        }
        findViewById<Button>(R.id.bSelAll).setOnClickListener {
            selected.clear()
            for (i in rows().indices) selected.add(i)
            render()
        }
        findViewById<Button>(R.id.bClear).setOnClickListener {
            if (mode == "VAULT") { Engine.vaultClear(); toast("Vault cleared") }
            else { Engine.clearHistory(); toast("History cleared") }
            selected.clear(); render()
        }
        findViewById<Button>(R.id.bRetry).setOnClickListener {
            toast("${Engine.retryFailed()} failed pin(s) re-queued")
        }
        render()
    }

    private fun definitive(note: String) =
        listOf("been used", "already used", "invalid", "does not exist", "expired").any { note.contains(it, true) }

    private fun rows(): List<Array<String>> {
        if (mode == "VAULT") {
            return Engine.vaultList().map { arrayOf("VAULT", it, "", "used - tap to remove") }
        }
        return Engine.historyList().mapNotNull { line ->
            val p = line.split("|")
            if (p.size < 5) null else arrayOf(p[0], p[1], p[2], p[3], p[4])
        }.filter { p ->
            when (mode) {
                "OK" -> p[3] == "SUCCESS"
                "FAIL" -> p[3] == "FAILED"
                "DEAD" -> p[3] == "FAILED" && definitive(p[4])
                else -> true
            }
        }
    }

    private fun render() {
        val (s, tot) = Engine.todayStats()
        val rate = if (tot > 0) s * 100 / tot else 100
        val score = findViewById<TextView>(R.id.tvScore)
        score.text = if (selecting) "${selected.size} selected"
        else "TODAY: $s loaded of $tot attempts · $rate% success"

        findViewById<Button>(R.id.bSelect).text = if (selecting) "DONE" else "SELECT"
        findViewById<Button>(R.id.bSelAll).visibility = if (selecting) View.VISIBLE else View.GONE

        list.removeAllViews()
        val fmt = SimpleDateFormat("HH:mm dd/MM", Locale.getDefault())
        val rws = rows()
        rws.forEachIndexed { idx, p ->
            val card = LinearLayout(this)
            card.orientation = LinearLayout.VERTICAL
            card.setBackgroundResource(R.drawable.btn_bg)
            val lp = LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = 8
            card.layoutParams = lp
            card.setPadding(16, 12, 16, 12)
            if (selecting && selected.contains(idx)) card.alpha = 0.55f else card.alpha = 1f

            if (p.size == 4) {
                val t1 = TextView(this)
                t1.text = (if (selecting && selected.contains(idx)) "✓ " else "") +
                        "•••• ${p[1].takeLast(4)}   (tap to remove)"
                t1.setTextColor(0xFFEAECEF.toInt()); t1.textSize = 12f
                card.addView(t1)
                val t2 = TextView(this)
                t2.text = p[1]
                t2.setTextColor(0xFF848E9C.toInt()); t2.textSize = 10f
                card.addView(t2)
                card.setOnClickListener {
                    if (selecting) { toggle(idx) } else { Engine.vaultRemove(p[1]); toast("Removed from vault"); render() }
                }
            } else {
                val row1 = LinearLayout(this)
                row1.orientation = LinearLayout.HORIZONTAL
                val time = TextView(this)
                time.text = (if (selecting && selected.contains(idx)) "✓ " else "") +
                        fmt.format(Date(p[0].toLongOrNull() ?: 0L))
                time.setTextColor(0xFF848E9C.toInt()); time.textSize = 10f
                time.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                row1.addView(time)
                val chip = TextView(this)
                chip.text = p[3]
                chip.textSize = 10f
                chip.setTextColor(if (p[3] == "SUCCESS") 0xFF0ECB81.toInt() else 0xFFF6465D.toInt())
                row1.addView(chip)
                card.addView(row1)
                val t2 = TextView(this)
                t2.text = "•••• ${p[1].takeLast(4)}  ->  ${p[2]}"
                t2.setTextColor(0xFFEAECEF.toInt()); t2.textSize = 12f
                t2.setPadding(0, 6, 0, 6)
                card.addView(t2)
                val t3 = TextView(this)
                t3.text = p[4]
                t3.setTextColor(0xFF848E9C.toInt()); t3.textSize = 10f
                card.addView(t3)
                card.setOnClickListener { if (selecting) toggle(idx) }
            }
            list.addView(card)
        }
        if (list.childCount == 0) {
            val empty = TextView(this)
            empty.text = if (mode == "VAULT") "vault empty" else "nothing here yet"
            empty.setTextColor(0xFF4A5158.toInt()); empty.textSize = 12f
            empty.setPadding(16, 24, 16, 24)
            list.addView(empty)
        }
    }

    private fun toggle(idx: Int) {
        if (selected.contains(idx)) selected.remove(idx) else selected.add(idx)
        render()
    }

    private fun share() {
        val sb = StringBuilder("BOMA LOAD report ($mode)\n")
        val fmt = SimpleDateFormat("HH:mm dd/MM", Locale.getDefault())
        val rws = rows()
        rws.forEachIndexed { idx, p ->
            if (selecting && !selected.contains(idx)) return@forEachIndexed
            if (p.size == 4) sb.append("USED ••••${p[1].takeLast(4)}\n")
            else sb.append("${fmt.format(Date(p[0].toLongOrNull() ?: 0L))} ••••${p[1].takeLast(4)} -> ${p[2]} [${p[3]}] ${p[4]}\n")
        }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND)
            .putExtra(Intent.EXTRA_TEXT, sb.toString()).setType("text/plain"), "Share report"))
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}
