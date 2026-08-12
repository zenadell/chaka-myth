import * as Hands from "../../../modules/chaka-hands";
import { useSettings } from "../../state/settings";
import { runControl } from "../abort";
import type { Tool } from "../types";

/**
 * Guide Mode — Chaka watches the user's screen live and coaches them through a
 * task step by step (floating bubble + spoken guidance), WITHOUT tapping. The
 * user drives. Use for "guide/walk/talk me through X", "help me do Y myself",
 * "show me how to Z". Different from operate_screen (which takes over).
 */
export const guideMe: Tool = {
  definition: {
    type: "function",
    function: {
      name: "guide_me",
      description:
        "Watch the user's screen live and COACH them through a task step by step — a floating bubble " +
        "shows and speaks the next step, but the USER taps (you never take over). Use for 'guide me through X', " +
        "'walk me through', 'help me do this myself', 'show me how to…'. For doing it FOR them, use operate_screen instead. " +
        "Requires Chaka Hands + a Gemini vision key. Runs until the goal is done or the user taps the bubble to stop.",
      parameters: {
        type: "object",
        properties: {
          goal: { type: "string", description: "What to guide the user through" },
        },
        required: ["goal"],
      },
    },
  },
  requiresConfirmation: true,
  describeCall: (args) => `Guide you through: ${String(args.goal).slice(0, 60)}`,
  execute: async (args) => {
    if (!Hands.canGuide()) {
      return { ok: false, output: "Guide Mode needs the newest Chaka APK." };
    }
    if (!Hands.available() || !Hands.isEnabled()) {
      Hands.openAccessibilitySettings();
      return { ok: false, output: "Chaka Hands isn't enabled — I opened Accessibility settings. Ask the user to turn it on." };
    }
    const { geminiKey } = useSettings.getState();
    if (!geminiKey) {
      return { ok: false, output: "Guide Mode needs a Gemini vision key (Settings → Screen control)." };
    }
    const onAbort = () => Hands.stopGuide();
    runControl.signal?.addEventListener?.("abort", onAbort);
    try {
      const outcome = await Hands.startGuide(String(args.goal), geminiKey);
      const map: Record<string, { ok: boolean; output: string }> = {
        done: { ok: true, output: "The user finished the task — Gemini saw the goal reached. Congratulate them briefly." },
        stopped: { ok: true, output: "The user tapped the bubble to stop the guide. Don't claim it's done — just ask if they want to pick it back up." },
        idle: { ok: true, output: "I paused the guide after a stretch of no activity — I did NOT see them finish. Tell them you're still here and can continue whenever; do not say it's done." },
        timeout: { ok: true, output: "The guide ran its full time limit without a confirmed finish. Ask if they got there or want to keep going; don't claim completion." },
      };
      return map[outcome] ?? { ok: true, output: `Guide ended (${outcome}). Ask if they need anything else.` };
    } finally {
      runControl.signal?.removeEventListener?.("abort", onAbort);
    }
  },
};
