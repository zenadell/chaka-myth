import { fetch } from "expo/fetch";

const BASE = "https://generativelanguage.googleapis.com/v1beta/models";

/**
 * Calls Gemini's generateContent with an image + prompt and returns the raw
 * text (expected to be JSON when responseMimeType is application/json).
 * Used as the vision "eyes" for the screen operator; the main brain stays DeepSeek.
 */
export async function geminiVision(params: {
  apiKey: string;
  model: string;
  prompt: string;
  imageBase64: string;
  mimeType?: string;
  signal?: AbortSignal;
  /** true (default) forces JSON output (operator); false = natural language ("look at this"). */
  json?: boolean;
}): Promise<string> {
  const { apiKey, model, prompt, imageBase64, mimeType = "image/jpeg", signal, json = true } = params;

  const res = await fetch(`${BASE}/${model}:generateContent?key=${apiKey}`, {
    method: "POST",
    signal,
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      contents: [
        {
          parts: [
            { text: prompt },
            { inline_data: { mime_type: mimeType, data: imageBase64 } },
          ],
        },
      ],
      generationConfig: {
        temperature: json ? 0 : 0.4,
        ...(json ? { responseMimeType: "application/json" } : {}),
      },
    }),
  });

  if (!res.ok) {
    const body = await res.text();
    throw new Error(`Gemini API error (${res.status}): ${body.slice(0, 200)}`);
  }

  const data: any = await res.json();
  const text = data?.candidates?.[0]?.content?.parts
    ?.map((p: any) => p.text ?? "")
    .join("");
  if (!text) throw new Error("Gemini returned no content");
  return text;
}
