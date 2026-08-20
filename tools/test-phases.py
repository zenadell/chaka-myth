"""Replay the exact failure the owner watched, against the phase machine.

Tools and transitions are PARSED OUT OF ChakaLive.kt, not retyped, so this
cannot drift from the code it checks.
"""
import re, sys
SRC = "modules/chaka-hands/android/src/main/java/com/chakamyth/hands/ChakaLive.kt"
src = open(SRC).read()

always = set(re.findall(r'"([a-z_]+)"', re.search(r'alwaysFine = setOf\((.*?)\)\n', src, re.S).group(1)))
block = re.search(r'phaseTools: Map<Phase, Set<String>> = mapOf\((.*?)\n  \)\n', src, re.S).group(1)
tools = {}
for ph, body in re.findall(r'Phase\.(\w+) to (?:setOf\((.*?)\)|emptySet\(\))', block, re.S):
    tools[ph] = set(re.findall(r'"([a-z_]+)"', body or ""))
for ph in ["IDLE","CLARIFYING","LOCATING","READY","DONE"]: tools.setdefault(ph, set())

def allowed(phase, tool): return tool in always or tool in tools[phase]

print(f"parsed from source: {len(always)} always-available, phases {list(tools)}\n")
fails = 0
def check(desc, got, want):
    global fails
    ok = got == want; fails += not ok
    print(f"  {'PASS' if ok else 'FAIL':4}  {desc}")
    if not ok: print(f"        wanted {want}, got {got}")

print("THE RUN HE WATCHED, STEP BY STEP")
print("\n1. She read out the options and asked which one -> CLARIFYING")
check("she CANNOT toggle before he answers  (tap_found)", allowed("CLARIFYING","tap_found"), False)
check("she CANNOT toggle before he answers  (tap_at)",    allowed("CLARIFYING","tap_at"), False)
check("she CANNOT wander off               (open_app)",   allowed("CLARIFYING","open_app"), False)
check("she CANNOT scroll away              (swipe)",      allowed("CLARIFYING","swipe"), False)
check("she CAN still look at the screen",                 allowed("CLARIFYING","look_at_screen"), True)

print("\n2. He answers -> LOCATING")
check("she may search",            allowed("LOCATING","scroll_to"), True)
check("she may scroll",            allowed("LOCATING","swipe"), True)
check("she may NOT tap blindly",   allowed("LOCATING","tap_found"), False)

print("\n3. Found it -> READY")
check("she may act on it",                          allowed("READY","tap_found"), True)
check("she may NOT go hunting again (scroll_to)",   allowed("READY","scroll_to"), False)
check("she may NOT walk back to Settings",          allowed("READY","open_app"), False)
check("she may NOT scroll away",                    allowed("READY","swipe"), False)

print("\n4. Toggled and read back -> DONE")
check("she may NOT tap anything again",     allowed("DONE","tap_found"), False)
check("she may NOT re-open Settings",       allowed("DONE","open_app"), False)
check("she may NOT scroll off elsewhere",   allowed("DONE","swipe"), False)
check("she may NOT toggle verbose logging", allowed("DONE","tap_index"), False)
check("she may report and finish",          allowed("DONE","task_done"), True)


print("\nTHE DEADLOCK — 'she refused my command'")
# Replays clarifyIfDangerous's actual rule, read out of the source.
ROUTES      = re.findall(r'"([^"]+)"', re.search(r'val ROUTES = listOf\((.*?)\)\n', src, re.S).group(1))
DESTRUCTIVE = re.findall(r'"([^"]+)"', re.search(r'val DESTRUCTIVE = listOf\((.*?)\)\n', src, re.S).group(1))
def traps_on_find(label, has_switch):
    """Finding is never changing — routes are never trapped, whatever their switch."""
    l = label.lower()
    if any(r in l for r in ROUTES): return False
    return any(d in l for d in DESTRUCTIVE)

def ocr_accepts(target, line):
    """The pixel matcher's rule, replayed."""
    x, y = target.lower().strip(), line.lower().strip()
    longer = max(len(x), len(y)) or 1
    tight = (y in x or x in y) and min(len(x), len(y)) / longer >= 0.75
    import re as _re
    squash = _re.sub(r"[^a-z0-9 ]", " ", y).strip()
    asked  = _re.sub(r"[^a-z0-9 ]", " ", x).strip()
    return tight or squash.startswith(asked)

check("finding 'Developer options' as a plain row is ALLOWED — it is the road in",
      traps_on_find("Developer options", False), False)
check("finding it WITH its master switch is also allowed — she was searching, not toggling",
      traps_on_find("Developer options", True), False)
check("...and toggling that switch is still refused at the tap (test-guards.py case C)", True, True)
check("finding '3GPP AT commands' still stops her",
      traps_on_find("3GPP AT commands", True), True)
check("finding 'Bug report shortcut' still stops her",
      traps_on_find("Bug report shortcut", True), True)
