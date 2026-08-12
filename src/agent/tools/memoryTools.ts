import AsyncStorage from "@react-native-async-storage/async-storage";
import { useMemory } from "../../state/memory";
import type { Tool } from "../types";

const NOTES_KEY = "chaka.notes";

// --- Persistent "profile" memory: durable facts about the user that are ALWAYS
// injected into Chaka's context, so she knows them without being asked. ---

export const remember: Tool = {
  definition: {
    type: "function",
    function: {
      name: "remember",
      description:
        "Save a DURABLE fact about the user to Chaka's persistent profile — things worth knowing every future " +
        "conversation: preferences ('prefers dark mode'), people ('mum = +234...'), habits, work, likes/dislikes, " +
        "recurring context. These are always in your memory going forward. Use it whenever you learn something " +
        "lasting about the user, even if they didn't explicitly say 'remember'. Keep each fact short and specific.",
      parameters: {
        type: "object",
        properties: {
          fact: { type: "string", description: "A short, specific fact about the user" },
        },
        required: ["fact"],
      },
    },
  },
  describeCall: (args) => `Remember: "${String(args.fact).slice(0, 60)}"`,
  execute: async (args) => {
    const m = await useMemory.getState().add(String(args.fact));
    return { ok: true, output: `Noted to your profile: "${m.text}"` };
  },
};

export const recallProfile: Tool = {
  definition: {
    type: "function",
    function: {
      name: "recall_profile",
      description:
        "List everything Chaka knows about the user in their persistent profile (ids + facts). " +
        "Your profile is already in your context, but use this to get ids for forget_fact, or to review it.",
      parameters: { type: "object", properties: {} },
    },
  },
  describeCall: () => "Review your profile",
  execute: async () => {
    return { ok: true, output: useMemory.getState().memories };
  },
};

export const forgetFact: Tool = {
  definition: {
    type: "function",
    function: {
      name: "forget_fact",
      description:
        "Remove a fact from the user's persistent profile by its id (get ids from recall_profile). " +
        "Use when a fact is wrong or no longer true.",
      parameters: {
        type: "object",
        properties: {
          id: { type: "string", description: "The id of the profile fact to remove" },
        },
        required: ["id"],
      },
    },
  },
  requiresConfirmation: true,
  describeCall: (args) => `Forget profile fact ${args.id}`,
  execute: async (args) => {
    await useMemory.getState().remove(String(args.id));
    return { ok: true, output: `Removed profile fact ${args.id}` };
  },
};

interface Note {
  id: string;
  text: string;
  createdAt: string;
}

async function loadNotes(): Promise<Note[]> {
  const raw = await AsyncStorage.getItem(NOTES_KEY);
  return raw ? JSON.parse(raw) : [];
}

async function saveNotes(notes: Note[]): Promise<void> {
  await AsyncStorage.setItem(NOTES_KEY, JSON.stringify(notes));
}

export const saveNote: Tool = {
  definition: {
    type: "function",
    function: {
      name: "save_note",
      description:
        "Save a note, reminder, or fact to Chaka's local memory on this phone. " +
        "Use when the user asks you to remember something.",
      parameters: {
        type: "object",
        properties: {
          text: { type: "string", description: "The note content to remember" },
        },
        required: ["text"],
      },
    },
  },
  describeCall: (args) =>
    `Save note: "${String(args.text).slice(0, 60)}${String(args.text).length > 60 ? "…" : ""}"`,
  execute: async (args) => {
    const notes = await loadNotes();
    const note: Note = {
      id: Date.now().toString(36),
      text: args.text,
      createdAt: new Date().toISOString(),
    };
    notes.push(note);
    await saveNotes(notes);
    return { ok: true, output: `Saved note ${note.id}` };
  },
};

export const listNotes: Tool = {
  definition: {
    type: "function",
    function: {
      name: "list_notes",
      description:
        "List everything saved in Chaka's local memory. Use to recall things the user asked you to remember.",
      parameters: { type: "object", properties: {} },
    },
  },
  describeCall: () => "Read saved notes",
  execute: async () => {
    const notes = await loadNotes();
    return { ok: true, output: notes };
  },
};

export const deleteNote: Tool = {
  definition: {
    type: "function",
    function: {
      name: "delete_note",
      description: "Delete a note from Chaka's local memory by its id (get ids from list_notes).",
      parameters: {
        type: "object",
        properties: {
          id: { type: "string", description: "The id of the note to delete" },
        },
        required: ["id"],
      },
    },
  },
  requiresConfirmation: true,
  describeCall: (args) => `Delete note ${args.id}`,
  execute: async (args) => {
    const notes = await loadNotes();
    const remaining = notes.filter((n) => n.id !== args.id);
    if (remaining.length === notes.length) {
      return { ok: false, output: `No note with id ${args.id}` };
    }
    await saveNotes(remaining);
    return { ok: true, output: `Deleted note ${args.id}` };
  },
};
