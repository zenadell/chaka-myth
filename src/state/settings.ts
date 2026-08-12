import * as SecureStore from "expo-secure-store";
import AsyncStorage from "@react-native-async-storage/async-storage";
import { create } from "zustand";

const API_KEY_STORE = "chaka.deepseek.apiKey";
const GEMINI_KEY_STORE = "chaka.gemini.apiKey";
const PREFS_KEY = "chaka.prefs";

export const VISION_MODEL = "gemini-3.1-flash-lite";

export interface Prefs {
  model: string;
  speakReplies: boolean;
  proactive: boolean;
  autoApproveScreen: boolean;
}

const DEFAULT_PREFS: Prefs = {
  model: "deepseek-v4-flash",
  speakReplies: false,
  proactive: false,
  autoApproveScreen: false,
};

interface SettingsState {
  loaded: boolean;
  apiKey: string | null;
  geminiKey: string | null;
  prefs: Prefs;
  load: () => Promise<void>;
  setApiKey: (key: string) => Promise<void>;
  setGeminiKey: (key: string) => Promise<void>;
  setPrefs: (patch: Partial<Prefs>) => Promise<void>;
}

export const useSettings = create<SettingsState>((set, get) => ({
  loaded: false,
  apiKey: null,
  geminiKey: null,
  prefs: DEFAULT_PREFS,

  load: async () => {
    const [apiKey, geminiKey, rawPrefs] = await Promise.all([
      SecureStore.getItemAsync(API_KEY_STORE),
      SecureStore.getItemAsync(GEMINI_KEY_STORE),
      AsyncStorage.getItem(PREFS_KEY),
    ]);
    set({
      loaded: true,
      apiKey: apiKey || null,
      geminiKey: geminiKey || null,
      prefs: rawPrefs ? { ...DEFAULT_PREFS, ...JSON.parse(rawPrefs) } : DEFAULT_PREFS,
    });
  },

  setApiKey: async (key: string) => {
    const trimmed = key.trim();
    await SecureStore.setItemAsync(API_KEY_STORE, trimmed);
    set({ apiKey: trimmed || null });
  },

  setGeminiKey: async (key: string) => {
    const trimmed = key.trim();
    await SecureStore.setItemAsync(GEMINI_KEY_STORE, trimmed);
    set({ geminiKey: trimmed || null });
  },

  setPrefs: async (patch: Partial<Prefs>) => {
    const prefs = { ...get().prefs, ...patch };
    set({ prefs });
    await AsyncStorage.setItem(PREFS_KEY, JSON.stringify(prefs));
  },
}));
