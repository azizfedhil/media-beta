package com.mediahub.pear.onboarding

import com.mediahub.pear.addons.DEFAULT_ADDON_URL
import com.mediahub.pear.data.PrefKeys
import com.mediahub.pear.data.PrefStore
import com.mediahub.pear.data.SecretKeys
import com.mediahub.pear.data.SecretStore

/**
 * First-launch setup state (iOS `Core/Onboarding.swift`). A fresh install is `pending` until the setup is finished or
 * skipped. Someone updating from an older build already has settings saved, so the first time this runs they are marked
 * `done` and never see the flow.
 */
object OnboardingState {
    const val PENDING = "pending"
    const val DONE = "done"

    /**
     * Settings that only a user action writes. Any one of them means an existing install. The add-on list is not here:
     * `AddonStore` saves its default list on the very first launch, so it is judged by [addonsCustomised] instead.
     */
    private val existingKeys = listOf(
        PrefKeys.PROFILES_LIST,
        PrefKeys.UI_ACCENT,
        PrefKeys.TMDB_KEY,
        PrefKeys.TVDB_KEY,
        PrefKeys.MDBLIST_KEY,
        PrefKeys.WATCH_HISTORY,
        PrefKeys.LIBRARY_LOCAL,
    )

    /**
     * Whether the setup should show. Decided once, the first time it is asked, and remembered: the flow itself writes the
     * accent, the add-ons and the keys, and those must not turn a half-finished setup into an existing install.
     * [hasSimklAccount] is the Simkl account loaded from its own file, not from [prefs].
     */
    fun needed(prefs: PrefStore, secrets: SecretStore, hasSimklAccount: Boolean): Boolean {
        if (PrefKeys.ONBOARDING_STATE in prefs) return prefs.string(PrefKeys.ONBOARDING_STATE) == PENDING
        val upgrading = existingKeys.any { it in prefs } ||
            addonsCustomised(prefs) ||
            hasSimklAccount ||
            secrets.get(SecretKeys.SIMKL_TOKEN) != null
        prefs.setString(PrefKeys.ONBOARDING_STATE, if (upgrading) DONE else PENDING)
        return !upgrading
    }

    /** True while the setup still has to be finished. Read-only. */
    fun isPending(prefs: PrefStore): Boolean = prefs.string(PrefKeys.ONBOARDING_STATE) == PENDING

    /** The setup is finished or skipped: it never shows again. */
    fun finish(prefs: PrefStore) {
        prefs.setString(PrefKeys.ONBOARDING_STATE, DONE)
    }

    /** Puts the setup back, for the developer tools. */
    fun replay(prefs: PrefStore) {
        prefs.setString(PrefKeys.ONBOARDING_STATE, PENDING)
    }

    /** A list other than the default one: the user has added or removed an add-on. */
    internal fun addonsCustomised(prefs: PrefStore): Boolean {
        val saved = prefs.stringList(PrefKeys.ADDON_MANIFEST_URLS) ?: return false
        return saved.toSet() != setOf(DEFAULT_ADDON_URL)
    }
}
