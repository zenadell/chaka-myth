# Chaka-Myth — handoff

## The one job

**Make vision mandatory before AND after every action. Accuracy over speed.**

This is the owner's top priority and it has been asked for repeatedly. What
exists today is vision *on failure* — a screenshot is pushed after a MISMATCH,
after a thin element list, after a blocked action. All reactive. So on a screen
where the accessibility tree looks healthy she never looks at all: she taps from
the text list, assumes it worked, and moves on.

That single gap causes the failures being reported:

- Asked to check whether USB debugging was on, she scrolled past it repeatedly
  while it sat in the element list in front of her.
- She toggled Developer options OFF entirely — a blind tap from the tree.
- Asked for a Google AI Studio API key, she created it, then copied only the
  last three characters and never noticed.

## Do this, in order

1. **Look before acting.** `tap_index` / `tap_at` should refuse when she has not
   looked at the *current* screen. There is already a `lastRealLookAt` timestamp
   and a screen signature to compare against.
2. **Verify after, from the image** — not from the element list.
3. **Sanity-check anything copied.** `read_clipboard` exists; an API key is ~39
   characters, so three characters is obviously wrong and trivially caught.
4. **Only then** optimise: smaller frames, skip the look on a screen she has
   already seen unchanged.

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

```bash
export PATH="$PATH:/opt/homebrew/share/android-commandlinetools/platform-tools"
adb kill-server; adb start-server; sleep 3
PORT=$(adb mdns services | grep _adb-tls-connect | awk '{print $NF}' | head -1)
adb connect "$PORT"
```

The port changes on every reconnect; always rediscover via mDNS. If pairing is
lost, ask for a fresh code from Settings → Developer options → Wireless debugging
→ Pair device with pairing code.

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
