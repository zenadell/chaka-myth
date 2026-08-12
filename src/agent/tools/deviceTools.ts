import { Platform } from "react-native";
import * as Linking from "expo-linking";
import * as Battery from "expo-battery";
import * as Device from "expo-device";
import * as IntentLauncher from "expo-intent-launcher";
import * as Brightness from "expo-brightness";
import * as Clipboard from "expo-clipboard";
import { Camera } from "expo-camera";
import { useDeviceFx } from "../../state/deviceFx";
import type { Tool } from "../types";

const isAndroid = Platform.OS === "android";

export const getDeviceStatus: Tool = {
  definition: {
    type: "function",
    function: {
      name: "get_device_status",
      description:
        "Get the phone's current status: battery level, charging state, device model, and OS version.",
      parameters: { type: "object", properties: {} },
    },
  },
  describeCall: () => "Check device status",
  execute: async () => {
    const [level, batteryState] = await Promise.all([
      Battery.getBatteryLevelAsync(),
      Battery.getBatteryStateAsync(),
    ]);
    return {
      ok: true,
      output: {
        batteryPercent: Math.round(level * 100),
        charging: batteryState === Battery.BatteryState.CHARGING,
        model: Device.modelName,
        os: `${Device.osName} ${Device.osVersion}`,
      },
    };
  },
};

const SETTINGS_PANELS: Record<string, string> = {
  wifi: IntentLauncher.ActivityAction.WIFI_SETTINGS,
  bluetooth: IntentLauncher.ActivityAction.BLUETOOTH_SETTINGS,
  hotspot: IntentLauncher.ActivityAction.TETHER_SETTINGS,
  airplane: IntentLauncher.ActivityAction.AIRPLANE_MODE_SETTINGS,
  location: IntentLauncher.ActivityAction.LOCATION_SOURCE_SETTINGS,
  display: IntentLauncher.ActivityAction.DISPLAY_SETTINGS,
  sound: IntentLauncher.ActivityAction.SOUND_SETTINGS,
  battery: IntentLauncher.ActivityAction.BATTERY_SAVER_SETTINGS,
  storage: IntentLauncher.ActivityAction.INTERNAL_STORAGE_SETTINGS,
  main: IntentLauncher.ActivityAction.SETTINGS,
};

export const openSettings: Tool = {
  definition: {
    type: "function",
    function: {
      name: "open_settings",
      description:
        "Open a phone settings screen so the user can toggle things with one tap. " +
        "Android cannot let apps flip wifi/bluetooth/hotspot directly, so this opens the exact " +
        "settings panel instead. Panels: wifi, bluetooth, hotspot, airplane, location, display, " +
        "sound, battery, storage, main.",
      parameters: {
        type: "object",
        properties: {
          panel: {
            type: "string",
            enum: Object.keys(SETTINGS_PANELS),
            description: "Which settings screen to open",
          },
        },
        required: ["panel"],
      },
    },
  },
  describeCall: (args) => `Open ${args.panel} settings`,
  execute: async (args) => {
    if (!isAndroid) {
      await Linking.openSettings();
      return { ok: true, output: "Opened app settings (iOS only allows the app's own settings page)." };
    }
    const action = SETTINGS_PANELS[args.panel];
    if (!action) return { ok: false, output: `Unknown panel: ${args.panel}` };
    await IntentLauncher.startActivityAsync(action);
    return { ok: true, output: `Opened ${args.panel} settings — one tap to toggle.` };
  },
};

export const setBrightness: Tool = {
  definition: {
    type: "function",
    function: {
      name: "set_brightness",
      description: "Set the screen brightness. Value is a percentage 0-100.",
      parameters: {
        type: "object",
        properties: {
          percent: { type: "number", description: "Brightness 0-100" },
        },
        required: ["percent"],
      },
    },
  },
  describeCall: (args) => `Set brightness to ${args.percent}%`,
  execute: async (args) => {
    const value = Math.min(100, Math.max(0, Number(args.percent))) / 100;
    const { status } = await Brightness.requestPermissionsAsync();
    if (status === "granted" && isAndroid) {
      await Brightness.setSystemBrightnessAsync(value);
      return { ok: true, output: `System brightness set to ${Math.round(value * 100)}%` };
    }
    await Brightness.setBrightnessAsync(value);
    return {
      ok: true,
      output: `App brightness set to ${Math.round(value * 100)}% (system-wide needs the Modify Settings permission).`,
    };
  },
};

export const flashlight: Tool = {
  definition: {
    type: "function",
    function: {
      name: "flashlight",
      description: "Turn the phone's torch/flashlight on or off.",
      parameters: {
        type: "object",
        properties: {
          on: { type: "boolean", description: "true = on, false = off" },
        },
        required: ["on"],
      },
    },
  },
  describeCall: (args) => (args.on ? "Turn flashlight ON" : "Turn flashlight OFF"),
  execute: async (args) => {
    if (args.on) {
      const { status } = await Camera.requestCameraPermissionsAsync();
      if (status !== "granted") {
        return { ok: false, output: "Camera permission denied — the torch needs it." };
      }
    }
    useDeviceFx.getState().setTorch(!!args.on);
    return { ok: true, output: `Flashlight ${args.on ? "on" : "off"}` };
  },
};

