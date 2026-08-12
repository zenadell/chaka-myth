import { PermissionsAndroid, Platform } from "react-native";
import * as IntentLauncher from "expo-intent-launcher";
import * as Linking from "expo-linking";
import type { Tool } from "../types";

const isAndroid = Platform.OS === "android";

export const callNumber: Tool = {
  definition: {
    type: "function",
    function: {
      name: "call_number",
      description:
        "Place a phone call directly (no dialer tap needed on Android; iOS opens the dialer). " +
        "Use find_contact first to resolve names into numbers.",
      parameters: {
        type: "object",
        properties: {
          phone: { type: "string", description: "Number to call, incl. country code" },
        },
        required: ["phone"],
      },
    },
  },
  requiresConfirmation: true,
  describeCall: (args) => `Call ${args.phone}`,
  execute: async (args) => {
    const tel = `tel:${String(args.phone).replace(/[^\d+]/g, "")}`;
    if (isAndroid) {
      try {
        const granted = await PermissionsAndroid.request(
          PermissionsAndroid.PERMISSIONS.CALL_PHONE
        );
        if (granted === PermissionsAndroid.RESULTS.GRANTED) {
          await IntentLauncher.startActivityAsync("android.intent.action.CALL", { data: tel });
          return { ok: true, output: "Calling now." };
        }
      } catch {
        /* fall through to dialer (works on builds without CALL_PHONE in manifest) */
      }
    }
    await Linking.openURL(tel);
    return { ok: true, output: "Dialer opened with the number — one tap to call." };
  },
};

export const setAlarm: Tool = {
  definition: {
    type: "function",
    function: {
      name: "set_alarm",
      description:
        "Set a real alarm in the phone's Clock app (Android). Provide 24h hour and minute.",
      parameters: {
        type: "object",
        properties: {
          hour: { type: "number", description: "0-23" },
          minute: { type: "number", description: "0-59" },
          label: { type: "string", description: "Alarm label" },
        },
        required: ["hour", "minute"],
      },
    },
  },
  describeCall: (args) =>
    `Set alarm ${String(args.hour).padStart(2, "0")}:${String(args.minute).padStart(2, "0")}${args.label ? ` (${args.label})` : ""}`,
  execute: async (args) => {
    if (!isAndroid) return { ok: false, output: "Alarms are Android-only for now." };
    try {
      await IntentLauncher.startActivityAsync("android.intent.action.SET_ALARM", {
        extra: {
          "android.intent.extra.alarm.HOUR": Number(args.hour),
          "android.intent.extra.alarm.MINUTES": Number(args.minute),
          "android.intent.extra.alarm.MESSAGE": String(args.label ?? "Chaka alarm"),
          "android.intent.extra.alarm.SKIP_UI": true,
        },
      });
      return { ok: true, output: "Alarm set in the Clock app." };
    } catch (err: any) {
      return {
        ok: false,
        output: `Couldn't set the alarm (${err?.message}). This needs the newest Chaka APK — use schedule_reminder meanwhile.`,
      };
    }
  },
};

export const setTimer: Tool = {
  definition: {
    type: "function",
    function: {
      name: "set_timer",
      description: "Start a countdown timer in the phone's Clock app (Android).",
      parameters: {
        type: "object",
        properties: {
          seconds: { type: "number", description: "Timer length in seconds" },
          label: { type: "string", description: "Timer label" },
        },
        required: ["seconds"],
      },
    },
  },
  describeCall: (args) => `Start ${args.seconds}s timer${args.label ? ` (${args.label})` : ""}`,
  execute: async (args) => {
    if (!isAndroid) return { ok: false, output: "Timers are Android-only for now." };
    try {
      await IntentLauncher.startActivityAsync("android.intent.action.SET_TIMER", {
        extra: {
          "android.intent.extra.alarm.LENGTH": Number(args.seconds),
          "android.intent.extra.alarm.MESSAGE": String(args.label ?? "Chaka timer"),
          "android.intent.extra.alarm.SKIP_UI": true,
        },
      });
      return { ok: true, output: "Timer running in the Clock app." };
    } catch (err: any) {
      return {
        ok: false,
        output: `Couldn't start the timer (${err?.message}). This needs the newest Chaka APK — use schedule_reminder meanwhile.`,
      };
    }
  },
};

export const uninstallApp: Tool = {
  definition: {
    type: "function",
    function: {
      name: "uninstall_app",
      description:
        "Open the system uninstall dialog for an app by its Android package name " +
        "(e.g. com.zhiliaoapp.musically for TikTok). The user confirms the final step.",
      parameters: {
        type: "object",
        properties: {
          packageName: { type: "string", description: "Android package to uninstall" },
        },
        required: ["packageName"],
      },
    },
  },
  requiresConfirmation: true,
  describeCall: (args) => `Uninstall ${args.packageName}`,
  execute: async (args) => {
    if (!isAndroid) return { ok: false, output: "Android-only." };
    await IntentLauncher.startActivityAsync("android.intent.action.DELETE", {
      data: `package:${String(args.packageName)}`,
    });
    return { ok: true, output: "Uninstall dialog opened — user confirms." };
  },
};
