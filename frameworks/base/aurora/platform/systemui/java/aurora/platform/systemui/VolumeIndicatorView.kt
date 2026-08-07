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

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val radius = w / 2f
        val a = (alpha01 * 255f).toInt().coerceIn(0, 255)

        // Track: the full height, dim.
        trackPaint.color = Color.argb((a * 0.35f).toInt().coerceIn(0, 255), 255, 255, 255)
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, radius, radius, trackPaint)

        // Fill: grows upward from the bottom, because that is the direction a volume level means.
        // A zero-height fill still draws its rounded cap, so the indicator never looks broken at
        // silence - it looks empty, which is a different thing and the true one.
        val filled = (h * level).coerceAtLeast(0f)
        fillPaint.color = Color.argb(a, 255, 255, 255)
        rect.set(0f, h - filled, w, h)
        canvas.drawRoundRect(rect, radius, radius, fillPaint)
    }
}
