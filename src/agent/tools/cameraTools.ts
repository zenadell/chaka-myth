import { Camera } from "expo-camera";
import * as MediaLibrary from "expo-media-library/legacy";
import { useDeviceFx } from "../../state/deviceFx";
import type { Tool } from "../types";

/**
 * take_photo works by mounting the CameraCapture overlay (rendered by App
 * while cameraRequest is set). The overlay counts down, snaps, and calls
 * completeCapture with the photo uri.
 */

let resolver: ((result: { uri?: string; error?: string }) => void) | null = null;

export function completeCapture(result: { uri?: string; error?: string }) {
  useDeviceFx.getState().setCameraRequest(null);
  resolver?.(result);
  resolver = null;
}

export const takePhoto: Tool = {
  definition: {
    type: "function",
    function: {
      name: "take_photo",
      description:
        "Take a photo with the phone camera (3-second countdown, then snaps and saves to the gallery). " +
        "facing='front' for selfies, 'back' for everything else. Returns the saved photo's uri.",
      parameters: {
        type: "object",
        properties: {
          facing: { type: "string", enum: ["front", "back"], description: "Which camera. Default back." },
        },
      },
    },
  },
  describeCall: (args) => (args.facing === "front" ? "Take a selfie" : "Take a photo"),
  execute: async (args) => {
    const cam = await Camera.requestCameraPermissionsAsync();
    if (cam.status !== "granted") return { ok: false, output: "Camera permission denied." };
    const media = await MediaLibrary.requestPermissionsAsync();
    if (media.status !== "granted") return { ok: false, output: "Media library permission denied — can't save the photo." };

    if (resolver) return { ok: false, output: "A capture is already in progress." };

    const result = await new Promise<{ uri?: string; error?: string }>((resolve) => {
      resolver = resolve;
      useDeviceFx.getState().setCameraRequest({
        facing: args.facing === "front" ? "front" : "back",
      });
    });

    if (result.error) return { ok: false, output: result.error };
    return { ok: true, output: `Photo saved to gallery: ${result.uri}` };
  },
};
