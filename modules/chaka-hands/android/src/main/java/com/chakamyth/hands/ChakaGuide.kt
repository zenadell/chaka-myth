package com.chakamyth.hands

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Guide Mode — Chaka watches the screen and coaches the USER through a task,
 * one step at a time, via a floating bubble + spoken guidance. She does NOT tap;
 * the user drives. Runs natively so it survives Chaka being backgrounded.
 */
class ChakaGuide(private val service: ChakaAccessibilityService, private val context: Context) {

  @Volatile
  var cancelled = false
  private var tts: TextToSpeech? = null

  companion object {
    private const val TAG = "ChakaGuide"
    private const val CHAKA_PKG = "com.chakamyth.app"
    private const val OVERALL_CAP_MS = 900000L  // 15 min hard ceiling
    private const val IDLE_MS = 150000L         // pause after 2.5 min of NO screen change
  }

  /**
   * Returns the outcome: "done" (goal reached), "stopped" (user tapped bubble),
   * "idle" (paused after long inactivity), or "timeout". The loop is time/idle
   * based — it does NOT quit after a fixed number of steps, so it never bails out
   * while the user is still working.
   */
  suspend fun run(goal: String, geminiKey: String): String {
    initTts()
    var lastGuidance = ""
    var lastSig = ""
    // How many times the screen has changed since we last gave a NEW instruction.
    // If this climbs while the instruction stays the same, Chaka is fixated /
    // misreading (e.g. repeating "swipe up" after the user already did it).
    var staleFor = 0
    val startedAt = System.currentTimeMillis()
    var lastChangeAt = System.currentTimeMillis()
    var outcome = "timeout"

    ChakaGuideOverlay.update("Looking at your screen…")

    try {
      while (true) {
        if (cancelled) { outcome = "stopped"; break }
        val now = System.currentTimeMillis()
        if (now - startedAt > OVERALL_CAP_MS) { outcome = "timeout"; break }
        if (now - lastChangeAt > IDLE_MS) { outcome = "idle"; break }

        val dump = runCatching { JSONObject(service.dumpScreen()) }.getOrNull()
        if (dump == null) { delay(1200); continue }

        // Don't coach on Chaka's own screen — wait until the user is in the target app.
        if (dump.optString("pkg") == CHAKA_PKG) {
          ChakaGuideOverlay.update("Open the screen you want help with…")
          lastSig = ""
          delay(1400)
          continue
        }

        val sig = signature(dump)
        // Screen unchanged = user reading/thinking. Keep watching; don't advance or re-ask.
        if (sig == lastSig && lastGuidance.isNotEmpty()) {
          delay(1300)
          continue
        }
        lastSig = sig
        lastChangeAt = now  // the screen moved → the user is actively engaged

        val shot = screenshotSuspend()
        // The screen just changed since our last instruction, so it was likely
        // followed. Count it — geminiGuide uses this to break out of a loop.
        if (lastGuidance.isNotEmpty()) staleFor++
        Log.i(TAG, "pkg=${dump.optString("pkg")} els=${dump.optJSONArray("els")?.length()} shot=${shot?.length ?: "NULL"} staleFor=$staleFor")

        val g = runCatching { geminiGuide(goal, dump, shot, lastGuidance, staleFor, geminiKey) }.getOrNull()
        if (g == null) { delay(2800); continue }  // rate-limit backoff — does NOT end the guide

        val instruction = g.optString("instruction").trim()
        Log.i(TAG, "instruction=\"$instruction\" done=${g.optBoolean("done", false)}")
        if (g.optBoolean("done", false)) {
          val msg = instruction.ifEmpty { "That's it — you did it!" }
          ChakaGuideOverlay.update("✓ $msg")
          speak(msg)
          outcome = "done"
          delay(2500)
          break
        }
        if (instruction.isNotEmpty() && instruction != lastGuidance) {
          ChakaGuideOverlay.update(instruction)
          speak(instruction)
          lastGuidance = instruction
          staleFor = 0  // fresh instruction → reset the fixation counter
        }
        delay(1500)
      }
    } finally {
      shutdownTts()
    }
    return outcome
  }

