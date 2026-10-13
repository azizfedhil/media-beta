package com.mediahub.pear.addons

import com.mediahub.pear.data.ContentRules
import com.mediahub.pear.data.addonRowKey
import com.mediahub.pear.model.Addon
import com.mediahub.pear.model.AddonManifest
import com.mediahub.pear.model.CatalogRow
import com.mediahub.pear.model.CatalogSource
import com.mediahub.pear.model.MetaPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** The stable id of this catalogue's "hide this row" / "added to Home" choice (`ContentRules.rowKey` in Swift). */
fun Addon.rowKey(catalog: AddonManifest.CatalogDef): String = addonRowKey(manifest.id, catalog.type, catalog.id)

/**
 * Whether this catalogue gets a row on Home. Add-ons with a catalogue picker show only what was added; every other
 * add-on shows everything that wasn't switched off (spec: `ContentRules.showsOnHome`).
 */
fun ContentRules.showsOnHome(addon: Addon, catalog: AddonManifest.CatalogDef, picker: Boolean): Boolean {
    val key = addon.rowKey(catalog)
    return if (picker) key in addedRows else key !in hiddenRows
}

/** What [HomeCatalogs.loadRows] found. */
class HomeRowsResult(
    /** Rows in add-on order. A catalogue that failed, was empty or was filtered away has no row. */
    val rows: List<CatalogRow>,
    /** How many of [jobs] could not be fetched at all (as opposed to being empty or fully filtered). */
    val failures: Int,
    val jobs: Int,
) {
    /** Every request failed (no connection): Home keeps what it already shows instead of swapping in an empty page. */
    val allFailed: Boolean get() = jobs > 0 && failures == jobs
}

/** The add-on rows of Home (spec: `HomeModel.homeJobs` and the add-on part of `HomeModel.load`). */
object HomeCatalogs {
    /** Rows an add-on without a catalogue picker may add to Home. */
    const val MAX_ROWS = 12

    /**
     * Add-on catalogues that get a row on Home, in add-on order. Catalogues the user switched off are dropped first,
     * so they don't use up the 12 slots. Add-ons with a catalogue picker (AIOMetadata) contribute only what the user
     * added, and those rows are never cut by the cap: the user asked for each one.
     */
    fun jobs(addons: List<Addon>, rules: ContentRules, pickers: Set<String>): List<Pair<Addon, AddonManifest.CatalogDef>> {
        var slots = MAX_ROWS
        val out = ArrayList<Pair<Addon, AddonManifest.CatalogDef>>()
        for (a in addons) {
            val picker = a.isAIOMetadata || a.id in pickers
            for (c in a.homeCatalogs) {
                if (!rules.showsOnHome(a, c, picker)) continue
                if (!picker) {
                    if (slots <= 0) continue
                    slots -= 1
                }
                out.add(a to c)
            }
        }
        return out
    }

    /** "Popular Movies", "Top Series", "Anime Shows": the catalogue's name plus what kind of title it holds. */
    fun rowTitle(catalog: AddonManifest.CatalogDef): String {
        val kind = when (catalog.type) {
            "movie" -> "Movies"
            "series" -> "Series"
            else -> catalog.type.replaceFirstChar { it.uppercase() }
        }
        return "${catalog.name ?: catalog.id} $kind"
    }

    /**
     * Fetches every job at once and builds the rows. [filter] applies the user's content rules (the
     * `TitleClassifier`). [onProgress] gets the rows found so far each time one lands, in stable order, so a first
     * load can show rows as they arrive. Never throws except when cancelled.
     */
    suspend fun loadRows(
        client: AddonClient,
        jobs: List<Pair<Addon, AddonManifest.CatalogDef>>,
        filter: suspend (List<MetaPreview>) -> List<MetaPreview> = { it },
        onProgress: ((List<CatalogRow>) -> Unit)? = null,
    ): HomeRowsResult {
        class Outcome(val index: Int, val row: CatalogRow?, val failed: Boolean)

        val done = HashMap<Int, CatalogRow>()
        var failures = 0
        coroutineScope {
            val results = Channel<Outcome>(Channel.UNLIMITED)
            jobs.forEachIndexed { i, (addon, cat) ->
                launch {
                    val outcome = try {
                        val raw = client.catalog(addon, cat)
                        if (raw.isEmpty()) {
                            Outcome(i, null, false)
                        } else {
                            val items = filter(raw)
                            if (items.isEmpty()) {
                                Outcome(i, null, false)
                            } else {
                                Outcome(
                                    i,
                                    CatalogRow(
                                        id = "${addon.id}/${cat.type}/${cat.id}",
                                        title = rowTitle(cat),
                                        items = items,
                                        source = CatalogSource.FromAddon(addon, cat),
                                        symbol = "film.stack",
                                        prefKey = addon.rowKey(cat),
                                        rawCount = raw.size,
                                    ),
                                    false,
                                )
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Outcome(i, null, true)
                    }
                    results.send(outcome)
                }
            }
            repeat(jobs.size) {
                val o = results.receive()
                if (o.failed) failures += 1
                val row = o.row
                if (row != null) {
                    done[o.index] = row
                    onProgress?.invoke(done.keys.sorted().map { done.getValue(it) })
                }
            }
        }
        return HomeRowsResult(done.keys.sorted().map { done.getValue(it) }, failures, jobs.size)
    }
}
