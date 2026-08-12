package com.chakamyth.hands

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import androidx.core.content.FileProvider
import java.io.File
import expo.modules.kotlin.Promise
import expo.modules.kotlin.exception.CodedException
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

class HandsNotEnabledException :
  CodedException("Chaka Hands accessibility service is not enabled. Ask the user to enable it in Settings.")

class ChakaHandsModule : Module() {

  private val context: Context
    get() = requireNotNull(appContext.reactContext) { "React context is not available" }

  private val opScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
  private var currentOperator: ChakaOperator? = null
  private var currentGuide: ChakaGuide? = null

  override fun definition() = ModuleDefinition {
    Name("ChakaHands")

    Function("isEnabled") {
      isAccessibilityEnabled()
    }

    Function("openAccessibilitySettings") {
      val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(intent)
    }

    Function("startKeepAlive") {
      ChakaKeepAliveService.start(context)
      ChakaOverlay.show(context)
    }

    Function("stopKeepAlive") {
      ChakaOverlay.hide(context)
      ChakaKeepAliveService.stop(context)
    }

    Function("canDrawOverlay") {
      ChakaOverlay.canDraw(context)
    }

    Function("isBatteryExempt") {
      val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
      pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    Function("requestBatteryExemption") {
      val intent = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}")
      ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
      context.startActivity(intent)
    }

    // --- Self-update (downloads come from GitHub Releases) -------------------

    /** Current build, so JS can tell whether a release is actually newer. */
    Function("appVersion") {
      val info = context.packageManager.getPackageInfo(context.packageName, 0)
      val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
      JSONObject().put("versionName", info.versionName ?: "").put("versionCode", code).toString()
    }

    Function("canInstallPackages") {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.packageManager.canRequestPackageInstalls()
      } else true
    }

    Function("requestInstallPermission") {
      val intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}")
      ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
      context.startActivity(intent)
    }

    /** Hands a downloaded APK to the system installer. */
    Function("installApk") { path: String ->
      val file = File(path.removePrefix("file://"))
      if (!file.exists()) throw CodedException("Update file not found: ${file.absolutePath}")
      val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
      val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(intent)
    }

    // -------------------------------------------------------------------------

    Function("isNotificationAccessGranted") {
      val flat = Settings.Secure.getString(
        context.contentResolver, "enabled_notification_listeners"
      ) ?: ""
      flat.contains("${context.packageName}/com.chakamyth.hands.ChakaNotificationService")
    }

    Function("requestNotificationAccess") {
      val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
        .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
      context.startActivity(intent)
    }

    // JS pushes the proactive on/off flag + the DeepSeek key so the background
    // notification service can judge notifications on its own.
    Function("setProactive") { enabled: Boolean, deepseekKey: String ->
      context.getSharedPreferences(ChakaNotificationService.PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean("proactive_enabled", enabled)
        .putString("deepseek_key", deepseekKey)
        .apply()
    }

    Function("recentNotifications") {
      ChakaNotificationService.recentJson()
    }

    Function("kickNotifications") {
      ChakaNotificationService.kick(context)
    }

    Function("requestOverlayPermission") {
      val intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}")
      ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
      context.startActivity(intent)
    }

    AsyncFunction("readScreen") {
      requireService().dumpScreen()
    }

    AsyncFunction("tap") { x: Int, y: Int ->
      requireService().tap(x, y)
    }

    AsyncFunction("swipe") { x1: Int, y1: Int, x2: Int, y2: Int, duration: Int ->
      requireService().swipe(x1, y1, x2, y2, duration.toLong())
    }

    AsyncFunction("typeText") { text: String ->
      requireService().typeText(text)
    }

    AsyncFunction("pressEnter") {
      requireService().pressEnter()
    }

    // Runs the whole operator loop NATIVELY (survives Chaka being backgrounded,
    // unlike the RN JS loop). Keeps the process alive with FGS + overlay.
    AsyncFunction("operate") { goal: String, deepseekKey: String, geminiKey: String?, maxSteps: Int, app: String?, promise: Promise ->
      val svc = ChakaAccessibilityService.instance
      if (svc == null) {
        promise.reject(HandsNotEnabledException())
      } else {
        ChakaKeepAliveService.start(context)
        ChakaOverlay.show(context)
        val op = ChakaOperator(svc)
        currentOperator = op
        opScope.launch {
          try {
            val result = op.run(goal, deepseekKey, geminiKey, maxSteps, app)
            promise.resolve(result.toString())
          } catch (e: Exception) {
            promise.reject(CodedException(e.message ?: "operator error"))
          } finally {
            ChakaOverlay.hide(context)
            ChakaKeepAliveService.stop(context)
            currentOperator = null
          }
        }
      }
    }

    Function("stopOperate") {
      currentOperator?.cancelled = true
    }

    // Guide Mode: Chaka watches the screen and coaches the user (does not tap).
    AsyncFunction("startGuide") { goal: String, geminiKey: String, promise: Promise ->
      val svc = ChakaAccessibilityService.instance
      if (svc == null) {
        promise.reject(HandsNotEnabledException())
      } else {
        ChakaKeepAliveService.start(context)
        ChakaGuideOverlay.show(context, "Guiding you… (tap to stop)") { currentGuide?.cancelled = true }
        val guide = ChakaGuide(svc, context)
        currentGuide = guide
        opScope.launch {
          try {
            val outcome = guide.run(goal, geminiKey)
            promise.resolve(outcome)
          } catch (e: Exception) {
            promise.reject(CodedException(e.message ?: "guide error"))
          } finally {
            ChakaGuideOverlay.hide(context)
            ChakaKeepAliveService.stop(context)
            currentGuide = null
          }
        }
      }
    }

    Function("stopGuide") {
      currentGuide?.cancelled = true
    }

    AsyncFunction("screenshot") { promise: Promise ->
      val service = ChakaAccessibilityService.instance
      if (service == null) {
        promise.reject(HandsNotEnabledException())
      } else {
        service.captureScreenshot { base64 ->
          if (base64 != null) promise.resolve(base64)
          else promise.reject(CodedException("Screenshot capture failed"))
        }
      }
    }

    AsyncFunction("globalAction") { name: String ->
      requireService().globalAction(name)
    }
  }

  private fun requireService(): ChakaAccessibilityService {
    return ChakaAccessibilityService.instance ?: throw HandsNotEnabledException()
  }

  private fun isAccessibilityEnabled(): Boolean {
    val enabled = Settings.Secure.getString(
      context.contentResolver,
      Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    val id = "${context.packageName}/${ChakaAccessibilityService::class.java.name}"
    val splitter = TextUtils.SimpleStringSplitter(':')
    splitter.setString(enabled)
    for (component in splitter) {
      if (component.equals(id, ignoreCase = true)) return true
    }
    return false
  }
}
