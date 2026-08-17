package com.chakamyth.hands

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * Chaka's "hands". A running instance can read the on-screen accessibility
 * tree and dispatch gestures (tap/swipe), type into the focused field, and
 * perform global navigation actions. The JS side reaches it via the static
 * [instance] once the user has enabled the service in system settings.
 */
class ChakaAccessibilityService : AccessibilityService() {

  companion object {
    @Volatile
    var instance: ChakaAccessibilityService? = null
  }

  override fun onServiceConnected() {
    super.onServiceConnected()
    instance = this
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

  override fun onInterrupt() {}

  override fun onDestroy() {
    super.onDestroy()
    if (instance === this) instance = null
  }

  /** Returns { w, h, els: [{ i, text?, desc?, cls, cx, cy, clickable?, editable? }] }. */
  fun dumpScreen(): String {
    val root = JSONObject()
    val metrics = resources.displayMetrics
    root.put("w", metrics.widthPixels)
    root.put("h", metrics.heightPixels)

    val els = JSONArray()
    val window = rootInActiveWindow
    root.put("pkg", window?.packageName?.toString() ?: "")
    if (window != null) {
      collect(window, els, intArrayOf(0), 0)
    }
    root.put("els", els)
    return root.toString()
  }

  /**
   * Android's own search over the live tree, which is what we should have been
   * using all along instead of matching against our own flattened copy.
   *
   * findAccessibilityNodeInfosByText searches text AND content-description
   * across the whole window, including nodes our collect() drops — it applies
   * an isVisibleToUser filter, a 400-node cap and a depth limit, any of which
   * can silently lose a row. "Wireless debugging" was never appearing in that
   * copy while sitting plainly on screen.
   *
   * Returns the row, where to tap it, and — the part that should never have
   * required eyes — whether its switch is on, read from isChecked.
   */
  fun findByText(query: String): String? {
    val root = rootInActiveWindow ?: return null
    val q = query.trim()
    if (q.isEmpty()) return null
    val hits = runCatching { root.findAccessibilityNodeInfosByText(q) }.getOrNull().orEmpty()
    if (hits.isEmpty()) return null
    val node = hits.firstOrNull { it.isVisibleToUser } ?: return null

    val label = node.text?.toString()?.takeIf { it.isNotBlank() }
      ?: node.contentDescription?.toString() ?: q

    // The row is the nearest clickable ancestor; the label itself usually is
    // not clickable, which is why tapping the words does nothing on some
    // screens and navigates away on others.
    var p: AccessibilityNodeInfo? = node
    var row: AccessibilityNodeInfo? = null
    var up = 0
    while (p != null && up < 8) {
      if (p.isClickable) { row = p; break }
      p = p.parent; up++
    }
    val toggle = findCheckable(row ?: node, 0)

    val nodeRect = Rect(); node.getBoundsInScreen(nodeRect)
    val o = JSONObject()
      .put("label", label)
      .put("visible", node.isVisibleToUser)
      .put("cx", nodeRect.centerX())
      .put("cy", nodeRect.centerY())
    row?.let {
      val r = Rect(); it.getBoundsInScreen(r)
      o.put("row_cx", r.centerX()).put("row_cy", r.centerY())
    }
    toggle?.let {
      val r = Rect(); it.getBoundsInScreen(r)
      o.put("has_switch", true)
        .put("switch_is", if (it.isChecked) "ON" else "OFF")
        .put("switch_cx", r.centerX())
        .put("switch_cy", r.centerY())
    }
    return o.toString()
  }

  /** First checkable node in this subtree — the switch belonging to a row. */
  private fun findCheckable(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
    if (node == null || depth > 6) return null
    if (node.isCheckable) return node
    for (i in 0 until node.childCount) {
      findCheckable(node.getChild(i), depth + 1)?.let { return it }
    }
    return null
  }

  /**
   * Scrolls using the list's OWN scroll action rather than a swipe gesture.
   * A gesture is a fling the system keeps decelerating, so the tree is read
   * mid-motion with rows recycled out of it; this moves exactly one page and
   * reports whether anything actually moved.
   */
  fun scrollList(forward: Boolean): Boolean {
    val root = rootInActiveWindow ?: return false
    val target = findScrollable(root, 0) ?: return false
    val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
    else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
    return runCatching { target.performAction(action) }.getOrDefault(false)
  }

  private fun findScrollable(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
    if (node == null || depth > 25) return null
    if (node.isScrollable) return node
    for (i in 0 until node.childCount) {
      findScrollable(node.getChild(i), depth + 1)?.let { return it }
    }
    return null
  }

  private fun collect(node: AccessibilityNodeInfo?, arr: JSONArray, counter: IntArray, depth: Int) {
    if (node == null || counter[0] > 400 || depth > 45) return

    val text = node.text?.toString()
    val desc = node.contentDescription?.toString()
    val clickable = node.isClickable
    val editable = node.isEditable
    val checkable = node.isCheckable

    if ((!text.isNullOrBlank() || !desc.isNullOrBlank() || clickable || editable || checkable) && node.isVisibleToUser) {
      val rect = Rect()
      node.getBoundsInScreen(rect)
      if (rect.width() > 0 && rect.height() > 0) {
        val o = JSONObject()
        o.put("i", counter[0]++)
        if (!text.isNullOrBlank()) o.put("text", text.take(140))
        if (!desc.isNullOrBlank()) o.put("desc", desc.take(140))
        o.put("cls", node.className?.toString()?.substringAfterLast('.') ?: "")
        o.put("cx", rect.centerX())
        o.put("cy", rect.centerY())
        // Full bounds so we can draw Set-of-Marks boxes on the screenshot.
        o.put("x1", rect.left)
        o.put("y1", rect.top)
        o.put("x2", rect.right)
        o.put("y2", rect.bottom)
        if (clickable) o.put("clickable", true)
        if (editable) o.put("editable", true)
        // Toggle/switch/checkbox state so the model knows if it's ALREADY on/off.
        if (checkable) {
          o.put("toggle", true)
          o.put("on", node.isChecked)
        }
        if (node.isSelected) o.put("selected", true)
        arr.put(o)
      }
    }

    for (i in 0 until node.childCount) {
      collect(node.getChild(i), arr, counter, depth + 1)
    }
  }

  fun tap(x: Int, y: Int): Boolean {
    val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
    val gesture = GestureDescription.Builder()
      .addStroke(GestureDescription.StrokeDescription(path, 0L, 60L))
      .build()
    return dispatchGesture(gesture, null, null)
  }

  fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, duration: Long): Boolean {
    val path = Path().apply {
      moveTo(x1.toFloat(), y1.toFloat())
      lineTo(x2.toFloat(), y2.toFloat())
    }
    val gesture = GestureDescription.Builder()
      .addStroke(GestureDescription.StrokeDescription(path, 0L, duration))
      .build()
    return dispatchGesture(gesture, null, null)
  }

