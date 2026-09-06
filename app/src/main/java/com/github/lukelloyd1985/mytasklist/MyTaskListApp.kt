package com.github.lukelloyd1985.mytasklist

import android.app.Application
import android.content.Intent
import android.os.Process
import android.util.Log
import android.widget.Toast
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import com.github.lukelloyd1985.mytasklist.notifications.NotificationHelper
import kotlin.system.exitProcess

@HiltAndroidApp
class MyTaskListApp : Application(), Configuration.Provider {

    companion object {
        private const val LOG_KEY = "debug_log"
    }

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()
        // TEMPORARY diagnostic checkpoints - the classic-SHA-1 fix (see
        // README "Publishing to Google Play" step 5) did NOT resolve the
        // Play-install-only startup crash; initFirebase() still dies the
        // same way. These bisect *inside* the function to find the exact
        // failing line, since "somewhere in initFirebase()" wasn't enough
        // last time. Remove once the cause is found.
        showPreviousRunLog()
        debugToast("1: App.onCreate start")
        installCrashHandler()
        debugToast("2: crash handler installed")
        initFirebase()
        debugToast("3: initFirebase() returned")
        NotificationHelper.createChannel(this)
        debugToast("4: App.onCreate complete")
    }

    // A Toast can't be selected/copied, wraps or truncates long text
    // (an exception's class+message, or a stack trace, routinely
    // doesn't fit), and disappears in ~2s regardless - none of which is
    // acceptable for reading back exactly where and why a run died.
    // Every checkpoint this run is appended to a persisted log
    // (commit(), not apply() - must be durable before returning, since
    // the process may not survive much longer); on the *next* launch,
    // before anything else, that whole log is shown on its own full
    // screen (DebugLogActivity - selectable, scrollable, shareable),
    // then cleared so each run's screen shows only that run's log.
    private fun showPreviousRunLog() {
        val log = debugPrefs().getString(LOG_KEY, null)
        if (!log.isNullOrBlank()) {
            startActivity(
                Intent(this, DebugLogActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    putExtra(DebugLogActivity.EXTRA_LOG, "PREVIOUS RUN:\n\n$log")
                },
            )
        }
        debugPrefs().edit().putString(LOG_KEY, "").commit()
    }

    private fun debugPrefs() = getSharedPreferences("debug", MODE_PRIVATE)

    private fun appendLog(message: String) {
        val existing = debugPrefs().getString(LOG_KEY, "").orEmpty()
        debugPrefs().edit().putString(LOG_KEY, existing + message + "\n").commit()
    }

    private fun debugToast(message: String) {
        appendLog(message)
        Toast.makeText(this, "DEBUG $message", Toast.LENGTH_SHORT).show()
    }

    // TEMPORARY: reads a BuildConfig String value and checkpoints three
    // separate operations on it, since "reaches the read but not the
    // interpolated display" (2c vs 2c-show) wasn't fine-grained enough -
    // see initFirebase()'s own comment for what each step isolates.
    private fun bisectValue(name: String, value: String): String {
        debugToast("2x-read($name): length=${value.length}")
        val shown = "2x-show($name): [$value]"
        debugToast("2x-built($name): interpolated string built OK")
        debugToast(shown)
        return value
    }

    // See CrashReportActivity's own comment for why this exists. Falls
    // through to the platform's own default handler (which shows the
    // usual "App keeps stopping" dialog) if launching the crash screen
    // itself fails for any reason, rather than risking a silent hang.
    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Logged (full stack trace, no truncation) and committed to
            // disk before anything else below - CrashReportActivity is
            // meant to show this same trace immediately, but if the
            // process dies before that Activity manages to draw (as
            // previously observed even with a delay added here - see
            // git history), this is what's shown on the *next* launch
            // instead, via showPreviousRunLog().
            val stackTrace = Log.getStackTraceString(throwable)
            appendLog("X: crash handler invoked, thread=${thread.name}\n$stackTrace")
            try {
                startActivity(
                    Intent(this, CrashReportActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        putExtra(CrashReportActivity.EXTRA_STACK_TRACE, stackTrace)
                    },
                )
                Process.killProcess(Process.myPid())
                exitProcess(10)
            } catch (t: Throwable) {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    // No google-services.json/plugin - see the BuildConfig fields' own
    // comment in app/build.gradle.kts for why. FirebaseOptions requires
    // applicationId and apiKey (verified against
    // firebase-common/.../FirebaseOptions.java in
    // github.com/firebase/firebase-android-sdk: the constructor calls
    // Preconditions.checkState on applicationId and the Builder's
    // setApiKey() calls checkNotEmpty() - both throw if blank);
    // projectId/gcmSenderId are technically optional there, but FCM's
    // HTTP v1 API is project-scoped, so both are supplied anyway rather
    // than relying on undocumented fallback behavior. Must run before
    // anything else in the app calls FirebaseMessaging.getInstance() -
    // Application.onCreate() is the earliest hook available.
    private fun initFirebase() {
        debugToast("2a: initFirebase start")
        val builder = FirebaseOptions.Builder()
        debugToast("2b: Builder() created")

        // Confirmed reproducible: dies between 2b and 2c-show - every
        // checkpoint up to and including 2c (plain static text, no
        // interpolation of the actual BuildConfig value) has been
        // reached; 2c-show (interpolates the real value into the toast
        // text) has not. bisectValue() below splits that further: is
        // *any* interpolation into a toast fatal at this point (2x-read,
        // which interpolates a safe Int - value.length), is evaluating the string
        // template with the real value fatal even before it reaches a
        // Toast/SharedPreferences call at all (2x-built), or is it
        // specifically persisting/displaying the real value that's fatal
        // (2x-show)? Applied to all four BuildConfig reads up front so a
        // Play-test round that gets further than projectId doesn't need
        // yet another cycle just to add the same split.
        val projectId = bisectValue("projectId", BuildConfig.FIREBASE_PROJECT_ID)
        builder.setProjectId(projectId)
        debugToast("2d: setProjectId done")

        val applicationId = bisectValue("applicationId", BuildConfig.FIREBASE_APPLICATION_ID)
        builder.setApplicationId(applicationId)
        debugToast("2f: setApplicationId done")

        val apiKey = bisectValue("apiKey", BuildConfig.FIREBASE_API_KEY)
        builder.setApiKey(apiKey)
        debugToast("2h: setApiKey done")

        val senderId = bisectValue("senderId", BuildConfig.FIREBASE_SENDER_ID)
        builder.setGcmSenderId(senderId)
        debugToast("2j: setGcmSenderId done")

        val options = builder.build()
        debugToast("2k: options built")
        FirebaseApp.initializeApp(this, options)
        debugToast("2l: FirebaseApp.initializeApp() returned")
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
