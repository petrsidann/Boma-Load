package com.bomaload.app

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    private var filter = "ALL"
    private lateinit var adapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        Engine.init(applicationContext)

        adapter = ArrayAdapter(this, R.layout.list_item, R.id.tv, mutableListOf())
        findViewById<ListView>(R.id.lvHistory).adapter = adapter

        findViewById<Button>(R.id.bAll).setOnClickListener { filter = "ALL"; render() }
        findViewById<Button>(R.id.bOk).setOnClickListener { filter = "SUCCESS"; render() }
        findViewById<Button>(R.id.bFail).setOnClickListener { filter = "FAILED"; render() }
        findViewById<Button>(R.id.bBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.bClear).setOnClickListener { Engine.clearHistory(); render() }
        findViewById<Button>(R.id.bRetry).setOnClickListener {
            val n = Engine.retryFailed()
            android.widget.Toast.makeText(this, "$n failed pin(s) re-queued", android.widget.Toast.LENGTH_LONG).show()
            render()
        }
        findViewById<Button>(R.id.bShare).setOnClickListener {
            val txt = StringBuilder("Boma Load report\n")
            Engine.historyList().forEach { l -> txt.append(fmt(l)).append("\n") }
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND)
                .putExtra(Intent.EXTRA_TEXT, txt.toString()).setType("text/plain"), "Share report"))
        }
        render()
    }

    private fun fmt(line: String): String {
        val p = line.split("|")
        if (p.size < 5) return line
        val time = SimpleDateFormat("HH:mm dd/MM", Locale.getDefault()).format(Date(p[0].toLongOrNull() ?: 0L))
        return "$time ••••${p[1].takeLast(4)} → ${p[2]} [${p[3]}] ${p[4]}"
    }

    private fun render() {
        adapter.clear()
        Engine.historyList().filter { filter == "ALL" || it.contains("|$filter|") }.forEach { adapter.add(fmt(it)) }
        adapter.notifyDataSetChanged()
    }
}
