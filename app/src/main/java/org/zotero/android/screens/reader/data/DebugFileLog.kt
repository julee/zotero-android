package org.zotero.android.screens.reader.data

import org.zotero.android.BuildConfig
import org.zotero.android.ZoteroApplication
import java.io.File

// TEMP debug helper: append to a pullable file so reader/annotation behavior can be
// inspected on devices whose logcat is encrypted/restricted. Debug builds only.
object DebugFileLog {
    fun log(message: String) {
        if (!BuildConfig.DEBUG) {
            return
        }
        try {
            val dir = ZoteroApplication.instance.getExternalFilesDir(null)
            File(dir, "reader_debug.log").appendText(
                System.currentTimeMillis().toString() + ": " + message + "\n"
            )
        } catch (_: Throwable) {
        }
    }
}
