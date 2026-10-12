package com.mediahub.pear.data

/** Setting keys. Same names as the iOS app, so a backup restores without translation. */
object PrefKeys {
    const val ADDON_MANIFEST_URLS = "addon.manifestURLs"
    const val ADDON_DISABLED = "addon.disabled"
    const val ADDON_CATALOG_PICKER = "addon.catalogPicker"
    const val MEDIASERVERS_LIST = "mediaservers.list"
    const val TMDB_KEY = "tmdb.key"
    const val TVDB_KEY = "tvdb.key"
    const val TVDB_PIN = "tvdb.pin"
    const val TVDB_TOKEN_DATE = "tvdb.tokenDate"
    const val MDBLIST_KEY = "mdblist.key"
    const val MDBLIST_LISTS = "mdblist.lists"
    const val UI_ACCENT = "ui.accent"
    const val UI_NETWORK_BADGES = "ui.networkBadges"
    const val UI_TITLE_LOGOS = "ui.titleLogos"
    const val UI_LANDSCAPE_LAYOUT = "ui.landscapeLayout"
    const val PLAYER_GLASS = "player.glass"
    const val PLAYER_AUTOPLAY_NEXT = "player.autoplayNext"
    const val PLAYER_PIP = "player.pip"
    const val PLAYER_PILL_PINNED = "player.pillPinned"
    const val PLAYER_PILL_ORDER = "player.pillOrder"
    const val SKIP_ENABLED = "skip.enabled"
    const val SKIP_FALLBACK_SECONDS = "skip.fallbackSeconds"
    const val SUB_LANG = "sub.lang"
    const val SUB_STYLE = "sub.style"

    /** Default audio language (desktop only). "off" leaves the file's own default track. */
    const val AUDIO_LANG = "audio.lang"
    const val SUBS_ONLINE = "subs.online"
    const val SUBS_BASE_URL = "subs.baseURL"
    const val LIBRARY_COLLAPSED = "library.collapsed"
    const val PROFILES_LIST = "profiles.list"
    const val PROFILES_ACTIVE = "profiles.active"
    const val PINNED_SOURCES = "pinned.sources"
    const val ARTWORK_PINNED = "artwork.pinned"

    // Per-profile bases (see ProfileKeys.scoped).
    const val WATCH_HISTORY = "watch.history"
    const val WATCH_ARCHIVE = "watch.archive"
    const val WATCH_LOG = "watch.log"
    const val LIBRARY_LOCAL = "library.local"
    const val LIBRARY_PREFS = "library.prefs"
    const val CONTENT_PREFS = "content.prefs"

    // Desktop-only. Not in the iOS app and not in backups.
    const val DESKTOP_GLASS_MODE = "desktop.glassMode"
    const val DESKTOP_GLASS_PERFORMANCE = "desktop.glassPerformance"

    /** Desktop only: keep the blur on the player controls while video plays (off = tint only, blur when paused). */
    const val DESKTOP_PLAYER_BLUR_PLAYING = "desktop.playerBlurWhilePlaying"
    const val DESKTOP_APPEARANCE = "desktop.appearance"

    /** Desktop only: episode cards on two rows in the wide layout (the phone layout is always one row). */
    const val DESKTOP_EPISODES_TWO_ROWS = "desktop.episodesTwoRows"
}

/** Secret names, kept outside [PrefStore] (iOS keeps them in the keychain). */
object SecretKeys {
    const val SIMKL_TOKEN = "simkl.token"
    const val SIMKL_REFRESH = "simkl.refresh"

    /** When the access token runs out (epoch milliseconds as text) and which client id it belongs to. Kept with the token, like the TVDB date. */
    const val SIMKL_TOKEN_EXPIRY = "simkl.tokenExpiry"
    const val SIMKL_CLIENT_ID = "simkl.tokenClientID"
    const val TVDB_TOKEN = "tvdb.token"

    /** When the TVDB token was issued (epoch milliseconds as text). Kept with the token, so no setting is written off the UI thread. */
    const val TVDB_TOKEN_DATE = "tvdb.tokenDate"

    fun mediaServerToken(id: String): String = "mediaserver.token.$id"
}

/** Mirrors `ProfileKeys` in Profiles.swift. */
object ProfileKeys {
    const val DEFAULT_ID = "default"

    /** Per-profile data that is wiped when its profile is deleted. */
    val scopedBases = listOf(
        PrefKeys.WATCH_HISTORY, PrefKeys.WATCH_ARCHIVE, PrefKeys.WATCH_LOG,
        PrefKeys.LIBRARY_LOCAL, PrefKeys.LIBRARY_PREFS, PrefKeys.CONTENT_PREFS,
    )

    /** "watch.history" for the first profile (the legacy key), "watch.history.<id>" for the rest. */
    fun scoped(base: String, profile: String): String = if (profile == DEFAULT_ID) base else "$base.$profile"
}

/** Which keys a settings backup holds. Mirrors `SettingsBackup` in SettingsBackup.swift (used by Batch 2.5). */
object BackupRules {
    const val FORMAT_KEY = "_pearBackupVersion"
    const val VERSION = 1

    val keys = setOf(
        PrefKeys.ADDON_MANIFEST_URLS, PrefKeys.ADDON_DISABLED, PrefKeys.ADDON_CATALOG_PICKER, PrefKeys.TMDB_KEY,
        PrefKeys.TVDB_KEY, PrefKeys.TVDB_PIN, PrefKeys.MDBLIST_KEY, PrefKeys.MDBLIST_LISTS,
        PrefKeys.UI_ACCENT, PrefKeys.UI_NETWORK_BADGES, PrefKeys.UI_TITLE_LOGOS, PrefKeys.UI_LANDSCAPE_LAYOUT,
        PrefKeys.PLAYER_GLASS, PrefKeys.PLAYER_AUTOPLAY_NEXT, PrefKeys.PLAYER_PIP, PrefKeys.PLAYER_PILL_PINNED,
        PrefKeys.PLAYER_PILL_ORDER, PrefKeys.SKIP_ENABLED, PrefKeys.SKIP_FALLBACK_SECONDS, PrefKeys.SUB_LANG,
        PrefKeys.SUB_STYLE, PrefKeys.AUDIO_LANG, PrefKeys.SUBS_ONLINE, PrefKeys.SUBS_BASE_URL, PrefKeys.LIBRARY_COLLAPSED,
        PrefKeys.PROFILES_LIST, PrefKeys.PROFILES_ACTIVE, PrefKeys.PINNED_SOURCES, PrefKeys.ARTWORK_PINNED,
    )

    /** Per-profile settings: always included. */
    val settingsPrefixes = listOf(PrefKeys.CONTENT_PREFS)

    /** Per-profile data: only when the user asks for it. */
    val dataPrefixes = listOf(
        PrefKeys.WATCH_HISTORY, PrefKeys.WATCH_ARCHIVE, PrefKeys.WATCH_LOG, PrefKeys.LIBRARY_LOCAL, PrefKeys.LIBRARY_PREFS,
    )

    fun isAllowed(key: String, includeData: Boolean): Boolean {
        if (key in keys) return true
        if (settingsPrefixes.any { matches(key, it) }) return true
        return includeData && dataPrefixes.any { matches(key, it) }
    }

    private fun matches(key: String, base: String) = key == base || key.startsWith("$base.")
}