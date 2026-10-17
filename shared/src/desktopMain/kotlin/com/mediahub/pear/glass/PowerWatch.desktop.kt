package com.mediahub.pear.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.sun.jna.Native
import com.sun.jna.Structure
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import kotlinx.coroutines.delay
import com.mediahub.pear.log.PearTrace
import java.io.File

/** Keeps [PowerStatus.lowBattery] current, read every 30 seconds. Call once from the window. */
@Composable
fun BatteryWatch() {
    LaunchedEffect(Unit) {
        while (true) {
            val before = PowerStatus.lowBattery
            val now = readLowBattery()
            PowerStatus.lowBattery = now
            // A change flips every glass surface to Flat, which can look like a flicker: it is logged when it happens.
            if (now != before) PearTrace.event("Power") { "lowBattery $before -> $now" }
            delay(30_000)
        }
    }
}

/** True when the machine runs on battery at 20% or less. Any reading that fails counts as "not low". */
internal fun readLowBattery(): Boolean = runCatching {
    if (System.getProperty("os.name").orEmpty().lowercase().contains("win")) windowsLowBattery() else linuxLowBattery()
}.getOrDefault(false)

/** The Win32 SYSTEM_POWER_STATUS structure (winbase.h). Declared here because the JNA version in use doesn't have it. */
@Structure.FieldOrder(
    "ACLineStatus", "BatteryFlag", "BatteryLifePercent",
    "SystemStatusFlag", "BatteryLifeTime", "BatteryFullLifeTime",
)
class SystemPowerStatus : Structure() {
    @JvmField var ACLineStatus: Byte = 0
    @JvmField var BatteryFlag: Byte = 0
    @JvmField var BatteryLifePercent: Byte = 0
    @JvmField var SystemStatusFlag: Byte = 0
    @JvmField var BatteryLifeTime: Int = 0
    @JvmField var BatteryFullLifeTime: Int = 0
}

/** The one kernel32 call this file needs, bound by hand. */
interface PowerKernel32 : StdCallLibrary {
    fun GetSystemPowerStatus(status: SystemPowerStatus): Boolean

    companion object {
        val INSTANCE: PowerKernel32 = Native.load("kernel32", PowerKernel32::class.java, W32APIOptions.DEFAULT_OPTIONS)
    }
}

private fun windowsLowBattery(): Boolean {
    val status = SystemPowerStatus()
    if (!PowerKernel32.INSTANCE.GetSystemPowerStatus(status)) return false
    val onAc = status.ACLineStatus.toInt() == 1
    val percent = status.BatteryLifePercent.toInt() and 0xFF // 255 means unknown
    return !onAc && percent in 0..20
}

private fun linuxLowBattery(): Boolean {
    val batteries = File("/sys/class/power_supply").listFiles().orEmpty().filter {
        runCatching { File(it, "type").readText().trim() == "Battery" }.getOrDefault(false)
    }
    return batteries.any { bat ->
        val percent = runCatching { File(bat, "capacity").readText().trim().toInt() }.getOrDefault(100)
        val state = runCatching { File(bat, "status").readText().trim() }.getOrDefault("")
        percent <= 20 && state != "Charging" && state != "Full"
    }
}