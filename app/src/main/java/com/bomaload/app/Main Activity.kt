package com.bomaload.app

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Engine.init(applicationContext)

        Engine.ui = { runOnUiThread { render() } }
        Engine.log = { m -> runOnUiThread { findViewById<TextView>(R.id.tvLog).append(m + "\n") } }

        val perms = mutableListOf(Manifest.permission.CALL_PHONE)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(perms.toTypedArray(), 9)

        findViewById<Button>(R.id.btnUpload).setOnClickListener {
            val i = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"; putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
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
        findViewById<Button>(R.id.btnAddTarget).setOnClickListener {
            val et = findViewById<EditText>(R.id.etNewTarget)
            val d = et.text.toString().filter { it.isDigit() }
            val n0 = when {
                d.length == 10 && d.startsWith("0") -> d
                d.length == 12 && d.startsWith("254") -> "0" + d.drop(3)
                d.length == 13 && et.text.toString().startsWith("+") -> "0" + d.drop(4)
                else -> null
            }
            if (n0 == null) toast("Enter a valid number") else { Engine.addTarget(n0); et.text.clear() }
        }
        findViewById<Button>(R.id.btnMode).setOnClickListener {
            Engine.turbo = !Engine.turbo; Engine.save(); render()
            toast(if (Engine.turbo) "⚡ TURBO – no pacing, your risk" else "🛡 SAFE – 45s per line")
        }
        findViewById<Button>(R.id.btnHistory).setOnClickListener { startActivity(Intent(this, HistoryActivity::class.java)) }
        findViewById<Button>(R.id.btnStart).setOnClickListener { startAutomation() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { Engine.stop() }
        findViewById<Button>(R.id.btnBalance).setOnClickListener { Engine.checkBalance() }
        findViewById<Button>(R.id.btnUpdate).setOnClickListener { checkUpdate() }

        adapter = ArrayAdapter(this, R.layout.list_item, R.id.tv, mutableListOf())
        findViewById<ListView>(R.id.lvQueue).apply {
            adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, pos, _ ->
                if (pos < Engine.queue.size) Engine.removeAt(pos)
                else Engine.promoteReview(pos - Engine.queue.size)
            }
        }
        render()
    }

    private fun startAutomation() {
        if (Engine.running) { toast("Already running"); return }
        if (Engine.queue.none { it.status == "PENDING" }) { toast("Queue empty – upload/add PINs first"); return }
        if (!Engine.accessibilityOn(this) || Engine.service == null) {
            toast("Reader asleep – toggle Boma Load OFF then ON in Accessibility, then Start")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); return
        }
        Engine.start()
    }

    private fun checkUpdate() {
        toast("Checking for update…")
        Thread {
            try {
                val conn = java.net.URL(UPDATE_URL).openConnection() as java.net.HttpURLConnection
                conn.instanceFollowRedirects = true; conn.connectTimeout = 15000
                val f = File(cacheDir, "update.apk")
                conn.inputStream.use { ins -> f.outputStream().use { outs -> ins.copyTo(outs) } }
                conn.disconnect()
                val uri = FileProvider.getUriForFile(this, "$packageName.fp", f)
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,
                    "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } catch (e: Exception) {
                runOnUiThread { toast("No update found – run workflow first / check internet") }
            }
        }.start()
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
        OcrExtractor.extract(b) { r ->
            runOnUiThread {
                val c = Engine.addPins(r.pins)
                r.review.forEach { rv -> if (!Engine.review.contains(rv)) Engine.review.add(rv) }
                Engine.log?.invoke("📷 Image → ${c[0]} new, ${c[1]} already-used skipped, ${r.review.size} in review")
                if (r.expired) toast("⚠ EXPIRED CARD detected in that image!")
                toast("Image: ${c[0]} new PIN(s)${if (r.review.isNotEmpty()) ", ${r.review.size} to REVIEW" else ""}")
            }
        }
    }

    private fun render() {
        // targets
        val ll = findViewById<LinearLayout>(R.id.llTargets)
        ll.removeAllViews()
        Engine.targets.forEach { t ->
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            val cb = CheckBox(this)
            cb.isChecked = t.enabled
            cb.setTextColor(0xFFFFFFFF.toInt())
            cb.text = "${t.label}   today: ${t.today}"
            cb.setOnCheckedChangeListener { _, on -> t.enabled = on; Engine.save() }
            row.addView(cb)
            ll.addView(row)
        }
        // queue + review
        adapter.clear()
        Engine.queue.forEachIndexed { i, p ->
            adapter.add("${i + 1}. •••• ${p.pin.takeLast(4)} → ${if (p.target.isEmpty()) "–" else p.target} [${p.status}] ${p.note}")
        }
        Engine.review.forEachIndexed { i, rv ->
            adapter.add("⚠ REVIEW ${rv} (tap=keep, tap again=drop)")
        }
        adapter.notifyDataSetChanged()
        val done = Engine.queue.count { it.status == "SUCCESS" || it.status == "FAILED" }
        val pend = Engine.queue.count { it.status == "PENDING" }
        val act = maxOf(1, Engine.targets.count { it.enabled })
        val eta = if (Engine.running && pend > 0)
            (pend * ((if (Engine.turbo) 3000L else Engine.SAFE_GAP_MS / act + 2000L)) / 1000) else 0
        findViewById<TextView>(R.id.tvProgress).text =
            "$done of ${Engine.queue.size} processed" + if (eta > 0) " • ~${eta}s left" else ""
        findViewById<TextView>(R.id.tvBalance).text =
            if (Engine.balance.isEmpty()) "Balance: tap CHECK BALANCE" else "💰 Balance: Ksh ${Engine.balance}"
        findViewById<Button>(R.id.btnMode).text =
            if (Engine.turbo) "MODE: TURBO ⚡ (risky)" else "MODE: SAFE 🛡"
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
                      }
