import { streamChatCompletion } from "../lib/deepseek";
import { geminiVision } from "../lib/gemini";
import { useSettings, VISION_MODEL } from "../state/settings";
import * as Hands from "../../modules/chaka-hands";
import { openApp } from "./tools/deviceTools";
import { runControl, throwIfAborted, withTimeout, abortableSleep, isAbortError } from "./abort";
import type { ChatMessage, Tool } from "./types";

/**
 * Screen operator — Chaka's agentic "hands".
 *
 * read → decide → act loop against the AccessibilityService. Perception is
 * hybrid: the accessibility tree gives numbered, tappable elements (the
 * reliable Set-of-Marks approach — the model picks an index, never raw
 * coordinates), and when a Gemini vision key is set a screenshot is added so
 * the model can also handle custom-drawn UIs the tree can't see.
 *
 * Hard-bounded: every model call is timeouted, the whole run has a wall-clock
 * cap, and it is cancellable at any point via runControl.
 */

const CALL_TIMEOUT_MS = 25_000;
const SHOT_TIMEOUT_MS = 6_000;
const RUN_BUDGET_MS = 100_000;
const MAX_STALLS = 3;

const log = (...a: unknown[]) => console.log("[operator]", ...a);

const CHAKA_PKG = "com.chakamyth.app";

async function llm(messages: ChatMessage[]): Promise<string> {
  const { apiKey } = useSettings.getState();
  if (!apiKey) throw new Error("No API key configured");
  const result = await withTimeout(
    streamChatCompletion({
      apiKey,
      model: "deepseek-v4-flash",
      messages,
      signal: runControl.signal ?? undefined,
    }),
    CALL_TIMEOUT_MS,
    "DeepSeek"
  );
  return result.content;
}

/** Numbered, tappable elements — this is the Set-of-Marks the model selects from. */
function numberedElements(dump: Hands.ScreenDump): string {
  if (dump.els.length === 0) {
    return "(no readable elements — this screen is custom-drawn; use vision coords or scroll)";
  }
  return dump.els
    .map((e) => {
      const label = e.text ?? e.desc ?? "";
      const flags = [e.clickable ? "tap" : "", e.editable ? "input" : ""].filter(Boolean).join(",");
      return `[${e.i}] "${label}" (${e.cls})${flags ? " " + flags : ""}`;
    })
    .join("\n");
}

function screenSignature(dump: Hands.ScreenDump): string {
  return dump.els.map((e) => `${e.text ?? e.desc ?? ""}@${e.cx},${e.cy}`).join("|");
}

interface Action {
  thought?: string;
  action: {
    type: string;
    index?: number;
    x?: number;
    y?: number;
    text?: string;
    direction?: string;
    button?: string;
    app?: string;
    result?: string;
    reason?: string;
  };
}

function parseAction(raw: string): Action | null {
  const match = raw.match(/\{[\s\S]*\}/);
  if (!match) return null;
  try {
    return JSON.parse(match[0]);
  } catch {
    return null;
  }
}

const ACTION_MENU = `Action types (ONE per turn):
- {"type":"tap_index","index":N}    tap element [N] from the list — PREFER THIS whenever the target is listed
- {"type":"tap","x":0.5,"y":0.3}    tap a screen point as FRACTIONS 0..1 (only for elements NOT in the list, e.g. photos/custom UI)
- {"type":"type","text":"..."}      type into the focused field (tap an "input" element first)
- {"type":"enter"}                  press the keyboard search/enter key after typing
- {"type":"swipe","direction":"up|down|left|right"}   scroll to reveal more
- {"type":"press","button":"back|home|recents|notifications|quick_settings"}
- {"type":"open","app":"spotify"}   launch an app — do this AT MOST ONCE; never re-open an app that's already open
- {"type":"wait"}                   wait ~1s for loading, then look again
- {"type":"done","result":"..."}    the goal is achieved
- {"type":"fail","reason":"..."}    genuinely stuck after trying alternatives`;

const RULES = `Rules:
- Prefer tap_index. Only use tap x,y fractions for things the element list doesn't contain.
- To search: tap the "input" field, type, then enter.
- Scroll (swipe up) to find things off-screen before giving up.
- The target app is ALREADY OPEN once you've opened it once — do NOT open it again; look at the current screen.
- If CURRENT APP shows the home launcher or the wrong app, use {"type":"open","app":"..."} to launch the app named in the goal. Then navigate.
- Be honest: never claim a step you didn't take.`;

