package com.chakamyth.hands

import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * The screen operator loop, run natively inside the AccessibilityService's
 * process. Because this doesn't touch React Native's JS runtime (which freezes
 * when Chaka is backgrounded), it keeps running while Chaka drives another app.
 *
 * Loop: dump screen (+ screenshot) → ask DeepSeek/Gemini for one action →
 * dispatch it → wait → repeat, until done / fail / budget.
 */
class ChakaOperator(private val service: ChakaAccessibilityService) {

  @Volatile
  var cancelled = false

  companion object {
    private const val TAG = "ChakaOperator"
    private const val CHAKA_PKG = "com.chakamyth.app"
    private const val CALL_TIMEOUT = 25000
    // Long enough to actually FINISH a real task in one run. Timing out and
    // letting the chat model retry meant every attempt restarted from zero,
    // which is what turned simple tasks into 30-60 minute ordeals.
    private const val RUN_BUDGET_MS = 600000L
    private const val MAX_BATCH = 4

    private val APP_MAP = mapOf(
      "spotify" to "com.spotify.music",
      "youtube" to "com.google.android.youtube",
      "whatsapp" to "com.whatsapp",
      "instagram" to "com.instagram.android",
      "chrome" to "com.android.chrome",
      "gmail" to "com.google.android.gm",
      "maps" to "com.google.android.apps.maps",
      "photos" to "com.google.android.apps.photos",
      "gallery" to "com.google.android.apps.photos",
      "settings" to "com.android.settings",
      "camera" to "com.android.camera",
      "tiktok" to "com.zhiliaoapp.musically",
      "telegram" to "org.telegram.messenger",
      "x" to "com.twitter.android",
      "twitter" to "com.twitter.android",
      "facebook" to "com.facebook.katana"
    )

    /** Resolves a spoken app name (or a raw package id) to a package. */
    fun appPackage(name: String): String? {
      val key = name.lowercase().replace(" ", "")
      return APP_MAP[key] ?: if (name.contains(".")) name else null
    }

    /**
     * Resolves an app name against what is ACTUALLY installed, by launcher
     * label. The hardcoded map maps "tiktok" to the full TikTok package, but
     * this phone has TikTok Lite - so open_app failed as "not installed" while
     * the icon sat on the home screen, and she resorted to hunting for it.
     */
    fun resolveInstalled(context: android.content.Context, name: String): String? {
      val pm = context.packageManager
      val want = name.lowercase().trim()
      // A mapped package that's genuinely present wins.
      appPackage(name)?.let { mapped ->
        if (runCatching { pm.getLaunchIntentForPackage(mapped) }.getOrNull() != null) return mapped
      }
      val launchable = runCatching {
        pm.queryIntentActivities(
          android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER),
          0
        )
      }.getOrNull() ?: return null

      data class App(val pkg: String, val label: String)
      val apps = launchable.mapNotNull { ri ->
        val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
        App(pkg, ri.loadLabel(pm).toString().lowercase())
      }
      // Exact label, then label starts-with, then either side containing the other.
      return apps.firstOrNull { it.label == want }?.pkg
        ?: apps.firstOrNull { it.label.startsWith(want) }?.pkg
        ?: apps.firstOrNull { it.label.contains(want) || want.contains(it.label) }?.pkg
    }

    private const val ACTION_MENU = """Action types (ONE per turn):
- {"type":"tap_index","index":N}  tap element [N] from the list — PREFER THIS when the target is listed
- {"type":"tap","x":0.5,"y":0.3}  tap a point as FRACTIONS 0..1 (for things NOT in the list)
- {"type":"type","text":"..."}    type into the focused field (tap an "input" element first)
- {"type":"enter"}                press keyboard search/enter after typing
- {"type":"swipe","direction":"down|up|left|right","amount":"tiny|normal|long"}  scroll — "down"=reveal content further DOWN the page, "up"=go toward the TOP. amount sets distance: "long" to cover lots of content or when a normal swipe didn't move the page, "tiny" for fine positioning, "normal" is the default. If even a LONG swipe reveals nothing new, that's the end that way — go the other direction instead
- {"type":"press","button":"back|home|recents|notifications|quick_settings"}
- {"type":"open","app":"spotify"} launch an app — at most once; never re-open an app already open
- {"type":"navigate","url":"https://example.com"}  open a WEBSITE in the browser — use this to GO TO a site rather than relying on whatever browser tab happens to be open
- {"type":"wait"}                 wait ~1s for loading
- {"type":"done","result":"...","return":true|false}  goal achieved
- {"type":"fail","reason":"..."}  genuinely stuck

SPEED — batch what you're sure of. Return "actions": an ARRAY of 1-4 actions to run back-to-back, so an obvious sequence costs one turn instead of four. Classic: [tap the search box, type the query, press enter].
- Batch ONLY steps you can predict without seeing the result. Stop the batch where you'd need to LOOK first (e.g. after a search you must see the results before tapping one).
- Put at most ONE tap_index in a batch and put it FIRST — indices go stale once the screen moves.
- "open"/"navigate" must be the LAST action in a batch.
- Unsure? Send a single action. A wrong batch costs more than an extra turn."""

