package com.gfx.boost

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager

class BoostService : Service() {

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var overlay: SurfaceView? = null
    private var gl: GlRenderer? = null
    private var wm: WindowManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        startFg()

        val code = intent.getIntExtra("code", 0)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>("data")
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = mpm.getMediaProjection(code, data)
        projection = proj
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, Handler(Looper.getMainLooper()))

        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        wm = manager
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        manager.defaultDisplay.getRealMetrics(dm)
        val w = dm.widthPixels
        val h = dm.heightPixels
        val dpi = dm.densityDpi

        val sv = SurfaceView(this)
        sv.setZOrderOnTop(true)
        sv.holder.setFormat(PixelFormat.TRANSLUCENT)
        sv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                val r = GlRenderer(holder.surface, w, h) { input ->
                    display = proj.createVirtualDisplay(
                        "gfxboost", w, h, dpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        input, null, null
                    )
                }
                gl = r
                r.start()
            }

            override fun surfaceChanged(holder: SurfaceHolder, f: Int, ww: Int, hh: Int) {}

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                gl?.running = false
            }
        })
        overlay = sv

        val lp = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        manager.addView(sv, lp)

        return START_NOT_STICKY
    }

    private fun startFg() {
        val channel = "gfxboost"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(channel, "GfxBoost", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, channel)
            .setContentTitle("GfxBoost ON")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    override fun onDestroy() {
        gl?.running = false
        display?.release()
        overlay?.let { wm?.removeView(it) }
        projection?.stop()
        super.onDestroy()
    }
}