  /**
   * Types [text] into the best available field. Finds the target as: the
   * input-focused node → an editable accessibility-focused node → any visible
   * editable node in the tree (WebViews frequently don't answer findFocus). Uses
   * ACTION_SET_TEXT for native fields, and falls back to clipboard + ACTION_PASTE
   * for Chrome/WebView fields that silently reject SET_TEXT. Returns true ONLY if
   * a field actually accepted the text, so the operator can report honestly.
   */
  fun typeText(text: String): Boolean {
    val root = rootInActiveWindow ?: return false
    val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
      ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)?.takeIf { it.isEditable }
      ?: findEditable(root)
      ?: return false

    runCatching { target.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }

    val setArgs = Bundle().apply {
      putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
    }
    if (target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs)) return true

    // Chrome/WebView inputs often reject SET_TEXT — put the text on the clipboard
    // and let the focused field paste it (paste is performed by the app itself).
    return runCatching {
      val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
      clip.setPrimaryClip(ClipData.newPlainText("chaka", text))
      target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
      target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }.getOrDefault(false)
  }

  /**
   * Press and hold, then drag while still held, then release. This is the only
   * way to move a home-screen icon or reorder a list: a plain swipe never picks
   * the item up, so the drag slides the page underneath it instead.
   *
   * The hold is deliberately just past the long-press threshold (~500ms). Held
   * much longer, a launcher shows its context menu instead of entering drag
   * mode, and the move never starts.
   */
  fun longPressDrag(x1: Int, y1: Int, x2: Int, y2: Int, holdMs: Long = 550L, moveMs: Long = 900L): Boolean {
    val hold = Path().apply { moveTo(x1.toFloat(), y1.toFloat()) }
    val first = GestureDescription.StrokeDescription(hold, 0L, holdMs, true)
    val move = Path().apply {
      moveTo(x1.toFloat(), y1.toFloat())
      lineTo(x2.toFloat(), y2.toFloat())
    }
    val second = first.continueStroke(move, 0L, moveMs, false)

    var ok = false
    val done = java.util.concurrent.CountDownLatch(1)
    dispatchGesture(
      GestureDescription.Builder().addStroke(first).build(),
      object : GestureResultCallback() {
        override fun onCompleted(d: GestureDescription?) {
          // The item is lifted; now move it in the same continuous gesture.
          ok = dispatchGesture(
            GestureDescription.Builder().addStroke(second).build(),
            object : GestureResultCallback() {
              override fun onCompleted(d2: GestureDescription?) { done.countDown() }
              override fun onCancelled(d2: GestureDescription?) { done.countDown() }
            },
            null
          )
          if (!ok) done.countDown()
        }
        override fun onCancelled(d: GestureDescription?) { done.countDown() }
      },
      null
    )
    runCatching { done.await(4, java.util.concurrent.TimeUnit.SECONDS) }
    return ok
  }

  /** Current clipboard text, so a copy can be verified before it's pasted. */
  fun readClipboard(): String? = runCatching {
    val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clip.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
  }.getOrNull()

  /** Depth-first search for the first visible editable node (used for WebView fields). */
  private fun findEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
    if (node == null) return null
    if (node.isEditable && node.isVisibleToUser) return node
    for (i in 0 until node.childCount) {
      findEditable(node.getChild(i))?.let { return it }
    }
    return null
  }

  fun pressEnter(): Boolean {
    val root = rootInActiveWindow ?: return false
    val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      return focused.performAction(
        AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
      )
    }
    return false
  }

  /**
   * Captures the screen as a downscaled base64 JPEG. API 30+.
   * Retries once after ~1.1s if it fails — Android rate-limits accessibility
   * screenshots to roughly one per second, so a fast second call can fail.
   */
  fun captureScreenshot(
    marks: JSONArray? = null,
    maxWidth: Int = 1240,
    quality: Int = 84,
    onResult: (String?) -> Unit
  ) {
    captureOnce(marks, maxWidth, quality) { b64 ->
      if (b64 != null) {
        onResult(b64)
      } else {
        Handler(Looper.getMainLooper()).postDelayed({ captureOnce(marks, maxWidth, quality, onResult) }, 1100)
      }
    }
  }

  private fun captureOnce(marks: JSONArray?, maxWidth: Int, quality: Int, onResult: (String?) -> Unit) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
      onResult(null)
      return
    }
    try {
      takeScreenshot(
        Display.DEFAULT_DISPLAY,
        applicationContext.mainExecutor,
        object : TakeScreenshotCallback {
          override fun onSuccess(screenshot: ScreenshotResult) {
            try {
              val buffer = screenshot.hardwareBuffer
              val hw = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
              buffer.close()
              if (hw == null) {
                Log.e("ChakaHands", "screenshot: wrapHardwareBuffer returned null")
                onResult(null)
                return
              }
              val soft = hw.copy(Bitmap.Config.ARGB_8888, false)
              hw.recycle()
              if (soft == null) {
                Log.e("ChakaHands", "screenshot: copy to software bitmap returned null")
                onResult(null)
                return
              }
              val scaled = downscale(soft, maxWidth)
              // Set-of-Marks: draw numbered boxes on tappable elements so the
              // model taps by index instead of guessing pixel coordinates.
              val marked = if (marks != null) drawMarks(scaled, marks, soft.width) else scaled
              val stream = ByteArrayOutputStream()
              marked.compress(Bitmap.CompressFormat.JPEG, quality, stream)
              val b64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
              Log.i("ChakaHands", "screenshot OK: ${b64.length} b64 chars marks=${marks?.length() ?: 0}")
              onResult(b64)
            } catch (e: Exception) {
              Log.e("ChakaHands", "screenshot processing threw", e)
              onResult(null)
            }
          }

          override fun onFailure(errorCode: Int) {
            Log.e("ChakaHands", "takeScreenshot onFailure errorCode=$errorCode")
            onResult(null)
          }
        }
      )
    } catch (e: Exception) {
      Log.e("ChakaHands", "takeScreenshot threw", e)
      onResult(null)
    }
  }

  private fun downscale(bmp: Bitmap, maxWidth: Int): Bitmap {
    if (bmp.width <= maxWidth) return bmp
    val ratio = maxWidth.toFloat() / bmp.width
    return Bitmap.createScaledBitmap(bmp, maxWidth, (bmp.height * ratio).toInt(), true)
  }

  /**
   * Set-of-Marks (OmniParser technique): overlays each tappable element's index
   * as a numbered box on the screenshot, so the vision model can reference a
   * target by number ("tap 14") instead of guessing pixel coordinates — the
   * single biggest grounding-accuracy win for GUI agents. Bounds in [marks] are
   * in original screen pixels; [originalWidth] is the pre-downscale width so we
   * can rescale them onto the (possibly downscaled) [bmp].
   */
  private fun drawMarks(bmp: Bitmap, marks: JSONArray, originalWidth: Int): Bitmap {
    val out = bmp.copy(Bitmap.Config.ARGB_8888, true) ?: return bmp
    val scale = out.width.toFloat() / originalWidth.coerceAtLeast(1)
    val canvas = Canvas(out)
    val box = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 2.5f; color = Color.rgb(255, 40, 40); isAntiAlias = true }
    val labelBg = Paint().apply { style = Paint.Style.FILL; color = Color.rgb(255, 40, 40) }
    val labelTxt = Paint().apply { color = Color.WHITE; textSize = 22f; isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD }
    for (k in 0 until marks.length()) {
      val e = marks.optJSONObject(k) ?: continue
      // Only mark actionable targets (tap/type/toggle) — keeps the overlay clean.
      val actionable = e.optBoolean("clickable") || e.optBoolean("editable") || e.optBoolean("toggle")
      if (!actionable || !e.has("x1")) continue
      val l = e.optInt("x1") * scale
      val t = e.optInt("y1") * scale
      val r = e.optInt("x2") * scale
      val b = e.optInt("y2") * scale
      canvas.drawRect(l, t, r, b, box)
      val tag = e.optInt("i").toString()
      val tw = labelTxt.measureText(tag)
      // Label sits just inside the top-left corner, on a solid chip for legibility.
      canvas.drawRect(l, t, l + tw + 10f, t + 28f, labelBg)
      canvas.drawText(tag, l + 5f, t + 22f, labelTxt)
    }
    return out
  }

  /**
   * Answers an incoming call. Uses TelecomManager where permitted, and falls
   * back to the accessibility "answer" affordance on the call screen — the
   * in-call UI often isn't reachable as a normal tappable element.
   */
  fun answerCall(): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val tm = getSystemService(Context.TELECOM_SERVICE) as? android.telecom.TelecomManager
      val ok = runCatching { tm?.acceptRingingCall(); true }.getOrDefault(false)
      if (ok) return true
    }
    return tapNodeMatching(listOf("answer", "accept", "pick up"))
  }

  /** Ends/rejects the current call. */
  fun endCall(): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      val tm = getSystemService(Context.TELECOM_SERVICE) as? android.telecom.TelecomManager
      val ok = runCatching { tm?.endCall() ?: false }.getOrDefault(false)
      if (ok) return true
    }
    return tapNodeMatching(listOf("end call", "decline", "reject", "hang up"))
  }

  /** Clicks the first visible node whose label contains one of [words]. */
  private fun tapNodeMatching(words: List<String>): Boolean {
    val root = rootInActiveWindow ?: return false
    fun walk(n: AccessibilityNodeInfo?): Boolean {
      if (n == null) return false
      val label = ((n.text?.toString() ?: "") + " " + (n.contentDescription?.toString() ?: "")).lowercase()
      if (n.isVisibleToUser && words.any { label.contains(it) }) {
        if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val r = Rect(); n.getBoundsInScreen(r)
        if (r.width() > 0 && r.height() > 0) return tap(r.centerX(), r.centerY())
      }
      for (i in 0 until n.childCount) if (walk(n.getChild(i))) return true
      return false
    }
    return walk(root)
  }

  fun globalAction(name: String): Boolean {
    val action = when (name) {
      "back" -> GLOBAL_ACTION_BACK
      "home" -> GLOBAL_ACTION_HOME
      "recents" -> GLOBAL_ACTION_RECENTS
      "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
      "quick_settings" ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) GLOBAL_ACTION_QUICK_SETTINGS else return false
      "lock" ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) GLOBAL_ACTION_LOCK_SCREEN else return false
      else -> return false
    }
    return performGlobalAction(action)
  }
}