async function execute(action: Action["action"], dump: Hands.ScreenDump): Promise<string> {
  switch (action.type) {
    case "tap_index": {
      const el = dump.els.find((e) => e.i === action.index);
      if (!el) return `no element [${action.index}] on screen (scroll or re-check)`;
      await Hands.tap(el.cx, el.cy);
      return `tapped [${action.index}] "${el.text ?? el.desc ?? ""}"`;
    }
    case "tap": {
      // Fractions 0..1 → pixels.
      let x = action.x ?? 0;
      let y = action.y ?? 0;
      if (x <= 1 && y <= 1) {
        x = Math.round(x * dump.w);
        y = Math.round(y * dump.h);
      }
      await Hands.tap(x, y);
      return `tapped ${x},${y}`;
    }
    case "type":
      await Hands.typeText(action.text ?? "");
      return `typed "${action.text}"`;
    case "enter":
      await Hands.pressEnter();
      return "pressed enter";
    case "swipe": {
      const { w, h } = dump;
      const cx = Math.round(w / 2);
      const map: Record<string, [number, number, number, number]> = {
        up: [cx, Math.round(h * 0.7), cx, Math.round(h * 0.3)],
        down: [cx, Math.round(h * 0.3), cx, Math.round(h * 0.7)],
        left: [Math.round(w * 0.7), Math.round(h / 2), Math.round(w * 0.3), Math.round(h / 2)],
        right: [Math.round(w * 0.3), Math.round(h / 2), Math.round(w * 0.7), Math.round(h / 2)],
      };
      const coords = map[action.direction ?? "up"] ?? map.up;
      await Hands.swipe(...coords);
      return `swiped ${action.direction}`;
    }
    case "press":
      await Hands.globalAction((action.button ?? "back") as any);
      return `pressed ${action.button}`;
    case "open":
      await openApp.execute({ name: action.app ?? "" });
      return `opened ${action.app}`;
    case "wait":
      return "waited";
    default:
      return `unknown action ${action.type}`;
  }
}

async function visionDecide(
  goal: string,
  dump: Hands.ScreenDump,
  imageB64: string,
  transcript: string[]
): Promise<Action | null> {
  const { geminiKey } = useSettings.getState();
  const prompt =
    `You are Chaka's screen operator WITH VISION. Look at the screenshot AND the numbered element list, then choose ONE next action toward the goal. Reply with ONLY JSON: {"thought":"...","action":{...}}\n\n` +
    `${ACTION_MENU}\n\n${RULES}\n\n` +
    `GOAL: ${goal}\n\nCURRENT APP: ${dump.pkg || "unknown"}\nNUMBERED ELEMENTS:\n${numberedElements(dump)}\n\n` +
    `ACTIONS SO FAR:\n${transcript.join("\n") || "(none yet)"}\n\nNext action JSON:`;
  const raw = await withTimeout(
    geminiVision({
      apiKey: geminiKey!,
      model: VISION_MODEL,
      prompt,
      imageBase64: imageB64,
      signal: runControl.signal ?? undefined,
    }),
    CALL_TIMEOUT_MS,
    "Gemini vision"
  );
  return parseAction(raw);
}

async function treeDecide(
  goal: string,
  dump: Hands.ScreenDump,
  stallHint: string,
  transcript: string[]
): Promise<Action | null> {
  const raw = await llm([
    {
      role: "system",
      content:
        `You are Chaka's screen operator. Choose ONE next action toward the goal from the numbered element list. Reply with ONLY JSON: {"thought":"...","action":{...}}\n\n${ACTION_MENU}\n\n${RULES}`,
    },
    {
      role: "user",
      content:
        `GOAL: ${goal}\n\nCURRENT APP: ${dump.pkg || "unknown"}\nNUMBERED ELEMENTS:\n${numberedElements(dump)}\nscreen ${dump.w}x${dump.h}${stallHint}\n\n` +
        `ACTIONS SO FAR:\n${transcript.join("\n") || "(none yet)"}\n\nNext action JSON:`,
    },
  ]);
  return parseAction(raw);
}

