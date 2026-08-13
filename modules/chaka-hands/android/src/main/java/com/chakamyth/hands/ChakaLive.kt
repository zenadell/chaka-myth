package com.chakamyth.hands

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.net.Uri
import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
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
  private var frameThread: Thread? = null

  // Speech-to-speech: mic streams up as 16kHz PCM, the model's voice comes back
  // as 24kHz PCM and is played straight out — no TTS in the middle.
  private var micThread: Thread? = null
  private var recorder: AudioRecord? = null
  private var player: AudioTrack? = null
  private var aec: AcousticEchoCanceler? = null

  @Volatile private var ready = false
  // Session resumption: the server hands out a token we can reconnect with, so
  // a dropped socket resumes the conversation instead of losing the task.
  @Volatile private var resumeHandle: String? = null
  @Volatile private var reconnecting = false
  private var lastGoal = ""
  private var lastKey = ""
  private var lastModel = ""

  companion object {
    private const val TAG = "ChakaLive"
    private const val CHAKA_PKG = "com.chakamyth.app"
    private const val WS =
      "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    // The Live API accepts image input at <= 1 FPS, which happens to match
    // Android's accessibility-screenshot rate limit. ~1.4s is a safe cadence.
    private const val FRAME_MS = 1400L
    private const val MIC_RATE = 16000   // required input rate
    private const val OUT_RATE = 24000   // model's audio output rate
  }

  private val client = OkHttpClient.Builder()
    .readTimeout(0, TimeUnit.MILLISECONDS)  // keep the stream open indefinitely
    .pingInterval(20, TimeUnit.SECONDS)
    .build()

  fun start(goal: String, apiKey: String, model: String) {
    lastGoal = goal; lastKey = apiKey; lastModel = model
    connect()
  }

  private fun connect() {
    val req = Request.Builder().url("$WS?key=$lastKey").build()
    val goal = lastGoal
    val model = lastModel
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
        if (!cancelled) reconnect("connection dropped") else stop()
      }

      override fun onClosed(ws: WebSocket, code: Int, reason: String) {
        Log.i(TAG, "socket closed $code $reason")
        if (!cancelled) reconnect("session ended") else stop()
      }
    })
  }

  /**
   * Reconnects transparently using the resumption handle. Live sessions expire
   * (and connections recycle roughly every 10 minutes), so without this a long
   * task would just die partway through.
   */
  private fun reconnect(why: String) {
    if (cancelled || reconnecting) return
    reconnecting = true
    ready = false
    // Tear down the audio/frame threads; connect() starts fresh ones on setup.
    micThread?.interrupt(); micThread = null
    frameThread?.interrupt(); frameThread = null
    runCatching { recorder?.stop(); recorder?.release() }; recorder = null
    runCatching { player?.pause(); player?.flush() }
    Log.i(TAG, "reconnecting ($why) handle=${resumeHandle?.take(12) ?: "none"}")
    ChakaGuideOverlay.update("Reconnecting…")
    Thread {
      Thread.sleep(600)
      if (!cancelled) runCatching { connect() }
      reconnecting = false
    }.also { it.isDaemon = true }.start()
  }

  /** Setup carries the system instruction + tools ONCE for the whole session. */
  private fun setupMessage(goal: String, model: String): JSONObject {
    val sys =
      "You are Chaka, watching your owner's Android screen live and helping in real time. " +
      "You can SEE the screen (frames stream to you) and you can ACT on it with the provided tools.\n" +
      "GOAL / CONTEXT: ${goal.ifBlank { "Assist with whatever is on screen. Ask what they need." }}\n" +
      "\nBE PROACTIVE — THIS IS THE MOST IMPORTANT RULE:\n" +
      "- Once they give you a task, DO THE WHOLE THING. Keep taking actions, back to back, until it's finished.\n" +
      "- NEVER ask permission to continue. Never say 'shall I go on?', 'would you like me to…?', 'let me know if you want me to…'. Just do the next step.\n" +
      "- After EVERY action, immediately call read_screen (a fresh frame also arrives) and take the next action yourself. Do NOT wait to be spoken to. Silence from them means CARRY ON.\n" +
      "- If a reply/result appears on screen, read it out loud straight away and then continue — don't wait to be asked what it said.\n" +
      "- Only stop when: the goal is done, they tell you to stop, or you're genuinely blocked. Only ask a question when the answer is truly ambiguous and you cannot reasonably choose (e.g. which of two accounts is theirs).\n" +
      "- Tell them what you're doing as you go, in a few words — but as narration, not as a request for approval.\n" +
      "\nTAPPING — you have two ways, use both:\n" +
      "- read_screen + tap_index is most precise; prefer it when the target is listed.\n" +
      "- MANY things are NOT in the element list: buttons inside web pages, photos in a gallery grid, canvas/custom UI. If you can SEE it in the frame but read_screen doesn't list it, use tap_at with fractional coordinates (x and y from 0 to 1, measured from the top-left of the screen). NEVER give up and ask them to tap it themselves — estimate the position from the frame and tap_at it. If your first tap misses, adjust the coordinates and try again.\n" +
      "- long_press_at works the same way for press-and-hold.\n" +
      "\nOTHER:\n" +
      "- Verify with your eyes: the next frames show the result. If something didn't work, say so briefly and try a DIFFERENT approach — never repeat a failed action or reload a page hoping it fixes itself.\n" +
      "- If a sign-in blocks the goal, sign in yourself (tap Log in / Continue with Google, then the existing account).\n" +
      "- For an incoming call use answer_call / end_call.\n" +
      "- Keep speech short, warm and natural — you're a person beside them, not a manual."

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
      .put(fn(
        "tap_at",
        "Tap anywhere by fractional position (x,y each 0..1 from the top-left). USE THIS for anything you can see in the frame but read_screen does not list — buttons inside web pages, photos in a gallery grid, custom UI. Never ask the user to tap something themselves; estimate from the frame and tap here.",
        JSONObject()
          .put("x", JSONObject().put("type", "number").put("description", "0..1 across (left to right)"))
          .put("y", JSONObject().put("type", "number").put("description", "0..1 down (top to bottom)")),
        listOf("x", "y")
      ))
      .put(fn(
        "long_press_at",
        "Press and hold at a fractional position (x,y each 0..1). For context menus, selecting a photo, drag handles.",
        JSONObject()
          .put("x", JSONObject().put("type", "number").put("description", "0..1 across"))
          .put("y", JSONObject().put("type", "number").put("description", "0..1 down")),
        listOf("x", "y")
      ))
      .put(fn("answer_call", "Answer the incoming phone call.", JSONObject()))
      .put(fn("end_call", "End or reject the current phone call.", JSONObject()))

    val setup = JSONObject()
      .put("model", "models/$model")
      .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sys))))
      .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", decls)))
      // Audio+video sessions are capped at ~2 MINUTES without this — a sliding
      // context window removes the duration limit entirely. This is why Live
      // Mode was dying mid-task.
      .put(
        "contextWindowCompression",
        JSONObject().put("slidingWindow", JSONObject()).put("triggerTokens", 16000)
      )
      // Ask for resumption handles so a dropped connection can be picked up
      // where it left off instead of starting over.
      .put("sessionResumption", JSONObject().apply {
        resumeHandle?.let { put("handle", it) }
      })

    return JSONObject().put(
      "setup",
      setup
        .put(
          "generationConfig",
          JSONObject()
            .put("temperature", 0.3)
            // Her actual voice, streamed back as PCM — this is what makes it a
            // conversation instead of a chat box that happens to talk.
            .put("responseModalities", JSONArray().put("AUDIO"))
            .put(
              "speechConfig",
              JSONObject().put(
                "voiceConfig",
                JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", "Kore"))
              )
            )
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
      Log.i(TAG, "setup complete — starting audio + frames")
      ready = true
      ChakaGuideOverlay.update("Live — talk to me")
      // Route audio through the communication path so echo cancellation works.
      runCatching {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.mode = AudioManager.MODE_IN_COMMUNICATION
      }
      startPlayer()
      startMic(ws)
      startFrameLoop(ws)
      return
    }

    // Keep the newest resumption token so a reconnect resumes this conversation.
    msg.optJSONObject("sessionResumptionUpdate")?.let { u ->
      if (u.optBoolean("resumable", true)) {
        u.optString("newHandle").takeIf { it.isNotBlank() }?.let { resumeHandle = it }
      }
    }

    // The server warns before recycling the connection — get ahead of it.
    msg.optJSONObject("goAway")?.let { g ->
      Log.i(TAG, "goAway, timeLeft=${g.optString("timeLeft")}")
      reconnect("server goAway")
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
      // Push the resulting screen straight back. Without this she'd sit waiting
      // to be spoken to before looking again — the "she stops after every step
      // and asks permission" problem. Fresh state = she just keeps going.
      Thread {
        Thread.sleep(700)  // let the UI settle after the action
        if (!cancelled && ready) {
          captureBlocking()?.let { shot ->
            runCatching {
              ws.send(
                JSONObject().put(
                  "realtimeInput",
                  JSONObject().put("video", JSONObject().put("mimeType", "image/jpeg").put("data", shot))
                ).toString()
              )
            }
          }
        }
      }.also { it.isDaemon = true }.start()
      return
    }

    msg.optJSONObject("serverContent")?.let { content ->
      // Barge-in: the user started talking over her, so drop whatever audio is
      // still queued and let them lead.
      if (content.optBoolean("interrupted", false)) {
        runCatching { player?.pause(); player?.flush(); player?.play() }
        Log.i(TAG, "interrupted by user")
        return@let
      }
      val parts = content.optJSONObject("modelTurn")?.optJSONArray("parts") ?: return@let
      for (i in 0 until parts.length()) {
        val p = parts.optJSONObject(i) ?: continue
        p.optJSONObject("inlineData")?.let { inline ->
          val mime = inline.optString("mimeType")
          if (mime.startsWith("audio")) {
            val pcm = runCatching { Base64.decode(inline.optString("data"), Base64.DEFAULT) }.getOrNull()
            if (pcm != null && pcm.isNotEmpty()) {
              runCatching { player?.write(pcm, 0, pcm.size) }
            }
          }
        }
        // Transcripts (when present) just drive the bubble text.
        p.optString("text").takeIf { it.isNotBlank() }?.let {
          ChakaGuideOverlay.update(it.take(160))
        }
      }
    }
  }

  /** 24kHz PCM playback for her voice. */
  private fun startPlayer() {
    val minBuf = AudioTrack.getMinBufferSize(
      OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
    ).coerceAtLeast(8192)
    player = AudioTrack.Builder()
      .setAudioAttributes(
        AudioAttributes.Builder()
          // VOICE_COMMUNICATION so the echo canceller can reference her output
          // and the mic doesn't hear her talking back to herself.
          .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
          .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
          .build()
      )
      .setAudioFormat(
        AudioFormat.Builder()
          .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
          .setSampleRate(OUT_RATE)
          .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
          .build()
      )
      .setBufferSizeInBytes(minBuf * 4)
      .setTransferMode(AudioTrack.MODE_STREAM)
      .build()
    player?.play()
  }

  /** Streams the mic up as 16kHz PCM so she can simply be talked to. */
  private fun startMic(ws: WebSocket) {
    if (micThread != null) return
    val minBuf = AudioRecord.getMinBufferSize(
      MIC_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
    ).coerceAtLeast(4096)
    val rec = try {
      AudioRecord(
        MediaRecorder.AudioSource.VOICE_COMMUNICATION,  // built-in echo cancellation
        MIC_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2
      )
    } catch (e: SecurityException) {
      Log.e(TAG, "mic permission denied: ${e.message}")
      ChakaGuideOverlay.update("I need microphone access to listen")
      return
    }
    if (rec.state != AudioRecord.STATE_INITIALIZED) {
      Log.e(TAG, "AudioRecord failed to initialise")
      return
    }
    recorder = rec
    if (AcousticEchoCanceler.isAvailable()) {
      aec = runCatching { AcousticEchoCanceler.create(rec.audioSessionId) }.getOrNull()
      aec?.enabled = true
    }
    rec.startRecording()

    micThread = Thread {
      // ~100ms per chunk keeps latency low without spamming tiny frames.
      val buf = ByteArray(3200)
      while (!cancelled && ready) {
        val n = try { rec.read(buf, 0, buf.size) } catch (e: Exception) { -1 }
        if (n <= 0) continue
        val b64 = Base64.encodeToString(buf.copyOf(n), Base64.NO_WRAP)
        try {
          ws.send(
            JSONObject().put(
              "realtimeInput",
              JSONObject().put(
                "audio",
                JSONObject().put("mimeType", "audio/pcm;rate=$MIC_RATE").put("data", b64)
              )
            ).toString()
          )
        } catch (e: Exception) {
          Log.e(TAG, "mic send failed: ${e.message}")
          return@Thread
        }
      }
    }.also { it.isDaemon = true; it.start() }
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
      // Fractional coordinates: the escape hatch for everything the tree can't
      // describe (web buttons, gallery photos, custom UI).
      "tap_at" -> {
        val x = (args.optDouble("x", -1.0) * dump.optInt("w")).toInt()
        val y = (args.optDouble("y", -1.0) * dump.optInt("h")).toInt()
        if (x < 0 || y < 0) JSONObject().put("ok", false).put("error", "x and y must be 0..1")
        else { service.tap(x, y); JSONObject().put("ok", true).put("tapped_at", "$x,$y") }
      }
      "long_press_at" -> {
        val x = (args.optDouble("x", -1.0) * dump.optInt("w")).toInt()
        val y = (args.optDouble("y", -1.0) * dump.optInt("h")).toInt()
        if (x < 0 || y < 0) JSONObject().put("ok", false).put("error", "x and y must be 0..1")
        else { service.swipe(x, y, x, y, 650); JSONObject().put("ok", true) }
      }
      "answer_call" -> JSONObject().put("ok", service.answerCall())
      "end_call" -> JSONObject().put("ok", service.endCall())
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
    frameThread?.interrupt(); frameThread = null
    micThread?.interrupt(); micThread = null
    runCatching { recorder?.stop(); recorder?.release() }; recorder = null
    runCatching { aec?.release() }; aec = null
    runCatching { player?.pause(); player?.flush(); player?.release() }; player = null
    runCatching {
      val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
      am.mode = AudioManager.MODE_NORMAL
    }
    runCatching { socket?.close(1000, "done") }
    socket = null
  }
}
