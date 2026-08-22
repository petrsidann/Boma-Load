package com.bomaload.app

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class UpdateActivity : AppCompatActivity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_update)
        findViewById<TextView>(R.id.tvCurrent).text = "Current version: v${BuildConfig.VERSION_CODE}"
        status = findViewById(R.id.tvStatus)
        findViewById<ImageButton>(R.id.ibBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnInstall).isEnabled = false
        check()
    }

    private fun say(m: String) = runOnUiThread { status.text = m }

    private fun check() {
        say("Contacting GitHub...")
        Thread {
            try {
                val conn = URL("https://api.github.com/repos/petrsidann/Boma-Load/releases/latest")
                    .openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val tag = Regex("\"tag_name\"\\s*:\\s*\"v?(\\d+)\"").find(body)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val url = Regex("\"browser_download_url\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1) ?: ""
                runOnUiThread {
                    if (tag > BuildConfig.VERSION_CODE) {
                        status.text = "Update available: v$tag"
                        val b = findViewById<Button>(R.id.btnInstall)
                        b.isEnabled = true
                        b.text = "INSTALL UPDATE v$tag"
                        b.setOnClickListener { download(url) }
                    } else {
                        status.text = "Your App is Up To Date (v${BuildConfig.VERSION_CODE})"
                    }
                }
            } catch (e: Exception) {
                say("Check failed (${e.javaClass.simpleName}) - check internet")
            }
        }.start()
    }

    private fun download(url: String) {
        say("Downloading update...")
        Thread {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 10000
                conn.readTimeout = 15000
                val total = conn.contentLength
                val f = File(cacheDir, "update.apk")
                conn.inputStream.use { ins ->
                    f.outputStream().use { outs ->
                        val buf = ByteArray(8192)
                        var done = 0L; var r = ins.read(buf)
                        while (r >= 0) {
                            outs.write(buf, 0, r)
                            done += r
                            if (total > 0) say("Downloading ${done * 100 / total}%")
                            r = ins.read(buf)
                        }
                    }
                }
                conn.disconnect()
                say("Download complete - installing...")
                val uri = FileProvider.getUriForFile(this, "$packageName.fp", f)
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,
                    "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } catch (e: Exception) {
                say("Download failed (${e.javaClass.simpleName})")
            }
        }.start()
    }
}
