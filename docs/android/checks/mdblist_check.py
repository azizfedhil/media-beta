"""Model check for T9-b: HomeModel.loadLists (Kotlin) against iOS HomeView.loadLists + refiltered (spec).
State: `lists` (rows on screen, as ids). Inputs: has_key, selected ids, the user's lists (id -> titles after filter, or
None when the list fetch fails), and the previous lists. Each row is identified by "mdb-<id>"."""
import itertools, random

def kotlin_load(prev, has_key, selected, user_lists, titles):
    # user_lists: dict id -> raw (MDBList answered) ; titles: dict id -> titles kept by the classifier
    if not has_key or not selected:
        return []
    chosen = [l for l in user_lists if l in selected]            # userLists().filter { it.id in selected }
    progressive = len(prev) == 0
    done = {}
    for i, lid in enumerate(chosen):
        items = titles.get(lid, 0)
        if items == 0:
            continue                                              # items.isEmpty -> return@launch
        done[i] = f"mdb-{lid}"
    if not done and len(prev) > 0:                                # done.isEmpty && lists.isNotEmpty
        wanted = {f"mdb-{s}" for s in selected}
        return [r for r in prev if r in wanted and titles.get(int(r[4:]), 0) > 0]   # refiltered
    return [done[k] for k in sorted(done)]                        # inListOrder

def ios_load(prev, has_key, selected, user_lists, titles):
    if not has_key or not selected:
        return []
    chosen = [l for l in user_lists if l in selected]
    done = {}
    for i, lid in enumerate(chosen):
        items = titles.get(lid, 0)
        if items: done[i] = f"mdb-{lid}"
    if not done and prev:
        wanted = {f"mdb-{s}" for s in selected}
        kept = [r for r in prev if r in wanted]
        return [r for r in kept if titles.get(int(r[4:]), 0) > 0]
    return [done[k] for k in sorted(done)]

random.seed(7)
n = 0
for _ in range(20000):
    ids = random.sample(range(1, 30), random.randint(0, 6))
    selected = set(random.sample(ids + [99], random.randint(0, min(len(ids) + 1, 4))))
    has_key = random.random() > 0.1
    titles = {i: random.choice([0, 0, 3, 7]) for i in ids}          # 0 = empty after filtering
    user_lists = ids if random.random() > 0.2 else []                # empty list = MDBList unreachable
    prev = [f"mdb-{i}" for i in random.sample(ids, random.randint(0, len(ids)))]
    k = kotlin_load(prev, has_key, selected, user_lists, titles)
    s = ios_load(prev, has_key, selected, user_lists, titles)
    assert k == s, (prev, has_key, selected, user_lists, titles, k, s)
    n += 1
print(f"loadLists matches the iOS logic on {n} random scenarios")
# The two rules the plan names explicitly:
print("unreachable on refresh keeps old rows:", kotlin_load(["mdb-5"], True, {5}, [], {5: 4}))
print("deselect everything clears rows:      ", kotlin_load(["mdb-5"], True, set(), [5], {5: 4}))
print("first load, list 2 empty, 1 and 3 ok: ", kotlin_load([], True, {1, 2, 3}, [1, 2, 3], {1: 2, 2: 0, 3: 5}))
