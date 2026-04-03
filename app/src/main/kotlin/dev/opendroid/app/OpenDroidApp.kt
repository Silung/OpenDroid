package dev.opendroid.app

import android.app.Application
import dev.opendroid.app.overlay.OpenDroidOverlayBridge
import java.io.File

class OpenDroidApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLocale.applyPersisted(this)
        File(filesDir, "skills").mkdirs()
        OpenDroidOverlayBridge.setEmptyState(this)
    }
}
