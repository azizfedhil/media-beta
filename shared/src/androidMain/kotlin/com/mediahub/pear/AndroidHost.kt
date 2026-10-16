package com.mediahub.pear

import android.app.Activity
import android.app.Application
import java.lang.ref.WeakReference

/**
 * Process-wide Android handles that the `expect` functions in commonMain cannot take as parameters. [attach] runs once,
 * from PearApplication, before any screen is built. [activity] is set by MainActivity.
 */
object AndroidHost {
    private var application: Application? = null

    /** The activity on screen. Keep-awake and the system back action read it. Weak, so it never keeps a closed one. */
    var activity: WeakReference<Activity>? = null

    fun attach(app: Application) {
        application = app
    }

    fun app(): Application = checkNotNull(application) { "AndroidHost.attach() was not called" }
}
