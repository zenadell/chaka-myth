package com.chakamyth.hands

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reads incoming notifications so Chaka can be proactive — watching for things
 * genuinely worth interrupting the owner for, judging them natively (works even
 * when the app is closed), and pinging back with a heads-up.
 *
 * Requires the user to grant "Notification access" in system settings.
 */
class ChakaNotificationService : NotificationListenerService() {

  companion object {
    private const val TAG = "ChakaNotif"
    const val PROACTIVE_CHANNEL = "chaka_proactive"
    const val PREFS = "chaka_cfg"
    private const val MAX_BUFFER = 60
    private const val JUDGE_DEBOUNCE_MS = 22000L

    // A rolling buffer of recent notifications, exposed to JS via the module.
    val recent = ArrayDeque<JSONObject>()

    // Noise we never surface or judge.
    private val SKIP_PACKAGES = setOf(
      "android",
      "com.android.systemui",
      "com.samsung.android.app.cocktailbarservice",
      "com.samsung.android.honeyboard",
      "com.google.android.inputmethod.latin",
      "com.samsung.android.spay",
      "com.chakamyth.app"
    )

    fun recentJson(): String {
      synchronized(recent) { return JSONArray(recent.toList()).toString() }
    }

    /** Nudge Android to (re)bind the listener — avoids the "granted but not working yet" delay. */
    fun kick(context: Context) {
      runCatching {
        requestRebind(ComponentName(context, ChakaNotificationService::class.java))
      }
    }
  }

  @Volatile
  private var judgeScheduled = false
  private val handler = Handler(Looper.getMainLooper())
  private val pendingSinceLastJudge = ArrayList<JSONObject>()

  override fun onListenerConnected() {
    super.onListenerConnected()
    ensureChannel()
    Log.i(TAG, "notification listener connected")
  }

  override fun onNotificationPosted(sbn: StatusBarNotification) {
    val pkg = sbn.packageName ?: return
    if (SKIP_PACKAGES.contains(pkg)) return
    val n = sbn.notification ?: return
    // Skip ongoing/foreground-service/group-summary noise.
    if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
    if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

    val extras = n.extras
    val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
    val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""
    if (title.isBlank() && text.isBlank()) return

    val obj = JSONObject()
      .put("pkg", pkg)
      .put("app", appLabel(pkg))
      .put("title", title)
      .put("text", text.take(300))
      .put("time", System.currentTimeMillis())

    synchronized(recent) {
      recent.addFirst(obj)
      while (recent.size > MAX_BUFFER) recent.removeLast()
    }
    synchronized(pendingSinceLastJudge) { pendingSinceLastJudge.add(obj) }

    if (isProactiveEnabled()) scheduleJudge()
  }

  private fun scheduleJudge() {
    if (judgeScheduled) return
    judgeScheduled = true
    handler.postDelayed({
      judgeScheduled = false
      Thread { runJudge() }.start()
    }, JUDGE_DEBOUNCE_MS)
  }

  /** Ask DeepSeek which of the recent notifications genuinely deserve a heads-up. */
  private fun runJudge() {
    val batch: List<JSONObject>
    synchronized(pendingSinceLastJudge) {
      if (pendingSinceLastJudge.isEmpty()) return
      batch = ArrayList(pendingSinceLastJudge)
      pendingSinceLastJudge.clear()
    }
    val key = deepseekKey() ?: return

    val list = batch.joinToString("\n") { "- [${it.optString("app")}] ${it.optString("title")}: ${it.optString("text")}" }
    val sys = "You are Chaka, the owner's personal assistant, deciding whether to interrupt them. From the notifications below, pick ONLY the ones genuinely worth a proactive heads-up right now: a message from a real person, money/payments, security, deadlines, or clearly time-sensitive things. IGNORE promos, marketing, social-media noise, app updates, games, and routine app chatter. For each worthy one give a short first-person heads-up (max ~15 words) and, if useful, a suggested action. Reply with ONLY a JSON array: [{\"app\":\"...\",\"heads_up\":\"...\"}]. If none are worth interrupting for, reply []."
    val user = "Notifications:\n$list"

    val messages = JSONArray()
      .put(JSONObject().put("role", "system").put("content", sys))
      .put(JSONObject().put("role", "user").put("content", user))
    val body = JSONObject().put("model", "deepseek-v4-flash").put("messages", messages).put("stream", false).toString()
    val resp = httpPost("https://api.deepseek.com/chat/completions", key, body) ?: return

    val content = try {
      JSONObject(resp).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
    } catch (e: Exception) { return }

    val arr = try {
      val start = content.indexOf('[')
      val end = content.lastIndexOf(']')
      if (start < 0 || end <= start) return
      JSONArray(content.substring(start, end + 1))
    } catch (e: Exception) { return }

    for (i in 0 until arr.length()) {
      val item = arr.optJSONObject(i) ?: continue
      val headsUp = item.optString("heads_up").trim()
      if (headsUp.isNotEmpty()) postPing(item.optString("app"), headsUp, 5000 + i)
    }
  }

  private fun postPing(app: String, headsUp: String, id: Int) {
    ensureChannel()
    val launch = packageManager.getLaunchIntentForPackage(packageName)?.apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
    val pi = PendingIntent.getActivity(
      this, id, launch ?: Intent(),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val notif = Notification.Builder(this, PROACTIVE_CHANNEL)
      .setContentTitle("Chaka")
      .setContentText(headsUp)
      .setStyle(Notification.BigTextStyle().bigText(headsUp))
      .setSmallIcon(android.R.drawable.ic_dialog_info)
      .setContentIntent(pi)
      .setAutoCancel(true)
      .build()
    (getSystemService(NotificationManager::class.java)).notify(id, notif)
    Log.i(TAG, "proactive ping: $headsUp")
  }

  private fun ensureChannel() {
    val ch = NotificationChannel(PROACTIVE_CHANNEL, "Chaka heads-ups", NotificationManager.IMPORTANCE_HIGH)
    ch.description = "Proactive alerts from Chaka about things that matter"
    getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
  }

  private fun appLabel(pkg: String): String {
    return try {
      val pm = packageManager
      pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg.substringAfterLast('.') }
  }

  private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
  private fun isProactiveEnabled() = prefs().getBoolean("proactive_enabled", false)
  private fun deepseekKey(): String? = prefs().getString("deepseek_key", null)?.ifBlank { null }

  private fun httpPost(urlStr: String, deepseekKey: String, body: String): String? {
    return try {
      val conn = URL(urlStr).openConnection() as HttpURLConnection
      conn.requestMethod = "POST"
      conn.connectTimeout = 15000
      conn.readTimeout = 30000
      conn.doOutput = true
      conn.setRequestProperty("Content-Type", "application/json")
      conn.setRequestProperty("Authorization", "Bearer $deepseekKey")
      OutputStreamWriter(conn.outputStream).use { it.write(body) }
      val code = conn.responseCode
      val stream = if (code in 200..299) conn.inputStream else conn.errorStream
      val resp = stream.bufferedReader().use { it.readText() }
      if (code in 200..299) resp else { Log.e(TAG, "judge http $code"); null }
    } catch (e: Exception) {
      Log.e(TAG, "judge http error: ${e.message}"); null
    }
  }
}
