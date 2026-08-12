import type { Tool } from "../types";

/** react-native-volume-manager is lazy-required — see calendarTools.ts for why. */
function loadVolumeManager(): any | null {
  try {
    return require("react-native-volume-manager").VolumeManager;
  } catch {
    return null;
  }
}

const NEEDS_APK = "Volume control needs the newest Chaka APK (coming in the next install).";

export const setVolume: Tool = {
  definition: {
    type: "function",
    function: {
      name: "set_volume",
      description: "Set the phone's media volume (0-100), or mute/unmute with 0/previous level.",
      parameters: {
        type: "object",
        properties: {
          percent: { type: "number", description: "Volume 0-100" },
        },
        required: ["percent"],
      },
    },
  },
  describeCall: (args) => `Set volume to ${args.percent}%`,
  execute: async (args) => {
    const VolumeManager = loadVolumeManager();
    if (!VolumeManager) return { ok: false, output: NEEDS_APK };
    const value = Math.min(100, Math.max(0, Number(args.percent))) / 100;
    await VolumeManager.setVolume(value, { showUI: true });
    return { ok: true, output: `Media volume set to ${Math.round(value * 100)}%` };
  },
};
