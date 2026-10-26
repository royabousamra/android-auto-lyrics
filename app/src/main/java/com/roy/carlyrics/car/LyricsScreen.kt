package com.roy.carlyrics.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import com.roy.carlyrics.R
import com.roy.carlyrics.lyrics.LyricsController
import com.roy.carlyrics.media.NowPlayingTracker

/**
 * The only screen. It uses NavigationTemplate purely to get the full-screen map surface, which
 * [SurfaceRenderer] paints with lyrics. The template itself only contributes a small action strip.
 */
class LyricsScreen(carContext: CarContext) : Screen(carContext) {

    @Suppress("unused") private val surfaceRenderer = SurfaceRenderer(carContext, lifecycle)

    override fun onGetTemplate(): Template {
        if (!NowPlayingTracker.hasNotificationAccess(carContext)) {
            return MessageTemplate.Builder(
                "Open Car Lyrics on your phone once and grant notification access so it can see what's playing."
            )
                .setTitle("Car Lyrics")
                .setHeaderAction(Action.APP_ICON)
                .addAction(
                    Action.Builder()
                        .setTitle("Check again")
                        .setOnClickListener {
                            NowPlayingTracker.start(carContext)
                            invalidate()
                        }
                        .build()
                )
                .build()
        }

        val strip = ActionStrip.Builder()
            .addAction(iconAction(R.drawable.ic_minus) { nudge(-250) })
            .addAction(iconAction(R.drawable.ic_plus) { nudge(250) })
            .addAction(iconAction(R.drawable.ic_refresh) {
                LyricsController.retry()
                CarToast.makeText(carContext, "Searching lyrics again", CarToast.LENGTH_SHORT).show()
            })
            .build()

        return NavigationTemplate.Builder()
            .setActionStrip(strip)
            .build()
    }

    private fun nudge(deltaMs: Long) {
        LyricsController.nudgeOffset(deltaMs)
        val ms = LyricsController.offsetMs.value
        val label = when {
            ms == 0L -> "Lyrics timing reset"
            ms > 0 -> "Lyrics %.2fs later".format(ms / 1000f)
            else -> "Lyrics %.2fs earlier".format(-ms / 1000f)
        }
        CarToast.makeText(carContext, label, CarToast.LENGTH_SHORT).show()
    }

    private fun iconAction(res: Int, onClick: () -> Unit): Action =
        Action.Builder()
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, res)).build())
            .setOnClickListener { onClick() }
            .build()
}
