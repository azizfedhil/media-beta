package com.mediahub.pear.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinNT.SYSTEM_POWER_STATUS
import kotlinx.coroutines.delay
import java.io.File

/** Keeps [PowerStatus.lowBattery] current, read every 30 seconds. Call once from the window. */
@Composable
fun BatteryWatch() {
    LaunchedEffect(Unit) {
        while (true) {
            PowerStatus.lowBattery = readLowBattery()
            delay(30_000)
        }
    }
}

/** True when the machine runs on battery at 20% or less. Any reading that fails counts as "not low". */
internal fun readLowBattery(): Boolean = runCatching {
    if (System.getProperty("os.name").orEmpty().lowercase().contains("win")) windowsLowBattery() else linuxLowBattery()
}.getOrDefault(false)

private fun windowsLowBattery(): Boolean {
    val status = SYSTEM_POWER_STATUS()
    if (!Kernel32.INSTANCE.GetSystemPowerStatus(status)) return false
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
