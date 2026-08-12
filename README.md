# Chaka-Myth 🜲

Your personal AI. A Jarvis-style assistant that lives on your phone, powered by DeepSeek V4 with function calling. Chat with it, or tell it to do things — it decides which tools to use and chains them.

## Status: Phase 1 ✅

- **Chatbot** — streaming chat UI with DeepSeek V4 Flash/Pro (switchable in settings)
- **Agent loop** — multi-round function calling; Chaka picks tools and chains them
- **Starter tools** — open apps & deep links (YouTube search, Spotify, WhatsApp drafts, email/SMS/call composers, maps), device status (battery etc.), local notes memory
- **Safety rail** — destructive actions show an Approve/Decline card before running
- **Voice** — spoken replies (TTS), mic dictation (STT), and "Hey Chaka" wake-word scaffolding
- **Privacy** — API key in the device secure enclave; notes memory never leaves the phone

## Run it

```bash
npm install
npx expo start
```

Scan the QR with the **Expo Go** app for the quickest test (chat, tools, and spoken replies all work; mic/wake word do not — see below).

Then tap ⚙︎ in the app and paste your DeepSeek API key from platform.deepseek.com.

### Full build (unlocks mic + wake word)

Voice *input* uses native modules, so you need a development build instead of Expo Go:

```bash
npx expo run:android   # Android phone connected with USB debugging
npx expo run:ios       # iPhone (needs Xcode; free Apple ID works for 7-day installs)
```

### Wake word ("Hey Chaka")

One-time setup, free tier:

1. Create an account at https://console.picovoice.ai and copy your AccessKey
2. Train wake words ("Hey Chaka", "Chaka", "Myth") in the console; download the `.ppn` files for Android and iOS
3. Follow the notes in [src/voice/wakeword.ts](src/voice/wakeword.ts) — paste the key, add the keyword paths
4. Rebuild the dev client

Wake-word detection runs 100% on-device. When active, a 👂 badge shows in the header.

## Architecture

```
src/
  lib/deepseek.ts       streaming client (SSE, tool-call deltas)
  agent/
    loop.ts             the agent: model ↔ tools until a final answer
    prompt.ts           Chaka's persona + capabilities
    tools/index.ts      TOOL REGISTRY — new capabilities plug in here
    types.ts
  state/                zustand stores (chat, settings)
  voice/                tts.ts / stt.ts / wakeword.ts
  ui/                   chat screen, settings, components
```

To add a capability: write a `Tool` object (definition + execute + `requiresConfirmation` if destructive) and add it to the array in `src/agent/tools/index.ts`. That's it — the agent loop and UI pick it up automatically.

## Roadmap

- **Phase 2 — device basics:** torch, camera capture, brightness, clipboard, share sheet
- **Phase 3 — find anything:** on-device embedding index over photos & files → "find that image I took at the beach"
- **Phase 4 — accounts:** Gmail (send/delete/search email), Spotify playback API, YouTube, calendar
- **Phase 5 — Android accessibility agent:** the "do literally anything" layer — Chaka sees the screen and taps/types on your behalf
- **Phase 6 — real automations:** MCP connectors, scheduled routines, multi-agent research team

## Privacy notes

- Your API key is stored with `expo-secure-store` (Keychain / Android Keystore), never in code
- Notes memory is local AsyncStorage — never uploaded
- What you type/say **is sent to DeepSeek's API** for processing; keep that in mind for sensitive content. A local-model fallback is a candidate for a later phase.
