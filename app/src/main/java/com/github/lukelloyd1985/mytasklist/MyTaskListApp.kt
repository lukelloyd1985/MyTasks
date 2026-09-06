package com.github.lukelloyd1985.mytasklist

import android.app.Application
import android.content.Intent
import android.os.Handler
import android.os.Looper
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
        private const val LAST_CHECKPOINT_KEY = "last_checkpoint"
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
        showLastCheckpoint()
        debugToast("1: App.onCreate start")
        installCrashHandler()
        debugToast("2: crash handler installed")
        initFirebase()
        debugToast("3: initFirebase() returned")
        NotificationHelper.createChannel(this)
        debugToast("4: App.onCreate complete")
    }

    // Whatever kills this process (even something below Java exception
    // handling) happens well before a LENGTH_SHORT toast queued behind
    // several others would finish displaying, so there's no reliable way
    // to read the *last* checkpoint reached in the same run it's shown.
    // Persisting it here (commit(), not apply() - must be durable before
    // this function returns, since the process may not survive much
    // longer) and showing it back on the *next* launch, alone and with
    // LENGTH_LONG, removes the timing problem entirely.
    private fun showLastCheckpoint() {
        val last = debugPrefs().getString(LAST_CHECKPOINT_KEY, null)
        if (last != null) {
            Toast.makeText(this, "PREVIOUS RUN reached: $last", Toast.LENGTH_LONG).show()
        }
    }

    private fun debugPrefs() = getSharedPreferences("debug", MODE_PRIVATE)

    private fun debugToast(message: String) {
        debugPrefs().edit().putString(LAST_CHECKPOINT_KEY, message).commit()
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
            // TEMPORARY diagnostic: fires unconditionally the instant this
            // handler runs, before anything else below, on the main
            // thread regardless of which thread actually crashed - so we
            // find out in the same test round whether this is even a
            // catchable Kotlin/Java exception this time, rather than
            // needing a separate upload+test cycle just to check.
            val crashMessage = "X: crash handler invoked, thread=${thread.name}, throwable=${throwable::class.java.name}: ${throwable.message}"
            debugPrefs().edit().putString(LAST_CHECKPOINT_KEY, crashMessage).commit()
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this, "DEBUG $crashMessage", Toast.LENGTH_LONG).show()
            }
            try {
                val stackTrace = Log.getStackTraceString(throwable)
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
