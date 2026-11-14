package com.gfx.boost

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

object Cfg {
    @Volatile var sharp = 0.35f
    @Volatile var contrast = 1.08f
    @Volatile var alpha = 0.4f
}

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(48, 120, 48, 48)

        val title = TextView(this)
        title.text = "GfxBoost"
        title.textSize = 28f

        val label = TextView(this)
        label.text = "Sharpness"
        label.setPadding(0, 48, 0, 0)

        val bar = SeekBar(this)
        bar.max = 100
        bar.progress = 50
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                Cfg.sharp = p / 100f * 0.7f
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        val start = Button(this)
        start.text = "START"
        start.setOnClickListener { begin() }

        val stop = Button(this)
        stop.text = "STOP"
        stop.setOnClickListener {
            stopService(Intent(this, BoostService::class.java))
        }

        root.addView(title)
        root.addView(label)
        root.addView(bar)
        root.addView(start)
        root.addView(stop)
        setContentView(root)
    }

    private fun begin() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            Toast.makeText(this, "Permission ON karke wapas aao, phir START dabao", Toast.LENGTH_LONG).show()
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), 100)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, BoostService::class.java)
            i.putExtra("code", resultCode)
            i.putExtra("data", data)
            startForegroundService(i)
            moveTaskToBack(true)
        }
    }
}
