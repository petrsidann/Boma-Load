package com.bomaload.app

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : AppCompatActivity() {

    private val REQ_PICK = 1
    private val REQ_CAM = 2
    private var lastCamFile: File? = null
    private lateinit var adapter: ArrayAdapter<String>

    private val UPDATE_URL = "https://github.com/petrsidann/Boma-Load/releases/latest/download/app-debug.apk"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Engine.appCtx = applicationContext
        Engine.ui = { runOnUiThread { renderQueue() } }
        Engine.log = { m -> runOnUiThread { findViewById<TextView>(R.id.tvLog).append(m + "\n") } }

        requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), 9)

        findViewById<RadioGroup>(R.id.rgTarget).setOnCheckedChangeListener { _, id ->
            findViewById<EditText>(R.id.etOther).visibility =
                if (id == R.id.rbOther) View.VISIBLE else View.GONE
        }

        findViewById<Button>(R.id.btnUpload).setOnClickListener {
            val i = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            startActivityForResult(Intent.createChooser(i, "Select voucher images"), REQ_PICK)
        }

        findViewById<Button>(R.id.btnScan).setOnClickListener {
            val f = File(cacheDir, "scan_${System.currentTimeMillis()}.jpg")
            lastCamFile = f
            val uri = FileProvider.getUriForFile(this, "$packageName.fp", f)
            startActivityForResult(
                Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri), REQ_CAM)
        }

        findViewById<Button>(R.id.btnAddPin).setOnClickListener {
            val et = findViewById<EditText>(R.id.etManualPin)
            val p = et.text.toString().trim().filter { it.isDigit() }
            if (p.length == 16) { Engine.addPin(p); et.text.clear() } else toast("PIN must be 16 digits")
        }

        adapter = ArrayAdapter(this, R.layout.list_item, R.id.tv, mutableListOf())
        findViewById<ListView>(R.id.lvQueue).apply {
            adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> Engine.removeAt(pos) }
        }

        findViewById<Button>(R.id.btnStart).setOnClickListener { startAutomation() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { Engine.stop() }
        findViewById<Button>(R.id.btnBalance).setOnClickListener { Engine.checkBalance() }
        findViewById<Button>(R.id.btnUpdate).setOnClickListener { checkUpdate() }
        renderQueue()
    }

    private fun checkUpdate() {
        toast("Checking for update…")
        Thread {
            try {
                val conn = java.net.URL(UPDATE_URL).openConnection() as java.net.HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 15000
                val f = File(cacheDir, "update.apk")
                conn.inputStream.use { ins -> f.outputStream().use { outs -> ins.copyTo(outs) } }
                conn.disconnect()
                val uri = FileProvider.getUriForFile(this, "$packageName.fp", f)
                val intent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(intent)
            } catch (e: Exception) {
                runOnUiThread { toast("No update found – check internet / run the workflow first") }
            }
        }.start()
    }

    private fun startAutomation() {
        if (Engine.running) { toast("Already running"); return }
        if (Engine.queue.none { it.status == "PENDING" }) { toast("Queue empty – upload/add PINs first"); return }
        if (findViewById<RadioButton>(R.id.rbOther).isChecked) {
            val raw = findViewById<EditText>(R.id.etOther).text.toString()
            val d = raw.filter { it.isDigit() }
            val norm0 = when {
                d.length == 10 && d.startsWith("0") -> d
                d.length == 12 && d.startsWith("254") -> "0" + d.drop(3)
                d.length == 13 && raw.startsWith("+") -> "0" + d.drop(4)
                else -> null
            }
            if (norm0 == null) { toast("Enter a valid target number"); return }
            Engine.other0 = norm0
            Engine.mode = "OTHER"
        } else {
            Engine.mode = "SELF"
        }

        if (!Engine.accessibilityOn(this)) {
            toast("One-time setup: switch ON 'Boma Load' under Accessibility, then press Start again")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        Engine.start()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_PICK -> {
                val uris = mutableListOf<Uri>()
                val clip = data?.clipData
                if (clip != null) for (i in 0 until clip.itemCount) uris += clip.getItemAt(i).uri
                else data?.data?.let { uris += it }
                uris.forEach { processUri(it) }
            }
            REQ_CAM -> lastCamFile?.let { f ->
                BitmapFactory.decodeFile(f.absolutePath)?.let { extractFrom(shrink(it)) }
            }
        }
    }

    private fun processUri(uri: Uri) {
        try {
            contentResolver.openInputStream(uri).use { ins ->
                BitmapFactory.decodeStream(ins)?.let { extractFrom(shrink(it)) }
            }
        } catch (e: Exception) { toast("Could not read image") }
    }

    private fun shrink(b: Bitmap): Bitmap {
        val max = 1400
        if (maxOf(b.width, b.height) <= max) return b
        val s = max.toFloat() / maxOf(b.width, b.height)
        return Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true)
    }

    private fun extractFrom(b: Bitmap) {
        toast("Reading PINs…")
        OcrExtractor.extract(b) { pins ->
            runOnUiThread {
                pins.forEach { Engine.addPin(it) }
                toast(
                    if (pins.isEmpty()) "No 16-digit PIN found – take a clearer photo or add manually"
                    else "Found ${pins.size} PIN(s)"
                )
            }
        }
    }

    private fun renderQueue() {
        adapter.clear()
        Engine.queue.forEachIndexed { i, p ->
            adapter.add("${i + 1}.  •••• ${p.pin.takeLast(4)}   [${p.status}] ${p.note}")
        }
        adapter.notifyDataSetChanged()
        val done = Engine.queue.count { it.status == "SUCCESS" || it.status == "FAILED" }
        findViewById<TextView>(R.id.tvProgress).text = "$done of ${Engine.queue.size} processed"
        findViewById<TextView>(R.id.tvBalance).text =
            if (Engine.balance.isEmpty()) "Balance: tap CHECK BALANCE" else "💰 Balance: Ksh ${Engine.balance}"
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}
