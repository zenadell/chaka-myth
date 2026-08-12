import { create } from "zustand";
import { runAgentTurn } from "../agent/loop";
import { buildSystemPrompt } from "../agent/prompt";
import type { ChatMessage, ToolResult } from "../agent/types";
import { useSettings } from "./settings";
import { speak, stopSpeaking } from "../voice/tts";
import { runControl, isAbortError } from "../agent/abort";
import { useAudit } from "./auditLog";

export type DisplayItem =
  | { id: string; kind: "user"; text: string }
  | { id: string; kind: "assistant"; text: string; streaming: boolean }
  | {
      id: string;
      kind: "tool";
      name: string;
      summary: string;
      status: "running" | "ok" | "error" | "declined";
    }
  | { id: string; kind: "error"; text: string };

export interface PendingConfirmation {
  name: string;
  summary: string;
  resolve: (approved: boolean) => void;
}

interface ChatState {
  items: DisplayItem[];
  history: ChatMessage[];
  busy: boolean;
  abort: AbortController | null;
  pendingConfirmation: PendingConfirmation | null;
  sendMessage: (text: string) => Promise<void>;
  resolveConfirmation: (approved: boolean) => void;
  stop: () => void;
  clearChat: () => void;
}

let idCounter = 0;
const nextId = () => `i${++idCounter}`;

export const useChat = create<ChatState>((set, get) => ({
  items: [],
  history: [],
  busy: false,
  abort: null,
  pendingConfirmation: null,

  sendMessage: async (text: string) => {
    const trimmed = text.trim();
    if (!trimmed || get().busy) return;

    const controller = new AbortController();
    runControl.signal = controller.signal;

    const { apiKey, prefs } = useSettings.getState();
    if (!apiKey) {
      set((s) => ({
        items: [
          ...s.items,
          {
            id: nextId(),
            kind: "error",
            text: "No DeepSeek API key set. Open settings (gear icon) and paste your key.",
          },
        ],
      }));
      return;
    }

    stopSpeaking();

    const history: ChatMessage[] =
      get().history.length > 0
        ? [...get().history]
        : [{ role: "system", content: buildSystemPrompt() }];
    history.push({ role: "user", content: trimmed });

    set((s) => ({
      busy: true,
      abort: controller,
      history,
      items: [...s.items, { id: nextId(), kind: "user", text: trimmed }],
    }));

    let currentAssistantId: string | null = null;
    let finalReply = "";

    const patchItem = (id: string, patch: Partial<DisplayItem>) =>
      set((s) => ({
        items: s.items.map((it) => (it.id === id ? ({ ...it, ...patch } as DisplayItem) : it)),
      }));

    try {
      const appended = await runAgentTurn({
        apiKey,
        model: prefs.model,
        history,
        signal: controller.signal,
        events: {
          onAssistantMessageStart: () => {
            currentAssistantId = null;
          },
          onToken: (token) => {
            if (!currentAssistantId) {
              currentAssistantId = nextId();
              set((s) => ({
                items: [
                  ...s.items,
                  { id: currentAssistantId!, kind: "assistant", text: token, streaming: true },
                ],
              }));
            } else {
              const id = currentAssistantId;
              set((s) => ({
                items: s.items.map((it) =>
                  it.id === id && it.kind === "assistant"
                    ? { ...it, text: it.text + token }
                    : it
                ),
              }));
            }
          },
          onToolCallStart: (name, summary) => {
            if (currentAssistantId) {
              patchItem(currentAssistantId, { streaming: false });
              currentAssistantId = null;
            }
            const id = nextId();
            set((s) => ({
              items: [...s.items, { id, kind: "tool", name, summary, status: "running" }],
            }));
          },
          onToolCallEnd: (name, summary, result: ToolResult) => {
            useAudit.getState().log({
              tool: name,
              summary,
              status: result.ok ? "ok" : "error",
              detail:
                typeof result.output === "string"
                  ? result.output.slice(0, 160)
                  : JSON.stringify(result.output).slice(0, 160),
            });
            set((s) => {
              const items = [...s.items];
              for (let i = items.length - 1; i >= 0; i--) {
                const it = items[i];
                if (it.kind === "tool" && it.name === name && it.status === "running") {
                  items[i] = { ...it, status: result.ok ? "ok" : "error" };
                  break;
                }
              }
              return { items };
            });
          },
          requestConfirmation: (name, summary) =>
            new Promise<boolean>((resolve) => {
              set({
                pendingConfirmation: {
                  name,
                  summary,
                  resolve: (approved) => {
                    if (!approved) {
                      useAudit.getState().log({ tool: name, summary, status: "declined" });
                    }
                    set((s) => ({
                      pendingConfirmation: null,
                      items: approved
                        ? s.items
                        : [
                            ...s.items,
                            { id: nextId(), kind: "tool", name, summary, status: "declined" },
                          ],
                    }));
                    resolve(approved);
                  },
                },
              });
            }),
        },
      });

      set((s) => ({ history: [...s.history, ...appended] }));
      const lastAssistant = [...appended].reverse().find((m) => m.role === "assistant");
      finalReply = lastAssistant?.content ?? "";
    } catch (err: any) {
      if (!isAbortError(err)) {
        set((s) => ({
          items: [
            ...s.items,
            { id: nextId(), kind: "error", text: err?.message ?? String(err) },
          ],
        }));
      }
    } finally {
      if (currentAssistantId) patchItem(currentAssistantId, { streaming: false });
      if (runControl.signal === controller.signal) runControl.signal = null;
      set({ busy: false, abort: null });
    }

    if (finalReply && useSettings.getState().prefs.speakReplies) {
      speak(finalReply);
    }
  },

  resolveConfirmation: (approved: boolean) => {
    get().pendingConfirmation?.resolve(approved);
  },

  stop: () => {
    const { abort, pendingConfirmation } = get();
    abort?.abort();
    runControl.signal = null;
    pendingConfirmation?.resolve(false);
    stopSpeaking();
    set((s) => ({
      busy: false,
      abort: null,
      pendingConfirmation: null,
      items: [...s.items, { id: nextId(), kind: "tool", name: "stop", summary: "Stopped", status: "declined" }],
    }));
  },

  clearChat: () => {
    get().abort?.abort();
    runControl.signal = null;
    stopSpeaking();
    set({ items: [], history: [], pendingConfirmation: null, busy: false, abort: null });
  },
}));
