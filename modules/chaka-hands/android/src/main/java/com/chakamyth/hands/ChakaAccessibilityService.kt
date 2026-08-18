package com.chakamyth.hands

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import android.provider.Settings
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
    private const val SETTINGS_PACKAGE = "com.android.settings"
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

  /**
   * One accessibility tree per interactive window. rootInActiveWindow is only
   * the window receiving touch/focus, which is not necessarily the window that
   * Samsung Settings draws a visible preference into. We opt in to interactive
   * windows in chaka_accessibility_config.xml, so always use that capability
   * instead of silently treating the active root as the whole screen.
   */
  private data class WindowRoot(
    val id: Int,
    val type: Int,
    val layer: Int,
    val root: AccessibilityNodeInfo
  )

  private fun interactiveRoots(): List<WindowRoot> {
    val roots = LinkedHashMap<Int, WindowRoot>()
    fun add(id: Int, type: Int, layer: Int, node: AccessibilityNodeInfo) {
      // The active root and its corresponding entry in getWindows() are the
      // same tree. Key by Android's window id so it is collected only once.
      val key = node.windowId.takeIf { it >= 0 } ?: id
      roots.putIfAbsent(key, WindowRoot(id, type, layer, node))
    }

    rootInActiveWindow?.let { add(it.windowId, -1, Int.MAX_VALUE, it) }
    windows.forEach { window ->
      window.root?.let { add(window.id, window.type, window.layer, it) }
    }
    return roots.values.toList()
  }

  /** Returns { w, h, els: [{ i, text?, desc?, cls, cx, cy, clickable?, editable? }] }. */
  fun dumpScreen(): String {
    val root = JSONObject()
    val metrics = resources.displayMetrics
    root.put("w", metrics.widthPixels)
    root.put("h", metrics.heightPixels)

    val els = JSONArray()
    val roots = interactiveRoots()
    root.put("pkg", rootInActiveWindow?.packageName?.toString()
      ?: roots.firstOrNull()?.root?.packageName?.toString().orEmpty())
    val windowInfo = JSONArray()
    val counter = intArrayOf(0)
    for (window in roots) {
      val before = counter[0]
      collect(window.root, els, counter, 0, window.id, metrics.widthPixels, metrics.heightPixels)
      windowInfo.put(
        JSONObject()
          .put("id", window.id)
          .put("type", window.type)
          .put("layer", window.layer)
          .put("pkg", window.root.packageName?.toString().orEmpty())
          .put("elements", counter[0] - before)
      )
    }
    root.put("windows", windowInfo)
    root.put("els", els)
    return root.toString()
  }

  /**
   * Searches the live accessibility trees directly, instead of trusting the
   * flattened model payload. A row may be drawn in a non-active window, or the
   * flattened payload may omit it, while the live tree still has its exact
   * label and control state.
   */
  fun findByText(query: String): String? = findByText(query, mayShowOnScreen = true)

  private data class TextMatch(
    val node: AccessibilityNodeInfo,
    val windowId: Int,
    val label: String,
    val row: AccessibilityNodeInfo?,
    val toggle: AccessibilityNodeInfo?,
    val score: Int
  )

  /**
   * Search every interactive window and rank the results. Settings commonly
   * exposes the same text twice: once in the toolbar and once in a preference
   * row. The row that owns a checkable control is the only useful match when
   * the user asks for a setting's state.
   */
  private fun findByText(query: String, mayShowOnScreen: Boolean): String? {
    val match = resolveTextMatch(query, mayShowOnScreen) ?: return null
    // On Samsung's Settings detail pages, the title is in the toolbar and the
    // actual switch is in a separate switch-bar immediately below it. It is
    // still the state of this exact setting, but it is not a child of the title.
    val toggle = match.toggle ?: detailPageToggle(match)
    val nodeRect = Rect(); match.node.getBoundsInScreen(nodeRect)
    val o = JSONObject()
      .put("label", match.label)
      .put("window_id", match.windowId)
      .put("visible", match.node.isVisibleToUser)
      .put("cx", nodeRect.centerX())
      .put("cy", nodeRect.centerY())
      .put("kind", if (toggle != null) "switch" else "row")
    match.row?.let {
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

  /** Resolves a label once and, if necessary, asks Android to reveal that node. */
  private fun resolveTextMatch(query: String, mayShowOnScreen: Boolean): TextMatch? {
    val q = query.trim()
    if (q.isEmpty()) return null

    val match = findTextMatch(q) ?: run {
      Log.i("ChakaHands", "findByText '$q' missing across ${interactiveRoots().size} interactive window(s)")
      return null
    }

    // Samsung can mark a plainly painted preference false for
    // isVisibleToUser. Geometry is the useful test for whether it is actually
    // on the display; otherwise ask Android to reveal the match exactly once.
    if (mayShowOnScreen && !isOnScreen(match.node)) {
      runCatching {
        match.node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
      }
      Thread.sleep(400)
      return resolveTextMatch(q, mayShowOnScreen = false)
    }
    return match
  }

  private fun findTextMatch(query: String, requiredPackage: String? = null): TextMatch? {
    val normalizedQuery = normalizeText(query)
    if (normalizedQuery.isBlank()) return null

    val queryWords = normalizedQuery.split(" ").filter { it.isNotBlank() }
    val matches = ArrayList<TextMatch>()

    fun consider(node: AccessibilityNodeInfo, windowId: Int) {
      val label = nodeLabel(node)
      val normalizedLabel = normalizeText(label)
      if (normalizedLabel.isBlank()) return
      val matched = normalizedLabel.contains(normalizedQuery) ||
        queryWords.isNotEmpty() && queryWords.all { normalizedLabel.contains(it) }
      if (!matched) return

      val row = clickableAncestor(node)
      val toggle = findCheckable(row ?: node, 0)
      var score = when {
        normalizedLabel == normalizedQuery -> 10_000
        normalizedLabel.contains(normalizedQuery) -> 8_000
        else -> 6_000
      }
      // A Settings title and its preference row can have identical text. The
      // row is deterministically better because it is structurally paired with
      // the switch that supplies the requested state.
      // A containment match that is far longer than what was asked for is
      // probably a DIFFERENT setting that happens to quote it. "USB debugging"
      // matched "Revoke USB debugging authorisations" and reported that row's
      // switch state as the answer — a wrong fact, stated confidently, about a
      // control the user never mentioned. Penalise the surplus words.
      if (normalizedLabel != normalizedQuery) {
        score -= (normalizedLabel.length - normalizedQuery.length).coerceAtLeast(0) * 60
      }
      if (toggle != null) score += 2_000
      if (row != null) score += 100
      if (node.isVisibleToUser) score += 20
      matches.add(TextMatch(node, windowId, label, row, toggle, score))
    }

    fun walk(node: AccessibilityNodeInfo?, windowId: Int, depth: Int) {
      if (node == null || depth > 90) return
      consider(node, windowId)
      for (i in 0 until node.childCount) walk(node.getChild(i), windowId, depth + 1)
    }

    for (window in interactiveRoots()) {
      if (requiredPackage != null && window.root.packageName?.toString() != requiredPackage) continue
      walk(window.root, window.id, 0)
    }
    return matches.maxWithOrNull(compareBy<TextMatch> { it.score }.thenBy { it.windowId })
  }

  /**
   * A deterministic system-settings flow for the task that was repeatedly
   * failing: no vision decisions, no model-driven swipes, and no reversal.
   *
   * Samsung does not resolve the undocumented wireless-debugging Intent, so
   * enter through Android's documented Developer Options action, then scan the
   * Settings RecyclerView in one direction until the exact text selector wins.
   */
  fun checkWirelessDebuggingState(): String {
    val target = "Wireless debugging"
    val opened = runCatching {
      startActivity(
        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      )
      true
    }.getOrDefault(false)
    if (!opened) return JSONObject().put("ok", false)
      .put("error", "Android could not open Developer options.").toString()

    // Starting an activity is asynchronous. Do not scroll until the actual
    // Settings list is present, otherwise an old window could receive a swipe.
    for (attempt in 0 until 12) {
      if (interactiveRoots().any { it.root.packageName?.toString() == SETTINGS_PACKAGE }) break
      Thread.sleep(150)
    }

    var steps = 0
    var row: TextMatch? = null
    // Where OCR saw the row, when the accessibility tree could not. Verified on
    // this exact device: at every one of 14 scroll positions findByText
    // returned null for "Wireless debugging" while the row was drawn on screen.
    // The tree is a copy the system curates; the pixels are the screen itself.
    var ocrRow: JSONObject? = null
    while (steps <= 24) {
      val candidate = findTextMatch(target, SETTINGS_PACKAGE)
      if (candidate != null && isOnScreen(candidate.node)) {
        row = candidate
        break
      }

      // Eyes, before deciding the row is not here. This is the difference
      // between "Android says it is absent" and "it is absent".
      runCatching { findByPixels(target) }.getOrNull()?.let { raw ->
        val o = runCatching { JSONObject(raw) }.getOrNull()
        if (o != null && o.optBoolean("found")) {
          Log.i("ChakaHands", "native check: found '$target' BY OCR at step $steps -> $raw")
          ocrRow = o
        }
      }
      if (ocrRow != null) break

      val list = settingsRecycler() ?: return JSONObject().put("ok", false)
        .put("error", "Developer options opened, but Android did not expose its Settings list.")
        .put("steps", steps).toString()
      val before = settingsListSignature(list)
      if (!runCatching { list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }.getOrDefault(false)) {
        return JSONObject().put("ok", false).put("not_found", target)
          .put("error", "Reached the end of Developer options without finding Wireless debugging.")
          .put("steps", steps).toString()
      }
      // Long enough that the next OCR attempt can actually get a frame. The
      // scan is bounded at 24 steps, so this costs seconds, and seconds spent
      // looking beat a scroll to the bottom that saw nothing.
      Thread.sleep(1150)
      if (settingsListSignature(list) == before) {
        return JSONObject().put("ok", false).put("not_found", target)
          .put("error", "The Developer options list stopped moving before Wireless debugging was found.")
          .put("steps", steps).toString()
      }
      steps++
    }

    // Tree first — it gives a node we can activate properly. Otherwise open the
    // row at the pixel coordinates OCR read, which lands on the row's own page
    // where the switch IS exposed and can be read as a fact rather than judged.
    val exactRow = row
    if (exactRow != null) {
      if (!activateMatch(exactRow)) return JSONObject().put("ok", false)
        .put("error", "Android found Wireless debugging but would not open that exact row.")
        .put("steps", steps).toString()
    } else {
      val hit = ocrRow ?: return JSONObject().put("ok", false).put("not_found", target)
        .put("error", "Wireless debugging was not found within the bounded Developer options search, " +
          "by the element tree or by reading the screen.")
        .put("steps", steps).toString()
      if (!tap(hit.optInt("cx"), hit.optInt("cy"))) return JSONObject().put("ok", false)
        .put("error", "Read Wireless debugging off the screen but could not tap it.")
        .put("steps", steps).toString()
    }

    Thread.sleep(650)
    val detail = findTextMatch(target, SETTINGS_PACKAGE)
    // On the row's own page the switch is a normal control, so the tree
    // usually has it even when it hid the list row. If the title is still not
    // readable, look for the page's switch directly rather than giving up.
    val toggle = detail?.toggle ?: detail?.let { detailPageToggle(it) } ?: anyPageToggle()
      ?: return JSONObject().put("ok", false).put("label", target)
        .put("error", "Wireless debugging is open, but Android did not expose a readable switch.").toString()
    return JSONObject().put("ok", true).put("label", target)
      .put("switch_is", if (toggle.isChecked) "ON" else "OFF")
      .put("steps", steps).put("native_controller", true).toString()
  }

  /** Finds the actual Settings RecyclerView, never an arbitrary scrollable overlay. */
  private fun settingsRecycler(): AccessibilityNodeInfo? {
    var fallback: AccessibilityNodeInfo? = null
    fun walk(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
      if (node == null || depth > 50) return null
      if (node.isScrollable) {
        val id = node.viewIdResourceName?.lowercase().orEmpty()
        if (id.endsWith("/recycler_view") || id.contains("recycler")) return node
        if (fallback == null) fallback = node
      }
      for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)?.let { return it }
      return null
    }
    for (window in interactiveRoots()) {
      if (window.root.packageName?.toString() != SETTINGS_PACKAGE) continue
      walk(window.root, 0)?.let { return it }
    }
    return fallback
  }

  /** Stable visible-content signature for detecting a list that has stopped. */
  private fun settingsListSignature(list: AccessibilityNodeInfo): String {
    val labels = ArrayList<String>()
    fun walk(node: AccessibilityNodeInfo?, depth: Int) {
      if (node == null || depth > 20) return
      if (isOnScreen(node)) nodeLabel(node).takeIf { it.isNotBlank() }?.let { labels.add(it) }
      for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
    }
    walk(list, 0)
    return labels.sorted().joinToString("|")
  }

  /** Activates exactly the selected row, falling back only to its own bounds. */
  private fun activateMatch(match: TextMatch): Boolean {
    val target = match.row?.takeIf { it.isClickable } ?: match.node.takeIf { it.isClickable }
    if (target?.let { runCatching { it.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false) } == true) {
      return true
    }
    val rect = Rect(); match.node.getBoundsInScreen(rect)
    return isOnScreen(match.node) && tap(rect.centerX(), rect.centerY())
  }

  /**
   * Pairs a Settings detail-page title with its own switch-bar. This is
   * intentionally conservative: a switch-bar resource id is required, so a
   * random switch elsewhere in a list can never be claimed as this setting.
   */
  /**
   * The switch on a setting's own page, found without needing its title.
   *
   * A detail page has exactly one primary switch in its switch-bar, so when the
   * title cannot be read back — which happens on precisely the rows the tree
   * hides — the page's own checkable control is still the right answer, and it
   * is a fact rather than a judgement.
   */
  private fun anyPageToggle(): AccessibilityNodeInfo? {
    fun walk(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
      if (node == null || depth > 25) return null
      if (node.isCheckable && isOnScreen(node)) return node
      for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)?.let { return it }
      return null
    }
    for (window in interactiveRoots()) {
      if (window.root.packageName?.toString() != SETTINGS_PACKAGE) continue
      walk(window.root, 0)?.let { return it }
    }
    return null
  }

  private fun detailPageToggle(match: TextMatch): AccessibilityNodeInfo? {
    val title = Rect(); match.node.getBoundsInScreen(title)
    val screenHeight = resources.displayMetrics.heightPixels
    if (title.top > screenHeight / 2) return null
    val root = interactiveRoots().firstOrNull { it.id == match.windowId }?.root ?: return null
    var best: AccessibilityNodeInfo? = null
    var bestScore = Int.MIN_VALUE

    fun switchBarScore(node: AccessibilityNodeInfo): Int {
      var current: AccessibilityNodeInfo? = node
      var score = 0
      repeat(6) {
        val here = current ?: return@repeat
        val id = here.viewIdResourceName?.lowercase().orEmpty()
        if (id.contains("switchbar")) score += 20_000
        else if (id.contains("switch")) score += 500
        current = here.parent
      }
      return score
    }

    fun walk(node: AccessibilityNodeInfo?, depth: Int) {
      if (node == null || depth > 90) return
      if (node.isCheckable && isOnScreen(node)) {
        val r = Rect(); node.getBoundsInScreen(r)
        val score = switchBarScore(node) +
          if (r.top >= title.bottom - 8) 100 else 0
        if (score > bestScore) {
          bestScore = score
          best = node
        }
      }
      for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
    }
    walk(root, 0)
    return best?.takeIf { bestScore >= 20_000 }
  }

  /** Opens the exact visible row found by label, without model-supplied coordinates. */
  fun openByText(query: String): String? {
    val match = resolveTextMatch(query, mayShowOnScreen = true) ?: return null
    if (match.toggle != null || detailPageToggle(match) != null) {
      return JSONObject().put("ok", false).put("label", match.label)
        .put("error", "This target already has a switch; read or set its state instead of opening it.").toString()
    }
    val target = match.row?.takeIf { it.isClickable } ?: match.node.takeIf { it.isClickable }
    val clickedByAction = target?.let {
      runCatching { it.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
    } ?: false
    if (!clickedByAction) {
      // A few Samsung preference labels expose no clickable ancestor despite
      // accepting a touch. This is still an exact point inside the matched row,
      // not a coordinate guessed by the language model.
      val rect = Rect(); match.node.getBoundsInScreen(rect)
      if (!tap(rect.centerX(), rect.centerY())) return null
    }
    return JSONObject().put("ok", true).put("label", match.label)
      .put("opened_exact_target", true).toString()
  }

  /** Sets the exact switch paired with [query] and reads it back natively. */
  fun setSwitchByText(query: String, enabled: Boolean): String? {
    val match = resolveTextMatch(query, mayShowOnScreen = true) ?: return null
    val toggle = match.toggle ?: detailPageToggle(match) ?: return JSONObject()
      .put("ok", false).put("label", match.label)
      .put("error", "The exact target has no readable switch on this screen.").toString()
    val wasEnabled = toggle.isChecked
    var acted = false
    if (wasEnabled != enabled) {
      val target = toggle.takeIf { it.isClickable } ?: clickableAncestor(toggle)
      acted = target?.let {
        runCatching { it.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
      } ?: false
      if (!acted) {
        val rect = Rect(); toggle.getBoundsInScreen(rect)
        acted = tap(rect.centerX(), rect.centerY())
      }
      if (!acted) return null
      Thread.sleep(350)
    }
    val reread = resolveTextMatch(query, mayShowOnScreen = false)
    val verifiedToggle = reread?.toggle ?: reread?.let { detailPageToggle(it) }
    val verified = verifiedToggle?.isChecked
    val result = JSONObject().put("ok", verified == enabled).put("label", match.label)
      .put("changed", wasEnabled != enabled).put("verified", verified != null)
    if (verified == null) result.put("error", "Android did not provide a fresh switch read-back.")
    else result.put("switch_is", if (verified) "ON" else "OFF")
    return result.toString()
  }

  private fun normalizeText(value: String): String =
    value.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

  private fun nodeLabel(node: AccessibilityNodeInfo): String = listOfNotNull(
    node.text?.toString()?.takeIf { it.isNotBlank() },
    node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
  ).joinToString(" ")

  /** The row is the nearest clickable ancestor; labels are often not clickable. */
  private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
    var current: AccessibilityNodeInfo? = node
    repeat(10) {
      val here = current ?: return null
      if (here.isClickable) return here
      current = here.parent
    }
    return null
  }

  private fun isOnScreen(node: AccessibilityNodeInfo): Boolean {
    val r = Rect(); node.getBoundsInScreen(r)
    val m = resources.displayMetrics
    return r.width() > 0 && r.height() > 0 && r.right > 0 && r.bottom > 0 &&
      r.left < m.widthPixels && r.top < m.heightPixels
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
    val target = interactiveRoots().firstNotNullOfOrNull { findScrollable(it.root, 0) } ?: return false
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

  private fun collect(
    node: AccessibilityNodeInfo?,
    arr: JSONArray,
    counter: IntArray,
    depth: Int,
    windowId: Int,
    screenWidth: Int,
    screenHeight: Int
  ) {
    // Do not cap a visible tree: a cap is indistinguishable from a missing
    // control to the model. The depth bound still protects us from a malformed
    // hierarchy, and geometry keeps off-screen recycled rows out of the output.
    if (node == null || depth > 90) return

    var text = node.text?.toString()
    val desc = node.contentDescription?.toString()
    val clickable = node.isClickable
    val editable = node.isEditable
    val checkable = node.isCheckable

    // Switch nodes in Settings commonly have no label of their own (or only
    // say "On"). Give the switch its owning row's label so tap_index is both
    // precise and state-aware instead of offering a blank, anonymous control.
    val associatedLabel = if (checkable) controlLabel(node) else null
    if (checkable && associatedLabel != null &&
      (text.isNullOrBlank() || normalizeText(text ?: "") in setOf("on", "off"))) {
      text = associatedLabel
    }

    val rect = Rect()
    node.getBoundsInScreen(rect)
    val intersectsScreen = rect.width() > 0 && rect.height() > 0 && rect.right > 0 && rect.bottom > 0 &&
      rect.left < screenWidth && rect.top < screenHeight
    if ((!text.isNullOrBlank() || !desc.isNullOrBlank() || clickable || editable || checkable) &&
      (node.isVisibleToUser || intersectsScreen)) {
      if (intersectsScreen) {
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
        o.put("window_id", windowId)
        if (clickable) o.put("clickable", true)
        if (editable) o.put("editable", true)
        // Toggle/switch/checkbox state so the model knows if it's ALREADY on/off.
        if (checkable) {
          o.put("toggle", true)
          o.put("on", node.isChecked)
          associatedLabel?.let { o.put("control_for", it.take(140)) }
        }
        if (node.isSelected) o.put("selected", true)
        arr.put(o)
      }
    }

    for (i in 0 until node.childCount) {
      collect(node.getChild(i), arr, counter, depth + 1, windowId, screenWidth, screenHeight)
    }
  }

  /** Finds the meaningful setting label nearest to an unlabelled switch. */
  private fun controlLabel(node: AccessibilityNodeInfo): String? {
    var current: AccessibilityNodeInfo? = node.parent
    repeat(8) {
      val here = current ?: return null
      val label = nodeLabel(here)
      if (label.isNotBlank() && normalizeText(label) !in setOf("on", "off")) return label
      current = here.parent
    }
    return null
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

  /**
   * The screen as PIXELS, at full resolution, for OCR.
   *
   * Deliberately separate from captureScreenshot, which downscales and JPEGs
   * for the model's eyes. OCR wants every pixel it can get — the docs ask for
   * ~16px per character — and it wants coordinates in real screen space so a
   * match can be tapped directly without rescaling.
   *
   * Blocking, because every caller is already off the main thread.
   */
  fun captureBitmapBlocking(timeoutMs: Long = 4000): Bitmap? {
    // Android allows roughly one takeScreenshot per second, and the live frame
    // streamer is already spending that budget on her eyes. Whoever loses the
    // race gets null, which read as "OCR found nothing" when OCR was never
    // handed a frame at all. So wait our turn rather than give up: three tries,
    // spaced past the limit, is the difference between a search that works and
    // one that silently sees nothing.
    for (attempt in 0 until 3) {
      captureBitmapOnce(timeoutMs)?.let { return it }
      if (attempt < 2) Thread.sleep(700)
    }
    Log.w("ChakaHands", "captureBitmapBlocking: no frame after 3 tries (screenshot rate limit)")
    return null
  }

  private fun captureBitmapOnce(timeoutMs: Long): Bitmap? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
    var out: Bitmap? = null
    val lock = Object()
    var done = false
    try {
      takeScreenshot(
        Display.DEFAULT_DISPLAY,
        applicationContext.mainExecutor,
        object : TakeScreenshotCallback {
          override fun onSuccess(screenshot: ScreenshotResult) {
            runCatching {
              val buffer = screenshot.hardwareBuffer
              val hw = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
              buffer.close()
              out = hw?.copy(Bitmap.Config.ARGB_8888, false)
              hw?.recycle()
            }.onFailure { Log.e("ChakaHands", "bitmap capture: ${it.message}") }
            synchronized(lock) { done = true; lock.notifyAll() }
          }

          override fun onFailure(errorCode: Int) {
            Log.e("ChakaHands", "bitmap capture failed code=$errorCode")
            synchronized(lock) { done = true; lock.notifyAll() }
          }
        }
      )
    } catch (e: Exception) {
      Log.e("ChakaHands", "bitmap capture threw: ${e.message}")
      return null
    }
    synchronized(lock) {
      val deadline = System.currentTimeMillis() + timeoutMs
      while (!done && System.currentTimeMillis() < deadline) runCatching { lock.wait(200) }
    }
    return out
  }

  /**
   * Find text by READING THE SCREEN, not by asking the tree.
   *
   * The tree can be missing a row that is plainly drawn — that is the bug that
   * cost two days. Pixels cannot lie about what is on screen, so this is the
   * fallback that has no blind spot, and it hands back a real box to tap rather
   * than an estimated fraction.
   */
  fun findByPixels(query: String): String? = findByPixels(query, null)

  /**
   * [requirePackage] is what stops OCR reading the wrong screen entirely.
   *
   * Pixels cannot lie about what is drawn, but they say nothing about WHERE.
   * A "Wireless debugging connected" notification in the shade reads exactly
   * like the Developer options row, and a hit on it is not a hit on the
   * setting — it is a different object with the same words. Searching within
   * Settings must not be satisfied by the notification panel.
   */
  fun findByPixels(query: String, requirePackage: String?): String? {
    if (requirePackage != null) {
      val here = rootInActiveWindow?.packageName?.toString()
      if (here != null && here != requirePackage) {
        Log.i("ChakaHands", "OCR skipped: on '$here', expected '$requirePackage'")
        return JSONObject().put("found", false).put("wrong_screen", here).toString()
      }
    }
    val bmp = captureBitmapBlocking()
    if (bmp == null) {
      // Android rate-limits takeScreenshot to about one per second. A loop that
      // scrolls faster than that gets null every time and OCR never runs — which
      // is exactly how this looked like "OCR found nothing" when in truth OCR
      // was never handed a single frame. Say so out loud.
      Log.w("ChakaHands", "findByPixels('$query'): no bitmap (screenshot rate limit?) — OCR did not run")
      return null
    }
    val lines = ChakaOcr.readLines(bmp)
    val all = ChakaOcr.findAll(lines, query)
    val hit = ChakaOcr.find(lines, query)
    Log.i("ChakaHands", "OCR read ${lines.size} lines looking for '$query' -> ${if (hit != null) "HIT" else "no match"}")
    val metrics = resources.displayMetrics
    // The screenshot is full-resolution, so its pixels are screen pixels; guard
    // anyway in case a device hands back something scaled.
    val sx = metrics.widthPixels.toFloat() / bmp.width.coerceAtLeast(1)
    val sy = metrics.heightPixels.toFloat() / bmp.height.coerceAtLeast(1)
    bmp.recycle()
    if (hit == null) {
      return JSONObject()
        .put("found", false)
        .put("lines_read", lines.size)
        .put("saw", ChakaOcr.asJson(lines))
        .toString()
    }
    return JSONObject()
      .put("found", true)
      .put("text", hit.text)
      .apply {
        if (all.size > 1) {
          put("ambiguous", true)
          put("also_matched", org.json.JSONArray(all.map { it.text }))
        }
      }
      .put("cx", (hit.box.centerX() * sx).toInt())
      .put("cy", (hit.box.centerY() * sy).toInt())
      .put("x1", (hit.box.left * sx).toInt())
      .put("y1", (hit.box.top * sy).toInt())
      .put("x2", (hit.box.right * sx).toInt())
      .put("y2", (hit.box.bottom * sy).toInt())
      .toString()
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
