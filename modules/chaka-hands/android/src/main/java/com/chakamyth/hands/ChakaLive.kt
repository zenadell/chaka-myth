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
  // A second listener on the same microphone, whose only job is to confirm a
  // HUMAN spoke. See ChakaEars — this is the owner's noisy-market design.
  private val ears by lazy { ChakaEars(context) }

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
  // WHICH screen the last delivered frame actually showed. This is the whole
  // basis of "look before you act": the native side knows what it has put in
  // front of her, so an action can be gated on her having been shown THIS
  // screen rather than on her claiming she looked.
  @Volatile private var lastFrameSig = ""
  // Frames streamed since the session came up — proof for the log that she has
  // vision at all, which for a long time she silently did not.
  @Volatile private var framesSent = 0
  // When text last went up the realtime stream. Only here so that if the
  // server ever rejects that message the log says so outright, instead of
  // leaving the next person to guess the way the last one guessed wrong.
  @Volatile private var lastTextSentAt = 0L
  @Volatile private var lastMsgAt = 0L
  @Volatile private var connectedAt = 0L
  @Volatile private var attempts = 0
  // True when the failure was "no network at all" (e.g. a call suspended data).
  @Volatile private var offline = false
  // True while her audio is playing. The mic is muted during that window, which
  // is how a working implementation avoids hearing itself — far safer than
  // desensitising VAD, which stopped turns committing at all.
  @Volatile private var speaking = false
  // Set when she calls look_at_screen; the image is injected right after that
  // tool's response rather than streamed continuously.
  @Volatile private var pendingLook = false
  // Repeat detection for actions, so a wrong move can't be repeated blindly.
  @Volatile private var lastActionSig = ""
  @Volatile private var sameActionRepeats = 0
  // task_done is gated: the first call triggers a look at the real screen, and
  // only a second call - made after seeing it - is accepted. She had been
  // declaring victory with no evidence at all.
  @Volatile private var awaitingDoneProof = false
  @Volatile private var doneProofAt = 0L
  // Guards against looking at the same screen over and over. Each injected
  // image ends a turn, which makes her respond - and if she responds by asking
  // to look again, that is a self-sustaining loop that also drowns out the user.
  // Consecutive refused actions. A block she ignores is worse than no block -
  // she hammered the same blocked tap eleven times in a row.
  // Half-duplex gate. Her own voice was leaking into the mic and the VAD kept
  // scoring it as the user barging in - "interrupted by user" fired about once
  // a second and EVERY turn ended with said="", so she never finished a
  // sentence or a thought. Hardware AEC isn't reliable enough here, so the mic
  // simply stops uploading while she is speaking.
  // Hard stop. "Stop" must not depend on the model choosing to co-operate -
  // it ignored spoken stops and carried on with things the user didn't want.
  // This halts locally: every action is refused until they speak again.
  @Volatile private var halted = false
  @Volatile private var haltedAt = 0L
  @Volatile private var lastAudioOutAt = 0L
  // A turn that was cut off isn't a turn she chose to end - pushing her to
  // "continue" after one just makes her act without having finished thinking.
  @Volatile private var interruptedThisTurn = false
  // What she predicted the current action would do, and whether the last action
  // actually verified — step_done is refused until something has been checked.
  // Provably-false-claim detection. Gemini will state it looked at the screen,
  // or that it performed an action, without having called the tool at all. The
  // native side knows the truth, so the claim can be checked rather than
  // trusted - the user hit this repeatedly ("she says the camera is on when it
  // is off, three times before it actually happens").
  // A correction the user speaks mid-task must land BEFORE the next action.
  // She was tapping "Continue with Google" a second after being told to go
  // back, then obeying five seconds later.
  @Volatile private var pendingUserWord = ""
  @Volatile private var lastRealLookAt = 0L
  @Volatile private var lastCorrectionAt = 0L
  @Volatile private var lastScreenReadAt = 0L
  // How many actions in a row produced no real change. If nothing has moved,
  // the task cannot be finished, whatever she believes.
  @Volatile private var lastHoldDragAt = 0L
  // A hunt: consecutive swipes all looking for the same thing. The loop guard
  // cannot see this, because every swipe genuinely changes the screen, so each
  // (screen, action) pair is new and nothing ever repeats. Meanwhile she can
  // scroll past the target over and over — 28 times, measured, with the row in
  // the middle of the screen the whole while.
  // The last thing a search actually located, in real screen pixels. She was
  // handed "164,1429" and tapped x=0.869 y=0.992 — a fraction, at the very
  // bottom edge of the screen, 158px below the row OCR had measured for her.
  // Asking a model to transcribe coordinates it was just given is a step that
  // can fail, so it is removed: tap_found uses these directly.
  // How many times the SAME target has been located with nothing done about it
  // in between. There was a guard for searching and failing, and none for
  // searching and succeeding — so she found "USB debugging" roughly forty times
  // in forty seconds, each one a round trip, and never once acted on it.
  // Set the moment a search succeeds. A video frame opens no turn, so pushing
  // the picture alone gave her something to look at and no reason to look — she
  // found the row and went silent in front of it. This is the prompt that goes
  // out WITH the picture and forces her to say what she sees and decide.
  @Volatile private var pendingDecision = ""
  @Volatile private var lastFoundAt = 0L
  @Volatile private var foundRepeats = 0
  @Volatile private var foundLabel = ""
  @Volatile private var foundRowX = -1
  @Volatile private var foundRowY = -1
  @Volatile private var foundSwitchX = -1
  @Volatile private var foundSwitchY = -1
  @Volatile private var huntFor = ""
  @Volatile private var huntSwipes = 0
  // Direction, and how often she has changed her mind about it. Raw swipe count
  // is the wrong thing to punish: Developer options is long, and reaching
  // Wireless debugging honestly takes eight or ten swipes in one direction.
  // Blocking at four wedged her against a list she simply had not finished
  // scrolling. Turning round and round is the failure — down, up, down, up.
  @Volatile private var huntDir = ""
  @Volatile private var huntReversals = 0
  @Volatile private var noProgressRun = 0
  @Volatile private var pendingExpect = ""
  @Volatile private var lastVerified = false
  @Volatile private var consecutiveBlocks = 0
  @Volatile private var lastLookSig = ""
  @Volatile private var lastLookAt = 0L
  // What the user has said recently. The destructive-action rail consults this
  // so it blocks a runaway agent without blocking the user's own instruction.
  private val recentSpeech = StringBuilder()
  // THE request currently being worked on. Held separately from conversation
  // history, because history was letting her drift back into an older task -
  // asked to move an icon, she went and hunted for wireless debugging from a
  // previous request, and the off-plan guard allowed it because those words
  // were still in the transcript.
  @Volatile private var currentRequest = ""
  // A switch-state claim is evidence, not a sentence the model happened to
  // produce. Keep the last native reading so task_done can require it for
  // settings checks and changes.
  @Volatile private var lastSwitchProofLabel = ""
  @Volatile private var lastSwitchProofState = ""
  @Volatile private var lastSwitchProofRequest = ""
  @Volatile private var lastSwitchProofAt = 0L
  // Once scroll_to has found the requested control, the model must not be
  // allowed to scroll or freehand-tap past it. The only permitted follow-up is
  // a native action on that exact target, or reporting its proven state.
  @Volatile private var lockedTarget = ""
  @Volatile private var lockedTargetRequest = ""
  @Volatile private var lockedTargetAt = 0L
  @Volatile private var lockedTargetHasSwitch = false
  // Some system settings deserve a deterministic controller, not an LLM tool
  // loop. While it runs, no model-issued action may race it or scroll away.
  @Volatile private var nativeSettingsController = false
  // GOAL LOCK. While a task is running the objective is held here and cannot be
  // replaced by something heard mid-execution. A new instruction is queued and
  // she asks before switching, rather than silently abandoning what she is
  // doing - which is how a stray phrase became "go and play music".
  @Volatile private var queuedRequest = ""
  // The plan, held natively rather than in the model's head. It's replayed with
  // every action result so a mis-tap or a surprise screen can't make her forget
  // the objective and wander into some unrelated app.
  private val plan = ArrayList<String>()
  @Volatile private var planStep = 0
  @Volatile private var planGoal = ""
  // Action memory. Consecutive-repeat detection can't see an A->B->A->B loop:
  // swipe opens the drawer, swipe returns home, repeat - the screen changes
  // every time, so a "did it change?" check never fires. What's needed is
  // memory of WHERE she has been and WHAT she already tried from there.
  private val stateActionCount = HashMap<String, Int>()   // screen+action -> times
  private val stateVisits = HashMap<String, Int>()        // screen -> times seen
  private val triedFromState = HashMap<String, LinkedHashSet<String>>()
  private var supervisorThread: Thread? = null
  private var lastGoal = ""
  private var lastKey = ""
  private var lastModel = ""

  companion object {
    private const val TAG = "ChakaLive"
    private const val CHAKA_PKG = "com.chakamyth.app"
    private const val WS =
      "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    // How often the loop CHECKS whether the screen moved. The check is a cheap
    // accessibility dump, not a screenshot — only a real change spends a frame.
    private const val FRAME_MS = 500L
    private const val MIC_RATE = 16000   // required input rate
    // Frames are ~5x the cost of audio on the uplink. Small and throttled.
    private const val LIVE_FRAME_WIDTH = 760
    private const val LIVE_FRAME_QUALITY = 55
    // The Live API accepts video at <= 1 FPS, which also happens to be roughly
    // Android's accessibility-screenshot rate limit. Never go faster.
    private const val MIN_FRAME_GAP_MS = 1100L
    // A frame older than this, or of a different screen, no longer shows her
    // where she is — so anything about to act re-sends first.
    private const val FRAME_FRESH_MS = 5000L
    // Re-send an unchanged screen this often so it stays near the front of her
    // context instead of scrolling out of the sliding window.
    private const val FRAME_HEARTBEAT_MS = 9000L
    // Hard floor between drive prods. A screen signature can't be trusted to
    // rate-limit them: anything animated (a recording timer, a video, a
    // spinner) changes every frame, so the loop fired four turns in five
    // seconds and choked the session.
    private const val MIN_DRIVE_GAP_MS = 12000L
    private const val OUT_RATE = 24000   // model's audio output rate
    // Tools that actually move something on the phone. These are the ones that
    // must never fire against a screen she has not been shown.
    private val TOUCHES_THE_PHONE = setOf(
      "tap_index", "tap_at", "long_press_at", "type_text", "press_enter",
      "swipe", "scroll_to", "open_found_target", "set_found_switch", "drag",
      "press_button", "open_app", "open_app_drawer", "navigate"
    )
    // A halt: unambiguous, and fine as an opener.
    private val STOP_WORDS = listOf("stop", "wait", "cancel", "abort", "hold on", "quit")
    // A prohibition. "Don't create a new one, copy the one from before" is an
    // INSTRUCTION, not a request to freeze - it only means stop when it stands
    // more or less alone.
    private val SOFT_STOP = listOf("don't", "dont", "no no", "do not")
  }

  /**
   * Persistent memory for the live session, shared with the chat side of the
   * app. Without it she had nowhere to put things: asked to save an email and
   * password she stored the address correctly and then invented a password,
   * because "remembering" meant holding it in a conversation that had already
   * moved on. Values are written and read back verbatim.
   */
  private fun memStore() = context.getSharedPreferences("chaka.memory.v1", Context.MODE_PRIVATE)

  private fun memRemember(label: String, value: String) {
    memStore().edit().putString(label.lowercase().trim(), value).apply()
  }

  private fun memRecall(label: String): String? {
    val all = memStore().all
    val want = label.lowercase().trim()
    (all[want] as? String)?.let { return it }
    // Fall back to a loose match so "gmail password" finds "gmail" etc.
    return all.entries.firstOrNull { (k, _) ->
      k.contains(want) || want.contains(k)
    }?.value as? String
  }

  private fun memLabels(): List<String> = memStore().all.keys.sorted()

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
          if (ready && lastMsgAt > 0 && now - lastMsgAt > 40000) {
            Log.w(TAG, "no server message for ${(now - lastMsgAt) / 1000}s — session is dead")
            reconnect("went quiet")
            continue
          }
          // Blindness watchdog. She shipped blind for weeks and nothing could
          // tell: the frame loop existed, nothing called it, and the system
          // instruction cheerfully told her frames were streaming. So the loop
          // is now checked for a pulse rather than trusted to have one.
          //
          // Liveness, not silence, is the test. A long gap between frames is
          // normal — a still screen costs nothing, and the loop deliberately
          // sends nothing while Chaka's own UI is in front.
          if (ready && frameThread?.isAlive != true) {
            Log.w(TAG, "frame loop is not running (sent=$framesSent) — she is blind, restarting it")
            frameThread = null
            socket?.let { startFrameLoop(it) }
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
          val sinceText = System.currentTimeMillis() - lastTextSentAt
          if (lastTextSentAt > 0 && sinceText < 3000) {
            Log.e(TAG, "REJECTED ${sinceText}ms after a realtimeInput.text — that message shape is the suspect")
          }
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
      "You can SEE the screen and you can ACT on it with the provided tools.\n" +
      "\nYOUR EYES ARE ALWAYS OPEN. The phone streams you a picture every time the screen changes, a fresh one " +
      "immediately BEFORE each action you take, and another immediately AFTER it lands. You are not working from a " +
      "text list with the occasional photograph — you are watching, continuously, the way a person looking over " +
      "someone's shoulder would. So USE it: the newest picture is the truth about the phone, and it outranks the " +
      "element list, your expectation, and your memory of what you did.\n" +
      "\nWHAT YOU SEE FADES. WHAT YOU SAY STAYS. This is the single most important thing to understand about " +
      "yourself. What you are streamed is live SIGHT, not memory: a few seconds after something leaves the screen it " +
      "is gone from your mind completely, and you will not know you have lost it. Your own words do not fade — " +
      "anything you say out loud stays with you for the rest of the session.\n" +
      "So the instant you see something that matters, SAY IT ALOUD, before you scroll, tap or move on: whether a " +
      "switch is on or off, a code, a name, a value, a price, an error, the answer to whatever you were asked. " +
      "Scrolling past something without saying what it said is how you lose it forever — you will scroll back and " +
      "forth hunting for a thing you already looked straight at.\n" +
      "If you are checking whether something is turned on: the moment that row comes into view, read it out — " +
      "\"wireless debugging is on\" — and THEN carry on. Say it first, decide second.\n" +
      // This sentence used to forbid guessing AND forbid saying you don't know,
      // which between them left no answer she was allowed to give: in testing
      // she sat silent for four minutes rather than reply, while the session
      // was demonstrably alive. Never write a rule that closes every door.
      "And if you are asked something you no longer remember seeing, say so in one short sentence and go and look " +
      "again — never invent an answer, and NEVER go silent. Saying \"let me check\" costs a second; saying nothing " +
      "at all leaves them talking to a machine that has stopped responding.\n" +
      "GOAL / CONTEXT: ${goal.ifBlank { "Assist with whatever is on screen. Ask what they need." }}\n" +
      (memLabels().takeIf { it.isNotEmpty() }?.let {
        "ALREADY IN YOUR MEMORY (use recall to read any of these exactly): ${it.joinToString(", ")}\n"
      } ?: "") +
      "\nLOOK BEFORE YOU ASK, THEN ASK WITH THE REAL LIST. When a request is vague, do not ask from memory — go and find out what is actually there first. Use the app's own search box, or scroll_to, and read what genuinely exists. Then ask naming exactly what you found: \"there are three — USB debugging, Wireless debugging, and Revoke USB debugging authorisations. Which one?\" Offering two options when the phone has three is still guessing, just politely. One question, with the true list, and you never have to ask twice.\n" +
      "\nWHEN THEY ASK YOU SOMETHING, ANSWER IT. If they reply to your question with a question of their own — \"are those the only ones?\", \"what else is there?\", \"which do you recommend?\" — that is a NEW question and it is now yours to answer. Go and look if you do not know. NEVER repeat the question you already asked; they heard it the first time, and asking it again tells them you were not listening. Repeating yourself is the single most irritating thing you can do, and it is what makes someone stop trusting you.\n" +
      "\nVAGUE MEANS ASK, NOT GUESS. If the request names a CATEGORY rather than an exact control — \"the debugging thing\", \"the wifi setting\", \"that notification thing\", \"the battery one\" — you do not know which they mean, and picking the closest is guessing however confident you feel. Say what the candidates are, in their exact on-screen words, and ask which. \"Do you mean USB debugging or Wireless debugging?\" costs one second. Turning on the wrong one can hand someone remote access to the phone, sign them out, or wipe something, and being right by luck four times teaches them to trust you the fifth time when you are not. The same goes for a person, a file, a chat or an app whose name is not exact: if two things could be meant, ASK.\n" +
      "\nNEVER INVENT A VALUE. Passwords, codes, emails, account names: if the user gave it to you, remember() it the moment you hear it, and recall() it before typing. If it is not in memory, say you do not have it and ask - typing a made-up password is worse than useless, because it looks like it worked and silently isn't the real one.\n" +
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
      "- TO FIND SOMETHING BY NAME, USE scroll_to — NEVER SWIPE. scroll_to scrolls AND checks the screen after every single step, stops the instant your target appears, and locks that target so you physically cannot scroll or freehand-tap past it. If it finds a row, call open_found_target; it opens that exact row natively. If it finds a switch, read switch_is or call set_found_switch only when the user asked to change it. Swipe is only for reading a page you are already on.\n" +
      "- SWIPE MEANS CONTENT, NOT FINGER. 'down' reveals what is FURTHER DOWN the page; 'up' goes back toward the TOP. Never swipe to reach the app drawer or notifications — use open_app_drawer or press_button instead, so you can't land in the wrong place.\n" +
      "\nYOU CANNOT FAKE PERCEPTION. The system knows exactly what it has shown you and which tools you called. You can see the screen, so there is never an excuse for describing it wrongly — but describing it from memory, or from what you assume your last action did, is inventing it just the same. Say what is in the newest picture. And saying you did something without calling the tool will be caught and corrected in front of the user.\n" +
      "\nHOW YOU WORK — LOOK, ACT, CHECK. This is the loop, every step, without exception:\n" +
      "  1. KNOW WHERE YOU ARE, BY LOOKING. A picture of the screen you are about to act on has just been sent to you. Look at it. Read it against the element list — if they disagree, the picture is right. Never act on a screen you have not actually looked at.\n" +
      "  2. ACT, AND SAY WHAT YOU EXPECT. Every action takes an 'expect' — what the screen should look like afterwards. Be specific and concrete ('the Connections screen opens', 'the field reads golden brown'), because it is compared against the real screen.\n" +
      "  3. CHECK WHAT ACTUALLY HAPPENED, WITH YOUR EYES. The picture you are looking at is the screen the action produced — it arrives before the result does. Compare it to what you expected. The result also carries a 'verification': 'as_expected' means carry on, 'MISMATCH' means you were WRONG about what that action would do, so work out from the picture where you really are and fix THAT step. Never continue as though it worked.\n" +
      "  4. Only then call step_done. It is refused if nothing was verified.\n" +
      "This loop is what makes you accurate. Skipping the check is how you end up insisting something happened when it did not.\n" +
      "- PLAN BEFORE YOU ACT. Anything with more than one step: call set_plan first with the goal and the ordered steps. The plan is saved and handed back to you after EVERY action, so check it each time — it is what keeps you on the objective when something unexpected happens.\n" +
      "- WORK THE PLAN ONE STEP AT A TIME: look if you need to, act, then CHECK the result (screen_changed / screen_now / your own eyes) before calling step_done. If a step went wrong, fix that step — do not skip ahead and do not abandon the plan to go do something else.\n" +
      "- A MISTAKE IS NOT A REASON TO CHANGE THE GOAL. If you tap the wrong thing, go back and get that step right. Never quietly switch to a different app or a different objective; the user asked for one thing.\n" +
      "- WHEN THE USER SAYS STOP, YOU STOP. Stop, wait, don't, cancel — the tools will refuse to act. Acknowledge it, say briefly what you had done, and wait. Never argue or finish the action first.\n" +
      "- COPYING IS NOT VERIFIED UNTIL YOU READ IT BACK. After tapping any Copy button, call read_clipboard before you paste. Copy buttons very often grab the label next to a value rather than the value itself, and pasting a name where a key should be is silent and useless.\n" +
      "- CLAIMING SUCCESS FALSELY IS THE WORST THING YOU CAN DO. Worse than failing, worse than being slow, worse than asking. The user is relying on you to describe their phone accurately - if you say a thing is done and it is not, they act on something untrue. Before task_done: LOOK, and check the screen actually shows what was asked. If it does not, say so.\n" +
      "- IF NOTHING HAS CHANGED, NOTHING HAS BEEN ACHIEVED. Several actions in a row with no effect means it did not work - it does not mean it worked quietly. Report that plainly rather than declaring success.\n" +
      "- REPORT ON WHAT WAS ASKED. If the request named Contacts, your report is about Contacts. Describing a different app is either the wrong action or a wrong description, and both need saying out loud rather than papering over.\n" +
      "- USE YOUR EYES BEFORE SAYING ANYTHING IS DONE. You can SEE — call look_at_screen and actually check. Never say an account is created, a page is open, a message is sent or a task is finished unless you have just looked and can see it. Saying it does not make it so.\n" +
      "- WEB PAGES NEED YOUR EYES. Inside Chrome or any browser the element list is often nearly empty even though the page is full of buttons. If read_screen comes back thin or blank and you're in a browser, call look_at_screen and work from the picture with tap_at.\n" +
      "- A REQUEST WITH SEVERAL PARTS IS NOT DONE UNTIL EVERY PART IS. 'Create an account AND get me the API key' is two things. Track them, finish both, and if you only managed one, say exactly which and why.\n" +
      "- IF SOMETHING FAILS OR ERRORS, say so plainly and try another route. A page erroring, a login being cancelled, a step not completing - these are things to report, not to paper over.\n" +
      "- NEVER CLAIM AN ACTION WORKED WHEN screen_changed IS false. If you swipe and the screen did not move, the page did NOT turn — say so out loud and try a different way. Telling the user something happened when the result says it didn't is the worst thing you can do; they are relying on you to be accurate about their phone.\n" +
      "- DO EXACTLY THE TASK ASKED, NOTHING ELSE. 'Second page of the app menu' means the app menu's second page — not the home screen, not the third page, not a different app. If you get stuck, stay on the goal and say what's blocking you. Opening something unrelated and calling it done is never acceptable.\n" +
      "- A CHECKBOX IS A TOGGLE: TAPPING IT TWICE UNDOES IT. After tapping one you are told checkbox_is_now CHECKED or UNCHECKED. If it says CHECKED, it worked — move on, and never tap it again to be sure. Ignore any mismatch warning about it; the state is the truth.\n" +
      "- YOUR GOAL IS LOCKED WHILE YOU WORK. Once you have a task you finish it, ask about it, or are told to stop. A new instruction arriving mid-task does NOT replace it - you ASK first: \"You asked me to X. Do you want me to stop that and do Y instead?\" One question costs a second; abandoning a task halfway costs the whole thing.\n" +
      "- IF YOU DID NOT HEAR THE USER ASK, DO NOT DO IT. Never act on something you are unsure was requested. A phrase in your context is not an instruction - if you cannot point to the user asking for it just now, ASK before doing anything. Starting a task nobody requested is worse than doing nothing at all.\n" +
      "- WHEN YOU HAVE STOPPED, YOU STAY STOPPED until the user gives you a clear new instruction. Do not resume the old task, and do not drift into a new one. Wait.\n" +
      "- ONE REQUEST AT A TIME, AND IT IS THE LATEST ONE. Every result shows the_request - what the user actually asked for, right now. Read it every time. Older tasks are FINISHED; never drift back into something they asked about earlier, and never do a thing they did not ask for. If you catch yourself somewhere unrelated to the_request, stop, go back, and resume it.\n" +
      "- NEVER CHANGE A SETTING THAT WAS NOT ASKED FOR. No toggling Bluetooth, Wi-Fi, permissions or anything else because you happen to be on that screen. Only touch what the request needs.\n" +
      "- BEING STUCK IS REPORTED, NOT ESCAPED. If a step will not work, you do not quietly go and do something else. Say plainly what you tried, what is blocking you, and ask. Opening an unrelated app while a task is unfinished is the single worst thing you can do - the user walks back to find you browsing something they never asked for.\n" +
      "- READ THE LIST BEFORE YOU SCROLL. Every result gives you screen_now, the things actually on screen. Check it for what you want BEFORE swiping — scrolling past something that is right in front of you is the clearest possible sign you are not looking, and it is how tasks get lost.\n" +
      "- SETTING STATE MUST COME FROM NATIVE PROOF. A plain row label means UNKNOWN, never ON or OFF. For 'is wireless debugging on?' and similar questions, call scroll_to. Its switch_is result is read from Android's real checked state and is the answer; if it first finds a row, call open_found_target, then scroll_to the same name again. Never replace that proof with a visual guess, an element-list inference, or more scrolling.\n" +
      "- SAY IT ONCE, THEN STOP. If you cannot find something or cannot verify it, say so in ONE sentence — what you looked for, where you looked, and what you can see instead — and then WAIT. Repeating \"I could not verify it\" again and again is not reporting, it is nagging, and the user has to shut you up by hand. If you have already said it once, do not say it again unless they ask.\n" +
      "- IF TAPS DO NOTHING, THE SCREEN IS PROBABLY STILL LOADING. Several actions in a row reporting no change usually means the page has not finished drawing - not that you are aiming badly. Call wait (with more seconds) before trying anything else. Tapping a half-drawn screen achieves nothing and shifts the element numbers underneath you.\n" +
      "- A BLANK OR HALF-DRAWN SCREEN MEANS LOADING, NOT FAILURE. Sign-in pages, browsers and anything on a slow connection take a moment. Call wait and look again. NEVER press back on a screen that is still loading - the tap that got you there worked, and going back throws it away and starts you round the loop again.\n" +
      "- CHECKBOXES AND RADIO BUTTONS IN A FORM OR DIALOG: tap the LABEL TEXT beside the control, not the little box. The box itself is often a few pixels and not the clickable node; the row or its text usually is. If tapping the box does nothing, tap the words next to it.\n" +
      "  BUT NOT ON A SETTINGS LIST. There, the label is a link to that setting's own page and the switch is the thing that toggles — tapping the words is how you end up somewhere you were never asked to go. On a Settings row, aim at the switch.\n" +
      "- IF A TAP CHANGES NOTHING TWICE, THE TARGET IS WRONG, NOT THE AIM. Do not nudge the coordinates by a hair and retry. Read the screen again, pick a DIFFERENT element (the label, the row, the parent), or scroll in case the real control is elsewhere.\n" +
      "- AFTER MOVING AN ICON, LOOK - NEVER PRESS BACK. Back cancels the move and puts it straight back where it was. If the home screen is in edit mode afterwards, tap an empty part of the screen to settle it.\n" +
      "- MOVING AN ICON MEANS PICKING IT UP FIRST: drag with hold:true, from the icon to where it should go. A normal drag does not lift it, so nothing moves however many times you try. If a move does not work, check you are dragging the RIGHT icon - the one named in the request - and that you used hold.\n" +
      "- ON A PICKER WHEEL, TAP THE VALUE — DON'T CHASE IT BY DRAGGING. The nearby values are visible above and below the selected one. If the value you want is on screen, TAP IT DIRECTLY with tap_at; the wheel snaps to it exactly. That is how a person does it, and it lands first time. Only drag when the value is not yet visible, and then only far enough to bring it into view.\n" +
      "- DRAG DISTANCE IS ROUGHLY PROPORTIONAL: on a wheel showing about 5 values at once, one value is only ~0.06 of the screen height. A 0.3 drag moves about five values, so if you are 2 away, drag ~0.12 — not 0.3. Overshooting and coming back repeatedly means your steps are far too big.\n" +
      "- DRAGGING A WHEEL IS A FEEDBACK LOOP, NOT ONE MOVE. Drag a little, LOOK at the value in the screenshot that follows, judge how far is left, then drag again — bigger when far, smaller when close, reversed if you went past it. Repeating an identical drag without reading the value is how 2025 becomes 1926.\n" +
      "- A SWIPE MOVES THE PAGE; A DRAG MOVES A CONTROL. If a swipe reports nothing changed, the thing you are trying to move is a control, not a page — a date-of-birth wheel, a spinner, a slider, a carousel. Use drag along that control itself: for a year wheel, drag vertically down the middle of the year column, slow, a little at a time, checking the value after each drag. Never keep swiping a screen that refuses to move.\n" +
      "- KNOW WHERE YOU ARE before swiping between pages. The home screen and the app drawer look similar and both have pages. Check now_in_app and the screen contents first; if you're on the wrong one, fix that before paging.\n" +
      "- YOU CANNOT REPEAT YOURSELF. The system remembers every screen you've been on and every action you took from it. Try the same thing from the same screen twice and it will be refused, with a list of what you already tried there. If you see 'looping' or 'been_here_before', stop and take a genuinely different route immediately — that is a circle, and repeating it wastes the user's time.\n" +
      "- EVERY ACTION TELLS YOU WHAT IT DID. Each result includes now_in_app, screen_changed and screen_now. READ THEM. If screen_changed is false, that move failed — change approach, never repeat it. If now_in_app isn't where you meant to be, you went the wrong way: press back and correct it immediately.\n" +
      "- TYPING ACCURACY: type the EXACT words asked for. After typing, read the field back on screen and confirm it matches; if it's wrong, clear it and retype before searching. Searching for the wrong text wastes far more time than checking.\n" +
      "- Finish the whole intent, not the setup for it. 'Play X' means the song is PLAYING, not that you searched for it. 'Message X' means sent. Keep going until the real outcome is on screen.\n" +
      "- STAY ON THE TASK. Only touch what the task needs. If you don't know what to do next, say so and ask — never wander through Settings, App info, recent apps or unrelated apps hoping to find something. Never force stop, uninstall, clear data, reset, delete or sign out of anything unless the user asked for that exact thing.\n" +
      "\nNEVER CLAIM SOMETHING YOU HAVE NOT VERIFIED:\n" +
      "- Do not say an app is open, a photo is tapped, or a message is sent unless the CURRENT screen proves it. Check read_screen or the latest frame first.\n" +
      "- If the screen shows something different from what you expected, say so plainly and fix it. Do not insist it worked.\n" +
      "- Typing is not sending. After typing you MUST tap the send button (or press_enter) and then confirm it actually sent.\n" +
      "- If a reply appears on screen, read it out straight away and continue — don't wait to be asked what it said.\n" +
      "- Only stop when the goal is done, they tell you to stop, or you're genuinely blocked. Only ask when it's truly ambiguous and you cannot reasonably choose (e.g. which of two accounts is theirs).\n" +
      "\nTAPPING — THE NUMBERS ARE DRAWN ON THE SCREEN. LOOK AT THEM AND USE THEM:\n" +
      "- Every tappable thing has a RED NUMBERED BOX drawn over it in the picture you are being shown. That number IS the index for tap_index. So do not estimate where something is — READ ITS NUMBER OFF THE PICTURE and tap_index that number. This is the difference between hitting the control and hitting the gap beside it.\n" +
      "- CHECK THE BOX ACTUALLY SITS ON WHAT YOU WANT before you tap it. The number in read_screen's text list and the number drawn on the picture are the same number, so you can confirm the label and the position agree. If they disagree, believe the picture.\n" +
      "- A SETTINGS ROW AND ITS SWITCH ARE TWO DIFFERENT BOXES WITH TWO DIFFERENT NUMBERS. Tapping the ROW opens that setting's own page. Tapping the SWITCH turns it on or off. If you were asked to TURN SOMETHING ON, you want the switch's box — the small one at the right-hand end of the row — not the row. Tapping the row instead leaves you on a screen nobody asked for, and then you are recovering from your own mistake instead of doing the task.\n" +
      "- read_screen + tap_index is how you aim. tap_at, guessing at coordinates, is the last resort and it misses: measured against a real screen it lands up to half a row away, sometimes in the gap between two controls where the tap does nothing at all.\n" +
      "- MANY things are NOT in the element list: buttons inside web pages, photos in a gallery grid, canvas/custom UI. If you can SEE it in the frame but read_screen doesn't list it, use tap_at with fractional coordinates (x and y from 0 to 1, measured from the top-left of the screen). NEVER give up and ask them to tap it themselves — estimate the position from the frame and tap_at it. If your first tap misses, adjust the coordinates and try again.\n" +
      "- long_press_at works the same way for press-and-hold.\n" +
      "\nOTHER:\n" +
      "- Verify with your eyes: the next frames show the result. If something didn't work, say so briefly and try a DIFFERENT approach — never repeat a failed action or reload a page hoping it fixes itself.\n" +
      "- If a sign-in blocks the goal, sign in yourself (tap Log in / Continue with Google, then the existing account).\n" +
      "- For an incoming call use answer_call / end_call.\n" +
      "- Keep speech short, warm and natural — you're a person beside them, not a manual."

    val decls = JSONArray()
      .put(fn("read_screen", "Read the current screen: every element with its index, label, and whether it's tappable/editable/toggled. Call this before tapping.", JSONObject()))
      .put(fn("tap_index", "Tap the element with this index (from read_screen).", JSONObject()
        .put("index", JSONObject().put("type", "integer").put("description", "Element index"))
        .put("expect", JSONObject().put("type", "string").put("description", "REQUIRED. What you expect to see after this tap, e.g. 'the Settings screen opens' or 'the search box gets focus'. It is checked against the real screen.")),
        listOf("index", "expect")))
      .put(fn("type_text", "Type into the focused field. Tap the field first.", JSONObject()
        .put("text", JSONObject().put("type", "string").put("description", "Text to type"))
        .put("expect", JSONObject().put("type", "string").put("description", "REQUIRED. What the screen should show afterwards, e.g. 'the field contains golden brown'.")),
        listOf("text", "expect")))
      .put(fn("press_enter", "Press the keyboard enter/search key.", JSONObject()))
      .put(fn(
        "read_clipboard",
        "Read what is currently on the clipboard. ALWAYS call this after tapping a Copy button and before pasting, to confirm you copied the right thing — copy buttons often grab a label instead of the value.",
        JSONObject()
      ))
      .put(fn("swipe", "Scroll the screen. direction: down (reveal content further down), up, left, right. amount: tiny|normal|long.",
        JSONObject()
          .put("direction", JSONObject().put("type", "string").put("description", "down|up|left|right"))
          .put("amount", JSONObject().put("type", "string").put("description", "tiny|normal|long"))
          .put("expect", JSONObject().put("type", "string").put("description", "REQUIRED. What should come into view, e.g. 'the second page of the app drawer'.")),
        listOf("direction", "expect")))
      .put(fn(
        "scroll_to",
        "Find something by NAME and stop the moment it is on screen. ALWAYS USE THIS INSTEAD OF SWIPING when you are looking for a named thing — a setting, a row, a contact, a button. The phone checks after every step and locks the exact target when found. You cannot scroll, freehand-tap, or accidentally move past a locked target: call open_found_target to open its row, or read switch_is / call set_found_switch for its switch.",
        JSONObject()
          .put("target", JSONObject().put("type", "string").put("description", "The exact words on screen, e.g. 'Wireless debugging'. Use the real label, not a description of it."))
          .put("direction", JSONObject().put("type", "string").put("description", "down (further down the page, the default) or up")),
        listOf("target")
      ))
      .put(fn(
        "open_found_target",
        "Open the row currently locked by scroll_to. It acts on Android's exact matched node, not a coordinate. Use this after scroll_to finds a row with no switch.",
        JSONObject()
      ))
      .put(fn(
        "set_found_switch",
        "Set the switch currently locked by scroll_to, then read its real Android state back. Use only when the user explicitly asked to turn that setting on or off.",
        props("enabled", "boolean", "true = ON, false = OFF"),
        listOf("enabled")
      ))
      .put(fn(
        "tap_found",
        "Tap the thing scroll_to just located — no coordinates needed, and none can be got wrong. part:\"switch\" toggles it on or off; part:\"row\" opens that setting's own page. ALWAYS use this after a successful scroll_to instead of tap_at: the phone already measured exactly where it is, down to the pixel.",
        props("part", "string", "switch or row"),
        listOf("part")
      ))
      .put(fn("press_button", "Press a system button: back, home, recents, notifications, quick_settings.", props("button", "string", "back|home|recents|notifications|quick_settings"), listOf("button")))
      .put(fn("open_app", "Launch an app by name.", props("app", "string", "App name, e.g. spotify"), listOf("app")))
      .put(fn("navigate", "Open a website URL in the browser.", props("url", "string", "Full URL"), listOf("url")))
      .put(fn(
        "tap_at",
        "Tap anywhere by fractional position (x,y each 0..1 from the top-left). USE THIS for anything you can see in the frame but read_screen does not list — buttons inside web pages, photos in a gallery grid, custom UI. Never ask the user to tap something themselves; estimate from the frame and tap here.",
        JSONObject()
          .put("x", JSONObject().put("type", "number").put("description", "0..1 across (left to right)"))
          .put("y", JSONObject().put("type", "number").put("description", "0..1 down (top to bottom)"))
          .put("expect", JSONObject().put("type", "string").put("description", "REQUIRED. What you expect to happen when you tap there.")),
        listOf("x", "y", "expect")
      ))
      .put(fn(
        "long_press_at",
        "Press and hold at a fractional position (x,y each 0..1). For context menus, selecting a photo, drag handles.",
        JSONObject()
          .put("x", JSONObject().put("type", "number").put("description", "0..1 across"))
          .put("y", JSONObject().put("type", "number").put("description", "0..1 down")),
        listOf("x", "y")
      ))
      .put(fn(
        "look_at_screen",
        "Stop and study the screen properly. You are already being streamed a picture continuously, so use this only when you need a deliberate close look — reading small text, judging how something LOOKS, finding a control the element list does not mention, or checking a claim before you make it. A fresh picture arrives immediately.",
        JSONObject()
      ))
      .put(fn(
        "open_app_drawer",
        "Open the app drawer (the full list of installed apps). Use this instead of swiping when you need to find, launch, uninstall or inspect an app. Goes to the home screen first, so it works from anywhere.",
        JSONObject()
      ))
      .put(fn(
        "drag",
        "Drag from one point to another, both as fractions 0..1. USE THIS for anything a whole-screen swipe cannot move: date/time picker wheels, spinners, sliders, carousels, seek bars, reordering. A swipe scrolls the whole page; a drag works inside one control. For a picker wheel, drag vertically along the middle of THAT column (e.g. from 0.5,0.55 to 0.5,0.35 to move it up).",
        JSONObject()
          .put("from_x", JSONObject().put("type", "number").put("description", "0..1 across"))
          .put("from_y", JSONObject().put("type", "number").put("description", "0..1 down"))
          .put("to_x", JSONObject().put("type", "number").put("description", "0..1 across"))
          .put("to_y", JSONObject().put("type", "number").put("description", "0..1 down"))
          .put("slow", JSONObject().put("type", "boolean").put("description", "true for a precise, controlled drag (pickers); false/omit for a flick"))
          .put("hold", JSONObject().put("type", "boolean").put("description", "TRUE to press and hold first, then drag. REQUIRED for moving a home-screen icon, reordering a list, or anything you must pick up before moving. Without it you only scroll the page underneath."))
          .put("expect", JSONObject().put("type", "string").put("description", "REQUIRED. What should change, e.g. 'the year shows 1967'.")),
        listOf("from_x", "from_y", "to_x", "to_y", "expect")
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
      // Mode was dying mid-task. int64 fields must be sent as STRINGS in
      // Google's proto-JSON mapping; passing a number made the whole block
      // invalid, so the cap kept applying (and likely caused the 1007s).
      //
      // These were 16000/8000 against a 128k context window, with ~5.6k of that
      // spent before a word is exchanged (4.5k system instruction, 1.1k tool
      // declarations) — so compression cut the conversation to roughly 2.4k.
      //
      // Raising it did NOT fix her memory of the screen, and it is honest to
      // say so: the browser probe showed her failing to recall a switch after
      // 45 seconds having streamed only ~3.8k tokens of video, far below any
      // trigger. Compression had never fired. Frames simply do not persist.
      //
      // It stays raised because what DOES persist is text, and text is now the
      // mechanism she remembers with — her own narration has to survive the
      // window for the rest of the task.
      .put(
        "contextWindowCompression",
        JSONObject()
          .put("triggerTokens", "96000")
          .put("slidingWindow", JSONObject().put("targetTokens", "32000"))
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
      // Voice-activity detection. LOW sensitivity was tried to stop room noise
      // cutting her off, but "low end-of-speech sensitivity" means slow to
      // decide the user has STOPPED — turns stopped committing entirely and she
      // went mute while still transcribing every word. Being interrupted
      // occasionally beats never answering, so: default start sensitivity, and
      // end-of-speech biased towards closing the turn promptly.
      .put(
        "realtimeInputConfig",
        JSONObject().put(
          "automaticActivityDetection",
          JSONObject()
            .put("endOfSpeechSensitivity", "END_SENSITIVITY_HIGH")
            .put("prefixPaddingMs", 200)
            .put("silenceDurationMs", 600)
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
            // Video frames are 70 tokens here, the level the API is built
            // around for continuous streaming, and it drops a still image from
            // 1120 tokens to 280.
            //
            // It belongs INSIDE generationConfig. Putting it at the top of
            // setup — where the Python and JS SDK configs appear to show it —
            // is rejected outright: "Unknown name mediaResolution at 'setup':
            // Cannot find field", close 1007, every session dead on arrival.
            // The browser probe caught that before it reached the phone.
            .put("mediaResolution", "MEDIA_RESOLUTION_LOW")
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
      lastFrameSig = ""   // a resumed session has shown the new socket nothing
      framesSent = 0
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

      // Deliver a promised look NOW, not at the end of the turn.
      //
      // pendingLook was only ever consumed on turnComplete. She chains ten or
      // fifteen tool calls inside ONE turn, so every picture a guard promised
      // her - after a MISMATCH, a refused scroll, a failed search - sat queued
      // behind the whole chain and arrived long after the moment it was for,
      // if at all. That is why it looked like she chose not to use her eyes:
      // we kept telling her a picture was coming and then not sending it.
      //
      // A video frame opens no turn, so this cannot interrupt her. The flag is
      // deliberately NOT cleared: the reconciliation prompt still goes at
      // turnComplete, where forcing her to stop and answer is the point.
      if (pendingLook) {
        val demand = pendingDecision
        pendingDecision = ""
        Thread {
          Thread.sleep(350)
          if (!cancelled && ready) {
            val d = runCatching { JSONObject(service.dumpScreen()) }.getOrNull()
            sendFrame(ws, force = true, forSig = d?.let { sig(it) } ?: "", marks = d?.optJSONArray("els"))
            // The picture alone is not enough. Video opens no turn, so without
            // this she sits looking at the very thing she was told to find and
            // says nothing — which is exactly what happened in front of the
            // three debugging rows. Text commits a turn; a turn forces a
            // decision.
            if (demand.isNotEmpty()) {
              Thread.sleep(250)
              if (!cancelled && ready) sendText(ws, demand)
            }
          }
        }.also { it.isDaemon = true }.start()
      }

      return
    }

    msg.optJSONObject("serverContent")?.let { content ->
      // Barge-in: the user started talking over her, so drop whatever audio is
      // still queued and let them lead.
      if (content.optBoolean("interrupted", false)) {
        runCatching { player?.pause(); player?.flush(); player?.play() }
        interruptedThisTurn = true
        Log.i(TAG, "interrupted by user")
        return@let
      }

      // The user said something → there's a live instruction to execute.
      content.optJSONObject("inputTranscription")?.optString("text")
        ?.takeIf { it.isNotBlank() }?.let { heard ->
          // Critical: give her room to answer. Without this the drive loop fired
          // a [SYSTEM] turn on top of the user's turn and the session wedged.
          lastActivityAt = System.currentTimeMillis()
          val said = heard.lowercase().trim()
          val words = said.split(Regex("\\s+")).size
          val now = System.currentTimeMillis()

          // Anything transcribed while she is speaking is her own voice coming
          // back through the mic, not the user. It was being taken as a command:
          // a stray French phrase became "the_request" and she went off and
          // played music nobody asked for.
          if (now - lastAudioOutAt < 2000) {
            Log.i(TAG, "ignoring echo of her own speech: \"$heard\"")
            return@let
          }

          // A real stop is short and leads with it: "stop", "wait", "no no no".
          // Words like "don't" turn up constantly inside ordinary instructions -
          // "don't uninstall it, just remove it from the home screen" froze her
          // completely and she refused to act on a normal request.
          val isStop = STOP_WORDS.any { w -> said.startsWith(w) } ||
            (words <= 6 && STOP_WORDS.any { w -> said.contains(w) }) ||
            // "Don't." on its own halts; "Don't do X, do Y" is a redirect.
            (words <= 4 && SOFT_STOP.any { w -> said.startsWith(w) })
          if (isStop) {
            // Stop is enforced here, not left to the model — it ignored spoken
            // stops and kept going with things the user didn't want.
            halted = true
            haltedAt = System.currentTimeMillis()
            taskActive = false
            currentRequest = ""
            clearSwitchProof()
            autoContinues = 0
            drives = 0
            pendingLook = false
            plan.clear(); planStep = 0; planGoal = ""
            runCatching { player?.pause(); player?.flush(); player?.play() }  // cut her off mid-sentence
            Log.w(TAG, "HARD STOP heard: \"$heard\"")
            ChakaGuideOverlay.update("⏸ Stopped")
            pendingUserWord = ""
          } else if (taskActive && currentRequest.isNotEmpty() && !isFollowUp(said) && words >= 5) {
            // Busy. Only stop/wait/cancel act immediately (handled above);
            // anything else is queued and confirmed, never applied silently.
            queuedRequest = heard
            lastActivityAt = now
            Log.i(TAG, "QUEUED (not switching): \"$heard\"")
            socket?.let { ws ->
              sendText(
                ws,
                "[SYSTEM] The user said something while you are mid-task: \"$heard\". Do NOT switch to it and do " +
                  "NOT abandon what you are doing. You are working on: \"$currentRequest\". Ask them plainly, in one " +
                  "sentence: whether they want you to stop that and do the new thing instead, or carry on. Then wait " +
                  "for their answer."
              )
            }
          } else if (words >= 5 && !aPersonSaidThis(said)) {
            // Heard something, but it does not look like speech aimed at her.
            // Do NOT let it become the request — ask, the way a person would
            // when they half-caught a sentence in a noisy room.
            Log.w(TAG, "not an instruction, asking instead: \"$heard\"")
            socket?.let { ws ->
              sendText(
                ws,
                "[SYSTEM] What was heard did not come through as a clear instruction: \"$heard\". Do NOT act on it and " +
                  "do NOT treat it as a task. Say, in one short friendly sentence, that you did not catch that and ask " +
                  "them to say it again. Then wait."
              )
            }
          } else if (words >= 5 && now - haltedAt > 6000) {
            // Five words, and not straight after a stop. Three was low enough
            // that background chatter started tasks; and a stop that any noise
            // could cancel is not a stop.
            // Hold their words so the very next action has to reckon with them.
            pendingUserWord = heard
            // Only a real sentence starts work. Stray words the mic caught from
            // the room were kicking off tasks nobody asked for.
            halted = false
            taskActive = true
            nudges = 0
            drives = 0
            idleTurns = 0
            autoContinues = 0
            awaitingDoneProof = false
            // They agreed to the switch we queued, so promote it.
            if (queuedRequest.isNotEmpty() && confirmsSwitch(said)) {
              currentRequest = queuedRequest
              clearSwitchProof()
              queuedRequest = ""
              plan.clear(); planStep = 0; planGoal = ""
              stateActionCount.clear(); triedFromState.clear(); stateVisits.clear()
              noProgressRun = 0
              Log.i(TAG, "SWITCH CONFIRMED -> \"$currentRequest\"")
              return@let
            }
            // A new request REPLACES the old one. Anything still open from the
            // previous task is finished as far as she is concerned.
            if (!isFollowUp(said)) {
              currentRequest = heard
              clearSwitchProof()
              plan.clear(); planStep = 0; planGoal = ""
              stateActionCount.clear(); triedFromState.clear(); stateVisits.clear()
              noProgressRun = 0
              refusals.clear()
              Log.i(TAG, "NEW REQUEST: \"$heard\"")
            }
          }

          synchronized(recentSpeech) {
            recentSpeech.append(' ').append(said)
            if (recentSpeech.length > 600) recentSpeech.delete(0, recentSpeech.length - 600)
          }
          Log.i(TAG, "user: $heard")
          if (currentRequest == heard && startNativeWirelessDebuggingCheck()) {
            Log.i(TAG, "native Wireless debugging controller started")
          }
        }

      // Accumulate what SHE said this turn, so we can tell talk from action.
      content.optJSONObject("outputTranscription")?.optString("text")
        ?.takeIf { it.isNotBlank() }?.let { turnSaid.append(it) }

      if (content.optBoolean("turnComplete", false)) {
        // She asked to look — deliver the picture now that her turn has ended.
        if (pendingLook) {
          pendingLook = false
          Thread {
            Thread.sleep(450)  // let the screen settle
            if (!cancelled && ready) {
              val freshDump = runCatching { JSONObject(service.dumpScreen()) }.getOrNull()
              val nowSig = freshDump?.let { sig(it) } ?: ""
              captureBlocking(marks = freshDump?.optJSONArray("els"))?.let { shot ->
                val prompt = when {
                  awaitingDoneProof ->
                    "This is the REAL current screen. Check every part of what the user asked is visibly complete here. " +
                      "If it is, call task_done again to confirm. If it is not, say what is actually on screen and keep working."
                  taskActive ->
                    "This is the current screen. Look carefully, then take your next ACTION on the task. " +
                      "You already have the picture — do not ask to look again."
                  // With no task running, describing it IS the job. Telling her to
                  // "continue the task" here is what started her looping: the image
                  // ends a turn, she responds by looking again, forever.
                  else ->
                    "This is the current screen. Tell the user briefly what you can see. Do not call look_at_screen again."
                }
                Log.i(TAG, "showing screen (${shot.length} b64) + prompt")
                // emitFrame is what marks her as having seen it. Without that
                // the false-claim detector treats her next "I can see..." as a
                // lie, sends a correction and ANOTHER screenshot, and she
                // repeats herself forever - a loop caused entirely by the
                // detector.
                showScreen(ws, shot, nowSig, prompt)
              }
            }
          }.also { it.isDaemon = true }.start()
          turnSaid.setLength(0)
          toolCalledThisTurn = false
          return@let   // the image IS the continuation; don't also nudge
        }
        // Her audio is queued in the player; unmute the mic once it has drained.
        Thread { Thread.sleep(700); speaking = false }.also { it.isDaemon = true }.start()
        val said = turnSaid.toString().trim()
        turnSaid.setLength(0)
        Log.i(TAG, "turnComplete said=\"${said.take(90)}\" tool=$toolCalledThisTurn")
        if (said.isNotEmpty()) ChakaGuideOverlay.update(said.take(160))

        // Before anything else: did she just claim something untrue?
        catchFalseClaim(said, toolCalledThisTurn)
          ?.takeIf { System.currentTimeMillis() - lastCorrectionAt > 20000 }
          ?.let { correction ->
            lastCorrectionAt = System.currentTimeMillis()
          val wasVision = correction.contains("look_at_screen")
          if (wasVision) pendingLook = true   // give her the real screen
          turnSaid.setLength(0)
          toolCalledThisTurn = false
          interruptedThisTurn = false
          Thread {
            Thread.sleep(300)
            if (!cancelled && ready) sendText(ws, correction)
          }.also { it.isDaemon = true }.start()
          return@let
        }
        val actedThisTurn = toolCalledThisTurn
        val wasInterrupted = interruptedThisTurn
        interruptedThisTurn = false
        toolCalledThisTurn = false
        if (wasInterrupted) Log.i(TAG, "turn was cut off — not pushing her to continue")
        lastActivityAt = System.currentTimeMillis()
        // She finished a turn without touching the phone. If she promised to do
        // something, push her to actually do it — otherwise the session just
        // stalls until the user shouts, which is the whole complaint.
        if (!actedThisTurn && taskActive && promisedAction(said)) {
          checkTurn(ws, said)
        } else if (taskActive && !interruptedThisTurn) {
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
              speaking = true
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
    if (autoContinues > 30) {
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
  /**
   * Catches a claim the native side can disprove: saying she looked at the
   * screen when no perception tool ran, or reporting an action she never took.
   * Returns the correction to send, or null when the claim checks out.
   */
  private fun catchFalseClaim(said: String, actedThisTurn: Boolean): String? {
    val s = said.lowercase()
    if (s.isBlank()) return null
    val now = System.currentTimeMillis()

    val claimsVision = listOf(
      "i can see", "i see ", "looking at", "i'm looking", "im looking", "i've looked",
      "ive looked", "i looked", "on your screen i", "the screen shows", "i can view",
      "i'm viewing", "camera is on", "turned on the camera", "i can observe"
    ).any { s.contains(it) }
    val perceivedRecently =
      now - lastRealLookAt < 15000 || now - lastScreenReadAt < 15000
    if (claimsVision && !perceivedRecently) {
      Log.w(TAG, "FALSE CLAIM (vision): \"${said.take(80)}\"")
      return "[SYSTEM] You just told the user you could see the screen, but you did NOT call look_at_screen or " +
        "read_screen — so you saw nothing and were describing something you imagined. Never do this. " +
        "A real screenshot follows: look at it, then correct what you told them."
    }

    // "I could not find it" while the phone has it located is not a mistake, it
    // is a false report — and the owner acted on it, stepping in to do the job
    // himself. She stopped ON the row, said she could not find it, then wandered
    // off and switched Developer options off. The native side knows better.
    val claimsMissing = listOf(
      "couldn't find", "could not find", "can't find", "cannot find",
      "unable to find", "not able to find", "didn't find", "did not find",
      "isn't there", "is not there", "doesn't seem to be", "does not appear"
    ).any { s.contains(it) }
    if (claimsMissing && foundLabel.isNotBlank() && now - lastFoundAt < 60000) {
      Log.w(TAG, "FALSE CLAIM (missing): says not found, but '$foundLabel' is located")
      return "[SYSTEM] You just told the user you could not find it. That is NOT TRUE — the phone has \"" +
        foundLabel + "\" located on screen right now, and you were given its exact position. Do not say it is " +
        "missing. Either act on it with tap_found, or say precisely what you can see and ask them."
    }

    val claimsDone = listOf(
      "i've tapped", "ive tapped", "i tapped", "i've opened", "ive opened", "i opened",
      "i've sent", "ive sent", "i sent", "i've typed", "ive typed", "i typed",
      "i've turned on", "i turned on", "i've created", "i created", "i've deleted",
      "i deleted", "i've saved", "i saved", "done!", "that's done"
    ).any { s.contains(it) }
    if (claimsDone && !actedThisTurn) {
      Log.w(TAG, "FALSE CLAIM (action): \"${said.take(80)}\"")
      return "[SYSTEM] You told the user you did something, but you called NO tool this turn — so nothing happened " +
        "on the phone. Saying it is not doing it. Either perform the action now with the proper tool, or tell them " +
        "honestly that it has not been done."
    }
    return null
  }

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
      Log.i(TAG, "AEC available, enabled=${aec?.enabled}")
    } else {
      Log.w(TAG, "no hardware AEC on this device — relying on the half-duplex gate")
    }
    rec.startRecording()
    runCatching { ears.start() }   // never let the second ears break the first

    micThread = Thread {
      // ~100ms per chunk keeps latency low without spamming tiny frames.
      val buf = ByteArray(3200)
      while (!cancelled && ready) {
        val n = try { rec.read(buf, 0, buf.size) } catch (e: Exception) { -1 }
        if (n <= 0) continue
        // Keep draining the mic (so the buffer doesn't back up) but don't send
        // while she's speaking, or she hears herself and cuts herself off.
        if (System.currentTimeMillis() - lastAudioOutAt < 900) continue
        // Don't stream our own output back in — that's what kept "interrupting"
        // her mid-sentence and leaving turns empty.
        if (speaking) continue
        // Same bytes, two listeners: Gemini for the conversation, ChakaEars to
        // say whether that was a person or the room.
        // Only while it is actually working. This device answers BUSY to piped
        // audio every single time, so after it stands down there is no point
        // copying every buffer into a queue nobody drains.
        if (ears.available) runCatching { ears.feed(buf, n) }
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

  /**
   * Continuous vision — the thing that makes her an assistant watching a screen
   * rather than one guessing at a text list.
   *
   * This loop was written, then switched off, and for a long time nothing
   * called it while the system instruction went on telling her "frames stream
   * to you". She was blind and had been told she could see, which is the
   * shortest possible description of every failure in the handoff.
   *
   * It is cheap because the CHECK is not the FRAME: twice a second it compares
   * a cheap accessibility dump, and only a screen that actually moved spends a
   * ~65KB JPEG. A phone sitting still costs nothing at all, and the 1 FPS
   * ceiling is enforced in sendFrame for everyone.
   */
  private fun startFrameLoop(ws: WebSocket) {
    if (frameThread != null) return
    frameThread = Thread {
      while (!cancelled && ready) {
        try {
          Thread.sleep(FRAME_MS)
          val dump = runCatching { JSONObject(service.dumpScreen()) }.getOrNull()
          // Chaka's own UI is not the screen she is working on.
          if (dump == null || dump.optString("pkg") == CHAKA_PKG) continue

          val nowSig = sig(dump)
          val now = System.currentTimeMillis()
          val moved = nowSig != lastFrameSig
          val stale = now - lastFrameAt > FRAME_HEARTBEAT_MS
          // ON CHANGE ONLY. Never stream at a steady 1 FPS "so she is always
          // watching" — that was tried and it does not make her attentive, it
          // makes her mute.
          //
          // Measured, not assumed. Identical question, identical 45s gap, same
          // prompt: with ~7 change-driven frames she answered correctly in four
          // seconds; with ~55 frames at 1 FPS she failed, then stopped
          // responding altogether — she would not even answer "say the word
          // HELLO" while the socket was open and frames were still going up.
          //
          // 834628f said this three months ago ("injecting input constantly
          // disrupted turn-taking") and I talked myself out of it because the
          // same commits were also flooding the uplink. The uplink was a
          // separate bug. This one is real: a frame is an input, and a model
          // buried in inputs stops producing turns.
          if (!moved && !stale) continue

          sendFrame(ws, forSig = nowSig, marks = dump.optJSONArray("els"))
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
   * Sends text into the live conversation.
   *
   * This MUST be realtimeInput, not clientContent. On the Gemini 3.x live
   * models clientContent is accepted only for seeding history before the first
   * model turn (and only with historyConfig.initialHistoryInClientContent set);
   * mid-session it is not a supported way to say anything. Everything the
   * native side pushes — drives, nudges, corrections, the prompt that goes with
   * a screenshot — was going down that path, which is why those messages landed
   * as an interruption she then talked straight over.
   *
   * It was previously blamed for the 1007s and moved to clientContent. That was
   * a misread: the same commits also sent a ~250KB frame immediately before
   * every one of those messages, and the frame was the flood. The shape here is
   * the documented one — a plain string, not a Blob.
   */
  private fun sendText(ws: WebSocket, text: String): Boolean = runCatching {
    lastTextSentAt = System.currentTimeMillis()
    ws.send(JSONObject().put("realtimeInput", JSONObject().put("text", text)).toString())
  }.getOrDefault(false)

  /**
   * Puts the screen in front of her AND makes her deal with it: the frame goes
   * up the video stream, the prompt up the text stream, which commits a turn.
   *
   * This used to be one clientContent turn carrying inline_data and the text
   * together. On this model that is not a supported mid-session message: the
   * logs show every injection followed within ~100ms by "interrupted", then the
   * same failed tap repeated a second later. She was being interrupted by an
   * image she never received — 27 of them in one four-minute recording, while
   * the user was asking out loud "can't you use your vision?".
   */
  private fun showScreen(ws: WebSocket, b64: String, forSig: String, prompt: String): Boolean {
    if (!emitFrame(ws, b64, forSig)) return false
    return sendText(ws, prompt)
  }

  /**
   * The ONE place a JPEG goes out, and the only thing that may claim she has
   * seen a screen. Everything downstream — the action gate, the false-claim
   * detector — trusts lastFrameSig, so nothing else is allowed to set it.
   */
  private fun emitFrame(ws: WebSocket, b64: String, forSig: String): Boolean {
    val sent = runCatching {
      ws.send(
        JSONObject().put(
          "realtimeInput",
          JSONObject().put("video", JSONObject().put("mimeType", "image/jpeg").put("data", b64))
        ).toString()
      )
    }.getOrDefault(false)
    if (sent) {
      lastFrameSig = forSig
      lastFrameAt = System.currentTimeMillis()
      lastRealLookAt = lastFrameAt   // she has genuinely been shown this screen
      framesSent++
      if (framesSent % 10 == 1) Log.i(TAG, "vision: $framesSent frames streamed (${b64.length} b64 each)")
    }
    return sent
  }

  private fun sendFrame(
    ws: WebSocket,
    force: Boolean = false,
    forSig: String = "",
    timeoutMs: Long = 4000,
    marks: JSONArray? = null
  ): Boolean {
    val now = System.currentTimeMillis()
    synchronized(frameLock) {
      // The 1 FPS ceiling is the API's and Android's both — never breach it,
      // whoever is asking. A caller that can't have a frame this instant is
      // told so and carries on with the one already delivered.
      if (!force && now - lastFrameAt < MIN_FRAME_GAP_MS) return false
      lastFrameAt = now
    }
    // Callers that have just dumped the tree hand it over; anyone else pays for
    // one more walk, because a frame without numbers on it is a frame she has
    // to aim at by eye.
    var sig = forSig
    var els = marks
    if (els == null || sig.isEmpty()) {
      val dump = runCatching { JSONObject(service.dumpScreen()) }.getOrNull()
      if (els == null) els = dump?.optJSONArray("els")
      if (sig.isEmpty()) sig = dump?.let { sig(it) } ?: ""
    }
    val shot = captureBlocking(timeoutMs, els) ?: return false
    return emitFrame(ws, shot, sig)
  }

  /**
   * LOOK BEFORE YOU ACT. Guarantees that a picture of the screen she is about
   * to act on has actually reached her, and returns false only when the device
   * could not produce one.
   *
   * Almost always free: the streaming loop has usually sent this exact screen
   * already, so this is a signature comparison and nothing more. It spends a
   * capture only when she is about to act on a screen she has not been shown —
   * which is precisely the case that produced every failure in the handoff: the
   * blind tap from the element list that toggled Developer options off, the
   * scrolling past a setting sitting in front of her.
   */
  private fun ensureSeen(dump: JSONObject): Boolean {
    val ws = socket ?: return false
    val want = sig(dump)
    val now = System.currentTimeMillis()
    if (want.isNotEmpty() && want == lastFrameSig && now - lastFrameAt < FRAME_FRESH_MS) return true
    Log.i(TAG, "look-before-act: screen not yet shown to her, sending a frame first")
    return sendFrame(ws, force = true, forSig = want, timeoutMs = 1600, marks = dump.optJSONArray("els"))
  }

  /**
   * [timeoutMs] matters because the before/after frames are captured on the
   * websocket's reader thread — the same thread that carries her voice. A slow
   * capture there is a stutter in her speech, so the paths around an action
   * give up quickly and say they saw nothing rather than hold the audio up.
   */
  private fun captureBlocking(timeoutMs: Long = 4000, marks: JSONArray? = null): String? {
    var result: String? = null
    val lock = Object()
    var done = false
    // Small + cheap: she needs to recognise the screen, not read fine print, and
    // read_screen gives her exact labels anyway.
    //
    // [marks] draws each tappable element's index onto the frame — Set-of-Marks.
    // It has been in the service since v2.1.5 and ChakaOperator has always used
    // it; Live Mode passed null and never once had the numbers, which is why she
    // aims by eye here and misses.
    service.captureScreenshot(marks, LIVE_FRAME_WIDTH, LIVE_FRAME_QUALITY) { b64 ->
      synchronized(lock) { result = b64; done = true; lock.notifyAll() }
    }
    synchronized(lock) {
      val deadline = System.currentTimeMillis() + timeoutMs
      while (!done && System.currentTimeMillis() < deadline) {
        runCatching { lock.wait(250) }
      }
    }
    return result
  }

  /** Sends a typed/spoken message from the user into the live session. */
  fun say(text: String) {
    val ws = socket ?: return
    val said = text.trim()
    if (said.isEmpty()) return
    // Typed instructions used to bypass inputTranscription entirely, so the
    // native task/proof guards still described an older request. That made the
    // safety layer blind precisely when a user was using the text chat.
    if (!isFollowUp(said.lowercase())) {
      currentRequest = said
      clearSwitchProof()
      plan.clear(); planStep = 0; planGoal = ""
      stateActionCount.clear(); triedFromState.clear(); stateVisits.clear()
      noProgressRun = 0
    }
    pendingUserWord = said
    synchronized(recentSpeech) {
      recentSpeech.append(' ').append(said.lowercase())
      if (recentSpeech.length > 600) recentSpeech.delete(0, recentSpeech.length - 600)
    }
    taskActive = true
    nudges = 0
    startNativeWirelessDebuggingCheck()
    sendText(ws, said)
  }

  /** Label of element [i] on the current screen, if present. */
  /**
   * Turns whatever she sent into a real on-screen point, or null if it can't be
   * one. Fractions (0..1) are the documented form; plain pixels are accepted
   * because she reaches for them anyway; anything outside the screen is refused
   * rather than dispatched into nowhere.
   */
  private fun normalizeCoords(rx: Double, ry: Double, w: Int, h: Int): Pair<Int, Int>? {
    if (rx < 0 || ry < 0 || w <= 0 || h <= 0) return null
    val x: Int; val y: Int
    if (rx <= 1.0 && ry <= 1.0) {
      x = (rx * w).toInt(); y = (ry * h).toInt()
    } else {
      x = rx.toInt(); y = ry.toInt()
    }
    if (x !in 0..w || y !in 0..h) return null
    return x to y
  }

  /**
   * Refuses a jump to an app or site that has nothing to do with the current
   * plan. Getting stuck is not a reason to go and do something else, and it is
   * the behaviour the user notices most: the task is dropped silently and she
   * turns up somewhere unrelated.
   */
  private fun offPlanGuard(target: String): JSONObject? {
    if (target.isBlank()) return null
    // NOT conditional on a plan existing. This guard used to give up whenever
    // plan was empty — and she only sets a plan for jobs she thinks are hard,
    // so every "simple" task ran with no wandering protection at all. That is
    // how she left a half-finished job and turned up in another app entirely.
    // With no plan, the request itself is the boundary.
    if (plan.isEmpty() && currentRequest.isBlank()) return null
    val t = target.lowercase()
    val scope = (planGoal + " " + plan.joinToString(" ") + " " + currentRequest + " " +
      synchronized(recentSpeech) { recentSpeech.toString() }).lowercase().trim()
    // NO BOUNDARY, NO BLOCK. Judging "is this app part of the task?" requires
    // knowing the task. With an empty scope this refused to open Settings —
    // where most of her work happens — on the basis of nothing at all. A guard
    // with no evidence must stand down, not guess.
    if (scope.length < 8) return null
    // Settings and the launcher are not wandering. They are how you get
    // anywhere, and blocking them strands her at the first step of almost
    // every task she is ever given.
    if (t.contains("setting") || t.contains("launcher") || t.contains("home")) return null
    // Mentioned anywhere in the goal, the steps, or what the user has said? Fine.
    val words = t.split(Regex("[^a-z0-9]+")).filter { it.length > 2 }
    if (words.isEmpty() || words.any { scope.contains(it) }) return null
    Log.w(TAG, "OFF-PLAN blocked: '$target' — scope was \"${scope.take(120)}\"")
    return refusing("offplan:$t", JSONObject()
      .put("ok", false)
      .put("off_plan", true)
      .put(
        "error",
        "\"$target\" has nothing to do with what you were asked to do. You do not get to switch tasks because " +
          "this one is hard."
      )
      .put("your_plan", planText())
      .put(
        "do_now",
        "Go back to the plan above. If you are stuck on the current step, say so OUT LOUD - what you tried and what " +
          "is blocking you - and ask the user how to proceed. Being stuck is something to report, never a reason to " +
          "wander off into another app."
      ))
  }

  /**
   * If what she says she's scrolling to find is already on this screen, say so
   * and stop her. Scrolling past a visible target is one of the easiest ways to
   * lose a task, and it looks — correctly — like she isn't seeing the screen.
   */
  /**
   * Finds a named row on the current screen. Every content word must appear in
   * one label, so "Wireless debugging" does not match the section heading
   * "Debugging" — the mistake that had her tapping a title and getting nowhere.
   */
  private fun findOnScreen(dump: JSONObject, words: List<String>): JSONObject? {
    if (words.isEmpty()) return null
    val els = dump.optJSONArray("els") ?: return null
    var best: JSONObject? = null
    var bestScore = Int.MIN_VALUE
    for (k in 0 until els.length()) {
      val e = els.optJSONObject(k) ?: continue
      // Both, joined. On the row we keep failing to find, the title TextView
      // has text="Wireless debugging" and is NOT clickable, while the switch
      // beside it has text="" and content-desc="Wireless debugging". Reading
      // one field and falling back to the other finds whichever node comes
      // first and misses the one that actually matters.
      val label = listOf(e.optString("text"), e.optString("desc"))
        .filter { it.isNotBlank() }.joinToString(" ")
      if (label.isBlank()) continue
      val l = label.lowercase()
      if (words.all { l.contains(it) }) {
        // A Settings row and its control can now share the same label. Prefer
        // the control so a state answer and a requested toggle use the exact
        // switch bounds, never the row that opens a different page.
        val score = (if (e.optBoolean("toggle")) 100 else 0) +
          (if (e.optBoolean("clickable")) 10 else 0)
        if (score > bestScore) {
          bestScore = score
          best = JSONObject()
            .put("index", e.optInt("i"))
            .put("label", label)
            .put("clickable", e.optBoolean("clickable", false))
            .apply {
              if (e.optBoolean("toggle")) put("switch_says", if (e.optBoolean("on")) "ON" else "OFF")
            }
        }
      }
    }
    return best
  }

  private fun clearSwitchProof() {
    lastSwitchProofLabel = ""
    lastSwitchProofState = ""
    lastSwitchProofRequest = ""
    lastSwitchProofAt = 0L
    clearTargetLock()
  }

  private fun clearTargetLock() {
    lockedTarget = ""
    lockedTargetRequest = ""
    lockedTargetAt = 0L
    lockedTargetHasSwitch = false
  }

  private fun lockTarget(target: String, hasSwitch: Boolean) {
    lockedTarget = target
    lockedTargetRequest = currentRequest
    lockedTargetAt = System.currentTimeMillis()
    lockedTargetHasSwitch = hasSwitch
    Log.i(TAG, "target lock: '$target' switch=$hasSwitch for '${currentRequest.take(80)}'")
  }

  private fun hasTargetLock(): Boolean =
    lockedTarget.isNotBlank() && lockedTargetRequest == currentRequest

  private fun sameTarget(a: String, b: String): Boolean =
    a.lowercase().replace(Regex("[^a-z0-9]+"), "") ==
      b.lowercase().replace(Regex("[^a-z0-9]+"), "")

  /** Extracts a named setting from ordinary state-check language. */
  private fun settingStateTarget(request: String): String? {
    val patterns = listOf(
      Regex("(?:check\\s+(?:if|whether)|whether)\\s+(.+?)\\s+(?:is|are)\\s+(?:on|off|enabled|disabled)\\b", RegexOption.IGNORE_CASE),
      Regex("(?:is|are)\\s+(.+?)\\s+(?:on|off|enabled|disabled)\\b", RegexOption.IGNORE_CASE)
    )
    val raw = patterns.firstNotNullOfOrNull { it.find(request)?.groupValues?.getOrNull(1) } ?: return null
    val target = raw.trim().replace(Regex("^(?:the\\s+)?", RegexOption.IGNORE_CASE), "")
    return target.takeIf { it.split(Regex("\\s+")).any { word -> word.length > 2 } }
  }

  private fun targetLockedResponse(action: String): JSONObject = JSONObject()
    .put("ok", false)
    .put("target_locked", lockedTarget)
    .put(
      "error",
      "\"$lockedTarget\" has already been found exactly. $action is blocked because it could move past or act beside the target."
    )
    .put(
      "do_now",
      if (lockedTargetHasSwitch)
        "Read the proven switch_is result, call set_found_switch only if the user asked to change it, then finish."
      else
        "Call open_found_target. It opens this exact row natively; do not tap by coordinates or scroll again."
    )

  private fun isWirelessDebuggingStateCheck(request: String): Boolean {
    val normalized = request.lowercase().replace(Regex("[^a-z0-9]+"), "")
    return normalized.contains("wirelessdebugging") &&
      (needsSwitchProof(request) || request.lowercase().contains("check"))
  }

  /**
   * Starts the native finite workflow before the model has a chance to choose a
   * swipe. It is deliberately narrow: known system setting, exact state check,
   * direct Android entry point, bounded one-way selector scan, native readback.
   */
  private fun startNativeWirelessDebuggingCheck(): Boolean {
    if (!isWirelessDebuggingStateCheck(currentRequest) || nativeSettingsController) return false
    nativeSettingsController = true
    Thread {
      val raw = runCatching { service.checkWirelessDebuggingState() }.getOrElse { error ->
        JSONObject().put("ok", false).put("error", error.message ?: "Native Settings check failed.").toString()
      }
      val result = runCatching { JSONObject(raw) }.getOrElse {
        JSONObject().put("ok", false).put("error", "Native Settings check returned an unreadable result.")
      }
      val state = result.optString("switch_is")
      nativeSettingsController = false
      pendingLook = true
      socket?.let { ws ->
        if (result.optBoolean("ok") && state in setOf("ON", "OFF")) {
          recordSwitchProof("Wireless debugging", state)
          sendText(
            ws,
            "[SYSTEM] Native Settings controller completed the user's exact request. Wireless debugging is $state " +
              "(read directly from Android's checked switch). Do NOT scroll, tap, or search further. Tell the user " +
              "the result, then call task_done."
          )
        } else {
          taskActive = false
          sendText(
            ws,
            "[SYSTEM] Native Settings controller stopped safely: ${result.optString("error", "Wireless debugging could not be verified.")} " +
              "Do NOT scroll or guess. Tell the user exactly that it could not be verified."
          )
        }
      }
    }.also { it.isDaemon = true; it.start() }
    return true
  }

  private fun recordSwitchProof(label: String, state: String) {
    if (label.isBlank() || state !in setOf("ON", "OFF")) return
    lastSwitchProofLabel = label
    lastSwitchProofState = state
    lastSwitchProofRequest = currentRequest
    lastSwitchProofAt = System.currentTimeMillis()
    Log.i(TAG, "switch proof: '$label' is $state for '${currentRequest.take(80)}'")
  }

  /** Settings requests must end with a native state reading for the named control. */
  private fun needsSwitchProof(request: String): Boolean {
    val r = request.lowercase()
    // "Check my email" must not demand an Android switch proof. Only a phrase
    // that actually asks for or changes an ON/OFF state gets this strict gate.
    return listOf(
      "enabled", "disabled", "turned on", "turned off", "turn on", "turn off",
      "enable", "disable", "switch on", "switch off"
    ).any { r.contains(it) } ||
      Regex("\\b(is|are)\\b.*\\b(on|off)\\b").containsMatchIn(r)
  }

  private fun hasSwitchProofForCurrentRequest(): Boolean {
    if (lastSwitchProofState.isBlank() || lastSwitchProofRequest != currentRequest) return false
    // A stale reading from earlier in a long conversation is not proof of the
    // setting after the current task's actions.
    if (System.currentTimeMillis() - lastSwitchProofAt > 120_000L) return false
    val stop = setOf(
      "check", "whether", "turned", "turn", "enable", "disable", "enabled", "disabled",
      "switch", "setting", "settings", "please", "would", "could", "with", "that", "this",
      "what", "tell", "about", "status", "state", "the", "and", "for", "are", "was", "is",
      "on", "off"
    )
    val terms = currentRequest.lowercase().split(Regex("[^a-z0-9]+"))
      .filter { it.length > 2 && it !in stop }
    // Wi-Fi is the important edge case here: "wifi" in the request and
    // "Wi-Fi" on Android must be the same target, not an empty target that
    // accidentally accepts evidence from some unrelated switch.
    val proof = lastSwitchProofLabel.lowercase().replace(Regex("[^a-z0-9]+"), "")
    return terms.isEmpty() || terms.any { proof.contains(it) }
  }

  private fun alreadyOnScreen(dump: JSONObject, expect: String): JSONObject? {
    if (expect.isBlank()) return null
    val els = dump.optJSONArray("els") ?: return null
    val labels = (0 until els.length()).mapNotNull { i ->
      els.optJSONObject(i)?.let { e ->
        val t = e.optString("text", e.optString("desc", ""))
        if (t.isBlank()) null else e.optInt("i") to t
      }
    }
    if (labels.isEmpty()) return null

    val want = expect.lowercase()
      .split(Regex("[^a-z0-9]+"))
      .filter {
        it.length > 3 && it !in setOf(
          "the","this","that","with","option","appear","appears","screen","show","shows",
          "button","setting","settings","find","view",
          // Filler from the way she phrases an expectation. Leaving these in is
          // what broke this guard: "the 'Wireless debugging' setting comes into
          // view" reduced to [wireless, debugging, comes, into], every one of
          // which had to appear in a single label. No label contains "comes".
          // So the one guard written to stop her scrolling past Wireless
          // debugging never fired, not once, and she swiped 28 times with the
          // row sitting in the middle of the screen.
          "comes","come","into","onto","back","then","next","will","would","should",
          "becomes","become","visible","again","list","page","down","above","below",
          "where","which","there","here","been","have","gets","appearing"
        )
      }
    if (want.isEmpty()) return null

    // Score, don't demand-all. The label that shares the most words with what
    // she is looking for wins, and two matching words is enough to say "it is
    // right there" — as is one, if it is distinctive enough to be a real name.
    val scored = labels
      .map { (i, label) ->
        val l = label.lowercase()
        Triple(i, label, want.count { l.contains(it) })
      }
      .maxByOrNull { it.third }
      ?: return null
    // EVERY content word has to be there. Matching on one long word looked
    // generous and was wrong: hunting "Wireless debugging" it latched onto the
    // section HEADER "Debugging" and told her the row was already on screen
    // when the row was still well below the fold. She ignored it — correctly —
    // and then the hunt guard blocked the scrolling she actually needed, which
    // wedged her between two of my own guards.
    //
    // The original rule was right; only the filler words were wrong. So: all of
    // [wireless, debugging] must appear in one label. "Debugging" alone is one
    // out of two, and does not fire.
    if (scored.third < want.size) return null
    val hit = scored.first to scored.second

    Log.i(TAG, "refusing scroll — \"${hit.second}\" is already on screen at [${hit.first}]")
    // Show her, don't just tell her. She was handed this bare index thirty
    // times and swiped anyway — reasonably, because the tree splits the row and
    // the label she gets back ("Debugging") is not the one she is looking for
    // ("Wireless debugging"). The picture carries the numbered boxes and
    // settles it.
    pendingLook = true
    return JSONObject()
      .put("ok", false)
      .put("already_visible", hit.second)
      .put("index", hit.first)
      .put(
        "error",
        "\"${hit.second}\" is ALREADY on this screen, at index [${hit.first}]. There is nothing to scroll to."
      )
      .put("do_now", "Act on it now — tap_index ${hit.first} — instead of scrolling past it.")
      .put("screen_now", screenBrief(dump))
  }

  /** True when they've agreed to the switch we asked about. */
  private fun confirmsSwitch(said: String): Boolean = listOf(
    "yes", "yeah", "yep", "do that", "switch", "the new one", "go ahead", "please do", "correct"
  ).any { said.startsWith(it) || said.contains(" $it") }

  /** Corrections and asides continue the current task rather than replacing it. */
  private fun isFollowUp(said: String): Boolean = listOf(
    "no ", "not ", "wrong", "instead", "i said", "i meant", "that's not", "thats not",
    "why", "stop", "wait", "go back", "undo", "try", "again", "keep going", "continue",
    "yes", "correct", "good", "thank"
  ).any { said.startsWith(it) || said.contains(" $it") }

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
    val hit = listOf(
      "force stop", "uninstall", "clear data", "clear storage", "clear cache",
      "factory reset", "reset all settings", "erase all data", "delete account",
      "remove account", "format", "wipe", "disable", "deactivate", "log out",
      "sign out", "delete all"
    ).firstOrNull { l.contains(it) } ?: return false

    // The rail exists to stop a runaway agent, not to overrule the user. If
    // they asked for this in their own words, it's their phone and their call —
    // blocking it just sent her hunting for a worse route (it once blocked the
    // literal "uninstall Redbox TV" she'd been told to do).
    val asked = synchronized(recentSpeech) { recentSpeech.toString() }
    val synonyms = when (hit) {
      "uninstall" -> listOf("uninstall", "remove", "delete")
      "force stop" -> listOf("force stop", "stop the app", "kill")
      "clear data", "clear storage", "clear cache" -> listOf("clear", "wipe")
      "log out", "sign out" -> listOf("log out", "sign out", "logout")
      else -> listOf(hit)
    }
    if (synonyms.any { asked.contains(it) }) {
      Log.i(TAG, "allowing \"$label\" — the user asked for it")
      return false
    }
    return true
  }

  /** Compact description of the screen, for action feedback. */
  private fun screenBrief(dump: JSONObject): String {
    val els = dump.optJSONArray("els") ?: return "(nothing readable)"
    val sb = StringBuilder()
    var n = 0
    for (k in 0 until els.length()) {
      if (n >= 22) break
      val e = els.optJSONObject(k) ?: continue
      val label = listOf(e.optString("text"), e.optString("desc"))
        .filter { it.isNotBlank() }.joinToString(" ")
      if (label.isBlank()) continue
      sb.append("[").append(e.optInt("i")).append("] ").append(label.take(34))
      if (e.optBoolean("toggle")) sb.append(if (e.optBoolean("on")) "(ON)" else "(OFF)")
      sb.append("  ")
      n++
    }
    return sb.toString().trim().ifEmpty { "(nothing readable)" }
  }

  /**
   * WHAT is on screen, regardless of the order the tree hands it to us.
   *
   * sig() hashes the element array in order, and the order is not stable: the
   * same unchanged screen comes back as "[0] Developer options, [1] Navigate
   * up" one moment and "[0] Navigate up, [1] Developer options" the next. So a
   * stationary screen looked like it was changing — which defeated the
   * end-of-list check (it never fired) and made a scroll that moved nothing
   * look like it had worked. Seventeen steps, one screen, no scrolling.
   */
  private fun contentSig(dump: JSONObject): String {
    val els = dump.optJSONArray("els") ?: return ""
    return (0 until els.length())
      .mapNotNull { k ->
        els.optJSONObject(k)?.let { e ->
          listOf(e.optString("text"), e.optString("desc"))
            .filter { it.isNotBlank() }.joinToString(" ").takeIf { it.isNotBlank() }
        }
      }
      .sorted().joinToString("|").hashCode().toString()
  }

  private fun sig(dump: JSONObject): String =
    dump.optJSONArray("els")?.toString()?.hashCode()?.toString() ?: ""

  /**
   * Runs an action and reports WHAT IT ACTUALLY DID. Previously every action
   * returned a bare {"ok":true}, so she was acting blind: swipe, "ok", swipe
   * again — which is how she ended up opening the notification shade over and
   * over while believing she was heading for the app drawer. Now each result
   * carries the app she landed in, whether the screen moved, and a repeat
   * warning, so a wrong move is self-evident on the very next step.
   */
  /** Same thing, roughly — enough to notice she is re-finding what she has. */
  private fun sameTargetish(a: String, b: String): Boolean {
    val x = a.lowercase().trim(); val y = b.lowercase().trim()
    if (x == y) return true
    // Containment alone is far too loose. "Revoke USB debugging
    // authorisations" contains "USB debugging" and is a different setting —
    // treating them as the same let a wrong row block every further search for
    // the right one, 62 times over.
    val longer = maxOf(x.length, y.length).coerceAtLeast(1)
    val shorter = minOf(x.length, y.length)
    return (y.contains(x) || x.contains(y)) && shorter.toDouble() / longer >= 0.75
  }

  /**
   * ONE permission check, for every way of touching a control.
   *
   * This logic lived inside tap_index and nowhere else, so five other paths —
   * tap_at, tap_found, long_press_at, set_found_switch, open_found_target —
   * could change any switch on the phone with nothing to stop them. She turned
   * Developer options OFF through one of them, mid-task, unasked, and the owner
   * had to re-enable it by hand. Guarding one door of six is not guarding.
   *
   * Returns a refusal, or null when this control is genuinely part of the task.
   */
  private fun changeBlocked(label: String): JSONObject? {
    if (label.isBlank()) return null
    val req = (currentRequest + " " + synchronized(recentSpeech) { recentSpeech.toString() }).lowercase()
    val name = label.lowercase().trim()
    val tokens = name.split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
    val mentioned = req.contains(name) || (tokens.isNotEmpty() && tokens.all { req.contains(it) })
    val verbs = "turn on|turn off|turn|enable|disable|switch on|switch off|toggle|activate|deactivate|put on|put off|set"
    val askedToChangeThis =
      Regex("($verbs)\\W+(\\w+\\W+){0,2}?" + Regex.escape(name)).containsMatchIn(req) ||
      Regex("($verbs)\\W+(\\w+\\W+){0,2}?" + Regex.escape(tokens.firstOrNull() ?: name)).containsMatchIn(req)

    if (!mentioned) {
      Log.w(TAG, "BLOCKED change to \"$label\" — never mentioned in \"$currentRequest\"")
      return refusing("change:$name", JSONObject().put("ok", false).put("off_task", true)
        .put("the_request", currentRequest)
        .put("error", "\"$label\" is not part of what was asked. Do not change settings nobody mentioned.")
        .put("do_now", "Leave it alone and go back to the actual request."))
    }
    if (!askedToChangeThis) {
      Log.w(TAG, "BLOCKED change to \"$label\" — mentioned, but not asked to change")
      return refusing("change:$name", JSONObject().put("ok", false).put("off_task", true)
        .put("the_request", currentRequest)
        .put("error", "\"$label\" was mentioned, but nobody asked you to CHANGE it. Reading it is not permission to touch it.")
        .put("do_now", "Leave it exactly as it is. Report its state if that was the question, and move on."))
    }
    return null
  }

  /** The switch under a point, so a coordinate tap is checked like a named one. */
  private fun toggleLabelAt(dump: JSONObject, x: Int, y: Int): String? {
    val els = dump.optJSONArray("els") ?: return null
    for (k in 0 until els.length()) {
      val e = els.optJSONObject(k) ?: continue
      if (!e.optBoolean("toggle")) continue
      if (x < e.optInt("x1") || x > e.optInt("x2") || y < e.optInt("y1") || y > e.optInt("y2")) continue
      return listOf(e.optString("text"), e.optString("desc")).filter { it.isNotBlank() }.joinToString(" ")
    }
    return null
  }

  /**
   * Is this a human giving an instruction, or is it the room?
   *
   * The owner's web assistant runs the browser's own recogniser alongside the
   * audio stream with interimResults=false, so it only reports when real WORDS
   * were recognised — music, crowd and singing never produce a final result.
   * That second opinion is why it can sit in a noisy market and not react until
   * genuinely spoken to.
   *
   * Android will not hand the microphone to a second recogniser while the Live
   * socket is streaming from it, so the same principle is applied to the
   * transcript instead: an instruction has to look like one. Gemini transcribed
   * "turn on the debugging thing" as "Ton nom de debugging team", that nonsense
   * became the_request, and every guard downstream then reasoned carefully
   * about garbage — blocking Settings, and picking the wrong control.
   *
   * Deliberately generous. Refusing to hear the owner is far worse than acting
   * on the odd stray phrase, so this rejects only what is clearly not English
   * instruction.
   */
  /**
   * Was that the owner, or the room?
   *
   * The second recogniser is the real evidence: it only reports when actual
   * words were recognised, so noise, music and crowd never satisfy it. When it
   * cannot tell us — older device, no recogniser, nothing reported yet — we
   * fall back to judging the transcript, and if that is also unsure we let it
   * through. Being deaf to him is far worse than acting on a stray phrase.
   */
  private fun aPersonSaidThis(said: String): Boolean {
    when (ears.heardHumanSpeech()) {
      true -> {
        Log.i(TAG, "ears confirm a person spoke (\"${ears.lastWords.take(40)}\")")
        return true
      }
      false -> {
        // The recogniser is working and has heard no words recently. This is
        // the market-full-of-noise case, and the reason for all of it.
        Log.w(TAG, "ears heard no human speech — treating \"${said.take(40)}\" as noise")
        return false
      }
      null -> return soundsLikeAnInstruction(said)   // cannot tell; judge the words
    }
  }

  private fun soundsLikeAnInstruction(said: String): Boolean {
    val words = said.lowercase().split(Regex("[^a-z0-9']+")).filter { it.isNotBlank() }
    if (words.size < 3) return false
    // Ordinary connective English. Real speech is full of it; a mistranscription
    // of noise, or of another language, usually is not.
    val common = setOf(
      "the","a","an","is","are","was","it","this","that","to","on","off","in","of","and","or","for",
      "my","me","you","your","i","can","could","would","please","do","does","did","go","get","open",
      "turn","check","find","tell","show","what","where","which","how","if","not","don't","dont",
      "now","then","back","up","down","with","from","have","has","need","want","let","make","see","look"
    )
    val hits = words.count { it in common }
    // At least a fifth of it should be everyday English, or it is not a sentence
    // said to her — it is something the microphone happened to pick up.
    return hits.toDouble() / words.size >= 0.20
  }

  // Every refusal ever handed back, by what it was refusing.
  private val refusals = HashMap<String, Int>()

  /**
   * A refusal that cannot repeat forever.
   *
   * Eight guards in this file have failed the same way: they say "no" clearly
   * and say nothing about what to do INSTEAD, so she tries the same thing
   * again, gets the same "no", and the pair of us loop at one call a second.
   * The block on "Revoke USB debugging authorisations" was correct twice over
   * and still produced a loop, because being right about the refusal is not the
   * same as leaving her somewhere to go.
   *
   * So refusing is now a countable event. The first two carry a warning; the
   * third stops the task and hands it back to the owner with the real options
   * named. Any guard that routes through here is incapable of trapping her.
   */
  private fun refusing(key: String, body: JSONObject, dump: JSONObject? = null): JSONObject {
    val n = (refusals[key] ?: 0) + 1
    refusals[key] = n
    if (n >= 3) {
      refusals.remove(key)
      taskActive = false
      pendingLook = true
      Log.w(TAG, "REFUSAL ESCALATED after $n attempts at '$key' — stopping and asking the user")
      return JSONObject()
        .put("ok", false)
        .put("stop", true)
        .put("refused_times", n)
        .put(
          "error",
          "You have been refused this same thing $n times. Trying it again will be refused again. This is not " +
            "something to work around."
        )
        .put(
          "do_now",
          "STOP and TALK TO THEM. Say out loud, in plain words: what you were trying to do, why you cannot, and " +
            "what you can actually see on screen. Then ask them which they meant, naming the real options in their " +
            "exact on-screen words. Do not act again until they answer."
        )
        .apply { dump?.let { put("screen_now", screenBrief(it)) } }
    }
    return body
      .put("attempt", n)
      .put(
        "instead_of_repeating",
        "Do NOT try this again — it will be refused again. Do the thing suggested above, or if you cannot, say out " +
          "loud what is blocking you and ask. One more identical attempt and the task stops."
      )
  }

  private fun planText(): String {
    if (plan.isEmpty()) return "(no plan set — call set_plan if this needs more than one step)"
    return "GOAL: $planGoal\n" + plan.mapIndexed { i, st ->
      val mark = when { i < planStep -> "[x]"; i == planStep -> "[NOW]"; else -> "[ ]" }
      "$mark ${i + 1}. $st"
    }.joinToString("\n")
  }

  /**
   * Action-effect verification, done on-device so it costs nothing in latency.
   *
   * She states what she EXPECTS an action to produce; afterwards the real screen
   * is compared against it. This is the check the ACL "Don't Act Blindly" work
   * identifies as the missing piece - repeated ineffective actions are the
   * majority of GUI-agent failures precisely because nothing ever compares the
   * outcome to the intention.
   */
  private fun verifyEffect(expect: String, after: JSONObject, changed: Boolean, action: String = ""): JSONObject {
    val verdict = JSONObject()
    if (expect.isBlank()) {
      return verdict.put("effect", "not_declared")
        .put("note", "You did not say what you expected. Always pass 'expect' so the result can be checked against it.")
    }
    if (!changed) {
      return verdict.put("effect", "MISMATCH")
        .put("expected", expect)
        .put("actual", "nothing changed on screen at all")
        .put("do_now", "That action had NO effect. Do not repeat it. Look at the screen and take a different route.")
    }
    // Continuous controls move by degrees. A wheel dragged from 2025 towards
    // 1969 is working even though the target isn't on screen yet - calling that
    // a MISMATCH told her a working gesture had failed, which is how she ends up
    // abandoning the one approach that does move it.
    if (action.startsWith("drag") || action.startsWith("swipe")) {
      verdict.put("effect", "moved")
        .put("expected", expect)
        .put("now", screenBrief(after).take(300))
      if (action.startsWith("drag")) {
        // A picker wheel's value usually isn't in the element tree, so after a
        // drag she genuinely cannot tell where she landed - she repeated an
        // identical drag until 2025 became 1926 and only found out when the
        // user said so. She gets the picture after every drag, always.
        pendingLook = true
        verdict.put(
          "note",
          "The control moved, but you CANNOT tell how far from the text alone. A screenshot follows: READ THE CURRENT " +
            "VALUE off it before dragging again. Then judge the distance left — a long drag while far away, a short " +
            "one when close, and reverse direction if you overshot. Never repeat the same drag blind."
        )
      } else {
        verdict.put(
          "note",
          "The screen moved. Check the value above: if it has not reached what you wanted, repeat the gesture " +
            "(smaller as you close in). If it moved the WRONG way, reverse the direction."
        )
      }
      return verdict
    }

    // Does anything she predicted actually appear on the new screen?
    val haystack = screenBrief(after).lowercase() + " " + after.optString("pkg").lowercase()
    val keywords = expect.lowercase()
      .split(Regex("[^a-z0-9]+"))
      .filter { it.length > 3 && it !in setOf("the","this","that","with","should","will","open","opens","screen","page","show","shows","appear","appears") }
    val hits = keywords.count { haystack.contains(it) }
    return if (keywords.isEmpty() || hits > 0) {
      verdict.put("effect", "as_expected")
    } else {
      verdict.put("effect", "MISMATCH")
        .put("expected", expect)
        .put("actual", screenBrief(after).take(400))
        .put(
          "do_now",
          "The screen changed, but NOT into what you expected. Something else happened - possibly you hit the wrong " +
            "thing. A screenshot follows: look at it, work out where you actually are, and FIX THIS STEP before " +
            "moving on. Do not carry on as if it worked, and do not abandon the plan."
        )
    }
  }

  private fun withOutcome(before: JSONObject, action: String, result: JSONObject): JSONObject {
    // Let the UI actually respond before judging it. Reading the screen the
    // instant a tap fires reported working actions as MISMATCH - she then
    // "corrected" correct behaviour and looped. Navigation needs longer than a
    // toggle, so taps and presses get the most.
    val settle = when {
      action.startsWith("tap") || action.startsWith("press") || action.startsWith("open") -> 750L
      action.startsWith("type") || action.startsWith("enter") -> 500L
      else -> 350L
    }
    runCatching { Thread.sleep(settle) }
    Thread.sleep(350)
    val after = runCatching { JSONObject(service.dumpScreen()) }.getOrNull() ?: return result
    val changed = sig(after) != sig(before)
    val app = after.optString("pkg")

    // LOOK AFTER YOU ACT. The result of the action goes up the video stream
    // before this tool result reaches her, so by the time she reads "it
    // changed" she is already looking at what it changed into. The capture
    // takes roughly the 200ms that used to be dead sleep here, so verification
    // costs no more than the wait it replaced.
    val sawResult = socket?.let {
      sendFrame(it, force = true, forSig = sig(after), timeoutMs = 1600, marks = after.optJSONArray("els"))
    } ?: false

    consecutiveBlocks = 0

    // Compare the outcome against what she said she expected.
    val expectWas = pendingExpect
    val verdict = verifyEffect(pendingExpect, after, changed, action)
    pendingExpect = ""
    // A swipe that just brought the thing she was hunting for into view has to
    // announce itself. She has scrolled straight past the answer repeatedly
    // while it sat in screen_now, unread, at the bottom of a wall of text.
    if (action.startsWith("swipe")) {
      alreadyOnScreen(after, expectWas)?.let { hit ->
        result.put(
          "STOP_IT_IS_HERE",
          "\"${hit.optString("already_visible")}\" is ON SCREEN NOW, at index [${hit.optInt("index")}]. " +
            "You found it. Stop scrolling, say what you can see about it, and act on it."
        )
      }
    }
    if (!changed || verdict.optString("effect") == "MISMATCH") noProgressRun++ else noProgressRun = 0
    if (verdict.optString("effect") == "MISMATCH") {
      result.put("verification", verdict)
      lastVerified = false
      // She is demonstrably wrong about the screen, so stop guessing and look.
      pendingLook = true
      Log.w(TAG, "EFFECT MISMATCH after '$action'")
    } else {
      result.put("verification", verdict)
      lastVerified = verdict.optString("effect") == "as_expected"
    }

    // A thin element list means the tree can't describe this screen (web pages,
    // custom UI). Hand her the picture rather than letting her guess blind.
    if ((after.optJSONArray("els")?.length() ?: 0) < 4) {
      pendingLook = true
      result.put("note", "This screen exposes almost no elements — a screenshot follows, work from it with tap_at.")
    }

    if (currentRequest.isNotEmpty()) result.put("the_request", currentRequest)
    if (plan.isNotEmpty()) result.put("your_plan", planText())
    if (action == lastActionSig && !changed) sameActionRepeats++ else sameActionRepeats = 0
    lastActionSig = action
    recordAction(sig(before), action, sig(after))

    val visits = stateVisits[sig(after)] ?: 0
    if (visits >= 3) {
      result.put(
        "been_here_before",
        "You have landed on this screen $visits times now. Whatever you keep doing brings you back here — " +
          "stop and choose a genuinely different route. Already tried from here: " +
          (triedFromState[sig(after)]?.joinToString(", ") ?: "(none)")
      )
    }

    result.put("screen_changed", changed).put("now_in_app", app).put("screen_now", screenBrief(after))
    result.put(
      "your_eyes",
      if (sawResult)
        "The picture you are looking at RIGHT NOW is the screen this action produced. Read it before you decide " +
          "anything — it, not your expectation, is what actually happened. If it shows anything you will need " +
          "later, or anything you were asked to check, SAY IT OUT LOUD NOW: this picture fades from your memory " +
          "within seconds, your own words do not."
      else
        "The screen could NOT be captured this time, so you are working from the text list alone. Be careful, " +
          "and call look_at_screen before anything you would need to see to get right."
    )
    if (!changed) {
      result.put(
        "warning",
        if (action.startsWith("swipe"))
          "THE SCREEN DID NOT MOVE. The page did not change. You are still exactly where you were — " +
            "do NOT tell the user it moved, and do not repeat the same swipe."
        else
          "That did NOT change the screen. Do something different — the same action again will fail the same way."
      )
    }
    if (sameActionRepeats >= 2) {
      result.put(
        "stop_repeating",
        "You have now done '$action' $sameActionRepeats times with no progress. It is the wrong move. " +
          "Look at screen_now, pick a DIFFERENT approach, or say what's blocking you."
      )
    }
    // Landing somewhere the user didn't ask for is worth flagging loudly.
    if (app == "com.android.systemui") {
      result.put(
        "note",
        "You are in the system UI (notification shade / quick settings), not an app. Press back to leave it."
      )
    }
    return result
  }

  /**
   * Refuses an action she has already tried from this exact screen. This is what
   * breaks oscillation: the pair (screen, action) is the thing that repeats, not
   * the action alone. The refusal also hands back everything already attempted
   * from here, so the next choice is an informed one rather than another guess.
   */
  private fun loopGuard(dump: JSONObject, action: String): JSONObject? {
    val state = sig(dump)
    val key = "$state|$action"
    val times = (stateActionCount[key] ?: 0)
    if (times >= 2) {
      val tried = triedFromState[state]?.joinToString(", ") ?: "(none recorded)"
      val visits = stateVisits[state] ?: 0
      consecutiveBlocks++
      Log.w(TAG, "LOOP BLOCKED: '$action' already tried $times times from this screen (seen ${visits}x, block #$consecutiveBlocks)")

      // She can ignore a refusal indefinitely, so escalate: stop the task, put
      // the real screen in front of her, and hand the decision to the user.
      if (consecutiveBlocks >= 3) {
        Log.w(TAG, "escalating after $consecutiveBlocks ignored blocks — pausing task")
        consecutiveBlocks = 0
        taskActive = false
        pendingLook = true
        return JSONObject()
          .put("ok", false)
          .put("stop", true)
          .put(
            "error",
            "STOP. You have now tried the same blocked action several times in a row. It cannot work. " +
              "A screenshot of the real screen follows."
          )
          .put(
            "do_instead",
            "Look at that screenshot. Tell the user OUT LOUD, in plain words: where you actually are, what you were " +
              "trying to do, and what is blocking you. Then ask them how they want to proceed. Do NOT take another " +
              "action until they answer."
          )
      }
      return JSONObject()
        .put("ok", false)
        .put("looping", true)
        .put(
          "error",
          "You have already done '$action' $times times from THIS EXACT SCREEN and it brought you back here. " +
            "You are going in a circle. Already tried from this screen: $tried."
        )
        .put(
          "do_instead",
          "Do NOT repeat any of those. Call look_at_screen to see where you actually are, then find a different route " +
            "TO THE SAME GOAL. Never substitute an unrelated action and never claim the goal is met — if you genuinely " +
            "cannot find a way from here, say plainly what you tried and what is blocking you, and ask the user."
        )
        .put("screen_now", screenBrief(dump))
    }
    return null
  }

  private fun recordAction(beforeSig: String, action: String, afterSig: String) {
    val key = "$beforeSig|$action"
    stateActionCount[key] = (stateActionCount[key] ?: 0) + 1
    triedFromState.getOrPut(beforeSig) { LinkedHashSet() }.add(action)
    stateVisits[afterSig] = (stateVisits[afterSig] ?: 0) + 1
    // Keep the memory bounded over a long session.
    if (stateActionCount.size > 400) { stateActionCount.clear(); triedFromState.clear(); stateVisits.clear() }
  }

  private fun executeTool(name: String, args: JSONObject): JSONObject {
    // The user has just said something and she is about to act on the old
    // intention. Make her read it first — one interruption per utterance.
    val fresh = pendingUserWord
    if (fresh.isNotEmpty() && name !in setOf("read_screen", "look_at_screen", "read_clipboard")) {
      pendingUserWord = ""
      Log.i(TAG, "holding '$name' — user just said: \"${fresh.take(60)}\"")
      return JSONObject()
        .put("ok", false)
        .put("user_just_said", fresh)
        .put(
          "error",
          "STOP - the user spoke while you were mid-task and you were about to act on the OLD intention."
        )
        .put(
          "do_now",
          "What they just said takes priority over whatever you were doing. If it changes the goal, change what you " +
            "are doing. If it corrects a mistake, fix that. If they told you to go back or undo, do that FIRST. " +
            "Only carry on with the previous plan if their words clearly don't affect it."
        )
    }

    // Verification is only good for the screen she just looked at. If she does
    // anything else, the proof is stale and she has to look again - otherwise
    // one early hold buys her an unchecked task_done later in the session.
    if (awaitingDoneProof && name !in setOf("look_at_screen", "read_screen", "task_done")) {
      awaitingDoneProof = false
    }

    // Whatever she predicted this action would do, held for the check afterwards.
    args.optString("expect").takeIf { it.isNotBlank() }?.let { pendingExpect = it }

    // Nothing touches the phone while halted, whatever the model decides.
    if (halted && name !in setOf("read_screen", "look_at_screen", "read_clipboard", "task_done")) {
      Log.w(TAG, "refused '$name' — halted by the user")
      return JSONObject()
        .put("ok", false)
        .put("halted", true)
        .put(
          "error",
          "The user told you to STOP. You are not permitted to act. Do not continue the previous task, " +
            "do not try another route. Acknowledge that you stopped, briefly say what you had done so far, " +
            "and wait for their next instruction."
        )
    }

    if (nativeSettingsController) {
      return JSONObject()
        .put("ok", false)
        .put("native_settings_controller", true)
        .put(
          "error",
          "Android is performing the exact Wireless debugging check now. Do not issue any model-driven action or scroll."
        )
    }
    val dump = runCatching { JSONObject(service.dumpScreen()) }.getOrNull()
      ?: return JSONObject().put("error", "couldn't read the screen")

    // LOOK BEFORE YOU ACT — for real, not as an instruction she may ignore.
    // Nothing touches the phone until a picture of the screen being acted on
    // has left the device. The streaming loop has usually sent it already, so
    // this normally costs a string comparison; it spends a capture only when
    // she is about to act on something she has not been shown, which is exactly
    // the blind tap we are trying to make impossible.
    if (name in TOUCHES_THE_PHONE) ensureSeen(dump)

    // Finding a target is a state transition, not a hint for the model to
    // maybe remember. Once found, only native exact-target operations may
    // follow. This prevents the observed down-to-the-end, up-to-the-top loop.
    if (hasTargetLock()) {
      val sameLockedSearch = name == "scroll_to" && sameTarget(args.optString("target"), lockedTarget)
      // MOVING AND LOOKING ARE ALWAYS ALLOWED. The lock exists to stop her
      // ACTING on the wrong control, not to pin her to one spot. It listed only
      // reading and acting, so once it engaged she could not swipe at all —
      // she tried four times, was refused four times, and told the owner "I'm
      // stuck on the first screen, I can't scroll down". She was right, and it
      // was this. Locking a target and then forbidding movement is incoherent:
      // scrolling is how you reach the thing you are locked onto.
      val navigating = name in setOf(
        "swipe", "scroll_to", "press_button", "open_app_drawer", "wait",
        "read_screen", "look_at_screen", "read_clipboard", "task_done", "remember", "recall"
      )
      val allowed = navigating ||
        name in setOf("open_found_target", "set_found_switch", "tap_found") ||
        sameLockedSearch
      if (!allowed) {
        pendingLook = true
        return targetLockedResponse(name).put("screen_now", screenBrief(dump))
      }
    }

    // A named setting-state request must start with the deterministic locator,
    // never with vision-guided swipes. Without this gate the model can ignore
    // scroll_to altogether and recreate the same search loop by hand.
    // Only before anything has been located. Once she has found the row, she
    // may need to scroll to read what is around it — which is precisely what
    // the owner asked her to do ("tell me which ones are available") and what
    // this gate made impossible.
    if (name == "swipe" && foundLabel.isBlank()) {
      settingStateTarget(currentRequest)?.let { target ->
        pendingLook = true
        return JSONObject()
          .put("ok", false)
          .put("manual_search_blocked", true)
          .put("target", target)
          .put(
            "error",
            "This is a named setting-state check. Manual swiping is blocked because it can pass the target without proving its state."
          )
          .put("do_now", "Call scroll_to with target \"$target\". It will lock the exact row or switch and return native proof.")
          .put("screen_now", screenBrief(dump))
      }
    }

    // Only DOING something ends a hunt. Looking does not.
    //
    // This said `name != "swipe"`, and read_screen is not a swipe, so the tool
    // order on the phone came out as a perfect cycle: swipe swipe swipe swipe
    // read_screen, swipe swipe swipe swipe read_screen, forever. The block at 4
    // fired every time, was reset every time, and the halt at 8 was unreachable.
    // A guard whose own advice — "stop and look at the screen" — disarms it is
    // not a guard.
    // scroll_to has to be excluded too, or it resets the very counter it sets a
    // few lines later and can never reach its own limit. That is exactly what
    // happened: fifteen identical searches, guard never fired once.
    // Doing something with what she found clears the "stop re-finding it" state.
    if (name in TOUCHES_THE_PHONE && name != "scroll_to") { foundRepeats = 0 }
    if (name in TOUCHES_THE_PHONE && name != "swipe" && name != "scroll_to") {
      huntFor = ""; huntSwipes = 0; huntReversals = 0; huntDir = ""
    }

    return when (name) {
      "read_screen" -> {
        lastScreenReadAt = System.currentTimeMillis()
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
        // How many rows actually declare a state. On most Settings screens the
        // answer is none: the row is a plain layout and the switch beside it is
        // a separate, unlabelled node, so "Wireless debugging" arrives with no
        // ON or OFF attached to it. Asked whether it was on, she read this list,
        // found no state, and went scrolling for a better row - past the very
        // setting she was looking at. The list was never going to tell her. The
        // picture always would have.
        val known = (0 until els.length()).count { els.optJSONObject(it)?.optBoolean("toggle") == true }
        JSONObject()
          .put("app", dump.optString("pkg"))
          .put("elements", sb.toString().trim().take(4000))
          .put("switch_states_in_this_list", known)
          .put(
            "READ_THIS",
            if (known == 0)
              "NOT ONE row here tells you whether it is on or off. This list can tell you WHAT is on screen and WHERE " +
                "to tap - it cannot tell you the STATE of anything. If you were asked whether something is turned on, " +
                "you do not know yet and you must NOT guess or scroll off looking for a better row. LOOK AT THE " +
                "PICTURE and read the switch."
            else
              "Only $known row(s) here declare a state. Any switch not marked ON or OFF above is unknown from this " +
                "list - read it off the picture instead of assuming."
          )
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
          val label = hit.optString("text", hit.optString("desc", ""))
          loopGuard(dump, "tap:$label")?.let { return it }
          // A checkbox already in the wanted state must NOT be tapped again -
          // that turns it back off. She checked the delete-confirmation box,
          // was told MISMATCH, tapped again to "fix" it, and unchecked it.
          val isToggle = hit.optBoolean("toggle", false)
          val wasOn = hit.optBoolean("on", false)
          // Changing a setting nobody asked about is never part of a task. She
          // was asked to move an icon and turned Bluetooth on.
          if (isToggle && label.isNotBlank()) {
            // Everything she has been told recently, not just the last sentence.
            // currentRequest gets overwritten by asides — it read "Just calm
            // down, I will tell you what's next" at the moment she switched
            // Wi-Fi off — so a guard consulting only that has nothing to work
            // with when it matters most.
            val req = (currentRequest + " " + synchronized(recentSpeech) { recentSpeech.toString() }).lowercase()
            val name = label.lowercase().trim()
            // NO LENGTH FILTER. The old one kept only tokens longer than three
            // characters, so "Wi-Fi" became ["wi","fi"], both dropped, the list
            // came back empty and the guard skipped itself entirely. It was
            // blind to precisely the toggles that break a phone: Wi-Fi, NFC,
            // GPS, VPN. She turned Wi-Fi off mid-task and killed her own
            // connection, and nothing objected.
            val tokens = name.split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
            val mentioned = req.contains(name) || (tokens.isNotEmpty() && tokens.all { req.contains(it) })

            // MENTIONING A SETTING IS NOT PERMISSION TO CHANGE IT. "Check if
            // Wi-Fi is on" names Wi-Fi and asks for nothing to be touched. The
            // verb has to belong to THIS control, so it must appear just before
            // the name — "turn on Bluetooth" authorises Bluetooth and nothing
            // else in the same sentence.
            val verbs = "turn on|turn off|turn|enable|disable|switch on|switch off|toggle|activate|deactivate|put on|put off|set"
            val askedToChangeThis = Regex("($verbs)\\W+(\\w+\\W+){0,2}?" + Regex.escape(name)).containsMatchIn(req) ||
              Regex("($verbs)\\W+(\\w+\\W+){0,2}?" + Regex.escape(tokens.firstOrNull() ?: name)).containsMatchIn(req)

            if (mentioned && !askedToChangeThis) {
              Log.w(TAG, "refusing to toggle \"$label\" — mentioned, but never asked to be changed")
              return JSONObject()
                .put("ok", false)
                .put("off_task", true)
                .put("the_request", currentRequest)
                .put(
                  "error",
                  "\"$label\" was mentioned, but nobody asked you to change it. Reading whether it is on is not " +
                    "permission to turn it off."
                )
                .put("do_now", "Leave it exactly as it is. Report its state if that is what was asked, and move on.")
            }
            if (!mentioned) {
              Log.w(TAG, "refusing to toggle \"$label\" — not part of \"$currentRequest\"")
              return JSONObject()
                .put("ok", false)
                .put("off_task", true)
                .put("the_request", currentRequest)
                .put(
                  "error",
                  "\"$label\" is a setting the user did not mention. They asked: \"$currentRequest\". " +
                    "Do not change settings that are not part of what was asked."
                )
                .put("do_now", "Go back to the actual request. If you are lost, say so and ask.")
            }
          }
          service.tap(hit.optInt("cx"), hit.optInt("cy"))
          if (isToggle) {
            Thread.sleep(350)
            val fresh = runCatching { JSONObject(service.dumpScreen()) }.getOrNull()
            val nowOn = fresh?.optJSONArray("els")?.let { els ->
              (0 until els.length()).map { els.optJSONObject(it) }
                .firstOrNull { e ->
                  e != null && e.optBoolean("toggle") &&
                    e.optString("text", e.optString("desc", "")) == label
                }
                ?.optBoolean("on", false)
            }
            if (nowOn != null) {
              recordSwitchProof(label, if (nowOn) "ON" else "OFF")
              return JSONObject()
                .put("ok", true)
                .put("tapped", label)
                .put("checkbox_is_now", if (nowOn) "CHECKED" else "UNCHECKED")
                .put(
                  "important",
                  if (nowOn)
                    "It is CHECKED. That worked — do NOT tap it again, tapping a checked box unchecks it. " +
                      "Move on to the next thing (usually the Continue/Submit button)."
                  else
                    "It is still UNCHECKED, so the tap missed the real control. Try the LABEL TEXT beside it, " +
                      "or the whole row — not the same point again."
                )
                .put("screen_now", screenBrief(fresh))
            }
          }
          withOutcome(dump, "tap:$label", JSONObject().put("ok", true).put("tapped", label))
        }
      }
      "type_text" -> {
        val wanted = args.optString("text")
        val ok = service.typeText(wanted)
        if (!ok) JSONObject().put("ok", false).put("error", "no field focused — tap the input first")
        else withOutcome(
          dump, "type:$wanted",
          JSONObject().put("ok", true)
            .put("verify", "Check screen_now shows exactly \"$wanted\". If it doesn't, clear the field and retype before continuing.")
        )
      }
      "press_enter" -> withOutcome(dump, "enter", JSONObject().put("ok", service.pressEnter()))
      "set_plan" -> {
        plan.clear()
        planGoal = args.optString("goal")
        val arr = args.optJSONArray("steps")
        if (arr != null) for (i in 0 until arr.length()) plan.add(arr.optString(i))
        planStep = 0
        Log.i(TAG, "PLAN set: \"$planGoal\" (${plan.size} steps)")
        ChakaGuideOverlay.update("Plan: $planGoal")
        JSONObject().put("ok", true).put("plan", planText())
          .put("next", "Now do step 1. Look first if you need to, act, then check it worked before calling step_done.")
      }
      "step_done" -> {
        if (!lastVerified) {
          pendingLook = true
          return JSONObject()
            .put("ok", false)
            .put("error", "You have not verified this step. The last action either failed its check or was never checked.")
            .put(
              "do_now",
              "A screenshot follows. Look at it and confirm this step is REALLY done. If it is, act once more with a " +
                "clear 'expect' and then call step_done. If it is not, fix the step first."
            )
            .put("your_plan", planText())
        }
        lastVerified = false
        if (planStep < plan.size) planStep++
        Log.i(TAG, "step_done -> now on step ${planStep + 1}/${plan.size}")
        JSONObject().put("ok", true).put("plan", planText())
          .put(
            "next",
            if (planStep >= plan.size) "All steps are done. Verify the goal is truly met, then call task_done."
            else "Now do step ${planStep + 1}: ${plan.getOrNull(planStep)}"
          )
      }
      "wait" -> {
        // A single fixed wait is a guess. On a slow network the screen is still
        // drawing when it expires, and she starts tapping things that are not
        // there yet. So: keep looking until the screen is BOTH populated and
        // has stopped changing between checks, or the ceiling is reached.
        val asked = args.optDouble("seconds", 2.5).coerceIn(0.5, 8.0)
        val ceiling = (asked * 4).coerceAtMost(24.0)
        val startedAt = System.currentTimeMillis()
        var prevSig = sig(dump)
        var stableFor = 0
        var checks = 0
        var after = dump
        var settled = false

        Thread.sleep((asked * 1000).toLong())
        while ((System.currentTimeMillis() - startedAt) / 1000.0 < ceiling) {
          checks++
          after = runCatching { JSONObject(service.dumpScreen()) }.getOrNull() ?: after
          val count = after.optJSONArray("els")?.length() ?: 0
          val nowSig = sig(after)
          // Two consecutive identical reads with real content on screen means
          // it has finished, not that it is briefly paused mid-load.
          if (nowSig == prevSig && count >= 4) {
            stableFor++
            if (stableFor >= 2) { settled = true; break }
          } else {
            stableFor = 0
          }
          prevSig = nowSig
          Thread.sleep(1200)
        }

        val waited = Math.round((System.currentTimeMillis() - startedAt) / 100.0) / 10.0
        val count = after.optJSONArray("els")?.length() ?: 0
        if (!settled || count < 4) pendingLook = true
        JSONObject()
          .put("ok", true)
          .put("waited_seconds", waited)
          .put("checks", checks)
          .put("settled", settled)
          .put("now_in_app", after.optString("pkg"))
          .put("screen_now", screenBrief(after))
          .put(
            "note",
            when {
              settled && count >= 4 ->
                "Loaded and stable after ${waited}s. Carry on from what is on screen now."
              count < 4 ->
                "Still almost empty after ${waited}s — a screenshot follows. If it is genuinely still loading, call " +
                  "wait again with a larger seconds value. Do NOT start tapping and do NOT press back."
              else ->
                "Still changing after ${waited}s, so it is mid-load. Call wait again before acting — tapping a " +
                  "screen that is still drawing does nothing and moves the element indices under you."
            }
          )
      }
      "remember" -> {
        val label = args.optString("label"); val value = args.optString("value")
        if (label.isBlank() || value.isBlank()) {
          JSONObject().put("ok", false).put("error", "remember needs both a label and a value")
        } else {
          memRemember(label, value)
          Log.i(TAG, "remembered \"$label\" (${value.length} chars)")
          JSONObject().put("ok", true).put("label", label).put("stored_length", value.length)
            .put("note", "Saved exactly. Use recall(\"$label\") to get it back - do not retype it from memory.")
        }
      }
      "recall" -> {
        val label = args.optString("label")
        val hit = memRecall(label)
        if (hit == null) {
          JSONObject().put("ok", false)
            .put("error", "Nothing saved under \"$label\".")
            .put("known_labels", JSONArray(memLabels()))
            .put("do_now", "You do NOT know this value. Do not guess or invent one - ask the user for it, then remember() it.")
        } else {
          JSONObject().put("ok", true).put("label", label).put("value", hit)
            .put("note", "This is the exact stored value. Type it character for character.")
        }
      }
      "list_memory" -> JSONObject().put("ok", true).put("labels", JSONArray(memLabels()))
      "read_clipboard" -> {
        val clip = service.readClipboard()
        if (clip.isNullOrBlank()) {
          JSONObject().put("ok", false).put("error", "The clipboard is empty — the copy did not work.")
        } else {
          val res = JSONObject()
            .put("ok", true)
            .put("clipboard", clip.take(400))
            .put("length", clip.length)
            .put(
              "check",
              "Is this actually the value you meant to copy? A key or token is a long string of random characters — " +
                "if this looks like a label or a name instead, the copy grabbed the wrong thing, so go back and copy properly."
            )
          // She copied a Google AI Studio API key once and got three characters
          // of it, then handed those over as the key. A secret has a shape, and
          // "far too short to be one" is trivially checkable here rather than
          // hours later when the user finds out it does not work.
          val wantsSecret = listOf("key", "token", "password", "secret", "code", "api")
            .any { currentRequest.lowercase().contains(it) }
          if (wantsSecret && clip.trim().length < 16) {
            res.put("ok", false)
              .put(
                "almost_certainly_wrong",
                "This is ${clip.trim().length} characters. An API key, token or password is far longer than that — " +
                  "a Google AI Studio key is around 39. You have copied a fragment, a label, or the wrong element " +
                  "entirely. Do NOT give this to the user and do NOT paste it anywhere."
              )
              .put(
                "do_now",
                "Look at the screen, find the FULL value, and copy it properly — usually the copy button beside it " +
                  "rather than the text. Then read the clipboard again and check the length looks right."
              )
          }
          res
        }
      }
      "open_found_target" -> {
        if (!hasTargetLock()) {
          return JSONObject()
            .put("ok", false)
            .put("error", "There is no currently locked target. Call scroll_to first; never guess a row to open.")
        }
        val target = lockedTarget
        val raw = runCatching { service.openByText(target) }.getOrNull()
          ?: return JSONObject()
            .put("ok", false)
            .put("target_locked", target)
            .put("error", "The locked target could not be opened natively. Do not fall back to a guessed tap.")
        val res = JSONObject(raw).put("target_locked", target)
        if (!res.optBoolean("ok")) {
          pendingLook = true
          return res.put("screen_now", screenBrief(dump))
        }
        // The page is changing, so reset the old scroll hunt. A state check
        // keeps its lock until the detail-page switch is read; an ordinary
        // navigation target is now complete and may continue with its plan.
        huntFor = ""; huntSwipes = 0; huntReversals = 0; huntDir = ""
        val isStateTarget = settingStateTarget(currentRequest)?.let { sameTarget(it, target) } == true
        if (!isStateTarget) clearTargetLock()
        withOutcome(dump, "open_found_target:$target", res)
      }
      "set_found_switch" -> {
        foundLabel.takeIf { it.isNotBlank() }?.let { l -> changeBlocked(l)?.let { return it } }
        if (!hasTargetLock()) {
          return JSONObject()
            .put("ok", false)
            .put("error", "There is no currently locked switch. Call scroll_to first; never toggle by a guessed position.")
        }
        if (!lockedTargetHasSwitch) {
          return JSONObject()
            .put("ok", false)
            .put("target_locked", lockedTarget)
            .put("error", "The locked target is a row, not a readable switch. Call open_found_target first.")
        }
        val target = lockedTarget
        val desired = args.optBoolean("enabled")
        val raw = runCatching { service.setSwitchByText(target, desired) }.getOrNull()
          ?: return JSONObject()
            .put("ok", false)
            .put("target_locked", target)
            .put("error", "The exact switch could not be read back after the request. Its state is unknown; do not claim it changed.")
        val res = JSONObject(raw).put("target_locked", target)
        if (res.optBoolean("verified", false)) {
          val state = res.optString("switch_is")
          recordSwitchProof(target, state)
          lockedTargetHasSwitch = true
        }
        if (!res.optBoolean("ok")) {
          pendingLook = true
          return res.put("screen_now", screenBrief(dump))
        }
        // The exact requested state is now proven, so a longer multi-step task
        // is free to continue instead of being pinned to a completed switch.
        clearTargetLock()
        // No screen change is normal when the switch was already in the desired
        // state; it is still a successful, freshly verified outcome.
        if (!res.optBoolean("changed", false)) {
          pendingLook = true
          return res.put("screen_now", screenBrief(dump))
        }
        withOutcome(dump, "set_found_switch:$target", res)
      }
      // Searching by name is a solved problem the moment the phone does the
      // looking. She scrolled past Wireless debugging over and over, up and
      // down, having ALREADY read it out correctly minutes earlier — so this is
      // not her forgetting what the setting is. It is that "is it on screen
      // now?" is a question she has to re-answer after every swipe, from a
      // picture that has already faded, while she fires the next swipe. The
      // element tree answers it exactly, every time, for free.
      "tap_found" -> {
        foundLabel.takeIf { it.isNotBlank() }?.let { l -> changeBlocked(l)?.let { return it } }
        if (foundLabel.isBlank() || foundRowY < 0) {
          return JSONObject().put("ok", false)
            .put("error", "Nothing has been located yet. Call scroll_to first, then tap_found.")
        }
        val wantSwitch = args.optString("part", "row").startsWith("s")
        val x = if (wantSwitch && foundSwitchX >= 0) foundSwitchX else foundRowX
        val y = if (wantSwitch && foundSwitchY >= 0) foundSwitchY else foundRowY
        Log.i(TAG, "tap_found ${if (wantSwitch) "switch" else "row"} of '$foundLabel' at $x,$y")
        service.tap(x, y)
        withOutcome(
          dump, "tap_found:${if (wantSwitch) "switch" else "row"}:$foundLabel",
          JSONObject().put("ok", true).put("tapped", foundLabel).put("at", "$x,$y")
        )
      }
      "scroll_to" -> {
        val target = args.optString("target").trim()
        // Same guard as swiping, for the same reason: she called this fifteen
        // times for one target, alternating direction, once it started failing.
        // Already found, nothing done since. Searching again cannot help.
        if (foundLabel.isNotBlank() && sameTargetish(target, foundLabel)) {
          foundRepeats++
          // ALWAYS AN ESCAPE. Refusing forever is worse than the loop it
          // replaced: the stored find was the WRONG row, so she could neither
          // act on it nor search past it, and the guard refused 62 times in a
          // row. A guard that cannot be satisfied is a trap.
          if (foundRepeats >= 4) {
            Log.w(TAG, "clearing stale find '$foundLabel' — refused $foundRepeats times, letting her search again")
            foundLabel = ""; foundRowX = -1; foundRowY = -1
            foundSwitchX = -1; foundSwitchY = -1; foundRepeats = 0
          } else if (foundRepeats >= 2) {
            Log.w(TAG, "scroll_to REFUSED — '$target' already located ($foundRepeats), no action taken")
            pendingLook = true
            return refusing("refind:${target.lowercase()}", JSONObject()
              .put("ok", false)
              .put("already_found", foundLabel)
              .put(
                "error",
                "You have already found \"$foundLabel\" and have not done anything with it. Searching again finds " +
                  "the same thing in the same place."
              )
              .put(
                "do_now",
                "ACT on it now: tap_found(\"switch\") to turn it on or off, or tap_found(\"row\") to open its page. " +
                  "If it is not the right row, say so out loud and search for the exact words you actually want."
              ))
          }
        } else {
          foundRepeats = 0
        }
        val hkey = "scrollto:${target.lowercase()}"
        if (hkey == huntFor) huntSwipes++ else { huntFor = hkey; huntSwipes = 1; huntReversals = 0 }
        if (huntSwipes >= 3) {
          Log.w(TAG, "scroll_to REFUSED — $huntSwipes searches for '$target'")
          huntFor = ""; huntSwipes = 0
          pendingLook = true
          return JSONObject()
            .put("ok", false)
            .put("error", "You have searched for \"$target\" $huntSwipes times and it is not being found by that name.")
            .put(
              "do_now",
              "Searching again will not help. A picture follows: LOOK at it and read what the row is ACTUALLY " +
                "called, then either scroll_to those exact words, or tap its numbered box directly. If you cannot " +
                "see it at all, say so out loud and tell the user what you can see instead."
            )
            .put("screen_now", screenBrief(dump))
        }
        val words = target.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 }
        if (words.isEmpty()) {
          return JSONObject().put("ok", false).put("error", "scroll_to needs a target, e.g. 'Wireless debugging'.")
        }
        val dirDown = !args.optString("direction", "down").startsWith("up")
        val w = dump.optInt("w"); val h = dump.optInt("h")
        val cx = w / 2
        // Deliberately SHORT and SLOW. A 280ms drag across 672px is a fling:
        // Samsung keeps decelerating it long after the gesture ends, so every
        // dump taken afterwards catches a list still in motion with its rows
        // recycled out of the tree. That is how a search could cross the whole
        // of Developer options — 16 steps down, 15 back up — and never once see
        // "Wireless debugging", while the top and bottom screens read perfectly.
        // About four rows per step, slow enough to scroll rather than throw.
        val from = if (dirDown) (h * 0.68).toInt() else (h * 0.34).toInt()
        val to = if (dirDown) (h * 0.36).toInt() else (h * 0.66).toInt()

        var here = dump
        // The app we are searching IN. An OCR hit from anywhere else is a
        // different object that happens to share the words.
        val searchPkg = dump.optString("pkg").takeIf { it.isNotBlank() }
        var steps = 0
        var atEnd = false
        val seenAll = LinkedHashSet<String>()
        while (steps <= 25) {
          // Ask Android's live window trees, not our own flattened copy. This
          // bypasses payload caps and Samsung's unreliable visible flag that
          // were losing this row while it sat plainly on screen.
          val native = runCatching { service.findByText(target) }.getOrNull()
          Log.i(TAG, "scroll_to step $steps: els=${here.optJSONArray("els")?.length() ?: 0} " +
            "findByText=${native ?: "null"}")
          // A match that is not really what was asked for must be IGNORED, not
          // locked onto. Locking on "Revoke USB debugging authorisations" while
          // hunting "USB debugging" shut every door at once: tap_at and
          // tap_index were blocked as "already found exactly", tap_found led to
          // the Revoke switch, and changeBlocked refused that — three guards,
          // each correct alone, leaving her nowhere to go.
          //
          // The real row is hidden from the tree, so the only tree match is the
          // one quoting it. Skipping it lets OCR look at the actual pixels,
          // which can see what the tree will not show.
          val nativeLabel = native?.let { runCatching { JSONObject(it).optString("label") }.getOrNull() }.orEmpty()
          if (native != null && nativeLabel.isNotBlank() && !sameTargetish(target, nativeLabel)) {
            Log.w(TAG, "scroll_to '$target' -> ignoring loose match '$nativeLabel', letting the pixels decide")
          } else if (native != null) {
            val n = JSONObject(native)
            Log.i(TAG, "scroll_to '$target' -> FOUND natively after $steps steps: $native")
            pendingLook = true
            huntFor = ""; huntSwipes = 0; huntReversals = 0; huntDir = ""
            lockTarget(target, n.has("switch_is"))
            foundLabel = n.optString("label"); lastFoundAt = System.currentTimeMillis()
            pendingDecision =
              "[SYSTEM] You have found \"${n.optString("label")}\" and you are looking at that screen RIGHT NOW. " +
                "Read it. Say out loud what you can see around it. Then DECIDE and do it in this same turn: act with " +
                "tap_found if it is unambiguous, or ASK which they meant, naming the rows exactly as they appear. Do " +
                "not go quiet, and do not search for it again — it is in front of you."
            foundRowX = n.optInt("row_cx", n.optInt("cx")); foundRowY = n.optInt("row_cy", n.optInt("cy"))
            foundSwitchX = n.optInt("switch_cx", -1); foundSwitchY = n.optInt("switch_cy", -1)
            val res = JSONObject()
              .put("ok", true)
              .put("found", n.optString("label"))
              .put("now_call", "tap_found(\"switch\") to toggle it, or tap_found(\"row\") to open its page")
              .put("target_locked", target)
              .put("steps_scrolled", steps)
            if (!sameTargetish(target, n.optString("label"))) {
              res.put(
                "WARNING_NOT_EXACT",
                "You asked for \"$target\". The closest thing on screen is \"${n.optString("label")}\", which is a " +
                  "DIFFERENT setting. Do NOT report its state as the answer to your question. Say what you actually " +
                  "found, and if the one you want is not on this screen, say so plainly."
              )
            }
            if (n.has("switch_is")) {
              recordSwitchProof(n.optString("label"), n.optString("switch_is"))
              res.put("switch_is", n.optString("switch_is"))
                .put(
                  "answer",
                  "\"${n.optString("label")}\" is ${n.optString("switch_is")}. That is read straight off the " +
                    "control itself, so it is exact — you do not need to squint at a picture to confirm it."
                )
                .put(
                  "to_change_it",
                  "The target is locked. Call set_found_switch only if the user asked to change it; it uses this exact " +
                    "native switch and reads the state back. Do not tap or scroll by hand."
                )
            } else {
              res.put("to_open_it", "The exact row is locked. Call open_found_target; it opens this native row without guessed coordinates.")
            }
            return res.put("screen_now", screenBrief(here))
          }

          // PIXELS, when the tree has nothing. The tree is a curated copy of
          // the screen and rows can be missing from it while plainly drawn —
          // "Wireless debugging" is exactly that, and no flag, window or
          // platform search call reaches it. OCR reads what is actually on the
          // glass, and hands back a real box instead of an estimate, so this
          // fixes finding AND aiming in one move.
          // Scoped to the app the search STARTED in. A "Wireless debugging
          // connected" notification in the shade reads identically to the
          // Developer options row, and matching it looks like success while
          // being a different object entirely — I reported one as a fix.
          runCatching { service.findByPixels(target, searchPkg) }.getOrNull()?.let { raw ->
            val o = JSONObject(raw)
            if (o.optBoolean("found")) {
              // Several things on screen answer to this name. Choosing one and
              // acting is how an assistant turns on the wrong thing with total
              // confidence — "the debugging thing" is USB debugging, Wireless
              // debugging, or a snoop log, and one of those opens the phone up.
              if (o.optBoolean("ambiguous")) {
                val options = o.optJSONArray("also_matched")
                Log.w(TAG, "scroll_to '$target' AMBIGUOUS: $options")
                huntFor = ""; huntSwipes = 0; huntReversals = 0; huntDir = ""
                pendingLook = true
                return JSONObject()
                  .put("ok", false)
                  .put("ambiguous", true)
                  .put("matches", options)
                  .put(
                    "error",
                    "More than one thing on this screen matches \"$target\": $options. They are different settings."
                  )
                  .put(
                    "do_now",
                    "Do NOT pick one. ASK the user which they mean, naming the options exactly as they appear, and " +
                      "wait for their answer. Guessing right is still guessing, and one of these may be something " +
                      "they did not want touched."
                  )
                  .put("screen_now", screenBrief(here))
              }
              Log.i(TAG, "scroll_to '$target' -> FOUND BY OCR after $steps steps: ${raw.take(200)}")
              pendingLook = true
              huntFor = ""; huntSwipes = 0; huntReversals = 0; huntDir = ""
              // The switch belongs to the row, so it sits on the same line at
              // the right-hand edge. Tapping the words opens the setting's own
              // page; tapping the switch toggles it.
              val w = dump.optInt("w", 720)
              foundLabel = o.optString("text"); lastFoundAt = System.currentTimeMillis()
              pendingDecision =
                "[SYSTEM] You have found \"${o.optString("text")}\" and you are looking at that screen RIGHT NOW. " +
                  "Read it. Say out loud what you can see around it — the other rows near it, and whether each is on " +
                  "or off. Then DECIDE and do it in this same turn: if it is unambiguous, act with tap_found. If " +
                  "several things there could be what they meant, ASK them which, naming the rows exactly as they " +
                  "appear on screen. Do not go quiet, and do not search for it again — it is in front of you."
              foundRowX = o.optInt("cx"); foundRowY = o.optInt("cy")
              foundSwitchX = (w * 0.87).toInt(); foundSwitchY = o.optInt("cy")
              return JSONObject()
                .put("ok", true)
                .put("found", o.optString("text"))
                .put("now_call", "tap_found(\"switch\") to toggle it, or tap_found(\"row\") to open its page")
                .put("read_from", "the screen itself (OCR), not the element list")
                .put("steps_scrolled", steps)
                .put("tap_the_row_at", "${o.optInt("cx")},${o.optInt("cy")}")
                .put("tap_the_switch_at", "${(w * 0.87).toInt()},${o.optInt("cy")}")
                .put(
                  "do_now",
                  "It is on screen NOW. These are PIXELS, not fractions — pass them to tap_at exactly as given. " +
                    "To turn something on or off use tap_the_switch_at; to open its page use tap_the_row_at. " +
                    "Say out loud what you can see about it before you act."
                )
                .put("screen_now", screenBrief(here))
            }
          }

          findOnScreen(here, words)?.let { found ->
            Log.i(TAG, "scroll_to '$target' -> found in tree at [${found.optInt("index")}] after $steps steps")
            pendingLook = true
            huntFor = ""; huntSwipes = 0; huntReversals = 0; huntDir = ""
            val switchState = found.optString("switch_says")
            lockTarget(target, switchState.isNotBlank())
            if (switchState.isNotBlank()) recordSwitchProof(found.optString("label"), switchState)
            return JSONObject()
              .put("ok", true)
              .put("found", found.optString("label"))
              .put("target_locked", target)
              .put("index", found.optInt("index"))
              .put("steps_scrolled", steps)
              .apply { switchState.takeIf { it.isNotBlank() }?.let { put("switch_is", it) } }
              .put(
                "do_now",
                if (switchState.isNotBlank())
                  "The target is locked and its native state is proven. Report it, or call set_found_switch if the user asked to change it."
                else
                  "The target is locked. Call open_found_target; do not tap by index or scroll again."
              )
              .put("screen_now", screenBrief(here))
          }

          // Straight off els, UNCAPPED. seenAll was built from screenBrief, which
          // stops at 22 labels per screen — so "not in the trace" never meant
          // "not in the tree", and I twice concluded that it did. A diagnostic
          // that silently truncates is worse than none: it produces confident
          // wrong answers.
          here.optJSONArray("els")?.let { arr ->
            for (k in 0 until arr.length()) {
              arr.optJSONObject(k)?.let { e ->
                listOf(e.optString("text"), e.optString("desc"))
                  .filter { t -> t.isNotBlank() }.joinToString(" ")
                  .takeIf { t -> t.isNotBlank() }?.let { seenAll.add(it) }
              }
            }
          }
          // And say outright whether the platform search can see it from here,
          // separately from whether our own copy of the tree can.
          // EVERY step, not just the first. The step-0-only version only ever
          // reported the screen she happened to start on — usually Settings
          // root, where the row genuinely is not — so it proved nothing at all.
          val before = contentSig(here)
          // The list's own scroll action first — but TRUST NOTHING IT SAYS.
          // performAction returns true off a scrollable that is not the list in
          // front of us, and then nothing moves while we congratulate ourselves.
          // The only evidence that a scroll worked is different content.
          service.scrollList(dirDown)
          steps++
          var prev = ""
          for (settle in 0 until 6) {
            Thread.sleep(200)
            here = runCatching { JSONObject(service.dumpScreen()) }.getOrNull() ?: here
            val nowS = contentSig(here)
            if (nowS == prev) break
            prev = nowS
          }
          if (contentSig(here) == before) {
            // The action claimed success and moved nothing. Fall back to a real
            // gesture before believing we have reached the end.
            service.swipe(cx, from, cx, to, 450)
            var p2 = ""
            for (settle in 0 until 6) {
              Thread.sleep(200)
              here = runCatching { JSONObject(service.dumpScreen()) }.getOrNull() ?: here
              val nowS = contentSig(here)
              if (nowS == p2) break
              p2 = nowS
            }
            if (contentSig(here) == before) { atEnd = true; break }
          }
        }

        Log.w(TAG, "scroll_to '$target' -> NOT found after $steps steps (atEnd=$atEnd)")
        // Everything seen across EVERY step, not just the screen it stopped on.
        // The last-screen-only version answered a question nobody was asking:
        // it told me where she ended up, when what mattered was what she went
        // past on the way.
        Log.w(TAG, "scroll_to saw (${seenAll.size}): ${seenAll.joinToString(" | ").take(3000)}")
        val partial = seenAll.filter { lbl -> words.any { w -> lbl.lowercase().contains(w) } }
        Log.w(TAG, "scroll_to PARTIAL matches for ${words.joinToString("+")}: " +
          (partial.joinToString(" | ").take(500).ifBlank { "(NONE — the row never reached the element list)" }))
        pendingLook = true
        JSONObject()
          .put("ok", false)
          .put("not_found", target)
          .put("steps_scrolled", steps)
          .put("reached_end_of_list", atEnd)
          .put(
            "error",
            if (atEnd)
              "\"$target\" is not on this page. The list stopped moving after $steps steps, so that is the whole of " +
                "it and those exact words are not here."
            else
              "\"$target\" did not appear in $steps steps of scrolling."
          )
          .put(
            "do_now",
            "Do NOT start swiping by hand — that is what this tool replaced. Either try scroll_to again with the " +
              "OTHER direction, or with the words as they really appear on screen (shorter is safer: \"Wireless\" " +
              "rather than \"Wireless debugging settings\"). A picture follows; read it and say what is actually " +
              "there. If it genuinely is not on this screen, tell the user."
          )
          .put("screen_now", screenBrief(here))
      }
      "swipe" -> {
        val w = dump.optInt("w"); val h = dump.optInt("h")
        val cx = w / 2; val cy = h / 2
        val frac = when (args.optString("amount", "normal")) {
          "tiny" -> 0.09; "long" -> 0.30; else -> 0.16
        }
        // Stay inside the middle of the screen. A long swipe used to start at
        // ~12% from the top, which Android reads as pulling down the
        // notification shade — she kept "scrolling" straight into it.
        val top = (h * 0.22).toInt()
        val bottom = (h * 0.78).toInt()
        val dy = (h * frac).toInt()
        val dir = args.optString("direction", "down")
        // COUNT THE HUNT FIRST. This used to sit below the two guards, which
        // meant a refused swipe never counted — and a refusal she ignores is
        // precisely what needs catching. Measured on the phone: 30 refusals in
        // a row, all identical, and the backstop never once fired because the
        // refusal returned before reaching it.
        val goal = args.optString("expect").lowercase().trim()
        if (goal.isNotEmpty() && goal == huntFor) {
          huntSwipes++
          // Changing her mind about which way to go is the tell.
          if (huntDir.isNotEmpty() && dir != huntDir) huntReversals++
        } else {
          huntFor = goal; huntSwipes = 1; huntReversals = 0
        }
        huntDir = dir

        // Count TURNS, not swipes. Blocking at four raw swipes was wrong and it
        // wedged her: Developer options is long, and reaching Wireless
        // debugging honestly takes eight or ten swipes in a row downwards. She
        // was doing the right thing and being refused for it. Going down, then
        // up, then down again is what "lost" actually looks like.
        // She will not stop on her own — eleven identical blocked taps in
        // ccbbd4d, thirty refused swipes here — so this still has to escalate.
        if (huntReversals >= 4 || huntSwipes >= 18) {
          Log.w(TAG, "HUNT HALTED after $huntSwipes swipes / $huntReversals turns for \"$goal\"")
          huntFor = ""; huntSwipes = 0; huntReversals = 0; huntDir = ""
          taskActive = false
          pendingLook = true
          return JSONObject()
            .put("ok", false)
            .put("stop", true)
            .put(
              "error",
              "STOP. You have swiped for the same thing $huntSwipes times and been refused. Swiping is finished — " +
                "it will not be allowed again for this."
            )
            .put(
              "do_now",
              "A picture follows. Look at it and TELL THE USER OUT LOUD what is actually on the screen and what you " +
                "cannot find. Then wait for them. Do not swipe, do not tap at random."
            )
            .put("screen_now", screenBrief(dump))
        }
        if (huntReversals >= 2 || huntSwipes >= 12) {
          Log.w(TAG, "HUNT BLOCKED: $huntSwipes swipes / $huntReversals turns expecting \"$goal\"")
          pendingLook = true
          return JSONObject()
            .put("ok", false)
            .put("stop_scrolling", true)
            .put("swipes_wasted", huntSwipes)
            .put(
              "error",
              "You have swiped $huntSwipes times looking for the same thing. Scrolling is not working and it is " +
                "very likely already on screen, going past you each time."
            )
            .put(
              "do_now",
              "STOP scrolling. A picture follows. Read the rows on it one by one and SAY OUT LOUD what you can see. " +
                "If what you want is there, tap its numbered box. If it truly is not, say so and say which " +
                "directions you already tried — do not swipe again."
            )
            .put("screen_now", screenBrief(dump))
        }

        loopGuard(dump, "swipe:$dir")?.let { return it }
        // Refuse to go looking for something that is already here. She swiped up
        // and down six times hunting for "Wireless debugging" while it sat in
        // the element list in front of her.
        alreadyOnScreen(dump, args.optString("expect"))?.let { return it }

        // Horizontal swipes have to be a proper FLING or the page springs back.
        // The old 32%-of-width drag over 300ms was too short and too slow to
        // commit a launcher page — it moved halfway and snapped home, which read
        // as "the swipe does nothing".
        val res = when (dir) {
          "up" -> service.swipe(cx, (cy - dy).coerceAtLeast(top), cx, (cy + dy).coerceAtMost(bottom), 280)
          "left" -> service.swipe((w * 0.88).toInt(), cy, (w * 0.12).toInt(), cy, 170)
          "right" -> service.swipe((w * 0.12).toInt(), cy, (w * 0.88).toInt(), cy, 170)
          else -> service.swipe(cx, (cy + dy).coerceAtMost(bottom), cx, (cy - dy).coerceAtLeast(top), 280)
        }
        withOutcome(dump, "swipe:$dir", JSONObject().put("ok", res))
      }
      // Fractional coordinates: the escape hatch for everything the tree can't
      // describe (web buttons, gallery photos, custom UI).
      "tap_at" -> {
        val w = dump.optInt("w"); val h = dump.optInt("h")
        val rx = args.optDouble("x", -1.0); val ry = args.optDouble("y", -1.0)
        // She sometimes sends 150 instead of 0.15. Unvalidated, that was
        // multiplied by the screen size and tapped at 108000,240000 - far off
        // screen, silently. Accept fractions or real pixels, reject nonsense.
        val (x, y) = normalizeCoords(rx, ry, w, h)
          ?: return JSONObject().put("ok", false).put(
            "error",
            "x=$rx y=$ry are not valid coordinates. Use FRACTIONS between 0 and 1 " +
              "(0.5, 0.5 is the centre of the screen), or exact pixels within ${w}x${h}."
          )
        if (x < 0 || y < 0) JSONObject().put("ok", false).put("error", "x and y must be 0..1")
        else {
          // A coordinate tap on a switch is still changing a setting. Without
          // this, every guard could be walked round simply by tapping the
          // pixels instead of naming the row.
          toggleLabelAt(dump, x, y)?.let { l -> changeBlocked(l)?.let { return it } }
          // Round to a coarse grid: tapping 3px away is the same attempt.
          val gx = (args.optDouble("x") * 10).toInt(); val gy = (args.optDouble("y") * 10).toInt()
          loopGuard(dump, "tap_at:$gx,$gy")?.let { return it }
          service.tap(x, y)
          withOutcome(dump, "tap_at:$gx,$gy", JSONObject().put("ok", true).put("tapped_at", "$x,$y"))
        }
      }
      "long_press_at" -> {
        val w = dump.optInt("w"); val h = dump.optInt("h")
        val rx = args.optDouble("x", -1.0); val ry = args.optDouble("y", -1.0)
        val (x, y) = normalizeCoords(rx, ry, w, h)
          ?: return JSONObject().put("ok", false).put(
            "error",
            "x=$rx y=$ry are not valid coordinates. Use fractions 0..1, or exact pixels within ${w}x${h}."
          )
        if (x < 0 || y < 0) JSONObject().put("ok", false).put("error", "x and y must be 0..1")
        else {
          toggleLabelAt(dump, x, y)?.let { l -> changeBlocked(l)?.let { return it } }
          service.swipe(x, y, x, y, 650); JSONObject().put("ok", true)
        }
      }
      // Arbitrary two-point gesture — the only thing that can turn a picker
      // wheel, move a slider or drag a carousel. A whole-screen swipe scrolls
      // the page and leaves controls like these untouched.
      "drag" -> {
        val w = dump.optInt("w"); val h = dump.optInt("h")
        val a = normalizeCoords(args.optDouble("from_x", -1.0), args.optDouble("from_y", -1.0), w, h)
        val b = normalizeCoords(args.optDouble("to_x", -1.0), args.optDouble("to_y", -1.0), w, h)
        if (a == null || b == null) {
          JSONObject().put("ok", false)
            .put("error", "drag needs from_x/from_y/to_x/to_y as fractions 0..1 (or pixels within ${w}x${h}).")
        } else {
          val dsig = "drag:${a.first / 50},${a.second / 50}->${b.first / 50},${b.second / 50}"
          loopGuard(dump, dsig)?.let { return it }
          // Pickers need a deliberate drag; a fast flick overshoots or gets
          // treated as a page fling instead of moving the control.
          // hold=true picks the item UP first. Moving an icon or reordering a
          // list needs that; a plain swipe just scrolls the page beneath it.
          val ok = if (args.optBoolean("hold", false)) {
            lastHoldDragAt = System.currentTimeMillis()
            service.longPressDrag(a.first, a.second, b.first, b.second)
          } else {
            service.swipe(a.first, a.second, b.first, b.second, if (args.optBoolean("slow", true)) 600L else 220L)
          }
          withOutcome(
            dump, dsig,
            JSONObject().put("ok", ok).put("from", "${a.first},${a.second}").put("to", "${b.first},${b.second}")
              .put("held_first", args.optBoolean("hold", false))
          )
        }
      }
      "task_done" -> {
        val summary = args.optString("summary")

        // A language-model assertion is never evidence of a setting's state.
        // This blocks the exact failure where Chaka twice said "wireless
        // debugging is on" after scrolling past the row. scroll_to and a
        // verified toggle both record the reading directly from isChecked.
        if (needsSwitchProof(currentRequest) && !hasSwitchProofForCurrentRequest()) {
          awaitingDoneProof = false
          pendingLook = true
          return JSONObject()
            .put("ok", false)
            .put("refused", true)
            .put("the_request", currentRequest)
            .put(
              "error",
              "You cannot report this setting's state yet. There is no fresh native ON/OFF reading for the setting " +
                "named in the request. A picture follows, but do not infer state from it or from memory."
            )
            .put(
              "do_now",
              "Call scroll_to with the setting's exact on-screen name. It will stop at that row and return switch_is " +
                "from the actual Android control. Only then tell the user the result."
            )
        }

        // Nothing has actually changed on screen for several actions. She
        // dragged the same icon fourteen times, moved nothing, and declared
        // success twice. A claim cannot outrank the record.
        if (noProgressRun >= 3) {
          Log.w(TAG, "task_done REFUSED — $noProgressRun actions with no effect")
          awaitingDoneProof = false
          pendingLook = true
          return JSONObject()
            .put("ok", false)
            .put("refused", true)
            .put("actions_with_no_effect", noProgressRun)
            .put("the_request", currentRequest)
            .put(
              "error",
              "You cannot mark this done. Your last $noProgressRun actions changed NOTHING on screen — whatever you " +
                "were trying did not work, so the task is not complete."
            )
            .put(
              "do_now",
              "A screenshot follows. Look at what is actually there and tell the user the truth: what you tried, that " +
                "it did not work, and what you think is blocking it. Do not claim success."
            )
        }

        // Does the summary even describe the thing that was asked for? She
        // reported moving Spotify when the request named Contacts.
        if (currentRequest.isNotEmpty() && summary.isNotEmpty()) {
          val stop = setOf("the","this","that","with","from","into","your","have","been","moved","move","open","opened","app","apps","screen","home","top","row","next","and","for","you","asked","requested","done","completed","successfully")
          val reqWords = currentRequest.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 3 && it !in stop }
          val sumLower = summary.lowercase()
          if (reqWords.isNotEmpty() && reqWords.none { sumLower.contains(it) }) {
            Log.w(TAG, "task_done REFUSED — summary does not match the request")
            awaitingDoneProof = false
            pendingLook = true
            return JSONObject()
              .put("ok", false)
              .put("refused", true)
              .put("the_request", currentRequest)
              .put("your_summary", summary)
              .put(
                "error",
                "What you are reporting does not match what was asked. The request was: \"$currentRequest\". " +
                  "Your summary describes something else entirely."
              )
              .put("do_now", "You have done the wrong thing, or described it wrongly. Look at the screen, work out which, and either do the actual request or tell the user plainly what happened.")
          }
        }

        if (awaitingDoneProof && System.currentTimeMillis() - doneProofAt > 25000) {
          Log.i(TAG, "done-proof expired — re-verifying")
          awaitingDoneProof = false
        }
        if (!awaitingDoneProof) {
          // Don't take her word for it. Send the actual screen and make her
          // check every part of the request against it first.
          awaitingDoneProof = true
          doneProofAt = System.currentTimeMillis()
          pendingLook = true
          val asked = synchronized(recentSpeech) { recentSpeech.toString().takeLast(280).trim() }
          return JSONObject()
            .put("ok", false)
            .put("verify_first", true)
            .put("now_in_app", dump.optString("pkg"))
            .put("screen_now", screenBrief(dump))
            .put("the_user_asked", asked.ifEmpty { "(see the conversation)" })
            .put(
              "instruction",
              "NOT accepted yet. A screenshot of the real screen follows in the next message. LOOK at it and check " +
                "EVERY part of what the user asked is actually visible as complete — a request with two parts is not " +
                "done when only one is. If the screen truly proves it, call task_done again and it will be accepted. " +
                "If it does not, say what still needs doing and carry on working."
            )
        }
        // Second call, made after seeing the screen — accept it.
        awaitingDoneProof = false
        taskActive = false
        drives = 0
        autoContinues = 0
        clearTargetLock()
        plan.clear(); planStep = 0; planGoal = ""
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
      "look_at_screen" -> {
        val nowSig = sig(dump)
        val since = System.currentTimeMillis() - lastLookAt
        if (nowSig == lastLookSig && since < 15000) {
          Log.w(TAG, "refusing repeat look at an unchanged screen (${since}ms)")
          return JSONObject()
            .put("ok", false)
            .put(
              "error",
              "You have ALREADY seen this exact screen and nothing on it has changed. Looking again shows the same " +
                "picture. ACT on what you saw - tap something, type, or go somewhere - or if you are unsure what the " +
                "user wants, ask them."
            )
            .put("screen_now", screenBrief(dump))
        }
        lastLookSig = nowSig
        lastLookAt = System.currentTimeMillis()
        lastRealLookAt = lastLookAt
        pendingLook = true
        JSONObject()
          .put("ok", true)
          .put(
            "note",
            "A fresh picture is arriving now. Look at it and SAY OUT LOUD what you can see that matters to the task " +
              "— the state of any switch you were asked about, the value, the name, the error. Say it before you do " +
              "anything else. In a few seconds this picture will be gone from your memory and only your own words " +
              "will remain."
          )
      }
      "open_app_drawer" -> {
        loopGuard(dump, "open_app_drawer")?.let { return it }
        // Doing this by hand kept going wrong: a plain "swipe up" from the
        // middle scrolls the page, and a long one starts in the notification
        // shade's gesture zone. Home first, then one deliberate bottom-to-top
        // swipe well inside the safe area.
        service.globalAction("home")
        Thread.sleep(700)
        val w = dump.optInt("w"); val h = dump.optInt("h")
        service.swipe(w / 2, (h * 0.80).toInt(), w / 2, (h * 0.28).toInt(), 260)
        withOutcome(dump, "open_app_drawer", JSONObject().put("ok", true))
      }
      "answer_call" -> JSONObject().put("ok", service.answerCall())
      "end_call" -> JSONObject().put("ok", service.endCall())
      "press_button" -> {
        val b = args.optString("button", "back")
        // Back right after picking an icon up CANCELS the move. She did this
        // after every single drag and undid her own work each time.
        if (b == "back" && System.currentTimeMillis() - lastHoldDragAt < 4000) {
          Log.w(TAG, "refusing back immediately after a hold-drag")
          return JSONObject()
            .put("ok", false)
            .put(
              "error",
              "Pressing back now CANCELS the move you just made. You just dragged something; back throws that away."
            )
            .put("do_now", "Look at the screen instead and check whether it landed where you wanted. If the launcher is in edit mode, tap an empty area to finish - never back.")
            .put("screen_now", screenBrief(dump))
        }
        loopGuard(dump, "press:$b")?.let { return it }
        withOutcome(dump, "press:$b", JSONObject().put("ok", service.globalAction(b)))
      }
      "open_app" -> {
        val wanted = args.optString("app")
        // Wandering guard. When she gets stuck she has been abandoning the task
        // and opening something unrelated - failing at a Google account, then
        // ending up browsing TikTok. A plan is a boundary, not a suggestion.
        offPlanGuard(wanted)?.let { return it }
        val pkg = ChakaOperator.resolveInstalled(context, wanted)
        if (pkg == null) JSONObject().put("ok", false)
          .put("error", "No installed app matches \"$wanted\".")
          .put("do_now", "Open the app drawer and look for it by name, or use its exact label as shown under the icon.")
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
        val raw = args.optString("url")
        offPlanGuard(raw)?.let { return it }
        val url = if (raw.startsWith("http")) raw else "https://$raw"
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
    runCatching { ears.stop() }
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
