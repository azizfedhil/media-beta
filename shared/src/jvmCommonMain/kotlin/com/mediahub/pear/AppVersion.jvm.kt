package com.mediahub.pear

import com.mediahub.pear.update.PearBuild

/** The version this build shows, from pearVersion (gradle.properties, or CI's -PpearVersion). */
actual val appVersion: String = PearBuild.VERSION
