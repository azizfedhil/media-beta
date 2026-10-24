package com.mediahub.pear.android

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.os.Build
import com.mediahub.pear.AndroidHost
import com.mediahub.pear.data.Storage
import com.mediahub.pear.data.androidStorage
import com.mediahub.pear.log.FileCrashReportSource
import com.mediahub.pear.log.PearLogFile
import com.mediahub.pear.net.ImageMemoryCache
import com.mediahub.pear.perf.DeviceProfile
import com.mediahub.pear.perf.DeviceTier
import com.mediahub.pear.shell.CrashReports
import java.io.File

/** Phones with less RAM than this, or with Android's low-RAM flag, are Low. A guess: 3.5 GiB. */
private const val LOW_TIER_MAX_RAM_BYTES = 3_758_096_384L

/** Phones with less RAM than this are Mid. A guess: 6 GiB. */
private const val MID_TIER_MAX_RAM_BYTES = 6_442_450_944L

/** Decoded-image budget on Low phones, and its entry count. A guess for 2-4 GB of RAM. */
private const val LOW_IMAGE_BUDGET_BYTES = 32L * 1024 * 1024
private const val LOW_IMAGE_MAX_ENTRIES = 80

/** Decoded-image budget on Mid phones, and its entry count. A guess. */
private const val MID_IMAGE_BUDGET_BYTES = 96L * 1024 * 1024
private const val MID_IMAGE_MAX_ENTRIES = 150

/** The ImageMemoryCache default, used on High phones. Kept in step with it. */
private const val HIGH_IMAGE_BUDGET_BYTES = 256L * 1024 * 1024

class PearApplication : Application(), ComponentCallbacks2 {
    /**
     * The one settings and secrets store for the whole app. It used to be made by each activity, so a configuration change
     * that recreated the activity built a second store. The activity reads this one (MainActivity.storage). It is built on
     * first use; onCreate reads it on a background thread, so the activity usually finds it ready.
     */
    val storage: Storage by lazy { androidStorage(this) }

    override fun onCreate() {
        super.onCreate()
        val tier = deviceTier()
        DeviceProfile.tier = tier
        // Smaller phones keep a smaller decoded-image cache. High phones keep the ImageMemoryCache defaults.
        when (tier) {
            DeviceTier.Low -> ImageMemoryCache.configure(LOW_IMAGE_BUDGET_BYTES, LOW_IMAGE_MAX_ENTRIES)
            DeviceTier.Mid -> ImageMemoryCache.configure(MID_IMAGE_BUDGET_BYTES, MID_IMAGE_MAX_ENTRIES)
            DeviceTier.High -> Unit
        }
        registerComponentCallbacks(this)
        AndroidHost.attach(this)
        // The log and the crash briefing. Android has no window-close hook, so no session tracking; crash reports are still written.
        val logs = File(filesDir, "logs")
        PearLogFile.init(logs, "unknown", "android", trackSessions = false)
        CrashReports.source = FileCrashReportSource(logs, folderOpener = null)
        installCaBundle()
        // Settings parsing starts now, while the activity is being created, instead of on the main thread at its first read.
        // A failure is ignored here: the next read of storage, on the main thread, hits it again.
        Thread { runCatching { storage } }.start()
    }

    /**
     * Frees decoded images as the system asks for memory. When the UI is hidden the cache drops to a quarter of its
     * budget; at moderate pressure or worse it is emptied.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE -> ImageMemoryCache.clear()
            level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> ImageMemoryCache.trimTo(imageBudgetBytes() / 4)
        }
    }

    /** The device class, from the RAM and the Android version. Read once at launch. Phones before Android 12 are Mid at most. */
    private fun deviceTier(): DeviceTier {
        val activityManager = getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
        return when {
            activityManager.isLowRamDevice || memory.totalMem < LOW_TIER_MAX_RAM_BYTES -> DeviceTier.Low
            memory.totalMem < MID_TIER_MAX_RAM_BYTES || Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> DeviceTier.Mid
            else -> DeviceTier.High
        }
    }

    /** The decoded-image budget for this device's tier, in bytes. */
    private fun imageBudgetBytes(): Long = when (DeviceProfile.tier) {
        DeviceTier.Low -> LOW_IMAGE_BUDGET_BYTES
        DeviceTier.Mid -> MID_IMAGE_BUDGET_BYTES
        DeviceTier.High -> HIGH_IMAGE_BUDGET_BYTES
    }

    /**
     * Copies the bundled CA roots (assets/cacert.pem) into the app's files folder when they are missing or older than the
     * last app update. The player reads them there: without them, https sources cannot be verified (decisions.md, D15).
     */
    private fun installCaBundle() {
        runCatching {
            val target = File(filesDir, "cacert.pem")
            val updatedAt = packageManager.getPackageInfo(packageName, 0).lastUpdateTime
            if (!target.isFile || target.lastModified() < updatedAt) {
                target.writeBytes(assets.open("cacert.pem").use { it.readBytes() })
            }
        }
    }
}
