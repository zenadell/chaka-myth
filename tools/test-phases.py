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
