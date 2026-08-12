package com.chakamyth.hands

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * A tiny, near-invisible always-on-top overlay window. While it's shown the OS
 * treats Chaka's process as having a visible window, which stops aggressive OEM
 * freezers (Samsung OneUI) from suspending the JS thread while Chaka is
 * backgrounded and driving another app. Requires the "draw over other apps"
 * permission (SYSTEM_ALERT_WINDOW).
 */
object ChakaOverlay {
  private var view: View? = null

  fun canDraw(context: Context): Boolean = Settings.canDrawOverlays(context)

  fun show(context: Context) {
    if (!canDraw(context)) return
    Handler(Looper.getMainLooper()).post {
      if (view != null) return@post
      try {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val v = View(context).apply { setBackgroundColor(0x01FF6B1A.toInt()) }
        val type =
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
          else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(
          4, 4, type,
          WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
          PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        wm.addView(v, params)
        view = v
      } catch (e: Exception) {
        // overlay unavailable — non-fatal
      }
    }
  }

  fun hide(context: Context) {
    Handler(Looper.getMainLooper()).post {
      val v = view ?: return@post
      try {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm.removeView(v)
      } catch (e: Exception) {
      }
      view = null
    }
  }
}
