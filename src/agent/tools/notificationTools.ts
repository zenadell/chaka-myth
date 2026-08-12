import * as Hands from "../../../modules/chaka-hands";
import type { Tool } from "../types";

export const notificationDigest: Tool = {
  definition: {
    type: "function",
    function: {
      name: "notification_digest",
      description:
        "Read the phone's recent notifications so you can tell the user what they missed, or answer " +
        "questions about a message/alert. Use for 'what did I miss?', 'any important notifications?', " +
        "'did X message me?'. Requires notification access to be granted.",
      parameters: {
        type: "object",
        properties: {
          limit: { type: "number", description: "How many recent notifications to review (default 25)" },
        },
      },
    },
  },
  describeCall: () => "Review recent notifications",
  execute: async (args) => {
    if (!Hands.notificationSupport()) {
      return { ok: false, output: "Notification access needs the newest Chaka APK." };
    }
    if (!Hands.isNotificationAccessGranted()) {
      Hands.requestNotificationAccess();
      return {
        ok: false,
        output:
          "Notification access isn't granted — I opened the settings. Ask the user to enable 'Chaka Notifications', then try again.",
      };
    }
    const limit = Math.min(Number(args.limit) || 25, 60);
    const items = Hands.recentNotifications().slice(0, limit);
    if (items.length === 0) {
      return { ok: true, output: "No recent notifications captured yet." };
    }
    return {
      ok: true,
      output: items.map((n) => ({
        app: n.app,
        title: n.title,
        text: n.text,
        ago: `${Math.round((Date.now() - n.time) / 60000)}m ago`,
      })),
    };
  },
};
