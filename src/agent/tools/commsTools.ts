import * as Linking from "expo-linking";
import * as Contacts from "expo-contacts";
import type { Tool } from "../types";

export const findContact: Tool = {
  definition: {
    type: "function",
    function: {
      name: "find_contact",
      description:
        "Search the phone's contacts by name. Returns matching names with phone numbers and emails. " +
        "Use this to resolve names like 'mum' or 'John' into numbers before messaging or calling.",
      parameters: {
        type: "object",
        properties: {
          name: { type: "string", description: "Full or partial contact name" },
        },
        required: ["name"],
      },
    },
  },
  describeCall: (args) => `Look up contact "${args.name}"`,
  execute: async (args) => {
    const { status } = await Contacts.requestPermissionsAsync();
    if (status !== "granted") {
      return { ok: false, output: "Contacts permission denied by the user." };
    }
    const { data } = await Contacts.getContactsAsync({
      name: String(args.name),
      fields: [Contacts.Fields.PhoneNumbers, Contacts.Fields.Emails],
      pageSize: 8,
    });
    if (!data.length) return { ok: false, output: `No contact matching "${args.name}".` };
    return {
      ok: true,
      output: data.map((c) => ({
        name: c.name,
        phones: (c.phoneNumbers ?? []).map((p) => p.number),
        emails: (c.emails ?? []).map((e) => e.email),
      })),
    };
  },
};

function normalizePhone(raw: string): string {
  return String(raw).replace(/[^\d+]/g, "").replace(/^\+/, "");
}

export const whatsappMessage: Tool = {
  definition: {
    type: "function",
    function: {
      name: "whatsapp_message",
      description:
        "Open WhatsApp (the app, not the web) with a chat and pre-filled message ready to send — " +
        "the user just taps the send button. Provide the phone number in international format " +
        "(use find_contact first to resolve a name into a number).",
      parameters: {
        type: "object",
        properties: {
          phone: { type: "string", description: "Phone number incl. country code, e.g. +2348012345678" },
          text: { type: "string", description: "The message to pre-fill" },
        },
        required: ["phone", "text"],
      },
    },
  },
  describeCall: (args) => `WhatsApp ${args.phone}: "${String(args.text).slice(0, 50)}…"`,
  execute: async (args) => {
    const phone = normalizePhone(args.phone);
    const text = encodeURIComponent(String(args.text ?? ""));
    // Native scheme first — opens the installed app directly.
    try {
      await Linking.openURL(`whatsapp://send?phone=${phone}&text=${text}`);
      return { ok: true, output: "WhatsApp opened with the message pre-filled — user taps send." };
    } catch {
      /* app missing → web fallback */
    }
    try {
      await Linking.openURL(`https://wa.me/${phone}?text=${text}`);
      return { ok: true, output: "WhatsApp not installed — opened wa.me fallback in browser." };
    } catch {
      return { ok: false, output: "Could not open WhatsApp on this device." };
    }
  },
};

export const openUrl: Tool = {
  definition: {
    type: "function",
    function: {
      name: "open_url",
      description:
        "Open a URL or deep link on the phone. Recipes: " +
        "YouTube search: https://www.youtube.com/results?search_query=QUERY | " +
        "Spotify search: spotify:search:QUERY | " +
        "Email compose: mailto:ADDR?subject=S&body=B | " +
        "Phone call: tel:PHONE | SMS: sms:PHONE?body=TEXT | Maps: geo:0,0?q=QUERY. " +
        "For WhatsApp use the whatsapp_message tool, for launching plain apps use open_app.",
      parameters: {
        type: "object",
        properties: {
          url: { type: "string", description: "The URL or deep link to open" },
          label: { type: "string", description: "Short human-readable description" },
        },
        required: ["url"],
      },
    },
  },
  describeCall: (args) => args.label || `Open ${args.url}`,
  execute: async (args) => {
    // Don't pre-check with canOpenURL: on Android 11+ it lies unless the
    // target package is declared in the manifest <queries>. Just try.
    try {
      await Linking.openURL(args.url);
      return { ok: true, output: `Opened ${args.url}` };
    } catch {
      return {
        ok: false,
        output: `Could not open ${args.url} — scheme unsupported or app not installed.`,
      };
    }
  },
};
