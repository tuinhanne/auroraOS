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
package aurora.feature.volume

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.CornerPathEffect
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/**
 * The first thing Aurora draws.
 *
 * ## One shape and one number
 *
 * Sprint 09 chose the volume overlay over a notification chip on the criterion *one surface, not a
 * layout system*. So this is a single [View] with `onDraw` and no children: a rounded track, a
 * filled portion, and a speaker drawn from primitives.
 *
 * It has no opinion about volume. Every field is set from outside, per frame, by whatever is driving
 * the animation. A view that read `AudioManager` itself would be a second source of truth for a
 * number SystemUI already owns.
 */
internal class VolumeIndicatorView(context: Context) : View(context) {

    /** 0..1, the filled fraction. Set per frame; the view never computes it. */
    var level: Float = 0f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (clamped != field) { field = clamped; invalidate() }
        }

    /** 0..1, the whole indicator's opacity. Separate from [level] so entrance and value can differ. */
    var alpha01: Float = 1f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (clamped != field) { field = clamped; invalidate() }
        }

    /** Track width in pixels, animated between slim and wide. */
    var trackWidthPx: Float = 0f
        set(value) { if (value != field) { field = value; invalidate() } }

    /** Track height in pixels, animated with the width. */
    var trackHeightPx: Float = 0f
        set(value) { if (value != field) { field = value; invalidate() } }

    /** 0..1, how much of the icon is shown. It belongs to the wide form and leaves before it. */
    var iconAlpha01: Float = 0f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (clamped != field) { field = clamped; invalidate() }
        }

    /**
     * 0..1, how far the mute slash is drawn across the speaker.
     *
     * Animated rather than toggled, because muting is a state change a person performs and watching
     * the line arrive is what makes it read as *their* action rather than as the icon being swapped
     * for a different icon. At 0 there is no slash at all, so it costs nothing when unmuted.
     */
    var slash01: Float = 0f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (clamped != field) { field = clamped; invalidate() }
        }

    /**
     * The glyph for streams that are not media, or `null` for media.
     *
     * `volume-overlay.md` section 5 requires that a change of *subject* be legible, not just a change
     * of number - adjusting the ringer when you meant media is a mistake people make constantly. So
     * ring, alarm and call keep their own drawables. Media has none, and is drawn as a speaker whose
     * waves follow the level, which no fixed asset could do.
     */
    var icon: android.graphics.drawable.Drawable? = null
        set(value) { if (value !== field) { field = value; invalidate() } }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /**
     * The speaker's body. Its corners are rounded by a path effect rather than by hand.
     *
     * A cone drawn as six straight segments has six sharp points, and at this size they read as
     * crude rather than as precise - reported from a device as the icon looking rough. A corner
     * radius applied to the whole path softens every joint at once, including the two acute ones
     * at the cone's mouth where hand-rounding is most fiddly.
     */
    private val iconFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val iconStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val rect = RectF()
    private val arcRect = RectF()

    /** The track's outline and the speaker's body. Reused so onDraw allocates nothing. */
    private val clip = Path()
    private val cone = Path()

    private val density = context.resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        val tw = trackWidthPx
        val th = trackHeightPx
        if (tw <= 0f || th <= 0f) return

        // Right-aligned: the window sits a fixed margin from the screen edge and the track's right
        // edge sits on the window's, so widening extends inward and the gap never changes.
        val right = width.toFloat()
        val left = right - tw
        val top = (height - th) / 2f
        val bottom = top + th
        val radius = tw / 2f
        val a = (alpha01 * 255f).toInt().coerceIn(0, 255)

        rect.set(left, top, right, bottom)
        trackPaint.color = Color.argb((a * 0.35f).toInt().coerceIn(0, 255), 255, 255, 255)
        canvas.drawRoundRect(rect, radius, radius, trackPaint)

        // Fill: grows upward from the bottom, and is clipped to the track rather than rounded on its
        // own. Canvas clamps a corner radius to half the rect height, so a short fill drawn as its
        // own rounded rect gets squarer corners than the track and pokes out past its curve.
        val filled = (th * level).coerceAtLeast(0f)
        if (filled >= 1f) {
            clip.rewind()
            clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clip)
            rect.set(left, bottom - filled, right, bottom)
            fillPaint.color = Color.argb(a, 255, 255, 255)
            canvas.drawRect(rect, fillPaint)
            canvas.restore()
        }
        // Below a pixel nothing is drawn at all: a zero-height rounded rect does not vanish, it
        // becomes a degenerate lens, and an empty bar kept a bright smudge at the bottom.

        drawSpeaker(canvas, left, tw, bottom, bottom - filled, a)
    }

    /**
     * A speaker, with as many waves as the level deserves.
     *
     * Drawn from primitives rather than loaded as a vector, because the wave count is a function of
     * the level and a drawable would need one asset per state. Three arcs, revealed in turn, and
     * none at all at silence - so the icon says roughly what the bar says, and still says it when
     * the bar is too short to read at a glance.
     */
    private fun drawSpeaker(
        canvas: Canvas,
        left: Float,
        tw: Float,
        bottom: Float,
        fillTop: Float,
        a: Int,
    ) {
        val ia = (iconAlpha01 * alpha01 * 255f).toInt().coerceIn(0, 255)
        if (ia == 0) return

        val s = tw * ICON_FRACTION
        val h = s / 2f
        val cy = bottom - tw / 2f - (tw - s) / 2f

        // How many waves the level deserves. Computed here rather than at the draw site because the
        // glyph's width depends on it, and the glyph has to be centred as a whole.
        val waves = if (icon != null || slash01 >= 0.5f) 0 else when {
            level <= 0.02f -> 0
            level < 0.40f -> 1
            level < 0.75f -> 2
            else -> 3
        }

        // Centre the WHOLE glyph, not the cone.
        //
        // The speaker is asymmetric: its body runs from -0.95h to +0.05h and the waves extend to the
        // right. With three waves the pair balances; with none, centring the cone alone leaves the
        // icon visibly pushed left - reported as the speaker not sitting in the middle when the
        // waves are gone. So the extent is measured including whatever waves are present, and the
        // glyph is shifted to put THAT centre on the track's.
        val rightMost = if (waves == 0) h * 0.05f else h * (0.05f + 0.45f + 0.30f * (waves - 1))
        val cx = left + tw / 2f - (h * -0.95f + rightMost) / 2f

        // Dark over the white fill, white over the dim track. A fixed colour vanishes at one end of
        // the range, which only shows up when someone actually turns the volume down.
        val tint = if (cy > fillTop) ON_FILL else ON_TRACK
        iconFill.color = tint
        iconFill.alpha = ia
        iconStroke.color = tint
        iconStroke.alpha = ia
        iconStroke.strokeWidth = 1.6f * density

        val d = icon
        if (d != null) {
            // A stream with its own glyph: ring, alarm or call. No waves - those belong to the
            // speaker, and a bell with sound waves would be inventing a symbol nobody knows.
            d.setBounds(
                (cx - h).toInt(), (cy - h).toInt(), (cx + h).toInt(), (cy + h).toInt(),
            )
            d.setTint(tint)
            d.alpha = ia
            d.draw(canvas)
        } else {
            // Body: a small box on the left opening into a cone on the right.
            cone.rewind()
            cone.moveTo(cx - h * 0.95f, cy - h * 0.30f)
            cone.lineTo(cx - h * 0.45f, cy - h * 0.30f)
            cone.lineTo(cx + h * 0.05f, cy - h * 0.85f)
            cone.lineTo(cx + h * 0.05f, cy + h * 0.85f)
            cone.lineTo(cx - h * 0.45f, cy + h * 0.30f)
            cone.lineTo(cx - h * 0.95f, cy + h * 0.30f)
            cone.close()
            iconFill.pathEffect = cornerSoftening(h)
            canvas.drawPath(cone, iconFill)
            iconFill.pathEffect = null

            // Waves, and the slash replaces them rather than crossing them.
            //
            // A slash drawn over three arcs is two symbols competing in one glyph, and the arcs win
            // on ink. Silence has no waves anyway, so hiding them while the slash arrives is also
            // the honest picture: nothing is coming out of the speaker.
            //
            // Thresholds rather than a continuous count, because an arc that fades in over one
            // volume step reads as a rendering artefact rather than as a third wave arriving.
            for (i in 0 until waves) {
                val r = h * (0.45f + 0.30f * i)
                arcRect.set(cx + h * 0.05f - r, cy - r, cx + h * 0.05f + r, cy + r)
                canvas.drawArc(arcRect, -50f, 100f, false, iconStroke)
            }
        }

        if (slash01 <= 0f) return

        // Top-left to bottom-right, and it grows rather than appearing - muting reads as a stroke
        // being drawn rather than as one icon being swapped for another.
        val x0 = cx - h * 1.05f
        val y0 = cy - h * 1.05f
        val x1 = cx + h * 1.05f
        val y1 = cy + h * 1.05f
        iconStroke.strokeWidth = 2.2f * density
        canvas.drawLine(x0, y0, x0 + (x1 - x0) * slash01, y0 + (y1 - y0) * slash01, iconStroke)
    }

    /** Reused per size, because allocating a path effect every frame would be per-frame garbage. */
    private var softeningFor: Float = -1f
    private var softening: CornerPathEffect? = null

    private fun cornerSoftening(h: Float): CornerPathEffect {
        if (h != softeningFor || softening == null) {
            softeningFor = h
            softening = CornerPathEffect(h * CORNER_SOFTNESS)
        }
        return softening!!
    }

    private companion object {
        /** How much of the speaker's half-height becomes corner radius. */
        const val CORNER_SOFTNESS = 0.22f

        /** Icon size as a fraction of the track width, leaving a visible margin inside the pill. */
        const val ICON_FRACTION = 0.55f

        /** Dark enough to read against the white fill. */
        val ON_FILL = Color.argb(255, 26, 26, 26)

        /** White, for the dim track when the level is below the icon. */
        val ON_TRACK = Color.WHITE
    }
}
