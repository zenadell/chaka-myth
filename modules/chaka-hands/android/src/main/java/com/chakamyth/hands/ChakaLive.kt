package com.chakamyth.hands

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Live Mode — a persistent Gemini Live API session that watches the screen and
 * acts on it in one continuous conversation.
 *
 * Why this exists: the step-by-step operator re-opened an HTTPS connection and
 * re-uploaded the whole context (rules + element list + transcript) for EVERY
 * action, which is the latency floor that made tasks feel slow. Here the system
 * instruction and tool declarations are sent once at setup; after that only new
 * frames and tool results stream over an already-open socket, and the model keeps
 * the conversation state itself.
 *
 * Runs natively for the same reason ChakaOperator does: RN's JS thread freezes
 * when Chaka is backgrounded, which is precisely when Live Mode is useful.
 */
class ChakaLive(
  private val service: ChakaAccessibilityService,
  private val context: Context
) {

  @Volatile var cancelled = false
  private var socket: WebSocket? = null
  private var tts: TextToSpeech? = null
  private var frameThread: Thread? = null

  @Volatile private var ready = false
  @Volatile private var lastSpoken = ""

  companion object {
    private const val TAG = "ChakaLive"
    private const val CHAKA_PKG = "com.chakamyth.app"
    private const val WS =
      "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    // The Live API accepts image input at <= 1 FPS, which happens to match
    // Android's accessibility-screenshot rate limit. ~1.4s is a safe cadence.
    private const val FRAME_MS = 1400L
  }

  private val client = OkHttpClient.Builder()
    .readTimeout(0, TimeUnit.MILLISECONDS)  // keep the stream open indefinitely
    .pingInterval(20, TimeUnit.SECONDS)
    .build()

  fun start(goal: String, apiKey: String, model: String) {
    initTts()
    val req = Request.Builder().url("$WS?key=$apiKey").build()
    socket = client.newWebSocket(req, object : WebSocketListener() {

      override fun onOpen(ws: WebSocket, response: Response) {
        Log.i(TAG, "socket open — sending setup (model=$model)")
        ws.send(setupMessage(goal, model).toString())
      }

      override fun onMessage(ws: WebSocket, text: String) {
        handleServerMessage(ws, text)
      }

      override fun onMessage(ws: WebSocket, bytes: okio.ByteString) {
        handleServerMessage(ws, bytes.utf8())
      }

      override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
        Log.e(TAG, "socket failure: ${t.message} code=${response?.code}")
        ChakaGuideOverlay.update("Live connection dropped — ${t.message ?: "unknown"}")
        stop()
      }

      override fun onClosed(ws: WebSocket, code: Int, reason: String) {
        Log.i(TAG, "socket closed $code $reason")
        stop()
      }
    })
  }

  /** Setup carries the system instruction + tools ONCE for the whole session. */
  private fun setupMessage(goal: String, model: String): JSONObject {
    val sys =
      "You are Chaka, watching your owner's Android screen live and helping in real time. " +
      "You can SEE the screen (frames stream to you) and you can ACT on it with the provided tools.\n" +
      "GOAL / CONTEXT: ${goal.ifBlank { "Assist with whatever is on screen. Ask what they need." }}\n" +
      "HOW TO WORK:\n" +
      "- Call read_screen whenever you need exact, tappable elements — it returns each element's index, label and state. Tapping by index is precise; guessing coordinates is not.\n" +
      "- Act with the tools rather than describing what you'd do, unless they asked you to guide them.\n" +
      "- Verify with your own eyes: after acting, the next frames show the result. If it didn't work, say so and try a DIFFERENT approach — never repeat a failed action or reload a page hoping it fixes itself.\n" +
      "- If a sign-in blocks the goal, sign in (tap Log in / Continue with Google, then the existing account).\n" +
      "- Keep speech short, warm and natural — you're a person beside them, not a manual. Don't narrate every tap.\n" +
      "- When the goal is met, say so briefly with the actual result."

    val decls = JSONArray()
      .put(fn("read_screen", "Read the current screen: every element with its index, label, and whether it's tappable/editable/toggled. Call this before tapping.", JSONObject()))
      .put(fn("tap_index", "Tap the element with this index (from read_screen).", props("index", "integer", "Element index"), listOf("index")))
      .put(fn("type_text", "Type into the focused field. Tap the field first.", props("text", "string", "Text to type"), listOf("text")))
      .put(fn("press_enter", "Press the keyboard enter/search key.", JSONObject()))
      .put(fn("swipe", "Scroll the screen. direction: down (reveal content further down), up, left, right. amount: tiny|normal|long.",
        JSONObject()
          .put("direction", JSONObject().put("type", "string").put("description", "down|up|left|right"))
          .put("amount", JSONObject().put("type", "string").put("description", "tiny|normal|long")),
        listOf("direction")))
      .put(fn("press_button", "Press a system button: back, home, recents, notifications, quick_settings.", props("button", "string", "back|home|recents|notifications|quick_settings"), listOf("button")))
      .put(fn("open_app", "Launch an app by name.", props("app", "string", "App name, e.g. spotify"), listOf("app")))
      .put(fn("navigate", "Open a website URL in the browser.", props("url", "string", "Full URL"), listOf("url")))

    return JSONObject().put(
      "setup",
      JSONObject()
        .put("model", "models/$model")
        .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sys))))
        .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", decls)))
        .put(
          "generationConfig",
          JSONObject()
            .put("temperature", 0.3)
            // TEXT for now — we speak it with Android TTS. Native AUDIO output
            // needs a PCM playback path, which is the next phase.
            .put("responseModalities", JSONArray().put("TEXT"))
        )
    )
  }

  private fun fn(name: String, desc: String, properties: JSONObject, required: List<String> = emptyList()): JSONObject {
    val params = JSONObject().put("type", "object").put("properties", properties)
    if (required.isNotEmpty()) params.put("required", JSONArray(required))
    return JSONObject().put("name", name).put("description", desc).put("parameters", params)
  }

  private fun props(name: String, type: String, desc: String): JSONObject =
    JSONObject().put(name, JSONObject().put("type", type).put("description", desc))

  private fun handleServerMessage(ws: WebSocket, raw: String) {
    val msg = runCatching { JSONObject(raw) }.getOrNull() ?: return

    if (msg.has("setupComplete")) {
      Log.i(TAG, "setup complete — streaming frames")
      ready = true
      ChakaGuideOverlay.update("Live — I can see your screen")
      startFrameLoop(ws)
      return
    }

    msg.optJSONObject("toolCall")?.let { call ->
      val calls = call.optJSONArray("functionCalls") ?: return@let
      val responses = JSONArray()
      for (i in 0 until calls.length()) {
        val c = calls.optJSONObject(i) ?: continue
        val name = c.optString("name")
        val args = c.optJSONObject("args") ?: JSONObject()
        val result = runCatching { executeTool(name, args) }.getOrElse {
          JSONObject().put("error", it.message ?: "failed")
        }
        Log.i(TAG, "tool $name($args) -> ${result.toString().take(120)}")
        responses.put(JSONObject().put("id", c.optString("id")).put("name", name).put("response", result))
      }
      ws.send(JSONObject().put("toolResponse", JSONObject().put("functionResponses", responses)).toString())
      return
    }

    msg.optJSONObject("serverContent")?.let { content ->
      val parts = content.optJSONObject("modelTurn")?.optJSONArray("parts") ?: return@let
      val sb = StringBuilder()
      for (i in 0 until parts.length()) {
        parts.optJSONObject(i)?.optString("text")?.let { if (it.isNotBlank()) sb.append(it) }
      }
      val text = sb.toString().trim()
      if (text.isNotEmpty() && text != lastSpoken) {
        lastSpoken = text
        ChakaGuideOverlay.update(text.take(160))
        speak(text)
      }
    }
  }

  /** Streams screen frames, but only when the screen actually changed — a still
   *  screen costs no data, which matters on mobile. */
  private fun startFrameLoop(ws: WebSocket) {
    if (frameThread != null) return
    frameThread = Thread {
      var lastSig = ""
      var lastSentAt = 0L
      while (!cancelled && ready) {
        try {
          Thread.sleep(FRAME_MS)
          val dump = runCatching { JSONObject(service.dumpScreen()) }.getOrNull()
          if (dump == null || dump.optString("pkg") == CHAKA_PKG) continue

          val sig = dump.optJSONArray("els")?.toString()?.hashCode()?.toString() ?: ""
          val now = System.currentTimeMillis()
          // Resend at least every ~8s so the model keeps its bearings.
          if (sig == lastSig && now - lastSentAt < 8000L) continue
          lastSig = sig
          lastSentAt = now

          val shot = captureBlocking() ?: continue
          ws.send(
            JSONObject().put(
              "realtimeInput",
              JSONObject().put("video", JSONObject().put("mimeType", "image/jpeg").put("data", shot))
            ).toString()
          )
        } catch (e: InterruptedException) {
          return@Thread
        } catch (e: Exception) {
          Log.e(TAG, "frame loop: ${e.message}")
        }
      }
    }.also { it.isDaemon = true; it.start() }
  }

  private fun captureBlocking(): String? {
    var result: String? = null
    val lock = Object()
    var done = false
    service.captureScreenshot(null) { b64 ->
      synchronized(lock) { result = b64; done = true; lock.notifyAll() }
    }
    synchronized(lock) {
      val deadline = System.currentTimeMillis() + 4000
      while (!done && System.currentTimeMillis() < deadline) {
        runCatching { lock.wait(500) }
      }
    }
    return result
  }

  /** Sends a typed/spoken message from the user into the live session. */
  fun say(text: String) {
    socket?.send(
      JSONObject().put(
        "realtimeInput", JSONObject().put("text", text)
      ).toString()
    )
  }

  private fun executeTool(name: String, args: JSONObject): JSONObject {
    val dump = runCatching { JSONObject(service.dumpScreen()) }.getOrNull()
      ?: return JSONObject().put("error", "couldn't read the screen")

    return when (name) {
      "read_screen" -> {
        val els = dump.optJSONArray("els") ?: JSONArray()
        val sb = StringBuilder()
        for (i in 0 until els.length()) {
          val e = els.optJSONObject(i) ?: continue
          val label = e.optString("text", e.optString("desc", ""))
          if (label.isBlank() && !e.optBoolean("clickable") && !e.optBoolean("editable")) continue
          sb.append("[").append(e.optInt("i")).append("] \"").append(label.take(60)).append("\"")
          if (e.optBoolean("clickable")) sb.append(" tap")
          if (e.optBoolean("editable")) sb.append(" input")
          if (e.optBoolean("toggle")) sb.append(if (e.optBoolean("on")) " ON" else " OFF")
          sb.append("\n")
        }
        JSONObject().put("app", dump.optString("pkg")).put("elements", sb.toString().trim().take(4000))
      }
      "tap_index" -> {
        val idx = args.optInt("index", -1)
        val els = dump.optJSONArray("els") ?: JSONArray()
        var hit: JSONObject? = null
        for (i in 0 until els.length()) {
          val e = els.optJSONObject(i) ?: continue
          if (e.optInt("i") == idx) { hit = e; break }
        }
        if (hit == null) JSONObject().put("ok", false).put("error", "no element [$idx] — call read_screen again")
        else {
          service.tap(hit.optInt("cx"), hit.optInt("cy"))
          JSONObject().put("ok", true).put("tapped", hit.optString("text", hit.optString("desc", "")))
        }
      }
      "type_text" -> {
        val ok = service.typeText(args.optString("text"))
        if (ok) JSONObject().put("ok", true)
        else JSONObject().put("ok", false).put("error", "no field focused — tap the input first")
      }
      "press_enter" -> JSONObject().put("ok", service.pressEnter())
      "swipe" -> {
        val w = dump.optInt("w"); val h = dump.optInt("h")
        val cx = w / 2; val cy = h / 2
        val frac = when (args.optString("amount", "normal")) {
          "tiny" -> 0.09; "long" -> 0.38; else -> 0.16
        }
        val dy = (h * frac).toInt(); val dx = (w * frac).toInt()
        when (args.optString("direction", "down")) {
          "up" -> service.swipe(cx, cy - dy, cx, cy + dy, 300)
          "left" -> service.swipe(cx + dx, cy, cx - dx, cy, 300)
          "right" -> service.swipe(cx - dx, cy, cx + dx, cy, 300)
          else -> service.swipe(cx, cy + dy, cx, cy - dy, 300)
        }
        JSONObject().put("ok", true)
      }
      "press_button" -> JSONObject().put("ok", service.globalAction(args.optString("button", "back")))
      "open_app" -> {
        val pkg = ChakaOperator.appPackage(args.optString("app"))
        if (pkg == null) JSONObject().put("ok", false).put("error", "unknown app")
        else {
          val intent = context.packageManager.getLaunchIntentForPackage(pkg)
          if (intent == null) JSONObject().put("ok", false).put("error", "not installed")
          else {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            JSONObject().put("ok", true)
          }
        }
      }
      "navigate" -> {
        val url = args.optString("url").let { if (it.startsWith("http")) it else "https://$it" }
        context.startActivity(
          Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        JSONObject().put("ok", true)
      }
      else -> JSONObject().put("error", "unknown tool $name")
    }
  }

  fun stop() {
    cancelled = true
    ready = false
    frameThread?.interrupt()
    frameThread = null
    runCatching { socket?.close(1000, "done") }
    socket = null
    shutdownTts()
  }

  private fun initTts() {
    runCatching {
      tts = TextToSpeech(context) { status ->
        if (status == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault()
      }
    }
  }

  private fun speak(text: String) {
    runCatching { tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "chaka-live") }
  }

  private fun shutdownTts() {
    runCatching { tts?.stop(); tts?.shutdown() }
    tts = null
  }
}
