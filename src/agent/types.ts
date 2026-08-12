export type Role = "system" | "user" | "assistant" | "tool";

export interface ToolCall {
  id: string;
  type: "function";
  function: { name: string; arguments: string };
}

export interface ChatMessage {
  role: Role;
  content: string;
  tool_calls?: ToolCall[];
  tool_call_id?: string;
}

/** OpenAI-format tool definition sent to the model. */
export interface ToolDefinition {
  type: "function";
  function: {
    name: string;
    description: string;
    parameters: Record<string, unknown>;
  };
}

export interface ToolResult {
  ok: boolean;
  /** Serialized back to the model as the tool message content. */
  output: unknown;
}

export interface Tool {
  definition: ToolDefinition;
  /**
   * Destructive or outward-facing tools set this; the agent loop pauses
   * and asks the user to approve before executing.
   */
  requiresConfirmation?: boolean;
  /** Short human-readable summary of what a call will do, for confirm cards and chips. */
  describeCall: (args: Record<string, any>) => string;
  execute: (args: Record<string, any>) => Promise<ToolResult>;
}

/** Events the agent loop emits so the UI can render progress. */
export interface AgentEvents {
  onToken: (token: string) => void;
  onAssistantMessageStart: () => void;
  onToolCallStart: (name: string, summary: string) => void;
  onToolCallEnd: (name: string, summary: string, result: ToolResult) => void;
  /**
   * Called when a tool requires confirmation. Resolve true to run it,
   * false to skip it (the model is told the user declined).
   */
  requestConfirmation: (name: string, summary: string) => Promise<boolean>;
}