export async function runOperator(goal: string, maxSteps = 12): Promise<{
  outcome: "done" | "fail" | "exhausted" | "stopped";
  detail: string;
  mode: "vision" | "tree";
  transcript: string[];
}> {
  const { geminiKey } = useSettings.getState();
  const useVision = !!geminiKey && Hands.canScreenshot();
  const transcript: string[] = [];
  const startedAt = Date.now();
  let lastSignature = "";
  let stalls = 0;
  let openedApp = false;

  // Keep Chaka's process alive at foreground priority so the loop keeps running
  // while it's backgrounded and driving another app.
  Hands.startKeepAlive();

  log(`start goal="${goal}" vision=${useVision} hasGeminiKey=${!!geminiKey} canShot=${Hands.canScreenshot()} available=${Hands.available()} enabled=${Hands.isEnabled()}`);

  // Get Chaka out of the way so the operator sees the TARGET app, not its own
  // chat UI (otherwise it taps its own buttons — the "bounces back to chat" bug).
  try {
    const first = await withTimeout(Hands.readScreen(), SHOT_TIMEOUT_MS, "readScreen");
    if (first.pkg === CHAKA_PKG) {
      log("foreground is Chaka itself — pressing home to step aside");
      await Hands.globalAction("home");
      await abortableSleep(1200);
    }
  } catch {
    /* non-fatal */
  }

  try {
    for (let step = 0; step < maxSteps; step++) {
      throwIfAborted();
      if (Date.now() - startedAt > RUN_BUDGET_MS) {
        return { outcome: "fail", detail: "Took too long and stopped so it wouldn't hang.", mode: useVision ? "vision" : "tree", transcript };
      }

      let dump: Hands.ScreenDump;
      try {
        dump = await withTimeout(Hands.readScreen(), SHOT_TIMEOUT_MS, "readScreen");
      } catch (err) {
        if (isAbortError(err)) throw err;
        log(`step ${step + 1}: readScreen FAILED:`, (err as any)?.message ?? err);
        return { outcome: "fail", detail: `Couldn't read the screen: ${(err as any)?.message ?? err}`, mode: useVision ? "vision" : "tree", transcript };
      }
      const signature = screenSignature(dump);
      log(`step ${step + 1}: pkg=${dump.pkg} elements=${dump.els.length} screen=${dump.w}x${dump.h}`);

      // If we're somehow back on Chaka's own screen, don't act on it — step aside.
      if (dump.pkg === CHAKA_PKG) {
        await Hands.globalAction("home");
        await abortableSleep(1000);
        transcript.push(`step ${step + 1}: (was on Chaka's own screen — pressed home)`);
        continue;
      }

      let stallHint = "";
      if (signature === lastSignature && step > 0) {
        stalls++;
        if (stalls >= MAX_STALLS) {
          return { outcome: "fail", detail: "The screen stopped responding to taps — likely a view the accessibility layer can't act on.", mode: useVision ? "vision" : "tree", transcript };
        }
        stallHint = `\n\nNOTE: screen unchanged after the last action (${stalls}x). Try a different element, scroll, or wait.`;
      } else {
        stalls = 0;
      }
      lastSignature = signature;

      let parsed: Action | null = null;
      if (useVision) {
        try {
          const shot = await withTimeout(Hands.screenshot(), SHOT_TIMEOUT_MS, "Screenshot");
          log(`step ${step + 1}: screenshot=${shot ? shot.length + "b" : "null"}`);
          if (shot) parsed = await visionDecide(goal, dump, shot, transcript);
        } catch (err) {
          if (isAbortError(err)) throw err;
          log(`step ${step + 1}: vision FAILED, falling back to tree:`, (err as any)?.message ?? err);
        }
      }
      if (!parsed) {
        try {
          parsed = await treeDecide(goal, dump, stallHint, transcript);
        } catch (err) {
          if (isAbortError(err)) throw err;
          log(`step ${step + 1}: treeDecide FAILED:`, (err as any)?.message ?? err);
          return { outcome: "fail", detail: `The model call failed: ${(err as any)?.message ?? err}`, mode: useVision ? "vision" : "tree", transcript };
        }
      }

      if (!parsed) {
        log(`step ${step + 1}: model returned no parseable action`);
        transcript.push(`step ${step + 1}: no valid action`);
        continue;
      }

      const type = parsed.action.type;
      log(`step ${step + 1}: action=${type} ${JSON.stringify(parsed.action)}`);
      if (type === "done") return { outcome: "done", detail: parsed.action.result ?? "Done.", mode: useVision ? "vision" : "tree", transcript };
      if (type === "fail") return { outcome: "fail", detail: parsed.action.reason ?? "Stuck.", mode: useVision ? "vision" : "tree", transcript };

      // Guard against the re-open loop that made it look frozen.
      if (type === "open") {
        if (openedApp) {
          transcript.push(`step ${step + 1}: (skipped re-opening ${parsed.action.app}; already open)`);
          await abortableSleep(600);
          continue;
        }
        openedApp = true;
      }

      const did = await execute(parsed.action, dump);
      log(`step ${step + 1}: did=${did}`);
      transcript.push(`step ${step + 1}: ${parsed.thought ?? ""} → ${did}`);

      const settle = type === "open" ? 1700 : type === "wait" ? 1100 : 800;
      await abortableSleep(settle);
    }

    return { outcome: "exhausted", detail: `Tried ${maxSteps} steps without finishing.`, mode: useVision ? "vision" : "tree", transcript };
  } catch (err) {
    if (isAbortError(err)) return { outcome: "stopped", detail: "You stopped it.", mode: useVision ? "vision" : "tree", transcript };
    throw err;
  } finally {
    Hands.stopKeepAlive();
  }
}

