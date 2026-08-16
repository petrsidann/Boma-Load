package com.bomaload.app

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.File

class MainActivity : AppCompatActivity() {

    private val REQ_PICK = 1
    private val REQ_CAM = 2
    private var lastCamFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Engine.init(applicationContext)

        Engine.ui = { runOnUiThread { render() } }
        Engine.log = { m ->
            runOnUiThread {
                val sv = findViewById<ScrollView>(R.id.cTerm)
                val atBottom = !sv.canScrollVertically(1)
                findViewById<TextView>(R.id.tvLog).append(m + "\n")
                if (atBottom) sv.post { sv.fullScroll(View.FOCUS_DOWN) }
            }
        }

        val perms = mutableListOf(Manifest.permission.CALL_PHONE)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(perms.toTypedArray(), 9)

        bindCollapse(R.id.hTargets, R.id.cTargets)
        bindCollapse(R.id.hLoad, R.id.cLoad)
        bindCollapse(R.id.hTerm, R.id.cTerm)

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
            Engine.speed = Engine.speed % 4 + 1
            Engine.save(); render()
            toast("Speed ${Engine.speed}/4 ${Engine.speedName()}" +
                    if (Engine.speed == 4) " - maximum risk" else "")
        }
        findViewById<Button>(R.id.btnRequeue).setOnClickListener {
            toast("${Engine.requeueUnloaded()} re-queued")
        }
        findViewById<Button>(R.id.btnHistory).setOnClickListener { startActivity(Intent(this, HistoryActivity::class.java)) }
        findViewById<Button>(R.id.btnStart).setOnClickListener { startAutomation() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { Engine.stop() }
        findViewById<Button>(R.id.btnBalance).setOnClickListener { Engine.checkBalance() }
        findViewById<Button>(R.id.btnUpdate).setOnClickListener { startActivity(Intent(this, UpdateActivity::class.java)) }

        entranceAnimation()
        render()
    }

    private fun entranceAnimation() {
        val root = findViewById<LinearLayout>(R.id.root)
        for (i in 0 until root.childCount) {
            val v = root.getChildAt(i)
            v.alpha = 0f; v.translationY = 30f
            v.animate().alpha(1f).translationY(0f).setDuration(380).setStartDelay(i * 70L).start()
        }
    }

    private fun bindCollapse(header: Int, content: Int) {
        findViewById<TextView>(header).setOnClickListener {
            val v = findViewById<View>(content)
            v.visibility = if (v.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
    }

    private fun startAutomation() {
        if (Engine.running) { toast("Already running"); return }
        if (Engine.queue.none { it.status == "PENDING" }) { toast("Queue empty - upload or add PINs first"); return }
        if (!Engine.accessibilityOn(this) || Engine.service == null) {
            toast("Reader asleep - toggle Boma Load OFF then ON in Accessibility")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); return
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
                oriented(f.absolutePath)?.let { extractFrom(shrink(it)) }
            }
        }
    }

    private fun oriented(path: String): Bitmap? {
        val b = BitmapFactory.decodeFile(path) ?: return null
        return try {
            val ex = ExifInterface(path)
            when (ex.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> rotate(b, 90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> rotate(b, 180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> rotate(b, 270f)
                else -> b
            }
        } catch (e: Exception) { b }
    }

    private fun rotate(b: Bitmap, d: Float): Bitmap {
        val m = Matrix(); m.postRotate(d)
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }

    private fun processUri(uri: Uri) {
        try {
            contentResolver.openInputStream(uri).use { ins ->
                BitmapFactory.decodeStream(ins)?.let { extractFrom(shrink(it)) }
            }
        } catch (e: Exception) { toast("Could not read image") }
    }

    private fun shrink(b: Bitmap): Bitmap {
        val max = 2000
        if (maxOf(b.width, b.height) <= max) return b
        val s = max.toFloat() / maxOf(b.width, b.height)
        return Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true)
    }

    private fun extractFrom(b: Bitmap) {
        toast("Reading PINs (multi-pass)...")
        val expected = findViewById<EditText>(R.id.etExpected).text.toString().toIntOrNull() ?: 0
        OcrExtractor.extract(b, expected) { r ->
            runOnUiThread {
                val c = Engine.addPins(r.pins)
                r.review.forEach { rv ->
                    if (!Engine.inVault(rv) && !Engine.review.contains(rv)) Engine.review.add(rv)
                }
                val found = c.new + c.used
                val skipped = if (c.usedPins.isNotEmpty())
                    " [skipped: " + c.usedPins.joinToString(",") { "••••${it.takeLast(4)}" } + "]" else ""
                Engine.log?.invoke("IMG: ${c.new} new, ${c.used} before$skipped, ${r.review.size} review")
                if (r.expired) toast("WARN: expired card detected")
                when {
                    expected > 0 && found < expected ->
                        toast("READ $found/$expected - ${c.new} new, ${c.used} before$skipped")
                    else ->
                        toast("OK - ${c.new} new, ${c.used} already loaded$skipped")
                }
            }
        }
    }

    private fun render() {
        val ll = findViewById<LinearLayout>(R.id.llTargets)
        ll.removeAllViews()
        Engine.targets.forEach { t ->
            val cb = CheckBox(this)
            cb.isChecked = t.enabled
            cb.setTextColor(0xFFEAECEF.toInt())
            cb.text = "${t.label}   loaded today: ${t.today}"
            cb.setOnCheckedChangeListener { _, on -> t.enabled = on; Engine.save() }
            cb.setOnLongClickListener {
                if (t.number0 == "SELF") toast("Default target - untick to disable")
                else { Engine.removeTarget(t.number0); toast("Removed ${t.label}") }
                true
            }
            ll.addView(cb)
        }

        val q = findViewById<LinearLayout>(R.id.llQueue)
        q.removeAllViews()
        Engine.queue.forEachIndexed { i, p ->
            val tv = TextView(this)
            tv.text = "${i + 1}.  •••• ${p.pin.takeLast(4)}  ->  ${if (p.target.isEmpty()) "-" else p.target}   [${p.status}] ${p.note}"
            tv.setTextColor(0xFFEAECEF.toInt())
            tv.textSize = 12f
            tv.setPadding(16, 14, 16, 14)
            tv.setBackgroundResource(R.drawable.btn_bg)
            val lp = LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = 8
            tv.layoutParams = lp
            tv.setOnClickListener { Engine.removeAt(i) }
            q.addView(tv)
        }

        val rvw = findViewById<LinearLayout>(R.id.llReview)
        rvw.removeAllViews()
        findViewById<TextView>(R.id.tvReviewCount).text =
            if (Engine.review.isEmpty()) "no unclear pins" else "${Engine.review.size} unclear - keep or drop"
        Engine.review.forEachIndexed { ri, pin ->
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.setBackgroundResource(R.drawable.btn_bg)
            val lp = LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = 8
            row.layoutParams = lp
            row.setPadding(12, 10, 12, 10)
            val tv = TextView(this)
            tv.text = pin
            tv.setTextColor(0xFFF0B90B.toInt())
            tv.textSize = 11f
            tv.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            row.addView(tv)
            val keep = Button(this)
            keep.text = "KEEP"; keep.textSize = 10f
            keep.setBackgroundResource(R.drawable.btn_green)
            keep.setTextColor(0xFF0B0E11.toInt())
            keep.setOnClickListener { Engine.promoteReview(ri) }
            row.addView(keep)
            val drop = Button(this)
            drop.text = "DROP"; drop.textSize = 10f
            drop.setBackgroundResource(R.drawable.btn_red)
            drop.setTextColor(0xFFFFFFFF.toInt())
            drop.setOnClickListener { Engine.dropReview(ri) }
            row.addView(drop)
            rvw.addView(row)
        }

        val total = Engine.queue.size
        val done = Engine.queue.count { it.status == "SUCCESS" || it.status == "FAILED" }
        val pend = Engine.queue.count { it.status == "PENDING" }
        findViewById<ProgressBar>(R.id.pb).progress = if (total > 0) done * 100 / total else 0
        val act = maxOf(1, Engine.targets.count { it.enabled })
        val perPin = (listOf(5L, 4L, 3L, 2L)[Engine.speed - 1] / act) + 1L
        val eta = if (Engine.running && pend > 0) pend * perPin else 0
        findViewById<TextView>(R.id.tvProgress).text =
            "$done of $total processed" + if (eta > 0) "  ·  ~${eta}s left" else ""

        findViewById<TextView>(R.id.tvBalance).text =
            if (Engine.balance.isEmpty()) "Ksh 0.00" else "Ksh ${Engine.balance}"
        val rd = findViewById<TextView>(R.id.tvBReader)
        rd.text = if (Engine.service != null) "READER ON" else "READER OFF"
        rd.setTextColor(if (Engine.service != null) 0xFF0ECB81.toInt() else 0xFFF6465D.toInt())
        val (s, tot) = Engine.todayStats()
        val rate = if (tot > 0) s * 100 / tot else 100
        findViewById<TextView>(R.id.tvBMode).text = "${Engine.speed}/4 ${Engine.speedName()}"
        findViewById<TextView>(R.id.tvBToday).text = "TODAY $s · ${rate}%"
        findViewById<Button>(R.id.btnMode).text =
            "SPEED ${Engine.speed}/4 · ${Engine.speedName()}"
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}
