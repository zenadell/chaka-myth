import AsyncStorage from "@react-native-async-storage/async-storage";
import { create } from "zustand";

/**
 * Chaka's activity log — a transparent record of every tool she used, what it
 * did, and whether it worked. Stored on-device, viewable by the owner.
 */
export interface AuditEntry {
  id: string;
  ts: string; // ISO timestamp
  tool: string;
  summary: string; // human-readable "what she tried"
  status: "ok" | "error" | "declined";
  detail?: string; // short result / reason
}

const KEY = "chaka.audit";
const MAX = 300;

interface AuditState {
  entries: AuditEntry[];
  load: () => Promise<void>;
  log: (e: Omit<AuditEntry, "id" | "ts">) => void;
  clear: () => Promise<void>;
}

export const useAudit = create<AuditState>((set, get) => ({
  entries: [],

  load: async () => {
    const raw = await AsyncStorage.getItem(KEY);
    if (raw) set({ entries: JSON.parse(raw) });
  },

  log: (e) => {
    const entry: AuditEntry = {
      ...e,
      id: Date.now().toString(36) + Math.random().toString(36).slice(2, 6),
      ts: new Date().toISOString(),
    };
    const entries = [entry, ...get().entries].slice(0, MAX);
    set({ entries });
    AsyncStorage.setItem(KEY, JSON.stringify(entries)).catch(() => {});
  },

  clear: async () => {
    set({ entries: [] });
    await AsyncStorage.removeItem(KEY);
  },
}));
