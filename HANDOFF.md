# Chaka-Myth — handoff

## Where this stands (v6.1.0, ebc2b47)

Vision before and after every action is **built and installed, not yet watched
running**. Start Live Mode and check the log before trusting it — see
"Verify this first" below.

Two faults were found, and they explain the reported failures between them:

1. **`startFrameLoop` was never called.** It had been dead since 834628f while
   the system instruction told her "frames stream to you" and, after every
   action, "the next frames show the result". She had no vision at all and had
   been told twice that she did — which is why she described screens she had
   never seen.
2. **Screenshots were sent as `clientContent`.** On the 3.x live models that is
   only accepted for seeding history before the first model turn; mid-session
   it is not a way to say anything. The logs show each injection followed
   ~100ms later by `interrupted by user`, then the same failed tap a second
   later. 27 of them in four minutes.

Now: frames stream continuously over `realtimeInput.video`, `ensureSeen()`
guarantees a picture of the screen has left the device before any acting tool
fires, and `withOutcome` pushes the resulting screen before the tool result
reaches her. All text goes over `realtimeInput.text`.

## Verify this first

```bash
adb -s "$PORT" logcat -v time -s ChakaLive:V | grep -E "vision:|look-before-act|rejected|frame loop"
```

- `vision: N frames streamed` climbing = she can see. If it never appears, she
  is still blind and nothing else in this file matters.
- No `rejected (1007)`. If one appears right after a `realtimeInput.text`, the
  log now says so outright — that would mean the text shape is wrong and
  `sendText` has to go back to `clientContent`.
- `injecting screenshot` → `interrupted` → same tap repeated should be gone.

## Still open

- **The drive/nudge/correction pushes were all going down the dead
  clientContent path too.** They now work for the first time. Watch that she
  isn't over-driven as a result — the caps (`autoContinues > 30`, `drives >= 50`,
  `MIN_DRIVE_GAP_MS`) were tuned while those messages were being half-ignored.
- Only then optimise: smaller frames, longer heartbeat, skipping the pre-action
  frame on a screen she has already been shown unchanged.

## State

Live Mode is `modules/chaka-hands/android/src/main/java/com/chakamyth/hands/ChakaLive.kt`
— a native Gemini Live session (`gemini-3.1-flash-live-preview`), speech-to-speech,
that watches the screen and acts through tool calls. Native because RN's JS thread
freezes when the app is backgrounded.

Working and verified on device: goal lock (new instructions mid-task are queued and
confirmed, not applied), hard stop, action-effect verification, loop/oscillation
guards, `task_done` gated on proof, plan memory, persistent `remember`/`recall`
shared with the chat side, echo gating, drag/hold for pickers and icons, polling
`wait`.

Read `git log` — every commit explains the failure it fixes and why.

## A warning worth heeding

Three separate bugs in one session were *guards that caused the problem they were
meant to prevent*: a nudge loop killed the socket, VAD tuning made her mute, a
false-claim detector looped her speech. Two more were stop-word rules that froze
her on ordinary instructions ("don't create a new one, copy the one from before").

Test each guard against **normal speech and normal screens** before adding another.

## Device

Wireless debugging over ADB — no cable (the USB port is dead).

Address it by its **mDNS service name**, which is stable, rather than by IP:port,
which changes on every reconnect:

```bash
export PATH="$PATH:/opt/homebrew/share/android-commandlinetools/platform-tools"
adb devices -l
```

The A05 shows up as `adb-R94XC0DKS0F-wz6HqL._adb-tls-connect._tcp` and that whole
string works as the `-s` serial. Chasing the rotating port via `adb mdns services`
is unnecessary and unreliable — and note **`timeout` does not exist on macOS**, so
any discovery loop built around it fails silently and looks like "phone offline".

If pairing is lost, ask for a fresh code from Settings → Developer options →
Wireless debugging → Pair device with pairing code.

Build, install, watch:

```bash
cd android && ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
  ./gradlew :app:assembleRelease -PreactNativeArchitectures=arm64-v8a
adb -s "$PORT" install -r ../chaka-myth-<version>.apk
adb -s "$PORT" logcat -v time -s ChakaLive:V
```

Bump the version in `app.json` only — `build.gradle` derives from it.

If a build fails with a path that no longer exists, delete
`android/build/generated/autolinking/` and rebuild.

## Context

Testing happens on the owner's mother's Galaxy A05. His own phone — a Galaxy S10,
his late father's — has a broken screen and cannot be touched; making Chaka good
enough to run it is the point of the project. Watch the logs and diagnose from
them rather than guessing; he tests continuously and reports precisely.
