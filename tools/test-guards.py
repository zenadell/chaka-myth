import re, sys, xml.etree.ElementTree as ET
SRC = "modules/chaka-hands/android/src/main/java/com/chakamyth/hands/ChakaLive.kt"
src = open(SRC).read()

# --- constants pulled FROM THE SOURCE, not retyped, so this cannot drift ---
# The list now has two halves, because walking into Developer options and
# turning it off are not the same act. dangerBlocked still uses both.
ROUTES      = re.findall(r'"([^"]+)"', re.search(r'val ROUTES = listOf\((.*?)\)\n', src, re.S).group(1))
DESTRUCTIVE = re.findall(r'"([^"]+)"', re.search(r'val DESTRUCTIVE = listOf\((.*?)\)\n', src, re.S).group(1))
DEADLY = ROUTES + DESTRUCTIVE
assert DEADLY, "could not read the denylist from source"

# --- the real screen, straight off the phone ---
els = []
for node in ET.parse(sys.argv[1]).getroot().iter("node"):
    t, d = node.get("text","").strip(), node.get("content-desc","").strip()
    if not t and not d: continue
    b = re.findall(r"\d+", node.get("bounds",""))
    if len(b) != 4: continue
    x1,y1,x2,y2 = map(int,b)
    els.append({"label": " ".join(x for x in (t,d) if x), "x1":x1,"y1":y1,"x2":x2,"y2":y2,
                "cx":(x1+x2)//2, "cy":(y1+y2)//2})

def labels_at(x,y):
    return [e["label"] for e in els if e["x1"]<=x<=e["x2"] and e["y1"]<=y<=e["y2"]]

def danger_blocked(labels, said):
    said = said.lower()
    for lab in labels:
        l = lab.lower()
        for d in DEADLY:
            if d in l and d not in said: return lab
    return None

def named_target_on_screen(said):
    said = said.lower(); best = None
    for e in els:
        lab = e["label"]
        if len(lab) < 5: continue
        if len([w for w in re.split(r"[^a-z0-9]+", lab.lower()) if len(w)>=3]) < 2: continue
        if lab.lower() not in said: continue
        if best is None or len(lab) > len(best["label"]): best = e
    return best

def row(name):
    return next((e for e in els if e["label"].strip().lower()==name.lower()), None)

print(f"denylist read from source: {len(DEADLY)} entries")
print(f"real screen parsed: {len(els)} labelled elements\n")
fails = 0
def check(desc, got, want):
    global fails
    ok = got == want; fails += not ok
    print(f"  {'PASS' if ok else 'FAIL':4}  {desc}")
    if not ok: print(f"        expected {want!r}, got {got!r}")

print("A. DESTRUCTIVE CONTROLS — the ones that cut your connection / reboot the phone")
for name in ["Developer options","3GPP AT commands","Bug report shortcut","Revoke USB debugging authorisations"]:
    e = row(name)
    if not e: print(f"  SKIP  {name} not on this screen"); continue
    check(f"tapping {name!r} during 'turn off usb debugging' is BLOCKED",
          danger_blocked(labels_at(e["cx"],e["cy"]), "turn off usb debugging") is not None, True)

print("\nB. THE ROWS YOU ACTUALLY WANT — must NOT be blocked")
for name, said in [("USB debugging","turn off usb debugging"), ("Wireless debugging","turn on wireless debugging")]:
    e = row(name)
    if not e: print(f"  SKIP  {name} not on screen"); continue
    check(f"tapping {name!r} when you asked for it is ALLOWED",
          danger_blocked(labels_at(e["cx"],e["cy"]), said), None)

print("\nC. YOUR OWN WORDS OVERRIDE — your phone, your call")
e = row("Developer options")
if e: check("'turn off developer options' explicitly -> ALLOWED",
            danger_blocked(labels_at(e["cx"],e["cy"]), "turn off developer options"), None)

print("\nD. ACT ON WHAT IS IN FRONT OF HER — the failure you just reported")
t = named_target_on_screen("now turn off USB debugging")
check("'turn off USB debugging' finds it ON SCREEN (travel refused)", t["label"] if t else None, "USB debugging")
t = named_target_on_screen("turn on wireless debugging")
check("'turn on wireless debugging' finds it ON SCREEN", t["label"] if t else None, "Wireless debugging")
check("'open whatsapp and message my mum' -> travel allowed", named_target_on_screen("open whatsapp and message my mum"), None)
check("vague 'turn on the debugging thing' -> no match, she must ask",
      named_target_on_screen("turn on the debugging thing"), None)


print("\nE. MISTRANSCRIBED REQUEST — the failure that made it go insane")
e = row("USB debugging")
# she searched for it by name; the transcript arrived corrupted as "bargain thing"
def change_blocked(label, said, searched):
    if searched and searched.lower() == label.lower(): return None   # deliberate search = intent
    return danger_blocked([label], said)
check("searched 'USB debugging', heard 'bargain thing' -> ALLOWED",
      change_blocked("USB debugging", "turn on the bargain thing", "USB debugging"), None)
check("but 'Developer options' with the same bad transcript -> STILL BLOCKED",
      change_blocked("Developer options", "turn on the bargain thing", "USB debugging") is not None, True)
check("and 3GPP AT commands -> STILL BLOCKED",
      change_blocked("3GPP AT commands", "turn on the bargain thing", "USB debugging") is not None, True)

print(f"\n{'ALL PASS' if not fails else str(fails)+' FAILURES'}")
sys.exit(1 if fails else 0)
