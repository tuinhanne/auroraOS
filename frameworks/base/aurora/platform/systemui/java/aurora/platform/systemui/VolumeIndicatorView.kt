/*
 * Copyright (C) 2026 The AuroraOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package aurora.platform.systemui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View

/**
 * The first thing Aurora draws.
 *
 * ## One shape and one number
 *
 * Sprint 09 chose the volume overlay over a notification chip on the criterion *one surface, not a
 * layout system* — a chip carries an icon, a label and probably progress, and would have made the
 * first pixel also the first view hierarchy. So this is a single [View] with `onDraw` and no
 * children: a rounded track, a filled portion, and nothing else.
 *
 * It has no opinion about volume. [level] and [alpha01] are set from outside, every frame, by
 * whatever is driving the animation. A view that read `AudioManager` itself would be a second source
 * of truth for a number SystemUI already owns.
 */
internal class VolumeIndicatorView(context: Context) : View(context) {

    /** 0..1, the filled fraction. Set per frame; the view never computes it. */
    var level: Float = 0f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (clamped != field) {
                field = clamped
                invalidate()
            }
        }

    /** 0..1, the whole indicator's opacity. Separate from [level] so entrance and value can differ. */
    var alpha01: Float = 1f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (clamped != field) {
                field = clamped
                invalidate()
            }
        }

    /**
     * Track width in pixels, animated between slim and wide.
     *
     * The window is a constant width and this narrows inside it, rather than the window resizing.
     * A `WindowManager.updateViewLayout` per frame would put a layout pass and a surface resize on
     * the animation's critical path for a shape that could simply be drawn smaller.
     */
    var trackWidthPx: Float = 0f
        set(value) {
            if (value != field) {
                field = value
                invalidate()
            }
        }

    /**
     * Track height in pixels, animated with the width.
     *
     * The wide form is taller as well as fatter, so the entrance reads as one gesture rather than a
     * bar that got fatter. The window is sized for the tall form and this shrinks inside it.
     */
    var trackHeightPx: Float = 0f
        set(value) {
            if (value != field) {
                field = value
                invalidate()
            }
        }

    /** Shown while the track is wide, gone once it narrows. Owned by the caller. */
    var icon: Drawable? = null
        set(value) {
            if (value !== field) {
                field = value
                invalidate()
            }
        }

    /** 0..1, separate from [alpha01] so the icon can leave before the bar does. */
    var iconAlpha01: Float = 0f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (clamped != field) {
                field = clamped
                invalidate()
            }
        }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val tw = trackWidthPx
        val th = trackHeightPx
        if (tw <= 0f || th <= 0f) return

        // RIGHT-ALIGNED, not centred. The window sits a fixed margin from the screen edge and the
        // track's right edge sits on the window's, so widening extends inward and the gap at the
        // edge never changes. Centring inside the wide window made the bar appear to slide away
        // from the edge as it grew, which reads as the whole thing moving rather than growing.
        val right = width.toFloat()
        val left = right - tw

        // Vertically centred, so the taller wide form grows from the middle in both directions.
        val top = (height - th) / 2f
        val bottom = top + th

        val radius = tw / 2f
        val a = (alpha01 * 255f).toInt().coerceIn(0, 255)

        trackPaint.color = Color.argb((a * 0.35f).toInt().coerceIn(0, 255), 255, 255, 255)
        rect.set(left, top, right, bottom)
        canvas.drawRoundRect(rect, radius, radius, trackPaint)

        // Fill: grows upward from the bottom, because that is the direction a volume level means.
        // A zero-height fill still draws its rounded cap, so the indicator never looks broken at
        // silence - it looks empty, which is a different thing and the true one.
        val fillTop = bottom - (th * level).coerceAtLeast(0f)
        fillPaint.color = Color.argb(a, 255, 255, 255)
        rect.set(left, fillTop, right, bottom)
        canvas.drawRoundRect(rect, radius, radius, fillPaint)

        drawIcon(canvas, left, tw, bottom, fillTop)
    }

    /**
     * The stream icon, near the bottom of the track.
     *
     * Its colour is decided by what is behind it rather than fixed: white on the dim track, dark on
     * the white fill. A single colour would vanish at one end of the range - which is the kind of
     * thing that only shows up when someone actually turns the volume down.
     */
    private fun drawIcon(canvas: Canvas, left: Float, tw: Float, bottom: Float, fillTop: Float) {
        val d = icon ?: return
        val ia = (iconAlpha01 * alpha01 * 255f).toInt().coerceIn(0, 255)
        if (ia == 0) return

        val size = (tw * ICON_FRACTION)
        val cx = left + tw / 2f
        val cy = bottom - tw / 2f - (tw - size) / 2f
        val half = size / 2f

        d.setBounds(
            (cx - half).toInt(),
            (cy - half).toInt(),
            (cx + half).toInt(),
            (cy + half).toInt(),
        )
        d.setTint(if (cy > fillTop) ON_FILL else ON_TRACK)
        d.alpha = ia
        d.draw(canvas)
    }

    private companion object {
        /** Icon size as a fraction of the track width, leaving a visible margin inside the pill. */
        const val ICON_FRACTION = 0.55f

        /** Dark enough to read against the white fill. */
        val ON_FILL = Color.argb(255, 26, 26, 26)

        /** White, for the dim track when the level is below the icon. */
        val ON_TRACK = Color.WHITE
    }
}
