/**
 * "Hey Chaka" wake word via Picovoice Porcupine — fully on-device.
 *
 * Setup (one-time, free tier):
 *   1. Create an account at https://console.picovoice.ai
 *   2. Copy your AccessKey.
 *   3. Train custom wake words ("Hey Chaka", "Chaka", "Myth") in the console
 *      and download the .ppn files for Android and iOS.
 *   4. Put them in assets/wakewords/ and list them below.
 *   5. Build the dev client (`npx expo run:android`) — Porcupine is a native
 *      module and does not run in Expo Go.
 *
 * Until those steps are done, wakewordAvailable() returns false and the app
 * falls back to the mic button.
 */
import { Platform } from "react-native";

// Paste your Picovoice AccessKey here (or wire it into settings later).
const PICOVOICE_ACCESS_KEY = "";

// Relative to the native assets dir; see Porcupine RN docs for bundling .ppn files.
const KEYWORD_PATHS: string[] = Platform.select({
  android: [
    // "wakewords/hey-chaka_android.ppn",
  ],
  ios: [
    // "wakewords/hey-chaka_ios.ppn",
  ],
  default: [],
})!;

let PorcupineManager: any = null;
try {
  // eslint-disable-next-line @typescript-eslint/no-var-requires
  PorcupineManager = require("@picovoice/porcupine-react-native").PorcupineManager;
} catch {
  PorcupineManager = null;
}

export function wakewordAvailable(): boolean {
  return (
    PorcupineManager != null &&
    PICOVOICE_ACCESS_KEY.length > 0 &&
    KEYWORD_PATHS.length > 0
  );
}

let manager: any = null;

/** Starts always-on listening; onWake fires when "Hey Chaka" is detected. */
export async function startWakeword(onWake: () => void): Promise<boolean> {
  if (!wakewordAvailable()) return false;
  if (manager) return true;

  manager = await PorcupineManager.fromKeywordPaths(
    PICOVOICE_ACCESS_KEY,
    KEYWORD_PATHS,
    () => onWake(),
    (error: any) => console.warn("Porcupine error:", error)
  );
  await manager.start();
  return true;
}

export async function stopWakeword(): Promise<void> {
  if (!manager) return;
  await manager.stop();
  await manager.delete();
  manager = null;
}
