import { Camera } from "expo-camera";
import { useDeviceFx } from "../../state/deviceFx";
import { useSettings } from "../../state/settings";
import { geminiVision } from "../../lib/gemini";
import { VISION_MODEL } from "../../state/settings";
import type { Tool } from "../types";

/**
 * "Look at this" — opens a live camera view, snaps a frame, and asks Gemini the
 * user's question about what it sees. Requires a Gemini vision key (same one the
 * screen operator uses).
 */

let resolver: ((r: { base64?: string; error?: string }) => void) | null = null;

export function completeVision(result: { base64?: string; error?: string }) {
  useDeviceFx.getState().setVisionRequest(null);
  resolver?.(result);
  resolver = null;
}

export const look: Tool = {
  definition: {
    type: "function",
    function: {
      name: "look",
      description:
        "Open the camera and LOOK at something in the real world to answer a question about it — " +
        "'what is this?', 'read this label/sign/text', 'how do I fix this?', 'what's wrong with this?', " +
        "'identify this plant/object'. Point-and-see, like Jarvis. Provide the question to answer.",
      parameters: {
        type: "object",
        properties: {
          question: {
            type: "string",
            description: "What to find out about what the camera sees",
          },
          facing: { type: "string", enum: ["front", "back"], description: "Camera. Default back." },
        },
        required: ["question"],
      },
    },
  },
  describeCall: (args) => `Look: "${String(args.question).slice(0, 50)}"`,
  execute: async (args) => {
    const { geminiKey } = useSettings.getState();
    if (!geminiKey) {
      return {
        ok: false,
        output: "Live vision needs a Gemini key — add one in Settings → Screen control (aistudio.google.com/apikey).",
      };
    }
    const cam = await Camera.requestCameraPermissionsAsync();
    if (cam.status !== "granted") return { ok: false, output: "Camera permission denied." };
    if (resolver) return { ok: false, output: "Already looking at something." };

    const frame = await new Promise<{ base64?: string; error?: string }>((resolve) => {
      resolver = resolve;
      useDeviceFx.getState().setVisionRequest({
        facing: args.facing === "front" ? "front" : "back",
        question: String(args.question),
      });
    });

    if (frame.error || !frame.base64) {
      return { ok: false, output: frame.error ?? "Didn't capture anything." };
    }

    try {
      const answer = await geminiVision({
        apiKey: geminiKey,
        model: VISION_MODEL,
        prompt:
          `You are Chaka looking through your owner's camera. Answer their question about what you see, ` +
          `directly and helpfully, in 1-3 short sentences. Question: ${args.question}`,
        imageBase64: frame.base64,
        json: false,
      });
      return { ok: true, output: answer };
    } catch (err: any) {
      return { ok: false, output: `Vision failed: ${err?.message ?? err}` };
    }
  },
};
