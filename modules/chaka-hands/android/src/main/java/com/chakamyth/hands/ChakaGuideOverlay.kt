package com.chakamyth.hands

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A visible floating "coach" bubble pinned near the top of the screen. Guide Mode
 * updates it with the next step for the USER to take (Chaka watches, you tap).
 * Tapping the bubble stops Guide Mode.
 */
object ChakaGuideOverlay {
  private var row: View? = null
  private var textView: TextView? = null
  private val main = Handler(Looper.getMainLooper())

  fun show(context: Context, initial: String, onTap: () -> Unit) {
    if (!Settings.canDrawOverlays(context)) return
    main.post {
      if (row != null) {
        update(initial)
        return@post
      }
      try {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val d = context.resources.displayMetrics.density
        val pad = (16 * d).toInt()

        val tv = TextView(context).apply {
          text = initial
          setTextColor(Color.WHITE)
          textSize = 15.5f
          setTypeface(Typeface.DEFAULT_BOLD)
        }
        val bubble = LinearLayout(context).apply {
          orientation = LinearLayout.HORIZONTAL
          setPadding(pad, pad, pad, pad)
          background = GradientDrawable().apply {
            cornerRadius = 26f * d
            setColor(0xF21A0E06.toInt())
            setStroke((2 * d).toInt(), 0xFFFF6B1A.toInt())
          }
          addView(tv)
          setOnClickListener { onTap() }
        }

        val type =
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
          else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
          WindowManager.LayoutParams.MATCH_PARENT,
          WindowManager.LayoutParams.WRAP_CONTENT,
          type,
          WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
          PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP
        params.y = (54 * d).toInt()
        params.horizontalMargin = 0.03f

        wm.addView(bubble, params)
        row = bubble
        textView = tv
      } catch (e: Exception) {
        // overlay unavailable — non-fatal
      }
    }
  }

  fun update(text: String) {
    main.post { textView?.text = text }
  }

  fun hide(context: Context) {
    main.post {
      val v = row ?: return@post
      try {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm.removeView(v)
      } catch (e: Exception) {
      }
      row = null
      textView = null
    }
  }
}
