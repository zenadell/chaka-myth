import { requireNativeModule } from "expo-modules-core";

export interface ScreenElement {
  i: number;
  text?: string;
  desc?: string;
  cls: string;
  cx: number;
  cy: number;
  clickable?: boolean;
  editable?: boolean;
}

export interface ScreenDump {
  w: number;
  h: number;
  pkg: string;
  els: ScreenElement[];
}

// Native module is absent in Expo Go and in APKs built before 0.4.0.
// Keep this import non-fatal so OTA updates don't crash older installs.
let Hands: any = null;
try {
  Hands = requireNativeModule("ChakaHands");
} catch {
  Hands = null;
}

export function available(): boolean {
  return !!Hands;
}

export function isEnabled(): boolean {
  return Hands ? Hands.isEnabled() : false;
}

export function openAccessibilitySettings(): void {
  Hands?.openAccessibilitySettings();
}

// Foreground service to keep the JS loop alive while Chaka is backgrounded
// and driving another app (defeats Samsung's process freezer). 0.6.0+ native.
export function startKeepAlive(): void {
  if (Hands && typeof Hands.startKeepAlive === "function") Hands.startKeepAlive();
}

export function stopKeepAlive(): void {
  if (Hands && typeof Hands.stopKeepAlive === "function") Hands.stopKeepAlive();
}

export function canDrawOverlay(): boolean {
  return !!Hands && typeof Hands.canDrawOverlay === "function" && Hands.canDrawOverlay();
}

export function requestOverlayPermission(): void {
  if (Hands && typeof Hands.requestOverlayPermission === "function") Hands.requestOverlayPermission();
}

// Battery-optimization exemption stops aggressive OEMs (Samsung) from killing
// Chaka and disabling its accessibility service between/ during tasks.
export function isBatteryExempt(): boolean {
  return !!Hands && typeof Hands.isBatteryExempt === "function" && Hands.isBatteryExempt();
}

export function requestBatteryExemption(): void {
  if (Hands && typeof Hands.requestBatteryExemption === "function") Hands.requestBatteryExemption();
}

// --- Notification awareness ---

export interface NotificationItem {
  pkg: string;
  app: string;
  title: string;
  text: string;
  time: number;
}

export function isNotificationAccessGranted(): boolean {
  return !!Hands && typeof Hands.isNotificationAccessGranted === "function" && Hands.isNotificationAccessGranted();
}

export function requestNotificationAccess(): void {
  if (Hands && typeof Hands.requestNotificationAccess === "function") Hands.requestNotificationAccess();
}

/** Enable/disable proactive pings and hand the background service the DeepSeek key. */
export function setProactive(enabled: boolean, deepseekKey: string): void {
  if (Hands && typeof Hands.setProactive === "function") Hands.setProactive(enabled, deepseekKey);
}

export function recentNotifications(): NotificationItem[] {
  if (!Hands || typeof Hands.recentNotifications !== "function") return [];
  try {
    return JSON.parse(Hands.recentNotifications());
  } catch {
    return [];
  }
}

export function notificationSupport(): boolean {
  return !!Hands && typeof Hands.recentNotifications === "function";
}

/** Nudge Android to rebind the listener (fixes the "granted but not working yet" delay). */
export function kickNotifications(): void {
  if (Hands && typeof Hands.kickNotifications === "function") Hands.kickNotifications();
}

export interface OperateResult {
  outcome: "done" | "fail" | "exhausted" | "stopped";
  detail: string;
  perception: string;
  steps: string[];
}

export function canOperateNatively(): boolean {
  return !!Hands && typeof Hands.operate === "function";
}

/**
 * Runs the entire operator loop in native Kotlin (inside the accessibility
 * service), so it keeps going while Chaka is backgrounded and driving another
 * app. Returns the final outcome once done.
 */
export async function operate(
  goal: string,
  deepseekKey: string,
  geminiKey: string | null,
  maxSteps = 14,
  app: string | null = null
): Promise<OperateResult> {
  const json: string = await Hands.operate(goal, deepseekKey, geminiKey ?? null, maxSteps, app ?? null);
  return JSON.parse(json);
}