export const clipboardTool: Tool = {
  definition: {
    type: "function",
    function: {
      name: "clipboard",
      description: "Read or write the phone clipboard. action='read' returns current text; action='write' copies text.",
      parameters: {
        type: "object",
        properties: {
          action: { type: "string", enum: ["read", "write"] },
          text: { type: "string", description: "Text to copy (write only)" },
        },
        required: ["action"],
      },
    },
  },
  describeCall: (args) => (args.action === "read" ? "Read clipboard" : "Copy text to clipboard"),
  execute: async (args) => {
    if (args.action === "write") {
      await Clipboard.setStringAsync(String(args.text ?? ""));
      return { ok: true, output: "Copied to clipboard." };
    }
    const text = await Clipboard.getStringAsync();
    return { ok: true, output: text || "(clipboard is empty)" };
  },
};

/** Known apps: android package + optional URL scheme fallback. */
const APP_DIRECTORY: Record<string, { pkg?: string; scheme?: string; ios?: string }> = {
  whatsapp: { pkg: "com.whatsapp", scheme: "whatsapp://send", ios: "whatsapp://send" },
  youtube: { pkg: "com.google.android.youtube", scheme: "vnd.youtube://", ios: "youtube://" },
  spotify: { pkg: "com.spotify.music", scheme: "spotify:", ios: "spotify:" },
  instagram: { pkg: "com.instagram.android", scheme: "instagram://app", ios: "instagram://app" },
  x: { pkg: "com.twitter.android", scheme: "twitter://timeline", ios: "twitter://timeline" },
  twitter: { pkg: "com.twitter.android", scheme: "twitter://timeline", ios: "twitter://timeline" },
  facebook: { pkg: "com.facebook.katana", scheme: "fb://feed", ios: "fb://feed" },
  telegram: { pkg: "org.telegram.messenger", scheme: "tg://", ios: "tg://" },
  gmail: { pkg: "com.google.android.gm", scheme: "googlegmail://", ios: "googlegmail://" },
  chrome: { pkg: "com.android.chrome", scheme: "googlechrome://", ios: "googlechrome://" },
  maps: { pkg: "com.google.android.apps.maps", scheme: "geo:", ios: "comgooglemaps://" },
  photos: { pkg: "com.google.android.apps.photos" },
  gallery: { pkg: "com.google.android.apps.photos" },
  camera: { pkg: "com.android.camera" },
  tiktok: { pkg: "com.zhiliaoapp.musically", scheme: "snssdk1233://", ios: "snssdk1233://" },
  snapchat: { pkg: "com.snapchat.android", scheme: "snapchat://", ios: "snapchat://" },
  playstore: { pkg: "com.android.vending", scheme: "market://" },
};

export const openApp: Tool = {
  definition: {
    type: "function",
    function: {
      name: "open_app",
      description:
        "Open an app on the phone by name. Known names: " +
        Object.keys(APP_DIRECTORY).join(", ") +
        ". For other apps pass the Android package name in 'packageName' (e.g. com.example.app). " +
        "Prefer this over open_url for simply launching apps.",
      parameters: {
        type: "object",
        properties: {
          name: { type: "string", description: "App name, e.g. 'whatsapp'" },
          packageName: { type: "string", description: "Android package name if the app isn't in the known list" },
        },
        required: ["name"],
      },
    },
  },
  describeCall: (args) => `Open ${args.name}`,
  execute: async (args) => {
    const key = String(args.name || "").toLowerCase().replace(/\s+/g, "");
    const entry = APP_DIRECTORY[key];
    const pkg = args.packageName || entry?.pkg;

    if (key === "camera" && isAndroid) {
      try {
        await IntentLauncher.startActivityAsync("android.media.action.STILL_IMAGE_CAMERA");
        return { ok: true, output: "Camera opened." };
      } catch {
        /* fall through to package launch */
      }
    }

    if (isAndroid && pkg) {
      try {
        await IntentLauncher.openApplication(pkg);
        return { ok: true, output: `Opened ${args.name}` };
      } catch {
        /* fall through to scheme */
      }
    }
    const scheme = Platform.OS === "ios" ? entry?.ios : entry?.scheme;
    if (scheme) {
      try {
        await Linking.openURL(scheme);
        return { ok: true, output: `Opened ${args.name}` };
      } catch {
        /* fall through */
      }
    }
    return {
      ok: false,
      output: `Could not open ${args.name} — it may not be installed. Try open_url with a web link instead.`,
    };
  },
};

export const openDownloads: Tool = {
  definition: {
    type: "function",
    function: {
      name: "open_downloads",
      description: "Open the phone's Downloads folder in the system file manager (Android).",
      parameters: { type: "object", properties: {} },
    },
  },
  describeCall: () => "Open Downloads folder",
  execute: async () => {
    if (!isAndroid) return { ok: false, output: "Only available on Android." };
    await IntentLauncher.startActivityAsync("android.intent.action.VIEW_DOWNLOADS");
    return { ok: true, output: "Downloads folder opened." };
  },
};
