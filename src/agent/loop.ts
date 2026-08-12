import { streamChatCompletion } from "../lib/deepseek";
import { tools } from "./tools";
import { getMcpTools } from "./mcp";
import { useSettings } from "../state/settings";
import type { AgentEvents, ChatMessage, Tool } from "./types";

const MAX_TOOL_ROUNDS = 8;

/**
 * Runs one agent turn: streams the model's response, executes any tool
 * calls it makes (pausing for confirmation where required), feeds results
 * back, and repeats until the model produces a plain text answer.
 *
 * Returns the messages appended during this turn (assistant + tool
 * messages) so the caller can persist them into history.
 */
export async function runAgentTurn(params: {
  apiKey: string;
  model: string;
  history: ChatMessage[];
  events: AgentEvents;
  signal?: AbortSignal;
}): Promise<ChatMessage[]> {
  const { apiKey, model, history, events, signal } = params;
  const appended: ChatMessage[] = [];

  // Static registry + tools discovered from connected MCP servers.
  const allTools: Tool[] = [...tools, ...getMcpTools()];
  const toolByName = new Map(allTools.map((t) => [t.definition.function.name, t]));
  const toolDefinitions = allTools.map((t) => t.definition);

  for (let round = 0; round < MAX_TOOL_ROUNDS; round++) {
    events.onAssistantMessageStart();

    const result = await streamChatCompletion({
      apiKey,
      model,
      messages: [...history, ...appended],
      tools: toolDefinitions,
      signal,
      callbacks: { onToken: events.onToken },
    });

    const assistantMessage: ChatMessage = {
      role: "assistant",
      content: result.content,
      ...(result.toolCalls.length > 0 ? { tool_calls: result.toolCalls } : {}),
    };
    appended.push(assistantMessage);

    if (result.toolCalls.length === 0) {
      return appended; // plain text answer — turn complete
    }

    for (const call of result.toolCalls) {
      const tool = toolByName.get(call.function.name);
      let output: unknown;

      if (!tool) {
        output = `Unknown tool: ${call.function.name}`;
      } else {
        let args: Record<string, any> = {};
        try {
          args = call.function.arguments ? JSON.parse(call.function.arguments) : {};
        } catch {
          output = `Invalid JSON arguments for ${call.function.name}`;
        }

        if (output === undefined) {
          const summary = tool.describeCall(args);

          // Screen control can be set to "Always allow" so it stops asking each time.
          const autoApproved =
            call.function.name === "operate_screen" &&
            useSettings.getState().prefs.autoApproveScreen;

          if (tool.requiresConfirmation && !autoApproved) {
            const approved = await events.requestConfirmation(
              call.function.name,
              summary
            );
            if (!approved) {
              appended.push({
                role: "tool",
                tool_call_id: call.id,
                content: "The user declined this action. Do not retry it; ask what they would like instead.",
              });
              continue;
            }
          }

          events.onToolCallStart(call.function.name, summary);
          try {
            const result = await tool.execute(args);
            output = result.ok
              ? result.output
              : { error: result.output };
            events.onToolCallEnd(call.function.name, summary, result);
          } catch (err: any) {
            output = { error: err?.message ?? String(err) };
            events.onToolCallEnd(call.function.name, summary, {
              ok: false,
              output,
            });
          }
        }
      }

      appended.push({
        role: "tool",
        tool_call_id: call.id,
        content: typeof output === "string" ? output : JSON.stringify(output),
      });
    }
  }

  appended.push({
    role: "assistant",
    content:
      "I hit my tool-use limit for a single turn. Tell me to continue if you want me to keep going.",
  });
  return appended;
}