export function stopOperate(): void {
  if (Hands && typeof Hands.stopOperate === "function") Hands.stopOperate();
}

export function canGuide(): boolean {
  return !!Hands && typeof Hands.startGuide === "function";
}

/** Guide Mode: Chaka watches the screen and coaches the user (bubble + voice), no tapping. */
export async function startGuide(goal: string, geminiKey: string): Promise<string> {
  return await Hands.startGuide(goal, geminiKey);
}

export function stopGuide(): void {
  if (Hands && typeof Hands.stopGuide === "function") Hands.stopGuide();
}

export async function readScreen(): Promise<ScreenDump> {
  const json: string = await Hands.readScreen();
  return JSON.parse(json);
}

export function tap(x: number, y: number): Promise<boolean> {
  return Hands.tap(Math.round(x), Math.round(y));
}

export function swipe(
  x1: number,
  y1: number,
  x2: number,
  y2: number,
  duration = 300
): Promise<boolean> {
  return Hands.swipe(Math.round(x1), Math.round(y1), Math.round(x2), Math.round(y2), duration);
}

export function typeText(text: string): Promise<boolean> {
  return Hands.typeText(text);
}

// Added in the 0.5.0 native build. Guarded so this JS shipping OTA to a 0.4.0
// install (whose native module lacks it) degrades to a no-op instead of crashing.
export async function pressEnter(): Promise<boolean> {
  if (Hands && typeof Hands.pressEnter === "function") {
    return Hands.pressEnter();
  }
  return false;
}

// Screenshot capture for the vision path (0.5.0+). Returns a base64 PNG or null.
export async function screenshot(): Promise<string | null> {
  if (Hands && typeof Hands.screenshot === "function") {
    const b64: string = await Hands.screenshot();
    return b64 || null;
  }
  return null;
}

export function canScreenshot(): boolean {
  return !!Hands && typeof Hands.screenshot === "function";
}

export function globalAction(
  name: "back" | "home" | "recents" | "notifications" | "quick_settings" | "lock"
): Promise<boolean> {
  return Hands.globalAction(name);
}

// --- Self-update (2.2.1+) ---------------------------------------------------

export type AppVersion = { versionName: string; versionCode: number };

/** The currently installed build, used to decide if a release is newer. */
export function appVersion(): AppVersion | null {
  if (Hands && typeof Hands.appVersion === "function") {
    try {
      return JSON.parse(Hands.appVersion());
    } catch {
      return null;
    }
  }
  return null;
}

export function canInstallPackages(): boolean {
  if (Hands && typeof Hands.canInstallPackages === "function") {
    return Hands.canInstallPackages();
  }
  return false;
}

export function requestInstallPermission(): void {
  Hands?.requestInstallPermission?.();
}

/** Hands a downloaded APK to the system installer. */
export function installApk(path: string): void {
  Hands.installApk(path);
}

export function canSelfUpdate(): boolean {
  return !!Hands && typeof Hands.installApk === "function";
}

// --- Live Mode (2.4.0+) ----------------------------------------------------
// One persistent Gemini Live session: watches the screen, talks, and acts.

export function canGoLive(): boolean {
  return !!Hands && typeof Hands.startLive === "function";
}

/** Opens the live session. `goal` is optional context ("" = just assist). */
export async function startLive(
  goal: string,
  geminiKey: string,
  model: string
): Promise<string> {
  return Hands.startLive(goal, geminiKey, model);
}

export function stopLive(): void {
  Hands?.stopLive?.();
}

/** Sends a line from the user into the running session. */
export function sayLive(text: string): void {
  Hands?.sayLive?.(text);
}

export function isLiveRunning(): boolean {
  return !!Hands?.isLiveRunning?.();
}

// --- Shared memory (5.11.0+) -----------------------------------------------
// The same store Live Mode reads and writes, so a value saved by voice is
// available in chat and the other way round.

export function memoryAll(): Record<string, string> {
  try {
    return JSON.parse(Hands?.memoryAll?.() ?? "{}");
  } catch {
    return {};
  }
}

export function memorySet(label: string, value: string): void {
  Hands?.memorySet?.(label, value);
}

export function memoryDelete(label: string): void {
  Hands?.memoryDelete?.(label);
}
