package com.mediahub.pear.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackupRulesTest {
    @Test
    fun settingsKeysAreAlwaysAllowed() {
        // The 28 keys of SettingsBackup.swift, plus audio.lang, which only the desktop app writes (Batch 4.4a).
        assertEquals(29, BackupRules.keys.size)
        assertTrue(BackupRules.isAllowed("tmdb.key", includeData = false))
        assertTrue(BackupRules.isAllowed("ui.accent", includeData = false))
    }

    @Test
    fun contentPrefsAreSettingsForEveryProfile() {
        assertTrue(BackupRules.isAllowed("content.prefs", false))
        assertTrue(BackupRules.isAllowed("content.prefs.ABC", false))
        assertFalse(BackupRules.isAllowed("content.prefsX", false))
    }

    @Test
    fun historyAndLibraryNeedTheDataSwitch() {
        for (k in listOf("watch.history", "watch.history.ABC", "watch.archive", "watch.log.X", "library.local", "library.prefs.X")) {
            assertFalse(BackupRules.isAllowed(k, false), k)
            assertTrue(BackupRules.isAllowed(k, true), k)
        }
    }

    @Test
    fun otherKeysAreNeverAllowed() {
        for (k in listOf("simkl.token", "tmdb.idCache", "desktop.glassMode", "dev.counter")) {
            assertFalse(BackupRules.isAllowed(k, true), k)
        }
    }

    @Test
    fun scopedKeysKeepTheLegacyNameForTheFirstProfile() {
        assertEquals("watch.history", ProfileKeys.scoped("watch.history", ProfileKeys.DEFAULT_ID))
        assertEquals("watch.history.XYZ", ProfileKeys.scoped("watch.history", "XYZ"))
    }
}
