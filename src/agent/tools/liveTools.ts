import * as Hands from "../../../modules/chaka-hands";
import { LIVE_MODEL, useSettings } from "../../state/settings";
import type { Tool } from "../types";

/**
 * Live Mode — opens ONE persistent Gemini Live session that watches the screen,
 * talks, and acts through function calls.
 *
 * The step-by-step operator re-uploads the whole context (rules + elements +
 * transcript) on every single action; here the system instruction and tools are
 * sent once at setup and only frames/results stream afterwards, so it stays in
 * flow instead of stopping and starting.
 */
export const goLive: Tool = {
  definition: {
    type: "function",
    function: {
      name: "go_live",
      description:
        "Start LIVE mode: watch the user's screen continuously and act on it in real time within one streaming session, " +
        "talking to them as it goes. Use when they say 'go live', 'watch my screen', 'stay with me', 'live mode', " +
        "or want continuous real-time help rather than one-shot automation. Use operate_screen for a single quick task instead.",
      parameters: {
        type: "object",
        properties: {
          goal: {
            type: "string",
            description:
              "What to focus on, or '' to just watch and assist with whatever is on screen",
          },
        },
        required: [],
      },
    },
  },
  requiresConfirmation: true,
  describeCall: (args) =>
    args.goal ? `Go live: ${String(args.goal).slice(0, 60)}` : "Go live and watch your screen",
  execute: async (args) => {
    if (!Hands.canGoLive()) {
      return { ok: false, output: "Live mode needs the newest Chaka APK." };
    }
    if (!Hands.available() || !Hands.isEnabled()) {
      Hands.openAccessibilitySettings();
      return {
        ok: false,
        output: "Chaka Hands isn't enabled — I opened Accessibility settings. Ask the user to turn it on.",
      };
    }
    const { geminiKey } = useSettings.getState();
    if (!geminiKey) {
      return { ok: false, output: "Live mode needs the Gemini key (Settings → Screen control)." };
    }
    try {
      await Hands.startLive(String(args.goal ?? ""), geminiKey, LIVE_MODEL);
      return {
        ok: true,
        output:
          "Live session is open — I can see the screen and act on it. Tell the user you're watching and they can just talk to you. " +
          "They stop it by tapping the bubble.",
      };
    } catch (e) {
      return { ok: false, output: `Couldn't start live mode: ${(e as Error).message}` };
    }
  },
};

export const stopLive: Tool = {
  definition: {
    type: "function",
    function: {
      name: "stop_live",
      description: "End the live screen-watching session.",
      parameters: { type: "object", properties: {} },
    },
  },
  describeCall: () => "End the live session",
  execute: async () => {
    Hands.stopLive();
    return { ok: true, output: "Live session closed." };
  },
};