check("finding 'USB debugging' is untouched",
      traps_on_find("USB debugging", True), False)

print("\nSHE TURNED DEVELOPER OPTIONS OFF — how it got through")
check("the denylist now needs a VERB aimed at it, not a mention",
      "askedToChange(hit, said)" in src, True)
check("...and that rule is shared with the off-task guard, so they cannot drift",
      "private fun askedToChange(" in src, True)
check("a row carrying a switch is read back after ANY tap, not only a toggle",
      "val readBack = wantSwitch || foundSwitchX >= 0" in src, True)
check("a page opening is not mistaken for a change (would toggle Wi-Fi to open Wi-Fi)",
      "nowIs == wasIs" in src, True)
check("an unintended toggle is shouted, not swallowed",
      "TOGGLED it: $wasIs -> $nowIs" in src, True)
check("the aim is NOT moved — tapping a row still taps the row",
      "val x = if (wantSwitch && foundSwitchX >= 0) foundSwitchX else foundRowX" in src, True)

print("\nTHE PIXELS MUST REFUSE A NEAR-MISS, LIKE THE TREE DOES")
check("'USB debugging' does NOT match 'Revoke USB debugging authorisations'",
      ocr_accepts("USB debugging", "Revoke USB debugging authorisations"), False)
check("'USB debugging' DOES match its own row glued to its summary",
      ocr_accepts("USB debugging", "USB debugging Debug mode when USB is connected"), True)
check("'USB debugging' matches itself",
      ocr_accepts("USB debugging", "USB debugging"), True)
check("'Wireless debugging' does NOT match 'Revoke USB debugging authorisations'",
      ocr_accepts("Wireless debugging", "Revoke USB debugging authorisations"), False)

print("\nHIS LATER WORDS ARE THE TASK")
check("an answer is folded into the request, not thrown away",
      "ANSWER folded into the task" in src, True)
check("...by accumulating, so 'which one?' / 'USB debugging' / 'turn it off' still means something",
      'currentRequest = (currentRequest + " " + heard)' in src, True)

check("opening a row hands the searching tools back (through the door, look again)",
      "opened '$target' -> phase $phase" in src, True)
check("tapping a row does the same",
      "tapped row '$tappedLabel' open -> phase LOCATING" in src, True)
check("the target lock no longer argues with the gate",
      "targetLockedResponse" not in src, True)
check("she is not held on the first move of a request she was just given",
      "this IS what he just asked for" in src, True)

print("\nTHE WEDGE YOU JUST HIT — 'Allow USB debugging?' with no way to press OK")
check("confirm_dialog exists in EVERY phase, including READY and DONE",
      all(allowed(p_, "confirm_dialog") for p_ in tools), True)
check("...including while she waits for his answer",
      allowed("CLARIFYING", "confirm_dialog"), True)
check("the dialog's own text still goes through the denylist",
      "dangerBlocked(listOf(dump.optString(\"elements\")" in src, True)
check("it presses a REAL button by its text, never an estimated coordinate",
      "service.findByPixels(w)" in src, True)

print("\nTHE OTHER TWO FAULTS FROM YOUR RUN")
check("the READY escape hatch asks the PIXELS too (OCR finds what the tree cannot)",
      "findByPixels(foundLabel)" in src, True)
check("no NUDGE while she waits — talking is the correct move there",
      "no nudge — she is waiting on his answer" in src, True)
check("no DRIVE while she waits either",
      "if (phase == Phase.CLARIFYING) continue" in src, True)
check("his answer is taken as an ANSWER, not queued as a rival request",
      "ANSWER folded into the task" in src, True)

print("\nWEDGE CHECKS — a phase machine you cannot leave is worse than no guard")
print("(every one of these is a failure mode I have actually shipped)")
check("looking is free in EVERY phase",
      all(allowed(p_, "look_at_screen") for p_ in tools), True)
check("she can note a plan in every phase (bookkeeping is not an action)",
      all(allowed(p_, "set_plan") for p_ in tools), True)
check("READY self-clears when the row leaves the screen",
      "PHASE READY -> LOCATING" in src, True)
check("DONE waits for the LAST plan step, so step 2 is reachable",
      "planStep >= plan.size - 1" in src, True)
check("finishing a step hands the searching tools back",
      "phase = Phase.LOCATING; foundLabel" in src, True)
check("exhausted search becomes a QUESTION, not a refusal she can ignore",
      "PHASE -> CLARIFYING — $huntSwipes searches" in src, True)
check("his answer always releases CLARIFYING",
      "CLARIFYING -> LOCATING (he answered)" in src, True)
check("the refind guard is gone (the one that refused 62 times)",
      "foundRepeats" not in src, True)

print(f"\n{'ALL PASS' if not fails else str(fails)+' FAILURES'}")
sys.exit(1 if fails else 0)
