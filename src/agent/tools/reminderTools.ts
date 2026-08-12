import * as Notifications from "expo-notifications";
import type { Tool } from "../types";

export const scheduleReminder: Tool = {
  definition: {
    type: "function",
    function: {
      name: "schedule_reminder",
      description:
        "Schedule a phone notification reminder at a specific date/time. " +
        "Works even when the app is closed. Convert relative times ('in 20 minutes', 'tomorrow 9am') " +
        "to an absolute ISO datetime yourself using the current time from the system prompt.",
      parameters: {
        type: "object",
        properties: {
          text: { type: "string", description: "What to remind about" },
          when: { type: "string", description: "ISO datetime, e.g. 2026-07-06T21:30:00" },
        },
        required: ["text", "when"],
      },
    },
  },
  describeCall: (args) => `Remind: "${String(args.text).slice(0, 40)}" at ${args.when}`,
  execute: async (args) => {
    const { status } = await Notifications.requestPermissionsAsync();
    if (status !== "granted") return { ok: false, output: "Notification permission denied." };
    const date = new Date(String(args.when));
    if (isNaN(date.getTime())) return { ok: false, output: `Invalid datetime: ${args.when}` };
    if (date.getTime() < Date.now()) return { ok: false, output: "That time is in the past." };
    const id = await Notifications.scheduleNotificationAsync({
      content: { title: "Chaka reminder", body: String(args.text), sound: true },
      trigger: { type: Notifications.SchedulableTriggerInputTypes.DATE, date },
    });
    return { ok: true, output: `Reminder scheduled (id ${id}) for ${date.toLocaleString()}` };
  },
};

export const listReminders: Tool = {
  definition: {
    type: "function",
    function: {
      name: "list_reminders",
      description: "List all pending scheduled reminders.",
      parameters: { type: "object", properties: {} },
    },
  },
  describeCall: () => "List reminders",
  execute: async () => {
    const all = await Notifications.getAllScheduledNotificationsAsync();
    return {
      ok: true,
      output: all.map((n) => ({
        id: n.identifier,
        text: n.content.body,
        trigger: n.trigger,
      })),
    };
  },
};

export const cancelReminder: Tool = {
  definition: {
    type: "function",
    function: {
      name: "cancel_reminder",
      description: "Cancel a scheduled reminder by id (get ids from list_reminders).",
      parameters: {
        type: "object",
        properties: {
          id: { type: "string", description: "Reminder id" },
        },
        required: ["id"],
      },
    },
  },
  requiresConfirmation: true,
  describeCall: (args) => `Cancel reminder ${args.id}`,
  execute: async (args) => {
    await Notifications.cancelScheduledNotificationAsync(String(args.id));
    return { ok: true, output: "Reminder cancelled." };
  },
};