  private fun geminiGuide(
    goal: String,
    dump: JSONObject,
    shotB64: String?,
    lastGuidance: String,
    staleFor: Int,
    key: String
  ): JSONObject? {
    val staleNote = if (staleFor >= 1 && lastGuidance.isNotEmpty())
      "IMPORTANT: the screen has CHANGED since your last instruction ('$lastGuidance'), so they already DID it — do NOT tell them to do it again. Look at the NEW screen and give the step AFTER that (or done). Repeating '$lastGuidance' is a bug.\n"
    else ""

    val prompt =
      "You are Chaka, coaching your owner through a task on their phone in real time — like a warm, easy friend beside them. You WATCH; THEY tap (never say you'll do it).\n" +
      "VOICE: sound like a real person, not a robot. Speak the instruction naturally and briefly. Do NOT start with 'Perfect'/'Done'/'Now' every time — most steps need NO praise or filler at all, just tell them the next thing (e.g. 'Head into Connections' or 'The airplane toggle is top-right — tap it'). Vary your wording. Never repeat the same acknowledgement twice in a row.\n" +
      "CRITICAL: the SCREENSHOT is your ground truth. Read it carefully — what's actually on screen right now, which toggles look ON (highlighted/coloured) vs OFF (grey), and whether the thing you want them to tap is ACTUALLY VISIBLE. The text element list below can be incomplete or stale (it often misses the Quick Settings / notification shade), so trust your EYES over the list, and NEVER tell them to tap something you cannot see in the screenshot — if it's not visible, tell them how to reveal it first (scroll, expand the panel, etc.).\n" +
      staleNote +
      "ANDROID LAUNCHER: a screen full of app icons in a grid means the APP DRAWER is ALREADY OPEN (on Samsung it has a 'Search' bar across the top and small page dots at the bottom). If you see that, do NOT say 'swipe up to open your apps' — that's already done. Instead tell them the exact app to tap, or to type its name in the Search bar. Only say 'swipe up from the bottom' when you see the actual HOME screen (wallpaper with a bottom dock and just a few icons, NO search bar).\n" +
      "Compare the screenshot against your LAST instruction and the goal:\n" +
      "- Only praise ('nice'/'perfect') when the screen actually moved TOWARD the goal (e.g. the TARGET toggle you named is now ON). Never praise just because something changed.\n" +
      "- If your last instruction was to tap X, but X is still unchanged and a DIFFERENT toggle/thing changed, they tapped the WRONG one — do NOT praise; say it plainly and re-point, e.g. 'That turned on the flashlight, not airplane — the airplane icon is the plane shape; tap that one.'\n" +
      "- If they opened the wrong place, correct them warmly and specifically.\n" +
      "- Otherwise give the SINGLE next concrete step, short and clear (max ~16 words).\n" +
      "Only set done=true when the SCREENSHOT clearly shows the goal is achieved. Reply ONLY as JSON: {\"instruction\":\"...\",\"done\":false}.\n\n" +
      "GOAL: $goal\nYour LAST instruction was: ${lastGuidance.ifEmpty { "(none yet)" }}\n" +
      "Text element hints (may be incomplete — verify against the screenshot): ${numbered(dump)}"

    val parts = JSONArray().put(JSONObject().put("text", prompt))
    if (shotB64 != null) {
      parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", shotB64)))
    }
    val body = JSONObject()
      .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
      .put("generationConfig", JSONObject().put("temperature", 0.2).put("responseMimeType", "application/json"))
      .toString()

    val resp = httpPost(
      "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite:generateContent?key=$key",
      body
    ) ?: return null
    val text = runCatching {
      JSONObject(resp).getJSONArray("candidates").getJSONObject(0)
        .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
    }.getOrNull() ?: return null
    val start = text.indexOf('{'); val end = text.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    return runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull()
  }

  private fun numbered(dump: JSONObject): String {
    val els = dump.optJSONArray("els") ?: return "(none)"
    val sb = StringBuilder()
    var n = 0
    for (i in 0 until els.length()) {
      if (n >= 45) break
      val e = els.getJSONObject(i)
      val label = e.optString("text", e.optString("desc", ""))
      if (label.isBlank()) continue
      sb.append("\"").append(label.take(40)).append("\"")
      if (e.optBoolean("toggle", false)) sb.append(if (e.optBoolean("on", false)) "(ON)" else "(OFF)")
      sb.append(" ")
      n++
    }
    return sb.toString().trim()
  }

  // Include toggle on/off state so a flashlight/wifi/etc. tap registers as a change.
  private fun signature(dump: JSONObject): String {
    val els = dump.optJSONArray("els") ?: return ""
    val sb = StringBuilder()
    for (i in 0 until els.length()) {
      val e = els.getJSONObject(i)
      sb.append(e.optString("text", e.optString("desc", "")))
      if (e.optBoolean("toggle", false)) sb.append(if (e.optBoolean("on", false)) "#1" else "#0")
      sb.append("|")
    }
    return sb.toString()
  }

  private suspend fun screenshotSuspend(): String? = suspendCoroutine { cont ->
    service.captureScreenshot { b64 -> cont.resume(b64) }
  }

  private fun initTts() {
    runCatching {
      tts = TextToSpeech(context) { status ->
        if (status == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault()
      }
    }
  }

  private fun speak(text: String) {
    runCatching { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "chaka-guide") }
  }

  private fun shutdownTts() {
    runCatching { tts?.stop(); tts?.shutdown() }
    tts = null
  }

  private fun httpPost(urlStr: String, body: String): String? {
    return try {
      val conn = URL(urlStr).openConnection() as HttpURLConnection
      conn.requestMethod = "POST"
      conn.connectTimeout = 15000
      conn.readTimeout = 25000
      conn.doOutput = true
      conn.setRequestProperty("Content-Type", "application/json")
      OutputStreamWriter(conn.outputStream).use { it.write(body) }
      val code = conn.responseCode
      val stream = if (code in 200..299) conn.inputStream else conn.errorStream
      val resp = stream.bufferedReader().use { it.readText() }
      if (code in 200..299) resp else { Log.e(TAG, "guide http $code"); null }
    } catch (e: Exception) {
      Log.e(TAG, "guide http error: ${e.message}"); null
    }
  }
}
