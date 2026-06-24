package org.zotero.android

import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.gson.Gson
import com.pspdfkit.PSPDFKit
import dagger.hilt.android.HiltAndroidApp
import org.zotero.android.BuildConfig.EVENT_AND_CRASH_LOGGING_ENABLED
import org.zotero.android.androidx.content.longToast
import org.zotero.android.api.annotations.ForGsonWithRoundedDecimals
import org.zotero.android.architecture.Defaults
import org.zotero.android.architecture.coroutines.ApplicationScope
import org.zotero.android.architecture.crashreporting.FirebaseCrashReportingTree
import org.zotero.android.architecture.logging.crash.CrashFileWriter
import org.zotero.android.architecture.logging.debug.DebugLoggingTree
import org.zotero.android.files.FileStore
import org.zotero.android.sync.Controllers
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
open class ZoteroApplication: Application(), DefaultLifecycleObserver {

    @Inject
    lateinit var controllers: Controllers

    @Inject
    lateinit var applicationScope: ApplicationScope

    @Inject
    lateinit var fileStore: FileStore

    @Inject
    lateinit var debugLoggingTree: DebugLoggingTree

    @Inject
    lateinit var gson: Gson

    @Inject
    lateinit var crashFileWriter: CrashFileWriter

    @Inject
    lateinit var defaults: Defaults

    @Inject
    @ForGsonWithRoundedDecimals
    lateinit var gsonWithRoundedDecimals: Gson

    companion object {
        lateinit var instance: ZoteroApplication
    }

    override fun onCreate() {
        super<Application>.onCreate()
        instance = this
        setUpLogging()
        installDebugCrashHandler()
        installFreezeWatchdog()

        controllers.init()

        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        initializePspdfKit()
    }

    // TEMP debug: persist uncaught exceptions to a pullable file so crashes can be
    // diagnosed on devices whose logcat is encrypted/restricted.
    private fun installDebugCrashHandler() {
        if (!BuildConfig.DEBUG) {
            return
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val dir = getExternalFilesDir(null)
                java.io.File(dir, "crash_debug.txt").writeText(
                    java.util.Date().toString() + " on " + thread.name + "\n" +
                        android.util.Log.getStackTraceString(throwable)
                )
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    // TEMP debug: detect main-thread freezes (ANRs) and dump all thread stacks to a
    // pullable file, so freezes can be diagnosed without root or a debugger.
    private fun installFreezeWatchdog() {
        if (!BuildConfig.DEBUG) {
            return
        }
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        val responded = java.util.concurrent.atomic.AtomicBoolean(true)
        val watchdog = Thread {
            var lastDumpForThisFreeze = false
            while (true) {
                responded.set(false)
                mainHandler.post { responded.set(true) }
                try {
                    Thread.sleep(4000)
                } catch (_: InterruptedException) {
                }
                if (!responded.get()) {
                    if (!lastDumpForThisFreeze) {
                        lastDumpForThisFreeze = true
                        try {
                            val sb = StringBuilder()
                            sb.append("FREEZE detected ").append(java.util.Date()).append("\n\n")
                            val mainThread = android.os.Looper.getMainLooper().thread
                            sb.append("=== MAIN THREAD (").append(mainThread.state).append(") ===\n")
                            for (el in mainThread.stackTrace) {
                                sb.append("\tat ").append(el).append("\n")
                            }
                            sb.append("\n=== ALL THREADS ===\n")
                            for ((t, stack) in Thread.getAllStackTraces()) {
                                if (t == mainThread) continue
                                sb.append("\n-- ").append(t.name).append(" (").append(t.state).append(") --\n")
                                for (el in stack) {
                                    sb.append("\tat ").append(el).append("\n")
                                }
                            }
                            java.io.File(getExternalFilesDir(null), "freeze_debug.txt")
                                .writeText(sb.toString())
                        } catch (_: Throwable) {
                        }
                    }
                } else {
                    lastDumpForThisFreeze = false
                }
            }
        }
        watchdog.isDaemon = true
        watchdog.name = "FreezeWatchdog"
        watchdog.start()
    }

    private fun initializePspdfKit() {
        val initializationResult = attemptToInitializePspdfKit(this)
        defaults.setPspdfkitInitialized(initializationResult)

    }

    private fun attemptToInitializePspdfKit(context: Context): Boolean {
        try {
            if (BuildConfig.PSPDFKIT_KEY.isNotBlank()) {
                PSPDFKit.initialize(context, BuildConfig.PSPDFKIT_KEY)
            } else {
                PSPDFKit.initialize(context, null)
            }
        } catch (e: Exception) {
            Timber.e(e, "Unable to initialize PSPDFKIT")
            context.longToast("Unable to initialize PSPDFKIT")
            return false
        }
        return true
    }

    override fun onStart(owner: LifecycleOwner) {
        controllers.willEnterForeground()
    }

    override fun onStop(owner: LifecycleOwner) {
        controllers.didEnterBackground()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        controllers.willTerminate()
    }

    private fun setUpLogging() {
        val consoleDebugTree = object : Timber.DebugTree() {
            override fun createStackElementTag(element: StackTraceElement): String {
                return "[${Thread.currentThread().name}]" +
                        "${super.createStackElementTag(element)}"
            }
        }
        val listOfTrees = mutableListOf(consoleDebugTree, debugLoggingTree)
        if (EVENT_AND_CRASH_LOGGING_ENABLED) {
            val defaultExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                crashFileWriter.writeCrashToFile(throwable.stackTraceToString())
                defaultExceptionHandler?.uncaughtException(
                    thread,
                    throwable
                )
            }
            listOfTrees.add(FirebaseCrashReportingTree())
        }
        Timber.plant(*listOfTrees.toTypedArray())
    }
}