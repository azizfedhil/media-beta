"""Model check for T4: OnboardingStep.next()/previous() walk OnboardingRules.steps (Kotlin port), per platform."""
ALL = ["Welcome", "Profile", "Look", "Player", "Content", "Home", "Keys", "Done"]

def steps(has_glass_choice):
    return [s for s in ALL if s != "Player" or has_glass_choice]   # OnboardingRules.steps

def nxt(s, st):
    i = st.index(s) if s in st else -1
    return None if i < 0 else (st[i + 1] if i + 1 < len(st) else None)

def prv(s, st):
    i = st.index(s) if s in st else -1
    return None if i < 0 else (st[i - 1] if i - 1 >= 0 else None)

def walk(has_choice):
    st = steps(has_choice)
    seq, cur = [], st[0]
    while cur is not None:
        seq.append(cur); cur = nxt(cur, st)
    return seq

desktop, android = walk(False), walk(True)
# The existing OnboardingRulesTest expects exactly this on desktop (no Player):
assert desktop == ["Welcome", "Profile", "Look", "Content", "Home", "Keys", "Done"], desktop
assert android == ALL, android
assert prv("Welcome", steps(True)) is None and nxt("Done", steps(True)) is None
assert prv("Content", steps(True)) == "Player" and nxt("Look", steps(True)) == "Player"
assert prv("Content", steps(False)) == "Look" and nxt("Look", steps(False)) == "Content"
assert prv("Done", steps(False)) == "Keys"
# Jump back only goes to earlier steps (OnboardingHost.jump uses the enum order, unchanged).
assert ALL.index("Look") < ALL.index("Player") < ALL.index("Content")
print("desktop steps (unchanged):", " > ".join(desktop))
print("android steps:            ", " > ".join(android))
print("progress dots: desktop", len(desktop), "android", len(android))
print("OK: desktop navigation unchanged; Android shows Player between Look and Content")
