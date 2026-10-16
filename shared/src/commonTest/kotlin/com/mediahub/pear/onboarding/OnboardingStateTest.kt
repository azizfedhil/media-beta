package com.mediahub.pear.onboarding

import com.mediahub.pear.addons.DEFAULT_ADDON_URL
import com.mediahub.pear.data.InMemorySecretStore
import com.mediahub.pear.data.PrefKeys
import com.mediahub.pear.data.PrefStore
import com.mediahub.pear.data.SecretKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OnboardingStateTest {
    @Test
    fun freshInstallNeedsTheSetup() {
        val prefs = PrefStore()
        assertTrue(OnboardingState.needed(prefs, InMemorySecretStore(), hasSimklAccount = false))
        assertEquals(OnboardingState.PENDING, prefs.string(PrefKeys.ONBOARDING_STATE))
    }

    @Test
    fun theDefaultAddonListSavedOnFirstLaunchIsNotAnUpgrade() {
        // AddonStore saves its default list the first time it starts, so a fresh install already has this key.
        val prefs = PrefStore()
        prefs.setStringList(PrefKeys.ADDON_MANIFEST_URLS, listOf(DEFAULT_ADDON_URL))
        assertTrue(OnboardingState.needed(prefs, InMemorySecretStore(), hasSimklAccount = false))
    }

    @Test
    fun aCustomisedAddonListIsAnUpgrade() {
        val prefs = PrefStore()
        prefs.setStringList(PrefKeys.ADDON_MANIFEST_URLS, listOf(DEFAULT_ADDON_URL, "https://example.org/manifest.json"))
        assertFalse(OnboardingState.needed(prefs, InMemorySecretStore(), hasSimklAccount = false))
        assertEquals(OnboardingState.DONE, prefs.string(PrefKeys.ONBOARDING_STATE))
    }

    @Test
    fun anySettingOnlyAUserActionWritesMeansAnUpgrade() {
        val keys = listOf(
            PrefKeys.PROFILES_LIST,
            PrefKeys.UI_ACCENT,
            PrefKeys.TMDB_KEY,
            PrefKeys.TVDB_KEY,
            PrefKeys.MDBLIST_KEY,
            PrefKeys.WATCH_HISTORY,
            PrefKeys.LIBRARY_LOCAL,
        )
        for (key in keys) {
            val prefs = PrefStore()
            prefs.setString(key, "x")
            assertFalse(OnboardingState.needed(prefs, InMemorySecretStore(), hasSimklAccount = false), key)
        }
    }

    @Test
    fun aSimklAccountOrTokenMeansAnUpgrade() {
        assertFalse(OnboardingState.needed(PrefStore(), InMemorySecretStore(), hasSimklAccount = true))
        val secrets = InMemorySecretStore()
        secrets.set(SecretKeys.SIMKL_TOKEN, "token")
        assertFalse(OnboardingState.needed(PrefStore(), secrets, hasSimklAccount = false))
    }

    @Test
    fun theDecisionIsMadeOnceAndTheFlowsOwnWritesDoNotChangeIt() {
        val prefs = PrefStore()
        val secrets = InMemorySecretStore()
        assertTrue(OnboardingState.needed(prefs, secrets, hasSimklAccount = false))
        // The setup writes its own accent and keys as the user goes. That must not turn a pending setup into an upgrade.
        prefs.setString(PrefKeys.UI_ACCENT, "#FF5C8D")
        prefs.setString(PrefKeys.TMDB_KEY, "key")
        assertTrue(OnboardingState.needed(prefs, secrets, hasSimklAccount = false))
    }

    @Test
    fun finishingEndsTheSetupForGood() {
        val prefs = PrefStore()
        OnboardingState.needed(prefs, InMemorySecretStore(), hasSimklAccount = false)
        assertTrue(OnboardingState.isPending(prefs))
        OnboardingState.finish(prefs)
        assertFalse(OnboardingState.isPending(prefs))
        assertFalse(OnboardingState.needed(prefs, InMemorySecretStore(), hasSimklAccount = false))
    }

    @Test
    fun replayPutsTheSetupBack() {
        val prefs = PrefStore()
        prefs.setString(PrefKeys.ONBOARDING_STATE, OnboardingState.DONE)
        OnboardingState.replay(prefs)
        assertTrue(OnboardingState.isPending(prefs))
        assertTrue(OnboardingState.needed(prefs, InMemorySecretStore(), hasSimklAccount = false))
    }
}
