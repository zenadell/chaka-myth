import type { Tool } from "../types";

/**
 * expo-calendar is lazy-required: it isn't compiled into APKs older than 0.3.0,
 * so a static import would crash OTA-updated old builds at startup.
 */
function loadCalendar(): typeof import("expo-calendar") | null {
  try {
    return require("expo-calendar");
  } catch {
    return null;
  }
}

const NEEDS_APK = "Calendar access needs the newest Chaka APK (coming in the next install).";

export const listCalendarEvents: Tool = {
  definition: {
    type: "function",
    function: {
      name: "calendar_events",
      description: "List calendar events in the next N days (default 7).",
      parameters: {
        type: "object",
        properties: {
          days: { type: "number", description: "How many days ahead to look. Default 7." },
        },
      },
    },
  },
  describeCall: (args) => `Read calendar (next ${args.days ?? 7} days)`,
  execute: async (args) => {
    const Calendar = loadCalendar();
    if (!Calendar) return { ok: false, output: NEEDS_APK };
    const { status } = await Calendar.requestCalendarPermissionsAsync();
    if (status !== "granted") return { ok: false, output: "Calendar permission denied." };
    const calendars = await Calendar.getCalendarsAsync(Calendar.EntityTypes.EVENT);
    const start = new Date();
    const end = new Date(Date.now() + (Number(args.days) || 7) * 86400_000);
    const events = await Calendar.getEventsAsync(calendars.map((c) => c.id), start, end);
    return {
      ok: true,
      output: events.map((e) => ({
        id: e.id,
        title: e.title,
        start: e.startDate,
        end: e.endDate,
        location: e.location,
        allDay: e.allDay,
      })),
    };
  },
};

export const createCalendarEvent: Tool = {
  definition: {
    type: "function",
    function: {
      name: "create_calendar_event",
      description:
        "Create a calendar event. Times are ISO datetimes; convert relative phrasing using the current time.",
      parameters: {
        type: "object",
        properties: {
          title: { type: "string" },
          start: { type: "string", description: "ISO start datetime" },
          end: { type: "string", description: "ISO end datetime (default start + 1h)" },
          location: { type: "string" },
          notes: { type: "string" },
        },
        required: ["title", "start"],
      },
    },
  },
  requiresConfirmation: true,
  describeCall: (args) => `Create event "${args.title}" at ${args.start}`,
  execute: async (args) => {
    const Calendar = loadCalendar();
    if (!Calendar) return { ok: false, output: NEEDS_APK };
    const { status } = await Calendar.requestCalendarPermissionsAsync();
    if (status !== "granted") return { ok: false, output: "Calendar permission denied." };
    const startDate = new Date(String(args.start));
    if (isNaN(startDate.getTime())) return { ok: false, output: `Invalid start: ${args.start}` };
    const endDate = args.end ? new Date(String(args.end)) : new Date(startDate.getTime() + 3600_000);

    const calendars = await Calendar.getCalendarsAsync(Calendar.EntityTypes.EVENT);
    const target =
      calendars.find((c) => c.allowsModifications && c.isPrimary) ??
      calendars.find((c) => c.allowsModifications);
    if (!target) return { ok: false, output: "No writable calendar found on this phone." };

    const id = await Calendar.createEventAsync(target.id, {
      title: String(args.title),
      startDate,
      endDate,
      location: args.location ? String(args.location) : undefined,
      notes: args.notes ? String(args.notes) : undefined,
    });
    return { ok: true, output: `Event created (id ${id}) in calendar "${target.title}".` };
  },
};
