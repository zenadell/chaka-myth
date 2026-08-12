import AsyncStorage from "@react-native-async-storage/async-storage";
import type { Tool } from "./types";

/**
 * MCP connector — lets Chaka call tools on any MCP server that speaks
 * the streamable HTTP transport (JSON-RPC 2.0 over POST, JSON or SSE replies).
 */

const SERVERS_KEY = "chaka.mcpServers";
const PROTOCOL_VERSION = "2025-06-18";

export interface McpServerConfig {
  name: string;
  url: string;
}

export async function loadMcpServers(): Promise<McpServerConfig[]> {
  const raw = await AsyncStorage.getItem(SERVERS_KEY);
  return raw ? JSON.parse(raw) : [];
}

export async function saveMcpServers(servers: McpServerConfig[]): Promise<void> {
  await AsyncStorage.setItem(SERVERS_KEY, JSON.stringify(servers));
}

/** Parses either a plain JSON body or an SSE stream body into JSON-RPC messages. */
function parseRpcBody(text: string, contentType: string): any[] {
  if (contentType.includes("text/event-stream")) {
    const messages: any[] = [];
    for (const chunk of text.split("\n\n")) {
      for (const line of chunk.split("\n")) {
        if (!line.startsWith("data:")) continue;
        try {
          messages.push(JSON.parse(line.slice(5).trim()));
        } catch {
          /* keep-alive or partial frame */
        }
      }
    }
    return messages;
  }
  try {
    const parsed = JSON.parse(text);
    return Array.isArray(parsed) ? parsed : [parsed];
  } catch {
    return [];
  }
}

class McpClient {
  private sessionId: string | null = null;
  private nextId = 1;

  constructor(readonly config: McpServerConfig) {}

  private async rpc(method: string, params?: unknown, notification = false): Promise<any> {
    const id = notification ? undefined : this.nextId++;
    const res = await fetch(this.config.url, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Accept: "application/json, text/event-stream",
        "MCP-Protocol-Version": PROTOCOL_VERSION,
        ...(this.sessionId ? { "Mcp-Session-Id": this.sessionId } : {}),
      },
      body: JSON.stringify({ jsonrpc: "2.0", method, ...(params !== undefined ? { params } : {}), ...(id !== undefined ? { id } : {}) }),
    });

    const newSession = res.headers.get("mcp-session-id");
    if (newSession) this.sessionId = newSession;

    if (notification) return undefined;
    if (!res.ok) throw new Error(`MCP ${this.config.name}: HTTP ${res.status}`);

    const messages = parseRpcBody(await res.text(), res.headers.get("content-type") ?? "");
    const reply = messages.find((m) => m.id === id) ?? messages[messages.length - 1];
    if (!reply) throw new Error(`MCP ${this.config.name}: empty response`);
    if (reply.error) throw new Error(`MCP ${this.config.name}: ${reply.error.message}`);
    return reply.result;
  }

  async initialize(): Promise<void> {
    await this.rpc("initialize", {
      protocolVersion: PROTOCOL_VERSION,
      capabilities: {},
      clientInfo: { name: "chaka-myth", version: "0.2.0" },
    });
    await this.rpc("notifications/initialized", undefined, true);
  }

  async listTools(): Promise<{ name: string; description?: string; inputSchema?: unknown }[]> {
    const result = await this.rpc("tools/list", {});
    return result?.tools ?? [];
  }

  async callTool(name: string, args: Record<string, unknown>): Promise<string> {
    const result = await this.rpc("tools/call", { name, arguments: args });
    const parts = (result?.content ?? [])
      .map((c: any) => (c.type === "text" ? c.text : `[${c.type}]`))
      .join("\n");
    if (result?.isError) throw new Error(parts || "MCP tool reported an error");
    return parts || JSON.stringify(result);
  }
}

function sanitizeFnName(s: string): string {
  return s.replace(/[^a-zA-Z0-9_-]/g, "_").slice(0, 24);
}

let mcpTools: Tool[] = [];
let lastErrors: Record<string, string> = {};

/** Tools discovered from connected MCP servers (already merged format). */
export function getMcpTools(): Tool[] {
  return mcpTools;
}

export function getMcpErrors(): Record<string, string> {
  return lastErrors;
}

/**
 * (Re)connects to all saved servers and rebuilds the dynamic tool list.
 * Returns tool count per server; failures land in getMcpErrors().
 */
export async function refreshMcpTools(): Promise<Record<string, number>> {
  const servers = await loadMcpServers();
  const collected: Tool[] = [];
  const counts: Record<string, number> = {};
  lastErrors = {};

  await Promise.all(
    servers.map(async (server) => {
      try {
        const client = new McpClient(server);
        await client.initialize();
        const remoteTools = await client.listTools();
        counts[server.name] = remoteTools.length;

        for (const rt of remoteTools) {
          const fnName = `mcp_${sanitizeFnName(server.name)}_${sanitizeFnName(rt.name)}`;
          collected.push({
            definition: {
              type: "function",
              function: {
                name: fnName,
                description: `[via MCP server "${server.name}"] ${rt.description ?? rt.name}`,
                parameters: (rt.inputSchema as Record<string, unknown>) ?? { type: "object", properties: {} },
              },
            },
            describeCall: (args) =>
              `${server.name}: ${rt.name}(${JSON.stringify(args).slice(0, 60)})`,
            execute: async (args) => {
              try {
                const output = await client.callTool(rt.name, args);
                return { ok: true, output };
              } catch (err: any) {
                return { ok: false, output: err?.message ?? String(err) };
              }
            },
          });
        }
      } catch (err: any) {
        counts[server.name] = 0;
        lastErrors[server.name] = err?.message ?? String(err);
      }
    })
  );

  mcpTools = collected;
  return counts;
}
