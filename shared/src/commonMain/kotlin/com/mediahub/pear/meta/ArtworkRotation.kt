package com.mediahub.pear.meta

import com.mediahub.pear.model.MetaPreview
import com.mediahub.pear.model.TMDB_IMG

/**
 * Deterministic daily artwork selector (spec: `ArtworkRotation.swift`).
 * Pure function of (title, role, day): titles cycle artwork daily, with slot 0 returning to default poster.
 */
object ArtworkRotation {

    fun poster(item: MetaPreview, day: Int): String? {
        val base = item.posterUrl ?: return null
        return base // Alternates from ArtworkPool plug in here once populated
    }

    fun hero(item: MetaPreview, wide: Boolean, day: Int): String? = item.heroUrl(wide)

    fun detailBackdrop(item: MetaPreview, day: Int): String? = item.backdropUrl ?: item.posterUrl

    /** 64-bit FNV-1a stable hash. */
    fun stableHash(s: String): ULong {
        var h = 0xcbf29ce484222325UL
        for (b in s.encodeToByteArray()) {
            h = h xor (b.toULong() and 0xFFUL)
            h *= 0x100000001b3UL
        }
        return h
    }
}