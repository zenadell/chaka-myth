import type { Tool } from "../types";
import { getDeviceStatus } from "./deviceTools";
import { getWeather, webSearch } from "./webTools";
import { listReminders } from "./reminderTools";
import { listNotes } from "./memoryTools";

export const dailyBriefing: Tool = {
  definition: {
    type: "function",
    function: {
      name: "daily_briefing",
      description:
        "Gather everything for a personal briefing in one shot: current weather, top news headlines, " +
        "pending reminders, saved notes, and device status. Use when the user says 'brief me', " +
        "'good morning', 'what's up today', or similar. Compose the results into a short, warm briefing.",
      parameters: {
        type: "object",
        properties: {
          newsTopic: {
            type: "string",
            description: "Optional news focus, e.g. 'tech news'. Default: general top headlines.",
          },
        },
      },
    },
  },
  describeCall: () => "Gather daily briefing",
  execute: async (args) => {
    const topic = args.newsTopic ? String(args.newsTopic) : "top world news headlines";
    const [weather, news, reminders, notes, device] = await Promise.allSettled([
      getWeather.execute({}),
      webSearch.execute({ query: `${topic} today` }),
      listReminders.execute({}),
      listNotes.execute({}),
      getDeviceStatus.execute({}),
    ]);

    const unwrap = (r: PromiseSettledResult<{ ok: boolean; output: unknown }>) =>
      r.status === "fulfilled" ? r.value.output : `(unavailable: ${r.reason?.message ?? "error"})`;

    return {
      ok: true,
      output: {
        weather: unwrap(weather),
        news: unwrap(news),
        reminders: unwrap(reminders),
        notes: unwrap(notes),
        device: unwrap(device),
      },
    };
  },
};