    private const val RULES = """Rules:
- Prefer tap_index. Use tap x,y fractions only for things not listed. To search: tap the input field, type, then enter.
- "toggle:ON" / "toggle:OFF" show a switch's CURRENT state. If a switch is ALREADY in the state the goal wants, DO NOT tap it — that part is already done. Only tap a toggle to CHANGE it.
- Before navigating, check whether the goal is ALREADY satisfied on the current screen. If it is, finish immediately.
- FINISHING & RETURNING: when the objective is met reply {"type":"done","result":"..."}. Set "return":true ONLY when the user asked you to FIND OUT / CHECK / READ / REPORT something and needs the answer back in chat (e.g. "check if USB debugging is on and tell me"). Set "return":false when they just wanted you to open/play/navigate to something for them to use (e.g. "play this video", "open that website") — leave them there. Put what you found or did in "result" either way.
- READ THE RESULT: when your action produces a response, reply, answer, or result (you sent a message and it answered, you asked a question, you opened something containing the info), do NOT stop at "it worked". First SCROLL to reveal the whole response, actually READ it, then summarise what it SAYS in done.result. In a conversation the other side's reply IS the result — capture the gist of what they said, don't just report that you sent your message.
- If CURRENT APP is the home launcher or wrong app, use "open" to launch the app in the goal. The target app is already open once opened.
- POWER: you MAY restart or power off the phone (via the power menu) when the user asks or it's genuinely needed to fix something — do it deliberately and prefer gentler fixes first. Avoid factory reset or deleting accounts unless explicitly asked.
- IDENTITY CHECK: never assume the current screen is the goal's destination just because it looks related. If the goal names a specific app, site, or person, confirm it actually matches — read the URL bar / page title / branding. Some website's built-in chatbot is NOT your target just because it happens to be a chatbot.
- TYPING DISCIPLINE: only type a specific, purposeful message that advances the goal, as ONE complete "type" action. Never type random, partial, or placeholder text to "test" a field.
- If you can't confidently identify or REACH the destination the goal names, do NOT substitute a lookalike — reply fail and say exactly what you need (the app name or the URL).
- NEVER REPEAT A FAILED ACTION. Never navigate to a URL that is already open, never re-open an app that's already open, never scroll the same way more than 3 times. If something didn't work, do something DIFFERENT — a different element, a different path, or fail honestly.
- SIGN-IN: if a login/sign-in screen appears and the goal needs you logged in, just do it: tap the sign-in button ("Log in", "Sign in", "Continue with Google"), then pick the existing/first account shown. Don't reload the page hoping it logs itself in.
- Never claim a step you didn't take."""
  }

  private fun log(msg: String) = Log.i(TAG, msg)

  suspend fun run(
    goal: String,
    deepseekKey: String,
    geminiKey: String?,
    maxSteps: Int,
    app: String? = null
  ): JSONObject {
    val useVision = !geminiKey.isNullOrBlank()
    val transcript = ArrayList<String>()
    val startedAt = System.currentTimeMillis()
    var lastSig = ""
    var stalls = 0
    var openedApp = false
    var lastActionType = ""
    var lastSwipeDir = ""
    var lastSwipeAmount = ""
    // Phase 2 — self-awareness: the running plan (checklist of sub-goals), which
    // sub-goal we're on, and what we predicted the last action would produce (so
    // the next turn can VERIFY it happened instead of blindly moving on).
    var plan: JSONArray? = null
    var currentStep = 0
    var lastExpected = ""
    // Hard loop-breaker: fingerprint each action and refuse to run the same one
    // again past its limit — INDEPENDENT of whether the screen changed. (A
    // reloading page changes its signature every time, so screen-diff stall
    // detection alone never fires and the agent reloads forever.)
    var lastActionSig = ""
    var repeatCount = 0
    var blocks = 0
    var blockedHint = ""
    val visitCounts = HashMap<String, Int>()

    log("start goal=\"$goal\" vision=$useVision app=$app")

    // Fast path: deep-link straight into the target app instead of going Home and
    // hunting through the app drawer. Then use the hands from inside the app.
    var launched = false
    if (!app.isNullOrBlank()) {
      if (openApp(app)) {
        log("deep-linked directly into $app")
        openedApp = true
        launched = true
        transcript.add("(opened $app directly)")
        delay(1800)
      }
    }
    // Fallback (unknown app or none given): just step aside if Chaka is foreground.
    if (!launched) {
      runCatching {
        val first = JSONObject(service.dumpScreen())
        if (first.optString("pkg") == CHAKA_PKG) {
          service.globalAction("home")
          delay(1200)
        }
      }
    }

    for (step in 0 until maxSteps) {
      if (cancelled) return outcome("stopped", "You stopped it.", useVision, transcript)
      if (System.currentTimeMillis() - startedAt > RUN_BUDGET_MS) {
        return outcome("fail", "Took too long.", useVision, transcript, true)
      }

      val dump = try {
        JSONObject(service.dumpScreen())
      } catch (e: Exception) {
        log("readScreen failed: ${e.message}")
        return outcome(
          "fail",
          "I lost access to the screen partway through — the accessibility service may have been switched off. Please re-enable 'Chaka Hands' in Accessibility settings and I'll try again.",
          useVision, transcript, true
        )
      }
      val pkg = dump.optString("pkg")
      log("step ${step + 1}: pkg=$pkg els=${dump.getJSONArray("els").length()}")

      if (pkg == CHAKA_PKG) {
        service.globalAction("home")
        delay(1000)
        continue
      }

      val sig = signature(dump)
      val changed = sig != lastSig
      val visits = (visitCounts[sig] ?: 0) + 1
      visitCounts[sig] = visits
      val fb = StringBuilder()

      if (step > 0 && !changed) {
        // Screen didn't change after the last action — recovery ladder.
        stalls++
        // Direction-aware hints for the two classic traps: scrolling the wrong way
        // (which in a browser just refreshes) and text that never landed.
        when (lastActionType) {
          "swipe" -> {
            val opp = when (lastSwipeDir) { "down" -> "up"; "up" -> "down"; "left" -> "right"; "right" -> "left"; else -> "the other way" }
            if (lastSwipeAmount != "long") {
              // Maybe the swipe was just too weak to move a stubborn page — escalate before giving up.
              fb.append("Your swipe '$lastSwipeDir' didn't move anything — it may have been too small. Try a LONGER swipe the SAME way: {\"type\":\"swipe\",\"direction\":\"$lastSwipeDir\",\"amount\":\"long\"}. ")
            } else {
              fb.append("Even a LONG swipe '$lastSwipeDir' moved nothing — that's genuinely the end that way (in a browser it may have only refreshed). Go '$opp' or tap a different element instead. ")
            }
          }
          "type" -> fb.append("Your text didn't land — the field wasn't focused or the app blocked it. Tap the input field itself first, then type. ")
        }
        when (stalls) {
          1 -> fb.append("The screen didn't change after your last action — try a DIFFERENT element or path.")
          2 -> {
            val cx = dump.getInt("w") / 2; val h = dump.getInt("h")
            service.swipe(cx, (h * 0.80).toInt(), cx, (h * 0.20).toInt(), 240)  // long fling
            delay(800)
            log("recovery: auto long-scrolled to look for the target")
            transcript.add("(stuck — long-scrolled to reveal more)")
            fb.append(" I did a long scroll to reveal more — look again for the target.")
          }
          3 -> {
            service.globalAction("back")
            delay(900)
            log("recovery: pressed back to escape a dead-end")
            transcript.add("(stuck — pressed back to escape a dead-end)")
            fb.append(" I pressed back to escape a stuck screen — reassess from here.")
          }
          else -> return outcome("fail", reflectFailure(goal, transcript, deepseekKey), useVision, transcript, true)
        }
      } else {
        stalls = 0
        // Progress awareness: state plainly that the last action WORKED so she reads the
        // new screen instead of redoing a step that already succeeded.
        if (step > 0 && changed) {
          fb.append("The screen changed after your last action — it worked. Read the NEW screen: if it's the response/result you were after, scroll to see ALL of it, read it, and finish with a summary of what it SAYS in done.result; otherwise continue. Do NOT repeat that action.")
        }
        // Soft loop nudge: revisited this screen — rethink, don't blindly repeat.
        if (visits >= 4) {
          log("revisit nudge: this screen seen ${visits}x")
          if (fb.isNotEmpty()) fb.append(" ")
          fb.append("NOTE: you've landed on THIS screen $visits times. Don't repeat the same moves — check toggle states (toggle:ON/OFF), then pick ONE: finish if genuinely DONE, try a genuinely DIFFERENT action, or reply fail with the reason.")
        }
      }
      lastSig = sig
      if (blockedHint.isNotEmpty()) {
        if (fb.isNotEmpty()) fb.append(" ")
        fb.append(blockedHint)
        blockedHint = ""
      }
      val feedback = if (fb.isEmpty()) "" else "\n\n${fb.toString().trim()}"

      // SPEED: the accessibility tree already carries labels AND coordinates, so
      // most screens need no image at all. Uploading a ~200KB screenshot every
      // step was costing 5-15s per action. Go tree-first (fast text call) and
      // escalate to vision ONLY when the tree can't answer: a sparse/custom-drawn
      // screen, or when we're stalled and need eyes to get unstuck.
      val elCount = dump.optJSONArray("els")?.length() ?: 0
      val needVision = useVision && (elCount < 6 || stalls >= 1)
      var decision: JSONObject? = null
      if (needVision) {
        val shot = screenshotSuspend(dump.optJSONArray("els"))  // Set-of-Marks: number the tappable boxes
        log("step ${step + 1}: VISION (els=$elCount stalls=$stalls) shot=${shot?.length ?: 0}b")
        if (shot != null) decision = runCatching {
          geminiDecide(goal, dump, shot, transcript, feedback, plan, currentStep, lastExpected, geminiKey!!)
        }.getOrNull()
      }
      if (decision == null) {
        decision = runCatching {
          deepseekDecide(goal, dump, transcript, deepseekKey, feedback, plan, currentStep, lastExpected)
        }.getOrNull()
      }
      if (decision == null) {
        transcript.add("step ${step + 1}: no valid decision")
        continue
      }

      // Absorb the brain's self-awareness: adopt/revise the plan, advance the
      // current sub-goal when the screen proves it done, and remember what it
      // predicts THIS action will do so next turn can verify it.
      decision.optJSONArray("plan")?.let { if (it.length() > 0) plan = it }
      val planLen = plan?.length() ?: 0
      val stepIdx = decision.optInt("step_index", currentStep)
      if (planLen > 0 && stepIdx in 0 until planLen) currentStep = stepIdx
      if (decision.optBoolean("step_done", false) && planLen > 0 && currentStep < planLen - 1) currentStep++
      lastExpected = decision.optString("expected", "")
      log("step ${step + 1}: verify=\"${decision.optString("verify")}\" sub-goal ${currentStep + 1}/$planLen done=${decision.optBoolean("step_done", false)}")

      // A decision may carry a short SEQUENCE of confident actions ("actions"),
      // so an obvious run like tap-field → type → enter costs ONE model call
      // instead of three. Falls back to the single "action" form.
      val batch = decision.optJSONArray("actions")
        ?: decision.optJSONObject("action")?.let { JSONArray().put(it) }
      if (batch == null || batch.length() == 0) {
        transcript.add("step ${step + 1}: decision had no action")
        continue
      }

      var curDump = dump
      var curSig = sig
      var batchBlocked = false

      for (bi in 0 until minOf(batch.length(), MAX_BATCH)) {
        if (cancelled) return outcome("stopped", "You stopped it.", useVision, transcript)
        val action = batch.optJSONObject(bi) ?: break
        val type = action.optString("type")
        log("step ${step + 1}.${bi + 1}: action=$action")

        if (type == "done") return outcome("done", action.optString("result", "Done."), useVision, transcript, action.optBoolean("return", false))
        if (type == "fail") return outcome("fail", action.optString("reason", "Stuck."), useVision, transcript, true)

        if (type == "open") {
          if (openedApp) { delay(400); break }
          openedApp = true
        }

        // Mid-batch the screen has moved, so refresh our model of it and make
        // sure an index-based tap still points at the SAME element.
        if (bi > 0) {
          val fresh = runCatching { JSONObject(service.dumpScreen()) }.getOrNull()
          if (fresh == null) break
          if (type == "tap_index" && !sameElement(curDump, fresh, action.optInt("index", -1))) {
            transcript.add("step ${step + 1}: (batch stopped — screen moved, re-checking)")
            log("batch aborted at ${bi + 1}: element ${action.optInt("index", -1)} no longer matches")
            break
          }
          curDump = fresh
          curSig = signature(fresh)
        }

        // --- Hard loop-breaker -----------------------------------------------
        val aSig = actionSig(action)
        repeatCount = if (aSig == lastActionSig) repeatCount + 1 else 1
        lastActionSig = aSig
        // navigate/open to the SAME target is never right twice (that's the
        // page-reload loop); scrolling may legitimately repeat a few times.
        val limit = when (type) { "navigate", "open" -> 1; "swipe" -> 3; "wait" -> 2; else -> 2 }
        if (repeatCount > limit) {
          blocks++
          log("BLOCKED repeat #$repeatCount of $aSig (limit $limit)")
          transcript.add("step ${step + 1}: (blocked — tried '$aSig' $repeatCount times)")
          if (blocks >= 3) {
            return outcome("fail", reflectFailure(goal, transcript, deepseekKey), useVision, transcript, true)
          }
          blockedHint = "BLOCKED: you already did '$aSig' $repeatCount times and it did NOT get you closer. " +
            "Do NOT do it again. Look at what's actually on the screen now and take a DIFFERENT action " +
            "(a different element, a different route). If a login/sign-in is in the way, sign in. " +
            "If it's truly impossible, reply fail with the reason."
          repeatCount = 0
          batchBlocked = true
          break
        }
        // ---------------------------------------------------------------------

        val did = dispatch(action, curDump)
        lastActionType = type
        lastSwipeDir = if (type == "swipe") action.optString("direction") else ""
        lastSwipeAmount = if (type == "swipe") action.optString("amount", "normal") else ""
        val progressTag = if (planLen > 0) "[${currentStep + 1}/$planLen] " else ""
        transcript.add("step ${step + 1}: $progressTag$did")
        log("step ${step + 1}.${bi + 1}: did=$did")

        // Adaptive settle: continue as soon as the screen actually changes rather
        // than always sleeping the worst case.
        val maxSettle = when (type) { "open", "navigate" -> 3000L; "wait" -> 1200L; "type" -> 900L; else -> 1600L }
        awaitSettle(curSig, if (type == "type") 200L else 260L, maxSettle)

        // A context switch invalidates everything after it.
        if (type == "open" || type == "navigate") break
      }
      if (batchBlocked) { delay(200); continue }
    }

    return outcome("exhausted", reflectFailure(goal, transcript, deepseekKey), useVision, transcript, true)
  }

  /** One reasoning call to explain (in Chaka's voice) what blocked the task and what to try next. */
  private fun reflectFailure(goal: String, transcript: List<String>, key: String): String {
    val sys = "You are Chaka reporting back to your owner after a phone screen task got stuck. In ONE or TWO plain sentences (first person, 'I'), tell them what you were doing, exactly where you got stuck, and offer one concrete next step. No jargon, no lists."
    val user = "Task: $goal\n\nWhat I tried:\n${transcript.joinToString("\n").ifEmpty { "(barely started)" }}\n\nI couldn't finish. Report back:"
    val messages = JSONArray()
      .put(JSONObject().put("role", "system").put("content", sys))
      .put(JSONObject().put("role", "user").put("content", user))
    val body = JSONObject().put("model", "deepseek-v4-flash").put("messages", messages).put("stream", false).toString()
    val resp = httpPost("https://api.deepseek.com/chat/completions", mapOf("Authorization" to "Bearer $key"), body)
      ?: return "I got stuck partway through and couldn't finish it. Want me to try a different way?"
    return try {
      JSONObject(resp).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
    } catch (e: Exception) {
      "I got stuck partway through and couldn't finish it."
    }
  }

  private fun outcome(
    o: String,
    detail: String,
    vision: Boolean,
    transcript: List<String>,
    bringToFront: Boolean = false
  ): JSONObject {
    // Only return to Chaka when the user actually needs the result in chat — otherwise
    // leave them in the app they wanted (e.g. watching the YouTube video they asked for).
    if (bringToFront) runCatching {
      val launch = service.applicationContext.packageManager.getLaunchIntentForPackage(CHAKA_PKG)
      launch?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
      if (launch != null) service.applicationContext.startActivity(launch)
    }
    return JSONObject()
      .put("outcome", o)
      .put("detail", detail)
      .put("perception", if (vision) "vision+tree" else "accessibility tree")
      .put("steps", JSONArray(transcript))
  }

  /**
   * True when index [i] still refers to the same element it did in [before].
   * Indices are positional, so once the screen moves a batched tap could land on
   * something entirely different — this is the guard against that.
   */
  private fun sameElement(before: JSONObject, after: JSONObject, i: Int): Boolean {
    if (i < 0) return false
    fun labelOf(d: JSONObject): String? {
      val els = d.optJSONArray("els") ?: return null
      for (k in 0 until els.length()) {
        val e = els.optJSONObject(k) ?: continue
        if (e.optInt("i") == i) return e.optString("text", e.optString("desc", "")) + "|" + e.optString("cls")
      }
      return null
    }
    val a = labelOf(before) ?: return false
    val b = labelOf(after) ?: return false
    return a == b
  }

  /** Stable fingerprint of an action + its target, for repeat detection. */
  private fun actionSig(a: JSONObject): String = when (val t = a.optString("type")) {
    "tap_index" -> "tap[${a.optInt("index")}]"
    "tap" -> "tap@${"%.2f".format(a.optDouble("x"))},${"%.2f".format(a.optDouble("y"))}"
    "type" -> "type:${a.optString("text").take(24)}"
    "swipe" -> "swipe:${a.optString("direction")}"
    "press" -> "press:${a.optString("button")}"
    "navigate" -> "navigate:${a.optString("url")}"
    "open" -> "open:${a.optString("app")}"
    else -> t
  }

  /**
   * Waits just long enough for the screen to actually change, up to [maxMs].
   * Much faster than a fixed sleep on responsive screens, and more patient than
   * one on slow-loading pages.
   */
  private suspend fun awaitSettle(prevSig: String, minMs: Long, maxMs: Long) {
    delay(minMs)
    val start = System.currentTimeMillis()
    while (System.currentTimeMillis() - start < maxMs) {
      val now = runCatching { signature(JSONObject(service.dumpScreen())) }.getOrNull()
      if (now != null && now != prevSig) { delay(200); return }  // changed — brief settle for animations
      delay(180)
    }
  }

  private fun signature(dump: JSONObject): String {
    val els = dump.getJSONArray("els")
    val sb = StringBuilder()
    for (i in 0 until els.length()) {
      val e = els.getJSONObject(i)
      sb.append(e.optString("text", e.optString("desc", ""))).append("@").append(e.optInt("cx")).append(",").append(e.optInt("cy")).append("|")
    }
    return sb.toString()
  }

  private fun numbered(dump: JSONObject): String {
    val els = dump.getJSONArray("els")
    if (els.length() == 0) return "(no readable elements — custom-drawn screen; use vision coords or scroll)"
    val sb = StringBuilder()
    for (i in 0 until els.length()) {
      val e = els.getJSONObject(i)
      val label = e.optString("text", e.optString("desc", ""))
      val flags = buildString {
        if (e.optBoolean("clickable")) append("tap")
        if (e.optBoolean("editable")) { if (isNotEmpty()) append(","); append("input") }
        if (e.optBoolean("toggle")) {
          if (isNotEmpty()) append(" ")
          append(if (e.optBoolean("on")) "toggle:ON" else "toggle:OFF")
        }
        if (e.optBoolean("selected")) { if (isNotEmpty()) append(" "); append("selected") }
      }
      sb.append("[").append(e.getInt("i")).append("] \"").append(label).append("\" (").append(e.optString("cls")).append(")")
      if (flags.isNotEmpty()) sb.append(" ").append(flags)
      sb.append("\n")
    }
    return sb.toString().trim()
  }

  private fun dispatch(action: JSONObject, dump: JSONObject): String {
    return when (action.optString("type")) {
      "tap_index" -> {
        val idx = action.optInt("index", -1)
        val els = dump.getJSONArray("els")
        var target: JSONObject? = null
        for (i in 0 until els.length()) if (els.getJSONObject(i).getInt("i") == idx) { target = els.getJSONObject(i); break }
        if (target == null) "no element [$idx]" else {
          service.tap(target.getInt("cx"), target.getInt("cy")); "tapped [$idx]"
        }
      }
      "tap" -> {
        var x = action.optDouble("x", 0.0); var y = action.optDouble("y", 0.0)
        if (x <= 1 && y <= 1) { x *= dump.getInt("w"); y *= dump.getInt("h") }
        service.tap(x.toInt(), y.toInt()); "tapped ${x.toInt()},${y.toInt()}"
      }
      "type" -> {
        val t = action.optString("text")
        if (service.typeText(t)) "typed \"${t.take(30)}\"" else "type FAILED — field not focused or app blocked it; tap the field first"
      }
      "enter" -> { service.pressEnter(); "enter" }
      "swipe" -> {
        // Distance + speed scale with amount so she can fling far or nudge gently.
        // Semantics match the menu: "down" reveals content further down (finger drags UP);
        // "up" goes back toward the top (finger drags DOWN). "long" is a fast fling.
        val w = dump.getInt("w"); val h = dump.getInt("h"); val cx = w / 2; val cy = h / 2
        val amount = action.optString("amount", "normal")
        val (frac, dur) = when (amount) {
          "tiny" -> 0.09 to 300L
          "long" -> 0.38 to 220L
          else -> 0.16 to 340L
        }
        val dy = (h * frac).toInt(); val dx = (w * frac).toInt()
        when (action.optString("direction")) {
          "up" -> service.swipe(cx, cy - dy, cx, cy + dy, dur)
          "left" -> service.swipe(cx + dx, cy, cx - dx, cy, dur)
          "right" -> service.swipe(cx - dx, cy, cx + dx, cy, dur)
          else -> service.swipe(cx, cy + dy, cx, cy - dy, dur)  // "down"
        }
        "swiped ${action.optString("direction")} ($amount)"
      }
      "press" -> { service.globalAction(action.optString("button", "back")); "pressed ${action.optString("button")}" }
      "open" -> { if (openApp(action.optString("app"))) "opened ${action.optString("app")}" else "couldn't open ${action.optString("app")}" }
      "navigate" -> { if (openUrl(action.optString("url"))) "opened ${action.optString("url")}" else "couldn't open ${action.optString("url")}" }
      "wait" -> "waited"
      else -> "unknown ${action.optString("type")}"
    }
  }

  private fun openApp(name: String): Boolean {
    val key = name.lowercase().replace(" ", "")
    // Known name → package; otherwise if it's already a package id (com.x.y) use it directly.
    val pkg = APP_MAP[key] ?: if (name.contains(".")) name else return false
    val ctx = service.applicationContext
    val intent = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    ctx.startActivity(intent)
    return true
  }

  /** Opens a URL in the default browser via an ACTION_VIEW intent (a fresh, deliberate page). */
  private fun openUrl(url: String): Boolean {
    if (url.isBlank()) return false
    val full = if (url.startsWith("http", ignoreCase = true)) url else "https://$url"
    return runCatching {
      val intent = Intent(Intent.ACTION_VIEW, Uri.parse(full)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      service.applicationContext.startActivity(intent)
      true
    }.getOrDefault(false)
  }

  private suspend fun screenshotSuspend(marks: JSONArray? = null): String? = suspendCoroutine { cont ->
    service.captureScreenshot(marks) { b64 -> cont.resume(b64) }
  }

  private fun planText(plan: JSONArray?, currentStep: Int): String {
    if (plan == null || plan.length() == 0) return "(none yet — CREATE the checklist now, from the goal and what you see)"
    return (0 until plan.length()).joinToString("\n") { i ->
      val mark = when { i < currentStep -> "[x]"; i == currentStep -> "[→]"; else -> "[ ]" }
      "  $mark ${i + 1}. ${plan.optString(i)}"
    }
  }

  /**
   * The shared brain prompt. The element list already carries labels, states and
   * tappable indices, so the text-only (fast) path is just as plan-aware as the
   * vision path — vision only adds eyes for screens the tree can't describe.
   */
  private fun buildPrompt(
    goal: String,
    dump: JSONObject,
    transcript: List<String>,
    feedback: String,
    plan: JSONArray?,
    currentStep: Int,
    lastExpected: String,
    withVision: Boolean
  ): String {
    val eyes = if (withVision)
      "You also have a SCREENSHOT. Every tappable element has a small RED number drawn on it — read that number and use tap_index with it. Trust the image over the list when they disagree; never tap what you can't see.\n"
    else
      "You are working from the element list (it has each element's index, label and state). It is reliable for normal screens.\n"

    return "You are Chaka's screen operator — deliberate and self-aware, never reflexive. You always know your plan, which step you're on, and whether the last action worked.\n" +
      eyes +
      "IDENTITY: confirm this screen really is the goal's destination (URL bar / title / name) before acting on it.\n" +
      "EVERY TURN: 1) observe what's on screen. 2) VERIFY your last action against what you expected — if it didn't happen, say why and change approach; never pretend or repeat it. 3) tick the current sub-goal ONLY when the screen proves it, and revise the plan if reality diverged (login wall, error, popup). 4) choose ONE next action and state what you expect to see.\n" +
      "Finish (action done) only when the screen proves every sub-goal is met — put the real answer/result in done.result. If genuinely blocked after trying alternatives, action fail with the reason.\n\n" +
      "Reply with ONLY this JSON:\n" +
      "{\"observation\":\"what's on screen\",\"verify\":\"did my last action do what I expected? yes/no + why\",\"plan\":[\"sub-goal 1\",\"sub-goal 2\"],\"step_index\":<0-based sub-goal you're on NOW>,\"step_done\":<true if it's now complete>,\"thought\":\"brief\",\"actions\":[{...},{...}],\"expected\":\"what the screen should look like after the LAST action\"}\n\n" +
      "$ACTION_MENU\n\n$RULES\n\n" +
      "GOAL: $goal\n\n" +
      "YOUR PLAN (keep stable; revise only when reality forces it):\n${planText(plan, currentStep)}\nYou are on sub-goal #${currentStep + 1}.\n" +
      "AFTER YOUR LAST ACTION YOU EXPECTED: ${lastExpected.ifEmpty { "(first step — no prediction yet)" }}\n→ Verify whether that actually happened.\n\n" +
      "CURRENT APP: ${dump.optString("pkg", "unknown")}\nELEMENTS:\n${numbered(dump)}\nscreen ${dump.getInt("w")}x${dump.getInt("h")}$feedback\n\n" +
      "ACTIONS SO FAR:\n${if (transcript.isEmpty()) "(none yet)" else transcript.joinToString("\n")}\n\nReturn the JSON:"
  }

  private fun deepseekDecide(
    goal: String,
    dump: JSONObject,
    transcript: List<String>,
    key: String,
    feedback: String,
    plan: JSONArray?,
    currentStep: Int,
    lastExpected: String
  ): JSONObject? {
    val prompt = buildPrompt(goal, dump, transcript, feedback, plan, currentStep, lastExpected, withVision = false)
    val messages = JSONArray().put(JSONObject().put("role", "user").put("content", prompt))
    val body = JSONObject()
      .put("model", "deepseek-v4-flash")
      .put("messages", messages)
      .put("stream", false)
      .put("response_format", JSONObject().put("type", "json_object"))
      .toString()
    val resp = httpPost("https://api.deepseek.com/chat/completions", mapOf("Authorization" to "Bearer $key"), body) ?: return null
    val content = JSONObject(resp).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
    return parseDecision(content)
  }

  private fun geminiDecide(
    goal: String,
    dump: JSONObject,
    shotB64: String,
    transcript: List<String>,
    feedback: String,
    plan: JSONArray?,
    currentStep: Int,
    lastExpected: String,
    key: String
  ): JSONObject? {
    val prompt = buildPrompt(goal, dump, transcript, feedback, plan, currentStep, lastExpected, withVision = true)
    val parts = JSONArray()
      .put(JSONObject().put("text", prompt))
      .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", shotB64)))
    val body = JSONObject()
      .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
      .put("generationConfig", JSONObject().put("temperature", 0).put("responseMimeType", "application/json"))
      .toString()
    val resp = httpPost(
      "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite:generateContent?key=$key",
      emptyMap(), body
    ) ?: return null
    val text = JSONObject(resp).getJSONArray("candidates").getJSONObject(0)
      .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
    return parseDecision(text)
  }

  /** Parses the whole decision object ({observation, verify, plan, action, expected, ...}). */
  private fun parseDecision(raw: String): JSONObject? {
    val start = raw.indexOf('{')
    val end = raw.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    return runCatching { JSONObject(raw.substring(start, end + 1)) }.getOrNull()
  }

  private fun httpPost(urlStr: String, headers: Map<String, String>, body: String): String? {
    return try {
      val conn = URL(urlStr).openConnection() as HttpURLConnection
      conn.requestMethod = "POST"
      conn.connectTimeout = 15000
      conn.readTimeout = CALL_TIMEOUT
      conn.doOutput = true
      conn.setRequestProperty("Content-Type", "application/json")
      headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
      OutputStreamWriter(conn.outputStream).use { it.write(body) }
      val code = conn.responseCode
      val stream = if (code in 200..299) conn.inputStream else conn.errorStream
      val resp = stream.bufferedReader().use { it.readText() }
      if (code in 200..299) resp else { log("http $code: ${resp.take(200)}"); null }
    } catch (e: Exception) {
      log("http error: ${e.message}"); null
    }
  }
}
