package com.bomaload.app

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class BrainActivity : AppCompatActivity() {

    private lateinit var chat: LinearLayout
    private lateinit var sc: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_brain)
        chat = findViewById(R.id.llChat)
        sc = findViewById(R.id.scChat)
        findViewById<Button>(R.id.bBack).setOnClickListener { finish() }

        val chips = mapOf(
            R.id.cToday to "today", R.id.cSkips to "skips", R.id.cGhosts to "ghosts",
            R.id.cProblems to "problems", R.id.cSpeed to "speed", R.id.cRequeue to "requeue"
        )
        chips.forEach { (id, cmd) ->
            findViewById<Button>(id).setOnClickListener { send(cmd) }
        }
        findViewById<Button>(R.id.bSend).setOnClickListener {
            val et = findViewById<EditText>(R.id.etMsg)
            val q = et.text.toString().trim()
            if (q.isNotEmpty()) { send(q); et.text.clear() }
        }
        add("BOMA BRAIN", Brain.greet())
    }

    private fun send(q: String) {
        add("YOU", q)
        add("BRAIN", Brain.ask(q))
    }

    private fun add(who: String, msg: String) {
        val tv = TextView(this)
        tv.text = "$who: $msg"
        tv.textSize = 12f
        tv.setPadding(16, 12, 16, 12)
        tv.setBackgroundResource(R.drawable.btn_bg)
        tv.setTextColor(if (who == "BRAIN") 0xFF0ECB81.toInt() else 0xFFEAECEF.toInt())
        val lp = LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = 8
        tv.layoutParams = lp
        chat.addView(tv)
        sc.post { sc.fullScroll(View.FOCUS_DOWN) }
    }
}
