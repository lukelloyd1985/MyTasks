package com.github.lukelloyd1985.mytasklist

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * TEMPORARY diagnostic screen for the Play-Store-install-only startup
 * crash (see MyTaskListApp's checkpoint bisection). A Toast truncates
 * long text, disappears in ~2s, and can't be selected/copied - none of
 * which are acceptable when the interesting content is a full stack
 * trace or a long run-by-run checkpoint log. This is the same
 * dependency-free approach as CrashReportActivity (no Compose, no app
 * theme) for the same reason: it must stand a chance of rendering even
 * if something Compose-related is what's unstable, and there's no way
 * to pull logcat without adb.
 */
class DebugLogActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val log = intent.getStringExtra(EXTRA_LOG).orEmpty()

        val textView = TextView(this).apply {
            text = log
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
            movementMethod = ScrollingMovementMethod()
            setPadding(32, 32, 32, 32)
        }
        val shareButton = Button(this).apply {
            text = "Share log"
            setOnClickListener {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, log)
                }
                startActivity(Intent.createChooser(shareIntent, null))
            }
        }
        val continueButton = Button(this).apply {
            text = "Continue to app"
            setOnClickListener { finish() }
        }
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(shareButton)
            addView(continueButton)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(buttonRow)
            addView(
                textView,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f },
            )
        }
        setContentView(layout)
    }

    companion object {
        const val EXTRA_LOG = "log"
    }
}
