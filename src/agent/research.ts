import { streamChatCompletion } from "../lib/deepseek";
import { useSettings } from "../state/settings";
import { webSearch, readWebpage } from "./tools/webTools";
import type { ChatMessage, Tool } from "./types";

/**
 * Deep research pipeline — Chaka's research team.
 *
 * planner (1 call) → N researchers in parallel (search + read + summarize)
 * → synthesizer (1 call) producing a cited report.
 */

const DEPTHS: Record<string, number> = { quick: 2, standard: 4, deep: 6 };

async function llm(messages: ChatMessage[]): Promise<string> {
  const { apiKey, prefs } = useSettings.getState();
  if (!apiKey) throw new Error("No API key configured");
  const result = await streamChatCompletion({
    apiKey,
    model: prefs.model,
    messages,
  });
  return result.content;
}

function parseJsonArray(raw: string): string[] {
  const match = raw.match(/\[[\s\S]*\]/);
  if (!match) return [];
  try {
    const arr = JSON.parse(match[0]);
    return Array.isArray(arr) ? arr.map(String) : [];
  } catch {
    return [];
  }
}

interface Finding {
  query: string;
  notes: string;
  sources: string[];
}

async function researchOne(question: string, query: string): Promise<Finding> {
  const search = await webSearch.execute({ query });
  if (!search.ok) return { query, notes: `(search failed: ${search.output})`, sources: [] };

  const results = search.output as { title: string; url: string; snippet: string }[];
  const top = results.slice(0, 2);

  const pages: string[] = [];
  for (const r of top) {
    try {
      const page = await readWebpage.execute({ url: r.url });
      if (page.ok) pages.push(`SOURCE ${r.url}\n${String(page.output).slice(0, 2500)}`);
    } catch {
      /* dead page — snippets still carry signal */
    }
  }

  const material =
    pages.length > 0
      ? pages.join("\n\n---\n\n")
      : top.map((r) => `SOURCE ${r.url}\n${r.title}: ${r.snippet}`).join("\n\n");

  const notes = await llm([
    {
      role: "system",
      content:
        "You are a research analyst. Extract every fact from the material that helps answer the research question. " +
        "Dense bullet points. After each fact, cite its source URL in parentheses. " +
        "If the material is irrelevant, say NOTHING RELEVANT.",
    },
    {
      role: "user",
      content: `Research question: ${question}\nSub-topic being researched: ${query}\n\nMaterial:\n${material}`,
    },
  ]);

  return { query, notes, sources: top.map((r) => r.url) };
}

export async function runDeepResearch(question: string, depth: string): Promise<{
  report: string;
  sources: string[];
}> {
  const n = DEPTHS[depth] ?? DEPTHS.standard;

  const planRaw = await llm([
    {
      role: "system",
      content:
        `You are a research planner. Break the user's research question into exactly ${n} focused web search queries ` +
        "that together cover it from multiple angles (facts, comparisons, recent news, expert views as appropriate). " +
        'Reply with ONLY a JSON array of strings, e.g. ["query one", "query two"].',
    },
    { role: "user", content: question },
  ]);
  let queries = parseJsonArray(planRaw).slice(0, n);
  if (queries.length === 0) queries = [question];

  const findings = await Promise.all(queries.map((q) => researchOne(question, q)));

  const briefing = findings
    .map((f) => `## Angle: ${f.query}\n${f.notes}`)
    .join("\n\n");

  const report = await llm([
    {
      role: "system",
      content:
        "You are the lead researcher synthesizing your team's findings into a final report. " +
        "Answer the research question directly and thoroughly using ONLY the findings. " +
        "Structure: short direct answer first, then supporting detail. Keep source URLs cited inline in parentheses. " +
        "Flag contradictions between sources. Note what could not be verified. No preamble.",
    },
    { role: "user", content: `Research question: ${question}\n\nTeam findings:\n${briefing}` },
  ]);

  const sources = [...new Set(findings.flatMap((f) => f.sources))];
  return { report, sources };
}

export const deepResearch: Tool = {
  definition: {
    type: "function",
    function: {
      name: "deep_research",
      description:
        "Run a multi-agent research investigation: plans sub-questions, searches the web in parallel, " +
        "reads sources, and synthesizes a cited report. Use for open-ended or multi-angle questions " +
        "('research X', 'compare A vs B', 'find out everything about Y', 'what's the best Z'). " +
        "Takes 1-3 minutes. For a single quick fact, use web_search instead.",
      parameters: {
        type: "object",
        properties: {
          question: { type: "string", description: "The research question, fully specified" },
          depth: {
            type: "string",
            enum: ["quick", "standard", "deep"],
            description: "quick=2 angles, standard=4, deep=6. Default standard.",
          },
        },
        required: ["question"],
      },
    },
  },
  describeCall: (args) => `Deep research (${args.depth ?? "standard"}): ${String(args.question).slice(0, 60)}`,
  execute: async (args) => {
    const { report, sources } = await runDeepResearch(
      String(args.question),
      String(args.depth ?? "standard")
    );
    return { ok: true, output: { report, sources } };
  },
};
