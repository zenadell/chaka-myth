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
  @Volatile private var lastRealLookAt = 0L
  @Volatile private var lastScreenReadAt = 0L
  @Volatile private var pendingExpect = ""
  @Volatile private var lastVerified = false
  @Volatile private var consecutiveBlocks = 0
  @Volatile private var lastLookSig = ""
  @Volatile private var lastLookAt = 0L
  // What the user has said recently. The destructive-action rail consults this
  // so it blocks a runaway agent without blocking the user's own instruction.
  private val recentSpeech = StringBuilder()
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
    private val STOP_WORDS = listOf(
      "stop", "wait", "don't", "dont", "cancel", "abort", "hold on", "no no", "quit"
    )
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
          if (ready && lastMsgAt > 0 && now - lastMsgAt > 40000) {
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
      "- SWIPE MEANS CONTENT, NOT FINGER. 'down' reveals what is FURTHER DOWN the page; 'up' goes back toward the TOP. Never swipe to reach the app drawer or notifications — use open_app_drawer or press_button instead, so you can't land in the wrong place.\n" +
      "\nYOU CANNOT FAKE PERCEPTION. The system knows exactly which tools you called. If you say you can see the screen without having called look_at_screen or read_screen, or say you did something without calling the tool, you WILL be caught and corrected in front of the user. Describing a screen you did not look at is inventing it — you have no idea what is there until you call the tool. Look first, speak second.\n" +
      "\nHOW YOU WORK — LOOK, ACT, CHECK. This is the loop, every step, without exception:\n" +
      "  1. KNOW WHERE YOU ARE. Read the screen (or look_at_screen if the element list is thin or you are in a browser). Never act on a screen you have not examined this turn.\n" +
      "  2. ACT, AND SAY WHAT YOU EXPECT. Every action takes an 'expect' — what the screen should look like afterwards. Be specific and concrete ('the Connections screen opens', 'the field reads golden brown'), because it is compared against the real screen.\n" +
      "  3. CHECK WHAT ACTUALLY HAPPENED. The result carries a 'verification'. 'as_expected' means carry on. 'MISMATCH' means you were WRONG about what that action would do — a screenshot follows, look at it, work out where you really are, and fix THAT step. Never continue as though it worked.\n" +
      "  4. Only then call step_done. It is refused if nothing was verified.\n" +
      "This loop is what makes you accurate. Skipping the check is how you end up insisting something happened when it did not.\n" +
      "- PLAN BEFORE YOU ACT. Anything with more than one step: call set_plan first with the goal and the ordered steps. The plan is saved and handed back to you after EVERY action, so check it each time — it is what keeps you on the objective when something unexpected happens.\n" +
      "- WORK THE PLAN ONE STEP AT A TIME: look if you need to, act, then CHECK the result (screen_changed / screen_now / your own eyes) before calling step_done. If a step went wrong, fix that step — do not skip ahead and do not abandon the plan to go do something else.\n" +
      "- A MISTAKE IS NOT A REASON TO CHANGE THE GOAL. If you tap the wrong thing, go back and get that step right. Never quietly switch to a different app or a different objective; the user asked for one thing.\n" +
      "- WHEN THE USER SAYS STOP, YOU STOP. Stop, wait, don't, cancel — the tools will refuse to act. Acknowledge it, say briefly what you had done, and wait. Never argue or finish the action first.\n" +
      "- COPYING IS NOT VERIFIED UNTIL YOU READ IT BACK. After tapping any Copy button, call read_clipboard before you paste. Copy buttons very often grab the label next to a value rather than the value itself, and pasting a name where a key should be is silent and useless.\n" +
      "- USE YOUR EYES BEFORE SAYING ANYTHING IS DONE. You can SEE — call look_at_screen and actually check. Never say an account is created, a page is open, a message is sent or a task is finished unless you have just looked and can see it. Saying it does not make it so.\n" +
      "- WEB PAGES NEED YOUR EYES. Inside Chrome or any browser the element list is often nearly empty even though the page is full of buttons. If read_screen comes back thin or blank and you're in a browser, call look_at_screen and work from the picture with tap_at.\n" +
      "- A REQUEST WITH SEVERAL PARTS IS NOT DONE UNTIL EVERY PART IS. 'Create an account AND get me the API key' is two things. Track them, finish both, and if you only managed one, say exactly which and why.\n" +
      "- IF SOMETHING FAILS OR ERRORS, say so plainly and try another route. A page erroring, a login being cancelled, a step not completing - these are things to report, not to paper over.\n" +
      "- NEVER CLAIM AN ACTION WORKED WHEN screen_changed IS false. If you swipe and the screen did not move, the page did NOT turn — say so out loud and try a different way. Telling the user something happened when the result says it didn't is the worst thing you can do; they are relying on you to be accurate about their phone.\n" +
      "- DO EXACTLY THE TASK ASKED, NOTHING ELSE. 'Second page of the app menu' means the app menu's second page — not the home screen, not the third page, not a different app. If you get stuck, stay on the goal and say what's blocking you. Opening something unrelated and calling it done is never acceptable.\n" +
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
        "Take a picture of the screen and look at it with your own eyes. Use this when read_screen isn't enough — web pages, photos, galleries, games, custom or image-based UI, or when you need to judge how something LOOKS. The image arrives in the next message.",
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

          if (STOP_WORDS.any { w -> said.contains(w) }) {
            // Stop is enforced here, not left to the model — it ignored spoken
            // stops and kept going with things the user didn't want.
            halted = true
            taskActive = false
            autoContinues = 0
            drives = 0
            pendingLook = false
            plan.clear(); planStep = 0; planGoal = ""
            runCatching { player?.pause(); player?.flush(); player?.play() }  // cut her off mid-sentence
            Log.w(TAG, "HARD STOP heard: \"$heard\"")
            ChakaGuideOverlay.update("⏸ Stopped")
          } else if (words >= 3) {
            // Only a real sentence starts work. Stray words the mic caught from
            // the room were kicking off tasks nobody asked for.
            halted = false
            taskActive = true
            nudges = 0
            drives = 0
            idleTurns = 0
            autoContinues = 0
            awaitingDoneProof = false
          }

          synchronized(recentSpeech) {
            recentSpeech.append(' ').append(said)
            if (recentSpeech.length > 600) recentSpeech.delete(0, recentSpeech.length - 600)
          }
          Log.i(TAG, "user: $heard")
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
              captureBlocking()?.let { shot ->
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
                Log.i(TAG, "injecting screenshot (${shot.length} b64)")
                sendImage(ws, shot, prompt)
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
        catchFalseClaim(said, toolCalledThisTurn)?.let { correction ->
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

  /**
   * Sends the screenshot the way a content turn must be sent: clientContent
   * with inline_data AND a text prompt, turn_complete. A bare realtimeInput
   * video frame mid-turn gave her nothing to act on — she'd call
   * look_at_screen twice and then sit there having seen nothing.
   */
  private fun sendImage(ws: WebSocket, b64: String, prompt: String): Boolean = runCatching {
    ws.send(
      JSONObject().put(
        "clientContent",
        JSONObject()
          .put(
            "turns",
            JSONArray().put(
              JSONObject().put("role", "user").put(
                "parts",
                JSONArray()
                  .put(
                    JSONObject().put(
                      "inline_data",
                      JSONObject().put("mime_type", "image/jpeg").put("data", b64)
                    )
                  )
                  .put(JSONObject().put("text", prompt))
              )
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
      val label = e.optString("text", e.optString("desc", ""))
      if (label.isBlank()) continue
      sb.append("[").append(e.optInt("i")).append("] ").append(label.take(34))
      if (e.optBoolean("toggle")) sb.append(if (e.optBoolean("on")) "(ON)" else "(OFF)")
      sb.append("  ")
      n++
    }
    return sb.toString().trim().ifEmpty { "(nothing readable)" }
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
  private fun verifyEffect(expect: String, after: JSONObject, changed: Boolean): JSONObject {
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
    Thread.sleep(550)  // let the UI settle
    val after = runCatching { JSONObject(service.dumpScreen()) }.getOrNull() ?: return result
    val changed = sig(after) != sig(before)
    val app = after.optString("pkg")

    consecutiveBlocks = 0

    // Compare the outcome against what she said she expected.
    val verdict = verifyEffect(pendingExpect, after, changed)
    pendingExpect = ""
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
    val dump = runCatching { JSONObject(service.dumpScreen()) }.getOrNull()
      ?: return JSONObject().put("error", "couldn't read the screen")

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
          val label = hit.optString("text", hit.optString("desc", ""))
          loopGuard(dump, "tap:$label")?.let { return it }
          service.tap(hit.optInt("cx"), hit.optInt("cy"))
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
      "read_clipboard" -> {
        val clip = service.readClipboard()
        if (clip.isNullOrBlank()) {
          JSONObject().put("ok", false).put("error", "The clipboard is empty — the copy did not work.")
        } else {
          JSONObject()
            .put("ok", true)
            .put("clipboard", clip.take(400))
            .put("length", clip.length)
            .put(
              "check",
              "Is this actually the value you meant to copy? A key or token is a long string of random characters — " +
                "if this looks like a label or a name instead, the copy grabbed the wrong thing, so go back and copy properly."
            )
        }
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
        loopGuard(dump, "swipe:$dir")?.let { return it }
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
        else { service.swipe(x, y, x, y, 650); JSONObject().put("ok", true) }
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
          // Pickers need a deliberate drag; a fast flick overshoots or gets
          // treated as a page fling instead of moving the control.
          val dur = if (args.optBoolean("slow", true)) 600L else 220L
          val ok = service.swipe(a.first, a.second, b.first, b.second, dur)
          withOutcome(
            dump,
            "drag:${a.first / 50},${a.second / 50}->${b.first / 50},${b.second / 50}",
            JSONObject().put("ok", ok).put("from", "${a.first},${a.second}").put("to", "${b.first},${b.second}")
          )
        }
      }
      "task_done" -> {
        if (!awaitingDoneProof) {
          // Don't take her word for it. Send the actual screen and make her
          // check every part of the request against it first.
          awaitingDoneProof = true
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
          .put("note", "Screenshot is being sent now and arrives in the NEXT message. Look at it, then continue the task.")
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
        loopGuard(dump, "press:$b")?.let { return it }
        withOutcome(dump, "press:$b", JSONObject().put("ok", service.globalAction(b)))
      }
      "open_app" -> {
        val wanted = args.optString("app")
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
