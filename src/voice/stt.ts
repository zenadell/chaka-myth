/**
 * Speech-to-text via expo-speech-recognition (on-device native module).
 *
 * This module is NOT available in Expo Go — it needs a dev-client build
 * (`npx expo run:android` / `run:ios`). We require it lazily so the app
 * still runs in Expo Go with voice input disabled.
 */

let ExpoSpeechRecognitionModule: any = null;
try {
  // eslint-disable-next-line @typescript-eslint/no-var-requires
  ExpoSpeechRecognitionModule =
    require("expo-speech-recognition").ExpoSpeechRecognitionModule;
} catch {
  ExpoSpeechRecognitionModule = null;
}

export function sttAvailable(): boolean {
  return ExpoSpeechRecognitionModule != null;
}

export interface SttSession {
  stop: () => void;
}

/**
 * Starts listening and streams transcripts to onPartial; onFinal fires
 * once with the final transcript (or null if nothing was recognized).
 */
export async function startListening(handlers: {
  onPartial: (text: string) => void;
  onFinal: (text: string | null) => void;
  onError: (message: string) => void;
}): Promise<SttSession | null> {
  if (!ExpoSpeechRecognitionModule) {
    handlers.onError(
      "Voice input needs a development build of the app (not Expo Go). See README."
    );
    return null;
  }

  const perms = await ExpoSpeechRecognitionModule.requestPermissionsAsync();
  if (!perms.granted) {
    handlers.onError("Microphone permission denied.");
    return null;
  }

  let finalText: string | null = null;
  let finished = false;

  const subs = [
    ExpoSpeechRecognitionModule.addListener("result", (event: any) => {
      const transcript: string = event.results?.[0]?.transcript ?? "";
      if (event.isFinal) {
        finalText = transcript || finalText;
      } else if (transcript) {
        handlers.onPartial(transcript);
      }
    }),
    ExpoSpeechRecognitionModule.addListener("end", () => {
      if (finished) return;
      finished = true;
      subs.forEach((s) => s.remove());
      handlers.onFinal(finalText);
    }),
    ExpoSpeechRecognitionModule.addListener("error", (event: any) => {
      if (finished) return;
      finished = true;
      subs.forEach((s) => s.remove());
      // "no-speech" is a normal outcome, not an error worth surfacing
      if (event.error === "no-speech") handlers.onFinal(null);
      else handlers.onError(event.message ?? event.error ?? "Speech recognition error");
    }),
  ];

  ExpoSpeechRecognitionModule.start({
    lang: "en-US",
    interimResults: true,
    continuous: false,
  });

  return {
    stop: () => ExpoSpeechRecognitionModule.stop(),
  };
}
