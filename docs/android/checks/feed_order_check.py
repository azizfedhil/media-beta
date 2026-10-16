"""Model check for T9-a: feedOrder (Kotlin, HomeFeedOrder.kt) against iOS feedRows (HomeView.swift 381-398)
and trailingThemes. Each row is one token so the comparison is exact."""
import itertools

def kotlin_feed_order(show_continue, theme_count, addon_count, n_suggested, n_lists):
    # Port of feedOrder(...) plus the FeedRows expansion (a Suggested or Lists slot draws every row).
    out = []
    def theme(i):
        if i < theme_count:
            out.append(f"T{i}")
    if show_continue: out.append("C")
    theme(0)
    out += [f"S{j}" for j in range(n_suggested)]
    theme(1)
    out += [f"L{j}" for j in range(n_lists)]
    theme(2)
    for i in range(addon_count):
        out.append(f"A{i}")
        if i % 2 == 1:
            theme(3 + i // 2)
    for index in range(3 + addon_count // 2, theme_count):
        out.append(f"T{index}")
    return out

def ios_feed_rows(show_continue, themes, addon_count, n_suggested, n_lists, themes_hidden):
    # Port of the Swift body: themeRow(i) is nil when hidden or when i >= themes.count.
    def theme_row(i):
        if themes_hidden or i >= themes: return None
        return f"T{i}"
    out = []
    if show_continue: out.append("C")
    t = theme_row(0)
    if t: out.append(t)
    out += [f"S{j}" for j in range(n_suggested)]
    t = theme_row(1)
    if t: out.append(t)
    out += [f"L{j}" for j in range(n_lists)]
    t = theme_row(2)
    if t: out.append(t)
    for i in range(addon_count):
        out.append(f"A{i}")
        if i % 2 == 1:
            t = theme_row(3 + i // 2)
            if t: out.append(t)
    # trailingThemes(addonRowCount:)
    if not themes_hidden:
        first = 3 + addon_count // 2
        if themes > first:
            out += [f"T{k}" for k in range(first, themes)]
    return out

checked = 0
for show_continue, themes, addon_count, n_sugg, n_lists in itertools.product(
        [False, True], range(0, 13), range(0, 13), range(0, 4), range(0, 4)):
    # Kotlin receives themeCount = 0 when the built-in themes row is hidden (the iOS themesHidden case).
    for hidden in ([False, True] if themes > 0 else [False]):
        k = kotlin_feed_order(show_continue, 0 if hidden else themes, addon_count, n_sugg, n_lists)
        i = ios_feed_rows(show_continue, themes, addon_count, n_sugg, n_lists, hidden)
        assert k == i, (show_continue, themes, addon_count, n_sugg, n_lists, hidden, k, i)
        # Structural checks on the Kotlin result when themes are shown.
        theme_tokens = [t for t in k if t.startswith("T")]
        if not hidden:
            assert sorted(int(t[1:]) for t in theme_tokens) == list(range(themes)), ("theme missing or duplicated", k)
        else:
            assert theme_tokens == [], k
        addon_tokens = [t for t in k if t.startswith("A")]
        assert addon_tokens == [f"A{i}" for i in range(addon_count)], k
        checked += 1

print(f"feedOrder matches the iOS feed order for {checked} input combinations")
# A spot check that prints the real order for the acceptance case in the plan:
# Cinemeta only (2 add-on rows), TMDB suggested rows (2), MDBList lists (2), themes (6), continue shown.
print("Example, Cinemeta+TMDB+MDBList, 6 themes, 2 add-on rows:")
print(" ".join(kotlin_feed_order(True, 6, 2, 2, 2)))
print("Example, 0 add-on rows, 6 themes (themes must not be lost):")
print(" ".join(kotlin_feed_order(False, 6, 0, 0, 0)))
