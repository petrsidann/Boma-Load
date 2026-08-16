package com.bomaload.app

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class UpdateActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_update)
        findViewById<TextView>(R.id.tvCurrent).text = "Current version: v${BuildConfig.VERSION_CODE}"
        findViewById<ImageButton>(R.id.ibBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnInstall).isEnabled = false
        check()
    }

    private fun check() {
        findViewById<TextView>(R.id.tvStatus).text = "Checking..."
        Thread {
            try {
                val conn = URL("https://api.github.com/repos/petrsidann/Boma-Load/releases/latest")
                    .openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val tag = Regex("\"tag_name\"\\s*:\\s*\"v?(\\d+)\"").find(body)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val url = Regex("\"browser_download_url\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1) ?: ""
                runOnUiThread {
                    if (tag > BuildConfig.VERSION_CODE) {
                        findViewById<TextView>(R.id.tvStatus).text = "Update available: v$tag"
                        val b = findViewById<Button>(R.id.btnInstall)
                        b.isEnabled = true
                        b.text = "INSTALL UPDATE v$tag"
                        b.setOnClickListener { download(url) }
                    } else {
                        findViewById<TextView>(R.id.tvStatus).text = "Your App is Up To Date"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    findViewById<TextView>(R.id.tvStatus).text = "Check failed - check internet"
                }
            }
        }.start()
    }

    private fun download(url: String) {
        Toast.makeText(this, "Downloading update...", Toast.LENGTH_LONG).show()
        Thread {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 15000
                val f = File(cacheDir, "update.apk")
                conn.inputStream.use { ins -> f.outputStream().use { outs -> ins.copyTo(outs) } }
                conn.disconnect()
                val uri = FileProvider.getUriForFile(this, "$packageName.fp", f)
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,
                    "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Download failed", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }
}
