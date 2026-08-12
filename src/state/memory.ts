import AsyncStorage from "@react-native-async-storage/async-storage";
import { create } from "zustand";

const MEMORY_KEY = "chaka.memory.v1";

export interface Memory {
  id: string;
  text: string;
  createdAt: string;
}

interface MemoryState {
  memories: Memory[];
  loaded: boolean;
  load: () => Promise<void>;
  add: (text: string) => Promise<Memory>;
  remove: (id: string) => Promise<void>;
  clear: () => Promise<void>;
}

async function persist(memories: Memory[]) {
  await AsyncStorage.setItem(MEMORY_KEY, JSON.stringify(memories));
}

export const useMemory = create<MemoryState>((set, get) => ({
  memories: [],
  loaded: false,

  load: async () => {
    const raw = await AsyncStorage.getItem(MEMORY_KEY);
    set({ memories: raw ? JSON.parse(raw) : [], loaded: true });
  },

  add: async (text: string) => {
    const trimmed = text.trim();
    // De-dupe near-identical facts so the profile doesn't bloat.
    const existing = get().memories.find(
      (m) => m.text.toLowerCase() === trimmed.toLowerCase()
    );
    if (existing) return existing;
    const memory: Memory = {
      id: Date.now().toString(36) + Math.random().toString(36).slice(2, 6),
      text: trimmed,
      createdAt: new Date().toISOString(),
    };
    const memories = [...get().memories, memory];
    set({ memories });
    await persist(memories);
    return memory;
  },

  remove: async (id: string) => {
    const memories = get().memories.filter((m) => m.id !== id);
    set({ memories });
    await persist(memories);
  },

  clear: async () => {
    set({ memories: [] });
    await persist([]);
  },
}));

/** The block injected into the system prompt so Chaka always knows the user. */
export function memoryContext(): string {
  const memories = useMemory.getState().memories;
  if (memories.length === 0) return "";
  const lines = memories.map((m) => `- ${m.text}`).join("\n");
  return `\n\nWhat you know about your owner (their persistent profile — use it to personalize, anticipate, and avoid asking things you already know):\n${lines}`;
}
