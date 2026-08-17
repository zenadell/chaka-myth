# Chaka-Myth — handoff

## Read this first

The one thing that worked all session was **instrumentation**. Every confident
explanation I reasoned my way to was wrong — five in a row on a single row of a
single screen — and every real step forward came from adding a log line and
looking. If you find yourself about to build a fix from a theory, add the
diagnostic instead. It is faster, and it does not cost the owner a test run.

Current build: **7.7.0**, installed on the A05.

## The open bug

`scroll_to "Wireless debugging"` cannot find that row on Developer options,
while `uiautomator` reports `text="Wireless debugging"` at `[52,389][357,433]`
on the same screen. Its neighbours are found fine — the diagnostic prints:

```
PARTIAL matches for wireless+debugging:
  [27] Debugging | [6] Revoke USB debugging authorisation
  [14] Wireless display certification | [15] Show options for wireless display
```

So the list is being traversed correctly and this one row is invisible to us.
Both our own tree walk (`collect`) and Android's `findAccessibilityNodeInfosByText`
miss it.

**Ruled out by testing, do not re-try these:**

- filler words in the matcher (fixed, was real, not this)
- single-word vs all-word matching (fixed, was real, not this)
- gesture flinging past the row (fixed, was real, not this)
- reading `text` vs `content-desc` (fixed, was real, not this)
- `isVisibleToUser` on `findByText` (relaxed in 7.7.0 — **did not change anything**)

**Next hypothesis, untested:** the node lives in a window that
`rootInActiveWindow` does not cover. Samsung splits parts of Settings across
windows; `uiautomator` reads them all and we read one. Nothing in the codebase
calls `getWindows()`. Dump `getWindows()` on that screen and compare. If that is
it, `dumpScreen` has been silently missing content on other screens too.

**Fallback worth building either way:** the owner found the row by hand using the
Settings search bar. Type the name, tap the result. Make it a deliberate route,
not a fallback.

## Verified working (do not regress these)

Each was measured, on device or in the browser probe:

- **Transport.** `clientContent` is not accepted mid-session on 3.x live models.
  All text and vision go over `realtimeInput`. Screenshots used to arrive as an
  interruption she never saw — 27 of them in one four-minute recording.
- **Vision.** `startFrameLoop` had been dead since 834628f while the prompt told
  her frames were streaming. It runs; a frame goes out before every action and
  after every one; ~111 frames in a normal session.
- **Frame rate.** Change-driven only. 1 FPS streaming does not make her
  attentive, it makes her **mute** — she stopped answering "say the word HELLO"
  with the socket open. `834628f` said this three months ago and was right.
- **Her memory is words, not pictures.** A frame she does not put into words is
  gone within tens of seconds. Probe: 45s gap and no narration → fails; same gap
  having said it aloud → passes.
- **Set-of-Marks.** Existed since v2.1.5, used by ChakaOperator, and Live Mode
  passed `null`. Now drawn — `marks=25..31` in the log. Coordinates measured
  15–55px error with one dead tap; marks were exact.
- **`mediaResolution`** belongs in `generationConfig`. At the top of `setup` the
  server closes 1007 and every session dies on connect.
- **`contentSig`** sorts labels before hashing. `sig()` hashes in tree order and
  the order is unstable, so a stationary screen looked like it was changing —
  which silently defeated every end-of-list and no-progress check built on it.

## The recurring failure mode: guards that disarm themselves

Six times this session a guard produced the behaviour it existed to prevent:

- the hunt counter sat below the guards, so a refused swipe never counted
- `read_screen` reset the hunt, so "stop and look" disarmed the thing telling her to
- `scroll_to` reset its own counter and could never reach its limit
- "don't guess and don't say you don't know" left her silent for four minutes
- `alreadyOnScreen` matched a section heading and blocked scrolling she needed
- blocking at four swipes wedged her against a list that needs ten

Before adding a guard, ask what it does when she obeys it, and what it does on a
list that is simply long. Test against normal screens, not the failure you have
in mind.

## Also outstanding

- **`task_done` is satisfiable by asserting twice.** She twice reported
  "wireless debugging is on" without having read it this session. A claim about a
  setting's state should have to cite a `switch_is` reading from `findByText`.
- **Browser tasks.** In Chrome the tree is nearly empty, so few marks are drawn
  and she falls back to `tap_at` coordinates — one API-key session logged ~30
  `tap_at` calls and 19 MISMATCHes. Marks fix native apps and do little here.
- **Frame size.** Marks roughly double it (82KB vs 46KB); ten `takeScreenshot`
  rate-limit failures during one swipe storm.
- **`git push`** has been blocked by the permission classifier all session. The
  remote is set; the owner runs `git push -u origin main`.

## Device

Wireless debugging, no cable (the USB port is dead). Address it by its **stable
mDNS name**, not the rotating IP:port:

```bash
export PATH="$PATH:/opt/homebrew/share/android-commandlinetools/platform-tools"
adb devices -l
```

It appears as `adb-R94XC0DKS0F-wz6HqL._adb-tls-connect._tcp`, and that whole
string works as the `-s` serial. **macOS has no `timeout`** — a discovery loop
built around it fails silently and looks like "phone offline".

Build, install, watch:

```bash
cd android && ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
  ./gradlew :app:assembleRelease -PreactNativeArchitectures=arm64-v8a
```

Check `versionName` from `dumpsys` after installing, not the filename — a failed
build once shipped an APK whose name and contents disagreed. Bump the version in
`app.json` only. Clear the logcat buffer when capturing, or you will read an old
session as if it were the current one (this cost an hour).

Log tags: `ChakaLive:V`, and `ChakaHands:V` for `marks=N`.

## Context

Testing happens on the owner's mother's Galaxy A05. His own phone — a Galaxy
S10, his late father's — has a broken screen and cannot be touched; making Chaka
good enough to run it is the point of the project. He tests continuously and
reports precisely. Diagnose from the logs, and when you do not have the log,
say so rather than reasoning from the last one you saw.
