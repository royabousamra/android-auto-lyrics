package com.roy.carlyrics

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.roy.carlyrics.lyrics.LyricsController
import com.roy.carlyrics.media.NowPlayingTracker
import com.roy.carlyrics.render.KaraokeView

/**
 * Phone screen: one-time setup (notification access) plus a live preview of the karaoke view,
 * handy for checking sync without sitting in the car.
 */
class MainActivity : Activity() {

    private lateinit var setupCard: LinearLayout
    private lateinit var offsetLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(16, 16, 24))
            fitsSystemWindows = true
        }

        setupCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.rgb(40, 30, 70))
            }
            addView(text("One-time setup", 20f, bold = true))
            addView(text(
                "Car Lyrics reads what's playing from your music apps' media controls. " +
                    "Android only allows that for apps with notification access. " +
                    "Lyrics are looked up on lrclib.net; nothing else leaves your phone.",
                14f,
            ).apply { setPadding(0, dp(8), 0, dp(12)) })
            addView(Button(context).apply {
                text = "Grant notification access"
                setOnClickListener {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
            })
        }
        root.addView(setupCard, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            setMargins(dp(16), dp(16), dp(16), 0)
        })

        root.addView(KaraokeView(this), LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(12))
        }
        offsetLabel = text("", 14f).apply { gravity = Gravity.CENTER }
        controls.addView(Button(this).apply {
            text = "Earlier"
            setOnClickListener { LyricsController.nudgeOffset(-250); updateOffset() }
        })
        controls.addView(offsetLabel, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        controls.addView(Button(this).apply {
            text = "Later"
            setOnClickListener { LyricsController.nudgeOffset(250); updateOffset() }
        })
        controls.addView(Button(this).apply {
            text = "Retry"
            setOnClickListener { LyricsController.retry() }
        })
        root.addView(controls, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        val granted = NowPlayingTracker.hasNotificationAccess(this)
        if (granted) NowPlayingTracker.start(this)
        setupCard.visibility = if (granted) View.GONE else View.VISIBLE
        updateOffset()
    }

    private fun updateOffset() {
        val ms = LyricsController.offsetMs.value
        offsetLabel.text = if (ms == 0L) "Sync offset: 0" else "Sync offset: %+.2fs".format(ms / 1000f)
    }

    private fun text(s: String, sp: Float, bold: Boolean = false) = TextView(this).apply {
        text = s
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
