package com.mediahub.pear.player.ui

// The controls pill's rules (Batch 4.3c). No Compose in here, so they are unit tested. Mirrors `PillItem` and the
// layout helpers in PlayerControlsSettings.swift and PlayerView.swift.

/**
 * Everything that can sit in the player's controls pill. Declaration order is the default on-screen order; the user
 * can rearrange it in Settings -> Playback -> Player controls. The raw names are the iOS ones, so a backup restores
 * without translation.
 */
enum class PillItem(val raw: String, val title: String, val symbol: String) {
    Speed("speed", "Speed", "speedometer"),
    Episodes("episodes", "Episodes", "list.bullet"),
    Sources("sources", "Sources", "rectangle.stack"),
    Volume("volume", "Volume", "speaker.wave.2"),
    Pip("pip", "Picture in Picture", "pip.enter"),
    Subtitles("subtitles", "Subtitles", "captions.bubble"),
    Audio("audio", "Audio track", "waveform"),
    Next("next", "Next episode", "forward.end.fill");

    companion object {
        /** At most this many buttons can stay visible while the pill is folded. */
        const val MAX_PINNED = 4

        /** A pill with more buttons than this folds: the pinned ones show, the rest unfold to the left. */
        const val FOLD_ABOVE = MAX_PINNED

        /** What stays visible out of the box (`PillItem.defaultPinned`). */
        val defaultPinned: Set<PillItem> = setOf(Volume, Subtitles, Speed, Pip)

        val defaultOrder: List<PillItem> = entries.toList()

        fun fromRaw(raw: String): PillItem? = entries.firstOrNull { it.raw == raw }

        /** Unknown names are ignored, anything past [MAX_PINNED] is dropped, and an empty list means the default. */
        fun decodePinned(raw: String?): Set<PillItem> {
            val found = raw.orEmpty().split(",").mapNotNull { fromRaw(it) }
            val set = found.take(MAX_PINNED).toSet()
            return if (set.isEmpty()) defaultPinned else set
        }

        fun encodePinned(items: Set<PillItem>): String = entries.filter { it in items }.joinToString(",") { it.raw }

        /**
         * Always returns every item exactly once: unknown names and repeats are ignored, and items missing from the
         * saved list (a button added in a later version) go at the end, in their default order.
         */
        fun decodeOrder(raw: String?): List<PillItem> {
            val seen = LinkedHashSet<PillItem>()
            raw.orEmpty().split(",").forEach { part -> fromRaw(part)?.let { seen.add(it) } }
            return seen.toList() + entries.filter { it !in seen }
        }

        fun encodeOrder(items: List<PillItem>): String = items.joinToString(",") { it.raw }
    }
}

/**
 * The buttons this build can show, whatever the show. Each one joins the set when its batch lands: Subtitles and
 * Audio in 4.4a, Episodes later. Next depends on the episode (see [pillItemsAvailable]). Picture in Picture never
 * applies on desktop.
 */
val desktopPillItems: Set<PillItem> = setOf(PillItem.Speed, PillItem.Sources, PillItem.Volume)

/**
 * The buttons available right now: [desktopPillItems], plus Next while the show has an episode after this one
 * (`pillHas` in PlayerView.swift).
 */
fun pillItemsAvailable(hasNext: Boolean): Set<PillItem> = if (hasNext) desktopPillItems + PillItem.Next else desktopPillItems

/** What the pill shows right now: the buttons in on-screen order, and how many of them are folded away. */
data class PillLayout(val items: List<PillItem>, val folded: Int)

/**
 * The buttons that exist right now, in on-screen order (`pillLayout` in PlayerView.swift). Folding happens only
 * above [PillItem.FOLD_ABOVE]: the ones that are not pinned come first (left), so unfolding grows the pill to the
 * left, and each group keeps the user's order. A pinned set that matches nothing that exists falls back to the
 * defaults.
 */
fun pillLayout(order: List<PillItem>, pinned: Set<PillItem>, available: Set<PillItem>): PillLayout {
    val all = order.filter { it in available }
    if (all.size <= PillItem.FOLD_ABOVE) return PillLayout(all, 0)
    var keep: Set<PillItem> = pinned.filter { it in all }.toSet()
    if (keep.isEmpty()) keep = PillItem.defaultPinned.filter { it in all }.toSet()
    val folded = all.filter { it !in keep }
    val shown = all.filter { it in keep }
    return PillLayout(folded + shown, folded.size)
}

/** How the pill is currently open. */
enum class PillMode {
    /** Pinned buttons and the chevron. */
    Folded,

    /** Every button, the hidden ones unfolded to the left. */
    Unfolded,

    /** The speaker has become a volume slider. */
    Volume,
}

/** `open` in the Swift pill: folded, the chevron was tapped (and no panel or volume slider has taken over). */
fun pillMode(expanded: Boolean, volumeOpen: Boolean, folded: Int): PillMode = when {
    volumeOpen -> PillMode.Volume
    expanded && folded > 0 -> PillMode.Unfolded
    else -> PillMode.Folded
}

/**
 * How many icon slots the visible part of the pill is wide (`iconRow` in PlayerView.swift). A folded pill shows its
 * pinned buttons plus the chevron; the unfolded pill shows everything plus the chevron. The volume slider takes the
 * folded width, but never less than its minimum, which the caller applies.
 */
fun pillWindowSlots(layout: PillLayout, mode: PillMode): Int {
    val n = layout.items.size
    val folded = if (layout.folded > 0) n - layout.folded + 1 else n
    return when (mode) {
        PillMode.Folded, PillMode.Volume -> folded
        PillMode.Unfolded -> if (layout.folded > 0) n + 1 else folded
    }
}
