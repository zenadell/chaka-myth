import * as Speech from "expo-speech";

/** Strip markdown-ish noise so TTS doesn't read asterisks and links aloud. */
function cleanForSpeech(text: string): string {
  return text
    .replace(/```[\s\S]*?```/g, " code block omitted. ")
    .replace(/[*_#`>]/g, "")
    .replace(/\[([^\]]+)\]\([^)]*\)/g, "$1")
    .replace(/https?:\/\/\S+/g, "a link")
    .trim();
}

export function speak(text: string): void {
  const cleaned = cleanForSpeech(text);
  if (!cleaned) return;
  Speech.stop();
  Speech.speak(cleaned, { language: "en-US", rate: 1.0 });
}

export function stopSpeaking(): void {
  Speech.stop();
}
