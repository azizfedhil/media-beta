package com.mediahub.pear.data

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsBackupTest {

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun testIosXmlPlistImportRestoresKeysAndAddons() {
        val prefs = PrefStore()
        val xmlPlist = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
                <key>_pearBackupVersion</key>
                <integer>1</integer>
                <key>ui.accent</key>
                <string>#22C55E</string>
                <key>tmdb.key</key>
                <string>ios_test_key_123</string>
                <key>skip.enabled</key>
                <true/>
                <key>skip.fallbackSeconds</key>
                <integer>85</integer>
                <key>addon.manifestURLs</key>
                <array>
                    <string>https://v3-cinemeta.strem.io/manifest.json</string>
                    <string>https://torrentio.strem.fun/manifest.json</string>
                </array>
                <key>profiles.list</key>
                <data>${Base64.encode("""[{"id":"default","name":"Alice","colorHex":"#7D5CFF","symbol":""}]""".encodeToByteArray())}</data>
            </dict>
            </plist>
        """.trimIndent()

        val count = SettingsBackup.restore(xmlPlist, prefs)
        assertTrue(count >= 6)
        assertEquals("#22C55E", prefs.string(PrefKeys.UI_ACCENT))
        assertEquals("ios_test_key_123", prefs.string(PrefKeys.TMDB_KEY))
        assertEquals(true, prefs.bool(PrefKeys.SKIP_ENABLED))
        assertEquals(85, prefs.int(PrefKeys.SKIP_FALLBACK_SECONDS))
        assertEquals(
            listOf("https://v3-cinemeta.strem.io/manifest.json", "https://torrentio.strem.fun/manifest.json"),
            prefs.stringList(PrefKeys.ADDON_MANIFEST_URLS),
        )
        assertTrue(prefs.jsonText(PrefKeys.PROFILES_LIST)!!.contains("Alice"))
    }

    @Test
    fun testPlistExportAndImportRoundTrip() {
        val prefs = PrefStore()
        prefs.setString(PrefKeys.UI_ACCENT, "#FF5C8D")
        prefs.setString(PrefKeys.TMDB_KEY, "tmdb_key_abc")
        prefs.setStringList(PrefKeys.ADDON_MANIFEST_URLS, listOf("https://a.com/manifest.json"))
        prefs.setJsonText("content.prefs", """{"categories":["horror"],"rows":[],"added":[],"filterSearch":false}""")
        prefs.setJsonText(PrefKeys.WATCH_HISTORY, """[{"key":"1"}]""")

        val bytes = SettingsBackup.exportPlist(prefs, includeData = true)
        val restoredPrefs = PrefStore()
        val count = SettingsBackup.restore(bytes, restoredPrefs)

        assertTrue(count >= 5)
        assertEquals("#FF5C8D", restoredPrefs.string(PrefKeys.UI_ACCENT))
        assertEquals("tmdb_key_abc", restoredPrefs.string(PrefKeys.TMDB_KEY))
        assertEquals(listOf("https://a.com/manifest.json"), restoredPrefs.stringList(PrefKeys.ADDON_MANIFEST_URLS))
        assertEquals("""{"categories":["horror"],"rows":[],"added":[],"filterSearch":false}""", restoredPrefs.jsonText("content.prefs"))
        assertEquals("""[{"key":"1"}]""", restoredPrefs.jsonText(PrefKeys.WATCH_HISTORY))
    }

    @Test
    fun testJsonExportAndImportRoundTrip() {
        val prefs = PrefStore()
        prefs.setString(PrefKeys.UI_ACCENT, "#14B8D4")
        prefs.setString(PrefKeys.TVDB_KEY, "tvdb_key_xyz")
        prefs.setBool(PrefKeys.PLAYER_GLASS, false)
        prefs.setStringList(PrefKeys.ADDON_MANIFEST_URLS, listOf("https://cinemeta.strem.io/manifest.json"))

        val jsonText = SettingsBackup.exportJson(prefs, includeData = false)
        assertTrue("_pearBackupVersion" in jsonText)
        assertTrue("14B8D4" in jsonText)

        val restoredPrefs = PrefStore()
        val count = SettingsBackup.restore(jsonText, restoredPrefs)

        assertTrue(count >= 4)
        assertEquals("#14B8D4", restoredPrefs.string(PrefKeys.UI_ACCENT))
        assertEquals("tvdb_key_xyz", restoredPrefs.string(PrefKeys.TVDB_KEY))
        assertEquals(false, restoredPrefs.bool(PrefKeys.PLAYER_GLASS))
        assertEquals(listOf("https://cinemeta.strem.io/manifest.json"), restoredPrefs.stringList(PrefKeys.ADDON_MANIFEST_URLS))
    }

    @Test
    fun testDataExcludedWhenIncludeDataFalse() {
        val prefs = PrefStore()
        prefs.setString(PrefKeys.UI_ACCENT, "#7D5CFF")
        prefs.setJsonText(PrefKeys.WATCH_HISTORY, """[{"key":"1"}]""")
        prefs.setJsonText(PrefKeys.LIBRARY_LOCAL, """[{"id":"tt1"}]""")

        val bytes = SettingsBackup.exportPlist(prefs, includeData = false)
        val restoredPrefs = PrefStore()
        SettingsBackup.restore(bytes, restoredPrefs)

        assertEquals("#7D5CFF", restoredPrefs.string(PrefKeys.UI_ACCENT))
        assertNull(restoredPrefs.jsonText(PrefKeys.WATCH_HISTORY))
        assertNull(restoredPrefs.jsonText(PrefKeys.LIBRARY_LOCAL))
    }

    @Test
    fun testUnreadableDataThrows() {
        val prefs = PrefStore()
        assertFailsWith<BackupException.Unreadable> {
            SettingsBackup.restore(byteArrayOf(), prefs)
        }
        assertFailsWith<BackupException.Unreadable> {
            SettingsBackup.restore("corrupted garbage not a plist or json".encodeToByteArray(), prefs)
        }
    }

    @Test
    fun testNotBackupThrowsWhenVersionKeyMissing() {
        val prefs = PrefStore()
        val xmlNoVersion = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
                <key>someOtherAppKey</key>
                <string>value</string>
            </dict>
            </plist>
        """.trimIndent()

        assertFailsWith<BackupException.NotBackup> {
            SettingsBackup.restore(xmlNoVersion, prefs)
        }
    }

    @Test
    fun testNewerBackupVersionThrows() {
        val prefs = PrefStore()
        val xmlNewer = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
                <key>_pearBackupVersion</key>
                <integer>99</integer>
            </dict>
            </plist>
        """.trimIndent()

        val ex = assertFailsWith<BackupException.Newer> {
            SettingsBackup.restore(xmlNewer, prefs)
        }
        assertEquals(99, ex.version)
    }

    @Test
    fun testAddonDisabledKeyMigrationRule() {
        val prefs = PrefStore()
        prefs.setStringList(PrefKeys.ADDON_DISABLED, listOf("https://old.disabled.com"))

        val xmlNoDisabled = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
                <key>_pearBackupVersion</key>
                <integer>1</integer>
                <key>addon.manifestURLs</key>
                <array>
                    <string>https://new.com/manifest.json</string>
                </array>
            </dict>
            </plist>
        """.trimIndent()

        SettingsBackup.restore(xmlNoDisabled, prefs)
        assertNull(prefs.stringList(PrefKeys.ADDON_DISABLED))
    }
}