export const operateScreen: Tool = {
  definition: {
    type: "function",
    function: {
      name: "operate_screen",
      description:
        "Take control of the phone screen to accomplish a goal by looking and tapping/typing/swiping like a human — for ANY app or system setting, including things with no API (turn ON Bluetooth/Wi-Fi, operate an app UI). Requires the Chaka Hands accessibility service to be enabled. Give a concrete goal, e.g. 'in Spotify, play the top result for Golden Brown'. " +
        "IMPORTANT: if the task happens inside a specific app, pass its name/package in 'app' — Chaka will open it directly and start there (much faster than navigating from home). Do NOT call open_app first; let this tool open it. For system settings (bluetooth, display, etc.) use app='settings'.",
      parameters: {
        type: "object",
        properties: {
          goal: { type: "string", description: "Concrete goal to accomplish on-screen" },
          app: {
            type: "string",
            description:
              "The app the task happens in, so Chaka opens it directly. Known: spotify, youtube, whatsapp, instagram, chrome, gmail, maps, photos, settings, camera, tiktok, telegram, x, facebook. For others pass the Android package (e.g. com.example.app). Omit for pure home-screen/cross-app tasks.",
          },
          maxSteps: { type: "number", description: "Max actions (default 14)" },
        },
        required: ["goal"],
      },
    },
  },
  requiresConfirmation: true,
  describeCall: (args) => `Control the screen to: ${String(args.goal).slice(0, 70)}`,
  execute: async (args) => {
    if (!Hands.available()) {
      return { ok: false, output: "Screen control needs the newest Chaka APK. Not in this install." };
    }
    if (!Hands.isEnabled()) {
      Hands.openAccessibilitySettings();
      return {
        ok: false,
        output:
          "Chaka Hands is NOT enabled — I opened Accessibility settings. The user must toggle on 'Chaka Hands' there, then ask again. Do not claim you tapped anything.",
      };
    }
    // Preferred: run the loop NATIVELY so it survives Chaka being backgrounded
    // (RN's JS thread freezes in the background on Samsung etc.).
    if (Hands.canOperateNatively()) {
      const { apiKey, geminiKey } = useSettings.getState();
      const onAbort = () => Hands.stopOperate();
      runControl.signal?.addEventListener?.("abort", onAbort);
      try {
        const result = await Hands.operate(
          String(args.goal),
          apiKey ?? "",
          geminiKey,
          Number(args.maxSteps) || 18,
          args.app ? String(args.app) : null
        );
        log(`NATIVE OUTCOME=${result.outcome} detail="${result.detail}"`);
        return {
          ok: result.outcome === "done",
          output: {
            outcome: result.outcome,
            perception: result.perception,
            detail: result.detail,
            steps: result.steps,
          },
        };
      } finally {
        runControl.signal?.removeEventListener?.("abort", onAbort);
      }
    }

    // Fallback: JS loop (only works while Chaka stays foreground).
    const result = await runOperator(String(args.goal), Number(args.maxSteps) || 12);
    log(`JS OUTCOME=${result.outcome} mode=${result.mode} detail="${result.detail}"`);
    return {
      ok: result.outcome === "done",
      output: {
        outcome: result.outcome,
        perception: result.mode === "vision" ? "vision+tree" : "accessibility tree only (no Gemini key)",
        detail: result.detail,
        steps: result.transcript,
      },
    };
  },
};

export const pressButton: Tool = {
  definition: {
    type: "function",
    function: {
      name: "press_button",
      description:
        "Press a system navigation button via the accessibility service: back, home, recents, notifications (pull down shade), quick_settings, or lock.",
      parameters: {
        type: "object",
        properties: {
          button: {
            type: "string",
            enum: ["back", "home", "recents", "notifications", "quick_settings", "lock"],
          },
        },
        required: ["button"],
      },
    },
  },
  describeCall: (args) => `Press ${args.button}`,
  execute: async (args) => {
    if (!Hands.available() || !Hands.isEnabled()) {
      return { ok: false, output: "Chaka Hands accessibility service isn't enabled." };
    }
    const ok = await Hands.globalAction(args.button);
    return { ok, output: ok ? `Pressed ${args.button}` : `Couldn't press ${args.button}` };
  },
};
