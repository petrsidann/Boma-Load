package com.bomaload.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        val logo = findViewById<ImageView>(R.id.ivBolt)
        val title = findViewById<TextView>(R.id.tvTitle)
        val tag = findViewById<TextView>(R.id.tvTag)
        logo.alpha = 0f; title.alpha = 0f; tag.alpha = 0f
        logo.animate().alpha(1f).setDuration(500).start()
        title.animate().alpha(1f).setDuration(500).setStartDelay(200).start()
        tag.animate().alpha(1f).setDuration(500).setStartDelay(350).start()
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }, 1400)
    }
}
