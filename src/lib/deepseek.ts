import { fetch } from "expo/fetch";
import type {
  ChatMessage,
  ToolCall,
  ToolDefinition,
} from "../agent/types";

const BASE_URL = "https://api.deepseek.com";

export interface StreamCallbacks {
  /** Fired for each content token as it streams in. */
  onToken?: (token: string) => void;
  /** Fired for each reasoning token (thinking models). */
  onReasoningToken?: (token: string) => void;
}

export interface CompletionResult {
  content: string;
  reasoning: string;
  toolCalls: ToolCall[];
  finishReason: string | null;
}

interface ToolCallAccumulator {
  id: string;
  name: string;
  arguments: string;
}

/**
 * Streams a chat completion from the DeepSeek API (OpenAI-compatible).
 * Accumulates tool-call deltas and returns the fully assembled result.
 */
export async function streamChatCompletion(params: {
  apiKey: string;
  model: string;
  messages: ChatMessage[];
  tools?: ToolDefinition[];
  signal?: AbortSignal;
  callbacks?: StreamCallbacks;
}): Promise<CompletionResult> {
  const { apiKey, model, messages, tools, signal, callbacks } = params;

  const response = await fetch(`${BASE_URL}/chat/completions`, {
    method: "POST",
    signal,
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${apiKey}`,
    },
    body: JSON.stringify({
      model,
      messages,
      stream: true,
      ...(tools && tools.length > 0 ? { tools } : {}),
    }),
  });

  if (!response.ok) {
    const body = await response.text();
    throw new DeepSeekError(response.status, body);
  }

  const reader = response.body!.getReader();
  const decoder = new TextDecoder();

  let content = "";
  let reasoning = "";
  let finishReason: string | null = null;
  const toolCallsByIndex = new Map<number, ToolCallAccumulator>();
  let buffer = "";

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });

    // SSE events are separated by double newlines
    const events = buffer.split("\n\n");
    buffer = events.pop() ?? "";

    for (const event of events) {
      for (const line of event.split("\n")) {
        if (!line.startsWith("data:")) continue;
        const data = line.slice(5).trim();
        if (data === "[DONE]") continue;

        let parsed: any;
        try {
          parsed = JSON.parse(data);
        } catch {
          continue;
        }

        const choice = parsed.choices?.[0];
        if (!choice) continue;
        if (choice.finish_reason) finishReason = choice.finish_reason;

        const delta = choice.delta;
        if (!delta) continue;

        if (delta.content) {
          content += delta.content;
          callbacks?.onToken?.(delta.content);
        }
        if (delta.reasoning_content) {
          reasoning += delta.reasoning_content;
          callbacks?.onReasoningToken?.(delta.reasoning_content);
        }
        if (delta.tool_calls) {
          for (const tc of delta.tool_calls) {
            const index = tc.index ?? 0;
            let acc = toolCallsByIndex.get(index);
            if (!acc) {
              acc = { id: "", name: "", arguments: "" };
              toolCallsByIndex.set(index, acc);
            }
            if (tc.id) acc.id = tc.id;
            if (tc.function?.name) acc.name += tc.function.name;
            if (tc.function?.arguments) acc.arguments += tc.function.arguments;
          }
        }
      }
    }
  }

  const toolCalls: ToolCall[] = [...toolCallsByIndex.entries()]
    .sort(([a], [b]) => a - b)
    .map(([, acc]) => ({
      id: acc.id,
      type: "function" as const,
      function: { name: acc.name, arguments: acc.arguments },
    }));

  return { content, reasoning, toolCalls, finishReason };
}

export class DeepSeekError extends Error {
  status: number;

  constructor(status: number, body: string) {
    let message = `DeepSeek API error (${status})`;
    try {
      const parsed = JSON.parse(body);
      if (parsed.error?.message) message = parsed.error.message;
    } catch {
      if (body) message = `${message}: ${body.slice(0, 200)}`;
    }
    super(message);
    this.status = status;
  }
}
