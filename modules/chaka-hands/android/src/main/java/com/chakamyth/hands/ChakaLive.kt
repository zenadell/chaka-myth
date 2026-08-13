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
  // Talk-without-action detection: did this turn actually call a tool, what did
  // she say, and is there an outstanding task she's supposed to be executing?
  @Volatile private var toolCalledThisTurn = false
  @Volatile private var taskActive = false
  @Volatile private var nudges = 0
  // Consecutive completed turns where she took no action at all — used to tell
  // "idle, needs a push" from "genuinely stuck, stop pushing".
  @Volatile private var idleTurns = 0
  // Hard brake on auto-continue. Counting only inactive turns wasn't enough:
  // when she flails (acting, but aimlessly) the counter kept resetting and the
  // loop pushed forever — it once walked into App info and force-stopped Chaka.
  @Volatile private var autoContinues = 0
  private val turnSaid = StringBuilder()
  // Goal-driven drive loop: keeps her working while a task is open, whether or
  // not anyone speaks. Live sessions otherwise only advance on conversation
  // turns, so she'd idle until the user prodded her.
  @Volatile private var lastToolAt = 0L
  // Any sign of life: a tool call, a finished turn, or the user speaking. The
  // drive loop waits on THIS, so it can't cut in while she's mid-reply or a
  // second after the user has spoken.
  @Volatile private var lastActivityAt = 0L
  @Volatile private var readyAt = 0L
  @Volatile private var drives = 0
  @Volatile private var lastDriveAt = 0L
  private var driveThread: Thread? = null
  // Supervisor: the session can die silently — an error payload we don't parse,
  // or a half-open socket OkHttp never reports. The mic keeps recording into a
  // dead connection and everything just goes quiet. This watches liveness
  // independently of the socket callbacks and forces a reconnect.
  private val frameLock = Object()
  @Volatile private var lastFrameAt = 0L
  @Volatile private var lastMsgAt = 0L
  @Volatile private var connectedAt = 0L
  @Volatile private var attempts = 0
  // True when the failure was "no network at all" (e.g. a call suspended data).
  @Volatile private var offline = false
  private var supervisorThread: Thread? = null
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
    private const val FRAME_MS = 1200L
    private const val MIC_RATE = 16000   // required input rate
    // Frames are ~5x the cost of audio on the uplink. Small and throttled.
    private const val LIVE_FRAME_WIDTH = 760
    private const val LIVE_FRAME_QUALITY = 55
    private const val MIN_FRAME_GAP_MS = 1500L
    // Hard floor between drive prods. A screen signature can't be trusted to
    // rate-limit them: anything animated (a recording timer, a video, a
    // spinner) changes every frame, so the loop fired four turns in five
    // seconds and choked the session.
    private const val MIN_DRIVE_GAP_MS = 12000L
    private const val OUT_RATE = 24000   // model's audio output rate
  }

  private val client = OkHttpClient.Builder()
    .readTimeout(0, TimeUnit.MILLISECONDS)  // keep the stream open indefinitely
    // Ping generously: a burst of upstream data shouldn't be mistaken for a
    // dead peer. Real death is detected by the supervisor instead.
    .pingInterval(45, TimeUnit.SECONDS)
    .build()

  fun start(goal: String, apiKey: String, model: String) {
    lastGoal = goal; lastKey = apiKey; lastModel = model
    startSupervisor()
    connect()
  }

  /**
   * Liveness watchdog, modelled on the "wrap the whole session in a retry loop"
   * pattern. Runs for the life of the session, independent of any socket, so a
   * connection that dies quietly still gets rebuilt instead of leaving the mic
   * streaming into nothing.
   */
  private fun startSupervisor() {
    if (supervisorThread != null) return
    supervisorThread = Thread {
      while (!cancelled) {
        try {
          Thread.sleep(3000)
          if (cancelled) break
          if (reconnecting) continue
          val now = System.currentTimeMillis()

          // Connected, but the server has gone completely quiet. Kept short:
          // this is dead air the user is sitting through, and on a phone that
          // can't be touched it's the only thing standing between them and a
          // working assistant.
          if (ready && lastMsgAt > 0 && now - lastMsgAt > 20000) {
            Log.w(TAG, "no server message for ${(now - lastMsgAt) / 1000}s — session is dead")
            reconnect("went quiet")
            continue
          }
          // Socket opened but setup never completed (bad handle, quota, etc).
          if (!ready && connectedAt > 0 && now - connectedAt > 20000) {
            Log.w(TAG, "setup never completed after ${(now - connectedAt) / 1000}s")
            resumeHandle = null  // a stale handle is the usual culprit — start clean
            reconnect("setup timed out")
          }
        } catch (e: InterruptedException) {
          return@Thread
        } catch (e: Exception) {
          Log.e(TAG, "supervisor: ${e.message}")
        }
      }
    }.also { it.isDaemon = true; it.start() }
  }

  private fun connect() {
    val req = Request.Builder().url("$WS?key=$lastKey").build()
    val goal = lastGoal
    val model = lastModel
    socket = client.newWebSocket(req, object : WebSocketListener() {

      override fun onOpen(ws: WebSocket, response: Response) {
        Log.i(TAG, "socket open — sending setup (model=$model)")
        connectedAt = System.currentTimeMillis()
        lastMsgAt = connectedAt
        ws.send(setupMessage(goal, model).toString())
      }

      override fun onMessage(ws: WebSocket, text: String) {
        handleServerMessage(ws, text)
      }

      override fun onMessage(ws: WebSocket, bytes: okio.ByteString) {
        handleServerMessage(ws, bytes.utf8())
      }

      override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
        val why = t.message ?: ""
        Log.e(TAG, "socket failure: $why code=${response?.code}")
        // On 2G/3G a voice call suspends mobile data, so DNS fails outright.
        // Nothing to do but wait for the network to come back — retry calmly
        // instead of hammering it every few seconds.
        offline = why.contains("Unable to resolve host") || why.contains("No address associated")
        if (!cancelled) reconnect(if (offline) "no network" else "connection dropped") else stop()
      }

      override fun onClosed(ws: WebSocket, code: Int, reason: String) {
        Log.i(TAG, "socket closed $code $reason")
        // 1007 (invalid argument) / 1008 (policy) mean the request itself is
        // being rejected — a bad key or exhausted Live quota. Reconnecting just
        // hammers the API and burns more of it, so stop and say so plainly.
        // 1007/1008 = the request itself was rejected. Don't hammer, but don't
        // give up either — on a phone that can't be touched, "stopped forever"
        // is the one outcome we can't accept. Clear any resumption handle and
        // come back with a clean session after a pause.
        if (code == 1007 || code == 1008) {
          Log.e(TAG, "rejected ($code): $reason — clean restart in 30s")
          ChakaGuideOverlay.update("Reconnecting in 30s… ($reason)")
          resumeHandle = null
          attempts = 0
          Thread {
            Thread.sleep(30000)
            if (!cancelled) reconnect("clean restart after $code")
          }.also { it.isDaemon = true }.start()
          return
        }
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
    driveThread?.interrupt(); driveThread = null
    runCatching { recorder?.stop(); recorder?.release() }; recorder = null
    runCatching { player?.pause(); player?.flush() }
    runCatching { socket?.cancel() }  // make sure the dead socket is really gone
    socket = null
    connectedAt = 0
    attempts++
    // A session that dies almost immediately after coming up is the signature of
    // a resumption handle the server won't honour — it accepts setup, then goes
    // quiet or resets. Reusing it just reproduces the failure, so throw it away.
    val diedFast = readyAt > 0 && System.currentTimeMillis() - readyAt < 20000
    if (diedFast || attempts >= 2) {
      if (resumeHandle != null) Log.i(TAG, "dropping resumption handle (diedFast=$diedFast)")
      resumeHandle = null
    }
    readyAt = 0
    // Never give up. This has to survive unattended on a phone whose screen
    // can't be touched, so it keeps retrying with a capped backoff forever.
    val wait = if (offline) 6000L else minOf(800L * attempts, 10000L)
    Log.i(TAG, "reconnecting ($why) attempt=$attempts wait=${wait}ms handle=${resumeHandle?.take(12) ?: "none"}")
    ChakaGuideOverlay.update("Reconnecting…")
    Thread {
      try {
        Thread.sleep(wait)
        if (!cancelled) runCatching { connect() }
      } finally {
        reconnecting = false  // must always clear, or we'd never retry again
      }
    }.also { it.isDaemon = true }.start()
  }

  /** Setup carries the system instruction + tools ONCE for the whole session. */
  private fun setupMessage(goal: String, model: String): JSONObject {
    val sys =
      "You are Chaka, watching your owner's Android screen live and helping in real time. " +
      "You can SEE the screen (frames stream to you) and you can ACT on it with the provided tools.\n" +
      "GOAL / CONTEXT: ${goal.ifBlank { "Assist with whatever is on screen. Ask what they need." }}\n" +
      "\nACT — DO NOT TALK ABOUT ACTING. THIS OVERRIDES EVERYTHING ELSE:\n" +
      "- Saying something is NOT doing it. Words change nothing on the phone; only tool calls do.\n" +
      "- NEVER announce an action before performing it. These phrases are FORBIDDEN: 'I'm opening…', 'I'll tap…', 'I will now…', 'let me…', 'give me a second…', 'I'm going to…'. If you are about to say one, CALL THE TOOL INSTEAD.\n" +
      "- Order is always: call the tool FIRST → see the result → then speak, briefly, about what ALREADY happened (past tense only).\n" +
      "- A task = a chain of tool calls. Keep calling tools back to back until it is finished. Silence from them means CARRY ON.\n" +
      "- NEVER ask permission to continue: no 'shall I go on?', 'would you like me to…?', 'what would you like to do?'. They already told you the task — execute all of it.\n" +
      "- If they had to repeat themselves or tell you to 'do it already', you failed. Never let that happen.\n" +
      "- A task stays OPEN until you call task_done. While it is open you must keep acting on your own — nobody is going to prompt you. Call task_done the moment the screen proves it's finished.\n" +
      "- Words like 'ok', 'go on', 'continue', 'yes' are NOT required and you must never wait for them. Treat every instruction as pre-approved: the moment you know the next step, take it.\n" +
      "- A tool result is not the end of your work — it's the middle. After each one, immediately do the next thing. Ending your turn with the task unfinished and nothing pending is a failure.\n" +
      "- When you call task_done, SAY what you did and anything the user needs to know. Never finish in silence and make them ask 'are you done?'.\n" +
      "- If the connection drops (they may be on a call), pick straight back up when you return — say you're back and resume any unfinished task.\n" +
      "- OBSTACLES ARE YOURS TO CLEAR, not theirs. Permission dialogs (Allow / While using the app), cookie or consent banners, ads, 'Not now', update prompts, rating popups — deal with them yourself the instant they appear: accept what the task needs, dismiss or close anything it doesn't. Never stop and stare at a popup waiting for instructions.\n" +
      "- TYPING ACCURACY: type the EXACT words asked for. After typing, read the field back on screen and confirm it matches; if it's wrong, clear it and retype before searching. Searching for the wrong text wastes far more time than checking.\n" +
      "- Finish the whole intent, not the setup for it. 'Play X' means the song is PLAYING, not that you searched for it. 'Message X' means sent. Keep going until the real outcome is on screen.\n" +
      "- STAY ON THE TASK. Only touch what the task needs. If you don't know what to do next, say so and ask — never wander through Settings, App info, recent apps or unrelated apps hoping to find something. Never force stop, uninstall, clear data, reset, delete or sign out of anything unless the user asked for that exact thing.\n" +
      "\nNEVER CLAIM SOMETHING YOU HAVE NOT VERIFIED:\n" +
      "- Do not say an app is open, a photo is tapped, or a message is sent unless the CURRENT screen proves it. Check read_screen or the latest frame first.\n" +
      "- If the screen shows something different from what you expected, say so plainly and fix it. Do not insist it worked.\n" +
      "- Typing is not sending. After typing you MUST tap the send button (or press_enter) and then confirm it actually sent.\n" +
      "- If a reply appears on screen, read it out straight away and continue — don't wait to be asked what it said.\n" +
      "- Only stop when the goal is done, they tell you to stop, or you're genuinely blocked. Only ask when it's truly ambiguous and you cannot reasonably choose (e.g. which of two accounts is theirs).\n" +
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
      .put(fn(
        "task_done",
        "Call this ONLY when the current task is fully finished and the screen proves it. Until you call this, you are still working and must keep acting.",
        props("summary", "string", "What was accomplished, in one short sentence"),
        listOf("summary")
      ))

    val setup = JSONObject()
      .put("model", "models/$model")
      .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sys))))
      .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", decls)))
      // Audio+video sessions are capped at ~2 MINUTES without this — a sliding
      // context window removes the duration limit entirely. This is why Live
      // Mode was dying mid-task.
      // Without this, audio+video sessions are capped at ~2 minutes — which is
      // exactly how long every session was lasting. int64 fields must be sent as
      // STRINGS in Google's proto-JSON mapping; passing a number made the whole
      // block invalid, so the cap kept applying (and likely caused the 1007s).
      .put(
        "contextWindowCompression",
        JSONObject()
          .put("triggerTokens", "16000")
          .put("slidingWindow", JSONObject().put("targetTokens", "8000"))
      )
      // Ask for resumption handles so a dropped connection can be picked up
      // where it left off instead of starting over.
      .put("sessionResumption", JSONObject().apply {
        resumeHandle?.let { put("handle", it) }
      })
      // Transcripts of both sides. We need HER words to detect the failure mode
      // where she announces an action ("I'll open the gallery") and never calls
      // the tool — see checkTurn().
      .put("outputAudioTranscription", JSONObject())
      .put("inputAudioTranscription", JSONObject())
      // Voice-activity detection tuning. On default sensitivity, room noise and
      // her own voice leaking into the mic kept registering as the user barging
      // in — turns were cut off constantly and often ended with nothing said.
      // Low sensitivity + a longer silence window means she only yields when
      // someone is genuinely talking to her.
      .put(
        "realtimeInputConfig",
        JSONObject().put(
          "automaticActivityDetection",
          JSONObject()
            .put("startOfSpeechSensitivity", "START_SENSITIVITY_LOW")
            .put("endOfSpeechSensitivity", "END_SENSITIVITY_LOW")
            .put("prefixPaddingMs", 300)
            .put("silenceDurationMs", 900)
        )
      )

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
    lastMsgAt = System.currentTimeMillis()  // proof the session is still alive
    val msg = runCatching { JSONObject(raw) }.getOrNull() ?: return

    // Errors used to be silently ignored, which is how a dead session looked
    // identical to a quiet one.
    if (msg.has("error")) {
      val err = msg.optJSONObject("error")
      val code = err?.optInt("code") ?: 0
      val message = err?.optString("message") ?: raw.take(160)
      Log.e(TAG, "server error $code: $message")
      ChakaGuideOverlay.update("Live error: ${message.take(120)}")
      // 401/403 = bad key: retrying just burns quota. Anything else, rebuild.
      if (code == 401 || code == 403) stop() else reconnect("server error $code")
      return
    }

    if (msg.has("setupComplete")) {
      Log.i(TAG, "setup complete — starting audio + frames")
      ready = true
      if (offline) {
        offline = false
        sendText(ws, "[SYSTEM] You just came back online after losing connection (the user may have been on a call). Greet them briefly, tell them you're back, and if a task was still unfinished, resume it now.")
      }
      drives = 0
      lastDriveAt = System.currentTimeMillis()
      readyAt = System.currentTimeMillis()
      lastActivityAt = readyAt
      attempts = 0  // healthy again
      ChakaGuideOverlay.update("Live — talk to me")
      // Route audio through the communication path so echo cancellation works.
      runCatching {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.mode = AudioManager.MODE_IN_COMMUNICATION
      }
      startPlayer()
      startMic(ws)
      startFrameLoop(ws)
      startDriveLoop(ws)
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
        toolCalledThisTurn = true
        nudges = 0
        drives = 0
        lastToolAt = System.currentTimeMillis()
        lastActivityAt = lastToolAt
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
        if (!cancelled && ready) sendFrame(ws)
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

      // The user said something → there's a live instruction to execute.
      content.optJSONObject("inputTranscription")?.optString("text")
        ?.takeIf { it.isNotBlank() }?.let {
          taskActive = true
          nudges = 0
          drives = 0
          idleTurns = 0
          autoContinues = 0
          // Critical: give her room to answer. Without this the drive loop fired
          // a [SYSTEM] turn on top of the user's turn and the session wedged.
          lastActivityAt = System.currentTimeMillis()
          Log.i(TAG, "user: $it")
        }

      // Accumulate what SHE said this turn, so we can tell talk from action.
      content.optJSONObject("outputTranscription")?.optString("text")
        ?.takeIf { it.isNotBlank() }?.let { turnSaid.append(it) }

      if (content.optBoolean("turnComplete", false)) {
        val said = turnSaid.toString().trim()
        turnSaid.setLength(0)
        Log.i(TAG, "turnComplete said=\"${said.take(90)}\" tool=$toolCalledThisTurn")
        if (said.isNotEmpty()) ChakaGuideOverlay.update(said.take(160))
        val actedThisTurn = toolCalledThisTurn
        toolCalledThisTurn = false
        lastActivityAt = System.currentTimeMillis()
        // She finished a turn without touching the phone. If she promised to do
        // something, push her to actually do it — otherwise the session just
        // stalls until the user shouts, which is the whole complaint.
        if (!actedThisTurn && taskActive && promisedAction(said)) {
          checkTurn(ws, said)
        } else if (taskActive) {
          // The heart of it: she ends her turn after ONE action and yields, so
          // every step used to cost the user an 8s wait or a shout. A turn
          // ending with the task still open is the trigger to carry straight on.
          autoContinue(ws, actedThisTurn)
        }
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

  /**
   * Carries the task forward the moment a turn ends, instead of leaving the
   * user to say "continue" after every single action.
   *
   * One continue per completed turn — it can't burst, because each continue
   * produces exactly one more turn. If several turns pass with no tool call at
   * all she's genuinely stuck rather than merely idle, so it stands down and
   * lets the slower drive loop take over.
   */
  private fun autoContinue(ws: WebSocket, actedThisTurn: Boolean) {
    if (actedThisTurn) idleTurns = 0 else idleTurns++
    if (idleTurns >= 3) {
      Log.i(TAG, "auto-continue standing down after $idleTurns turns with no action")
      return
    }
    // Even while she's acting, a task can't legitimately need dozens of pushes.
    // Past this she's lost, and continuing to prod makes her flail.
    autoContinues++
    if (autoContinues > 12) {
      Log.w(TAG, "auto-continue cap hit ($autoContinues) — standing down, asking for direction")
      taskActive = false
      sendText(
        ws,
        "[SYSTEM] You have taken many steps without finishing. STOP acting now. " +
          "Tell the user plainly where you got to, what is blocking you, and ask what they want to do next."
      )
      return
    }
    Thread {
      Thread.sleep(600)  // let the screen settle after the last action
      if (cancelled || !ready || !taskActive) return@Thread
      sendFrame(ws)
      sendText(
        ws,
        "[SYSTEM] Task still open. Continue NOW with the next action — do not wait to be told. " +
          "Handle anything in the way yourself (permission dialogs, ads, popups: accept what the task needs, dismiss what it doesn't). " +
          "When it is genuinely finished, call task_done and say what you did."
      )
    }.also { it.isDaemon = true }.start()
  }

  /**
   * True when she described doing something rather than reporting it done —
   * "I'll open…", "let me tap…", "I'm going to…". Past tense ("I opened",
   * "it's open now") is a report, not a promise, so it isn't flagged.
   */
  private fun promisedAction(said: String): Boolean {
    val s = said.lowercase()
    if (s.isBlank()) return false
    val promises = listOf(
      "i'll ", "i will ", "i'm going to", "im going to", "going to ",
      "let me ", "i'm opening", "im opening", "i'm tapping", "im tapping",
      "i'm typing", "im typing", "i'm sending", "im sending", "one moment",
      "give me a", "hold on", "just a sec", "now i", "next i", "i'm about to"
    )
    // Asking a question mid-task is the same failure: it stalls instead of acting.
    val stalls = listOf("would you like", "shall i", "do you want me", "should i")
    return promises.any { s.contains(it) } || stalls.any { s.contains(it) }
  }

  /**
   * She talked instead of acting. Send the live screen back with a blunt
   * correction so the loop keeps moving without the user having to repeat
   * themselves. Capped so it can't turn into a nagging loop.
   */
  private fun checkTurn(ws: WebSocket, said: String) {
    if (nudges >= 3) { Log.i(TAG, "nudge cap reached — leaving it to the user"); return }
    nudges++
    Thread {
      Thread.sleep(400)
      if (cancelled || !ready) return@Thread
      Log.i(TAG, "NUDGE $nudges — talked without acting: \"${said.take(70)}\"")
      runCatching {
        sendFrame(ws, force = true)
        sendText(
          ws,
          "[SYSTEM] You just spoke without calling any tool, so NOTHING happened on the phone. " +
            "The screen above is the real current state. Do not reply with words. " +
            "Call the tool that performs the next step RIGHT NOW, then keep calling tools until the task is done."
        )
      }
    }.also { it.isDaemon = true }.start()
  }

  /**
   * The autonomous drive loop — the difference between a chat that happens to
   * see the screen and an agent that finishes things.
   *
   * A Live session only advances on conversation turns, so if she stops acting
   * nothing wakes her up and the user has to prod her. This ticks independently:
   * while a task is open (no task_done yet) and she has gone quiet without
   * acting, it pushes the current screen back with a directive to continue.
   * Stops pushing when nothing is changing, so it can't nag forever.
   */
  private fun startDriveLoop(ws: WebSocket) {
    if (driveThread != null) return
    driveThread = Thread {
      while (!cancelled && ready) {
        try {
          Thread.sleep(1500)
          if (!taskActive) continue
          // Only step in once she's genuinely stalled — not between her own
          // actions, and never straight after the user has spoken.
          if (System.currentTimeMillis() - lastActivityAt < 8000) continue
          // Never stop pushing while a task is open — going quiet and waiting
          // to be pressured is the exact behaviour we're eliminating. The 12s
          // floor keeps it civil; the ceiling only exists to catch a task that
          // was never closed out (~10 minutes of prodding).
          if (drives >= 50) continue
          // Never prod twice in quick succession, whatever the screen is doing.
          val now2 = System.currentTimeMillis()
          if (now2 - lastDriveAt < MIN_DRIVE_GAP_MS) continue
          lastDriveAt = now2
          drives++
          Log.i(TAG, "DRIVE $drives — task open, idle ${(System.currentTimeMillis() - lastActivityAt) / 1000}s")

          sendFrame(ws, force = true)
          sendText(
            ws,
            "[SYSTEM] The task is still open and you have stopped acting. This is the live screen. " +
              "Do not reply with words. Either call the next tool to move the task forward, " +
              "or call task_done if the screen proves it is finished."
          )
        } catch (e: InterruptedException) {
          return@Thread
        } catch (e: Exception) {
          Log.e(TAG, "drive loop: ${e.message}")
        }
      }
    }.also { it.isDaemon = true; it.start() }
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

          if (!sendFrame(ws)) continue
        } catch (e: InterruptedException) {
          return@Thread
        } catch (e: Exception) {
          Log.e(TAG, "frame loop: ${e.message}")
        }
      }
    }.also { it.isDaemon = true; it.start() }
  }

  /**
   * The ONE place frames go out. Everything that wants to show her the screen
   * comes through here so the uplink can't be flooded.
   *
   * This was the cause of sessions dying: three independent senders (the frame
   * loop, one after every tool call, one per drive tick) each pushing a ~250KB
   * base64 JPEG. That saturated the uplink, pings couldn't get through inside
   * their 20s window, and OkHttp declared the socket dead. Frames now cost
   * roughly a fifth as much and are rate-limited.
   */
  /**
   * Sends a text turn. This MUST be clientContent, not realtimeInput — the
   * latter is for streaming media only, and pushing text through it is what was
   * killing sessions: every death in the logs followed a drive/nudge, which
   * were the only places text was sent. The server answered with close 1007
   * ("invalid argument") or simply stopped responding.
   */
  private fun sendText(ws: WebSocket, text: String): Boolean = runCatching {
    ws.send(
      JSONObject().put(
        "clientContent",
        JSONObject()
          .put(
            "turns",
            JSONArray().put(
              JSONObject()
                .put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", text)))
            )
          )
          .put("turnComplete", true)
      ).toString()
    )
  }.getOrDefault(false)

  private fun sendFrame(ws: WebSocket, force: Boolean = false): Boolean {
    val now = System.currentTimeMillis()
    synchronized(frameLock) {
      if (!force && now - lastFrameAt < MIN_FRAME_GAP_MS) return false
      lastFrameAt = now
    }
    val shot = captureBlocking() ?: return false
    return runCatching {
      ws.send(
        JSONObject().put(
          "realtimeInput",
          JSONObject().put("video", JSONObject().put("mimeType", "image/jpeg").put("data", shot))
        ).toString()
      )
    }.getOrDefault(false)
  }

  private fun captureBlocking(): String? {
    var result: String? = null
    val lock = Object()
    var done = false
    // Small + cheap: she needs to recognise the screen, not read fine print, and
    // read_screen gives her exact labels anyway.
    service.captureScreenshot(null, LIVE_FRAME_WIDTH, LIVE_FRAME_QUALITY) { b64 ->
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
    val ws = socket ?: return
    taskActive = true
    nudges = 0
    sendText(ws, text)
  }

  /** Label of element [i] on the current screen, if present. */
  private fun elementLabel(dump: JSONObject, i: Int): String? {
    val els = dump.optJSONArray("els") ?: return null
    for (k in 0 until els.length()) {
      val e = els.optJSONObject(k) ?: continue
      if (e.optInt("i") == i) return e.optString("text", e.optString("desc", ""))
    }
    return null
  }

  /**
   * Controls that can destroy data or disable the assistant. These are never
   * safe to hit on the model's own initiative — only when the user asked for
   * that specific thing, which they can confirm on the system's own dialog.
   */
  private fun isDestructive(label: String): Boolean {
    val l = label.lowercase().trim()
    return listOf(
      "force stop", "uninstall", "clear data", "clear storage", "clear cache",
      "factory reset", "reset all settings", "erase all data", "delete account",
      "remove account", "format", "wipe", "disable", "deactivate", "log out",
      "sign out", "delete all"
    ).any { l.contains(it) }
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
        // Safety rail. A runaway continue-loop once walked into App info and
        // force-stopped Chaka herself. Controls that destroy data or disable
        // things are never worth an autonomous guess — the user must ask.
        elementLabel(dump, idx)?.let { label ->
          if (isDestructive(label)) {
            Log.w(TAG, "BLOCKED destructive tap: \"$label\"")
            return JSONObject()
              .put("ok", false)
              .put("blocked", true)
              .put(
                "error",
                "\"$label\" is a destructive control and is blocked unless the user explicitly asked for it. " +
                  "Do not try to reach it another way. Say what you were about to do and ask them."
              )
          }
        }
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
      "task_done" -> {
        // She's declared completion, so the drive loop stops pushing her.
        taskActive = false
        drives = 0
        autoContinues = 0
        Log.i(TAG, "task_done: ${args.optString("summary")}")
        ChakaGuideOverlay.update("✓ ${args.optString("summary").take(140)}")
        // She used to fall silent here and the user had to ask "are you done?".
        // The result carries the instruction to actually report back.
        JSONObject()
          .put("ok", true)
          .put(
            "next",
            "Now SAY OUT LOUD, in one short natural sentence, what you finished and anything the user needs to know from the screen. Do not stay silent."
          )
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
    supervisorThread?.interrupt(); supervisorThread = null
    frameThread?.interrupt(); frameThread = null
    driveThread?.interrupt(); driveThread = null
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
