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
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import aurora.runtime.animation.DefaultAnimationController
import aurora.sdk.animation.Animation
import aurora.sdk.animation.AnimationHandle
import aurora.sdk.animation.SpringSpec
import aurora.sdk.time.FrameTime
import com.android.systemui.plugins.PluginDependency
import com.android.systemui.plugins.VolumeDialog
import com.android.systemui.plugins.VolumeDialogController
import com.android.systemui.plugins.annotations.Requires
import com.android.systemui.plugins.annotations.Requirements

/**
 * Aurora's volume overlay, as a SystemUI plugin.
 *
 * ## Why this is a plugin and not a window from `system_server`
 *
 * Sprint 09 Task 2 found that `VolumeDialogComponent` registers AOSP's own dialog as a *default*
 * behind an `ExtensionController` extension point, and that a plugin supplying [VolumeDialog] causes
 * the old dialog to be `destroy()`ed and the new one `init`'d with the same window type. So there is
 * no second overlay to suppress and no upstream patch — the seam is AOSP's own.
 *
 * ## Where the volume state comes from, and why not from Aurora
 *
 * `VolumeDialogComponent.java:94`, one line before the extension point:
 *
 * ```java
 * pluginDependencyProvider.allowPluginDependency(VolumeDialogController.class);
 * ```
 *
 * SystemUI deliberately hands plugins its volume controller. So this reads state from
 * [VolumeDialogController] rather than from Aurora's own `VolumeService` — which is the honest
 * choice and an uncomfortable finding: **the first shipped Aurora feature does not use the service
 * Sprint 03 booted.** Feeding SystemUI's state through Aurora's service layer would be an
 * abstraction with no consumer, which Sprint 04.1 forbids. What `AuroraSystemService` is *for* is
 * now an open question and gets its own ADR rather than being answered by whichever process happened
 * to receive the feature.
 *
 * What Aurora *does* own here is the motion: [DefaultAnimationController] and a [SpringSpec], driven
 * by [PluginFrameScheduler] on SystemUI's main thread.
 */
@Requirements(
    Requires(target = VolumeDialog::class, version = VolumeDialog.VERSION),
    Requires(target = VolumeDialog.Callback::class, version = VolumeDialog.Callback.VERSION),
    Requires(target = VolumeDialogController::class, version = VolumeDialogController.VERSION),
)
class AuroraVolumeDialog : VolumeDialog {

    private var sysuiContext: Context? = null

    private var windowManager: WindowManager? = null
    private var view: VolumeIndicatorView? = null
    private var windowType: Int = 0
    private var shown: Boolean = false

    private val controller: DefaultAnimationController = DefaultAnimationController()
    private var scheduler: PluginFrameScheduler? = null

    /**
     * SystemUI's volume controller, held rather than re-fetched.
     *
     * Needed for three calls the first build omitted, each of which cost a visible bug:
     * [VolumeDialogController.getState] to be told the level at all, `notifyVisible` so the
     * controller knows something is on screen, and `userActivity` so it does not consider the user
     * idle while they are still pressing.
     */
    private var volume: VolumeDialogController? = null

    private var levelHandle: AnimationHandle? = null
    private var fadeHandle: AnimationHandle? = null
    private var widthHandle: AnimationHandle? = null

    /** Where the plugin's own resources live. Not [sysuiContext] - that resolves SystemUI's. */
    private var pluginContext: Context? = null

    /**
     * 0 = slim, 1 = wide. Animated once per appearance, and only downward.
     *
     * Starts at 1 and is reset to 1 on hide, so **every** appearance begins wide rather than the
     * first one growing into it and the rest arriving wide. The bar showing up at its final entrance
     * size is the iOS behaviour being copied; growing into it would be a second entrance animation
     * competing with the fade.
     */
    private var widthNow: Float = 1f

    private var frameIndex: Long = 0
    private var lastFrameNanos: Long = 0
    private var framePending: Boolean = false
    private var animationSeq: Int = 0

    /** Where the level currently is, so a replacement starts from the truth rather than from 0. */
    private var levelNow: Float = 0f
    private var alphaNow: Float = 0f

    private var slimPx: Float = 0f
    private var widePx: Float = 0f

    private val handler: Handler = Handler(Looper.getMainLooper())

    // ---------------------------------------------------------------- Plugin

    override fun onCreate(sysuiContext: Context, pluginContext: Context) {
        this.sysuiContext = sysuiContext
        // Both are handed over for a reason: sysuiContext reaches WindowManager and the display,
        // pluginContext reaches this APK's own res/. Using the wrong one for a drawable resolves
        // an id from SystemUI's table and returns something unrelated or nothing at all.
        this.pluginContext = pluginContext
        Log.i(TAG, "onCreate: Aurora's volume overlay is loaded inside SystemUI")
    }

    override fun onDestroy() {
        teardown()
        Log.i(TAG, "onDestroy")
    }

    // ---------------------------------------------------------- VolumeDialog

    override fun init(windowType: Int, callback: VolumeDialog.Callback) {
        this.windowType = windowType
        val context = sysuiContext ?: return

        windowManager = context.getSystemService(WindowManager::class.java)
        view = VolumeIndicatorView(context).also {
            it.alpha01 = 0f
            it.level = 0f
        }

        // Built here, on the thread init() runs on, because Choreographer is per-Looper and this
        // scheduler refuses postFrame from any other thread rather than silently starting a second
        // frame stream. Sprint 08 measured that this is the failure worth refusing.
        scheduler = PluginFrameScheduler()
        controller.start()

        val c = PluginDependency.get(this, VolumeDialogController::class.java)
        volume = c
        c.addCallback(callbacks, handler)

        // Ask. The first build only registered the callback and waited, which produced a track with
        // no fill: VolumeDialogController pushes onStateChanged for changes, and a plugin that never
        // asks for the current state has nothing to draw until something moves - and on the very
        // first press, "something moved" arrives after the window is already up.
        c.getState()

        Log.i(TAG, "init: windowType=$windowType, state requested")
    }

    override fun destroy() {
        teardown()
        Log.i(TAG, "destroy")
    }

    private fun teardown() {
        handler.removeCallbacks(dismissRunnable)
        runCatching { volume?.removeCallback(callbacks) }
        volume = null
        hideWindow()
        controller.stop()
        scheduler = null
        levelHandle = null
        fadeHandle = null
    }

    // ------------------------------------------------------ controller state

    private val callbacks = object : VolumeDialogController.Callbacks {

        override fun onShowRequested(reason: Int, keyguardLocked: Boolean, lockTaskModeState: Int) {
            val wasHidden = !shown
            showWindow()
            volume?.notifyVisible(true)
            volume?.getState()
            animate(target = levelNow, fadeTo = 1f)
            // Wide on arrival, then narrow — and only on arrival. A press while the bar is already
            // up must not widen it again: the widening is an entrance, not a reaction.
            if (wasHidden) {
                animateWidth(1f)
                scheduleNarrow()
            }
            scheduleDismiss()
        }

        override fun onDismissRequested(reason: Int) {
            beginDismiss()
        }

        override fun onStateChanged(state: VolumeDialogController.State) {
            val stream = state.activeStream
            if (stream == VolumeDialogController.State.NO_ACTIVE_STREAM) return
            val s = state.states.get(stream) ?: return
            val span = (s.levelMax - s.levelMin).toFloat()
            val fraction = if (span > 0f) (s.level - s.levelMin) / span else 0f
            view?.icon = iconFor(stream)
            // Width is not mentioned here on purpose. Whatever the entrance or the narrow started
            // keeps running; a state change has no opinion about how wide the bar is.
            animate(target = fraction.coerceIn(0f, 1f), fadeTo = if (shown) 1f else alphaNow)
            // Each change is the user still acting, so the DISMISS clock restarts. The NARROW clock
            // does not - the bar widens once and stays slim however long the user keeps pressing.
            if (shown) scheduleDismiss()
        }

        override fun onLayoutDirectionChanged(layoutDirection: Int) = Unit
        override fun onConfigurationChanged() = Unit
        override fun onShowVibrateHint() = Unit
        override fun onShowSilentHint() = Unit
        override fun onScreenOff() {
            // Sprint 08 left an open question about what a stopped frame source does to its
            // timestamps. Nothing here animates while the screen is off; the window is removed.
            hideWindow()
        }
        override fun onShowSafetyWarning(flags: Int) = Unit
        override fun onAccessibilityModeChanged(showA11yStream: Boolean?) = Unit
        override fun onCaptionComponentStateChanged(
            isComponentEnabled: Boolean?,
            fromTooltip: Boolean?,
        ) = Unit
        override fun onCaptionEnabledStateChanged(
            isEnabled: Boolean?,
            checkForSwitchState: Boolean?,
        ) = Unit

        // Named by the compiler, not by the survey - which read only lines 199-215 of the interface
        // and therefore saw a Callbacks with two methods missing. Left as no-ops deliberately: CSD
        // is a hearing-safety warning with its own dialog, and reacting to a key press is what
        // onStateChanged already covers by the time the level has actually changed.
        override fun onShowCsdWarning(csdWarning: Int, durationMs: Int) = Unit
        override fun onVolumeChangedFromKey() = Unit
    }

    // ------------------------------------------------------------- animation

    /**
     * Replaces whatever is running, from where it is.
     *
     * `volume-overlay.md` §4 requires that a run of presses leaves the overlay visibly one object:
     * no replayed entrance, the indicator moving from where it stands, no blink when a press
     * interrupts a fade. That is why `from` is [levelNow] and [alphaNow] rather than a constant —
     * the continuity is in the `from`, not in a special case.
     */
    private fun animate(target: Float, fadeTo: Float) {
        val seq = ++animationSeq
        levelHandle = controller.animator.play(
            Animation(
                name = "aurora.volume.level.$seq",
                spec = SpringSpec(),
                from = levelNow,
                to = target,
            )
        )
        fadeHandle = controller.animator.play(
            Animation(
                name = "aurora.volume.fade.$seq",
                spec = SpringSpec(),
                from = alphaNow,
                to = fadeTo,
            )
        )
        requestFrame()
    }

    /**
     * Width has its own life, and this separation is a bug fix rather than tidiness.
     *
     * The first version passed `widthTo` through [animate] and every state change re-targeted the
     * width to `widthNow` — "leave it where it is". That reads as harmless and is not: five key
     * presses arrive within a few milliseconds, **before any frame has ticked**, so `widthNow` is
     * still ≈0 and the entrance's 0→1 gets replaced four times by 0→0. The bar never widened, and
     * the code that did it looked like the code that preserved it.
     *
     * *Leave it alone* and *re-target it to its current value* are different instructions. Only one
     * of them survives a value that has not been sampled yet.
     */
    private fun animateWidth(to: Float) {
        widthHandle = controller.animator.play(
            Animation(
                name = "aurora.volume.width.${++animationSeq}",
                spec = SpringSpec(),
                from = widthNow,
                to = to,
            )
        )
        requestFrame()
    }

    /**
     * The overlay dismisses itself, because nothing else will.
     *
     * The first build assumed [VolumeDialogController.onDismissRequested] would arrive on a timer and
     * the overlay simply never went away. It does not: the controller sends that for *reasons* —
     * screen off, a touch outside, an explicit dismissal — and `VolumeDialogImpl` runs its own
     * `Handler` for the idle case. **Owning the surface means owning its lifetime**, which is a
     * consequence of replacing the dialog that Task 2's survey did not surface.
     */
    private fun scheduleDismiss() {
        handler.removeCallbacks(dismissRunnable)
        handler.postDelayed(dismissRunnable, DISMISS_DELAY_MS)
    }

    private val dismissRunnable = Runnable { beginDismiss() }

    private fun beginDismiss() {
        handler.removeCallbacks(dismissRunnable)
        volume?.notifyVisible(false)
        // Fades from where it is, not from 1. A dismissal that interrupts an entrance must not jump
        // to full opacity first - volume-overlay.md §4's "no blink when a press interrupts a fade".
        animate(target = levelNow, fadeTo = 0f)
    }

    /**
     * Narrows once, shortly after the entrance.
     *
     * NOT rescheduled on later presses, and that is the requested behaviour rather than an
     * omission: the wide form exists to say *which* stream is being changed, which is information
     * the first press carries and the fifth does not.
     */
    private fun scheduleNarrow() {
        handler.removeCallbacks(narrowRunnable)
        handler.postDelayed(narrowRunnable, NARROW_DELAY_MS)
    }

    private val narrowRunnable = Runnable { animateWidth(0f) }

    /** Which stream is being changed, as a picture. Falls back to media rather than to nothing. */
    private fun iconFor(stream: Int): android.graphics.drawable.Drawable? {
        val ctx = pluginContext ?: return null
        val id = when (stream) {
            AudioManager.STREAM_RING, AudioManager.STREAM_NOTIFICATION -> R.drawable.ic_aurora_ring
            AudioManager.STREAM_ALARM -> R.drawable.ic_aurora_alarm
            AudioManager.STREAM_VOICE_CALL -> R.drawable.ic_aurora_call
            else -> R.drawable.ic_aurora_media
        }
        return ctx.getDrawable(id)
    }

    private fun requestFrame() {
        if (framePending) return
        val s = scheduler ?: return
        framePending = true
        s.postFrame { nanos -> onFrame(nanos) }
    }

    private fun onFrame(nanos: Long) {
        framePending = false
        val delta = if (lastFrameNanos == 0L) 0L else (nanos - lastFrameNanos).coerceAtLeast(0L)
        lastFrameNanos = nanos
        controller.tick(FrameTime(nanos, delta, frameIndex++))

        levelHandle?.let { levelNow = it.value }
        fadeHandle?.let { alphaNow = it.value }
        widthHandle?.let { widthNow = it.value }

        view?.let {
            it.level = levelNow
            it.alpha01 = alphaNow
            it.trackWidthPx = slimPx + (widePx - slimPx) * widthNow
            // The icon belongs to the wide form. It leaves faster than the width so it is gone
            // before the pill is too narrow to hold it, rather than being clipped on the way out.
            it.iconAlpha01 = (widthNow * ICON_FADE_GAIN).coerceIn(0f, 1f)
        }

        val running = (levelHandle?.isRunning == true) ||
            (fadeHandle?.isRunning == true) ||
            (widthHandle?.isRunning == true)
        if (running) {
            requestFrame()
        } else if (alphaNow <= 0.01f) {
            // Faded out and nothing left to move: the window goes away rather than sitting there
            // invisible and holding a surface.
            hideWindow()
        }
    }

    // ---------------------------------------------------------------- window

    private fun showWindow() {
        if (shown) return
        val wm = windowManager ?: return
        val v = view ?: return
        val density = v.resources.displayMetrics.density
        slimPx = SLIM_DP * density
        widePx = WIDE_DP * density
        v.trackWidthPx = slimPx + (widePx - slimPx) * widthNow
        val lp = WindowManager.LayoutParams(
            // The window is always the WIDE size. Narrowing happens inside it, so no layout pass
            // and no surface resize lands on the animation's critical path.
            (WIDE_DP * density).toInt(),
            (HEIGHT_DP * density).toInt(),
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = (MARGIN_DP * density).toInt()
            title = "AuroraVolume"
            // The window must not animate itself. Every pixel of motion here comes from Aurora's
            // runtime, and a system window animation on top of it would be two motions arguing.
            windowAnimations = 0
        }
        runCatching { wm.addView(v, lp) }
            .onFailure { Log.e(TAG, "addView failed", it) }
            .onSuccess {
                shown = true
                Log.i(TAG, "window added: type=$windowType")
            }
    }

    private fun hideWindow() {
        handler.removeCallbacks(dismissRunnable)
        handler.removeCallbacks(narrowRunnable)
        // Reset to the wide form so the next appearance is an entrance again rather than resuming
        // wherever the last one stopped. The handle goes with it: a finished 1→0 animation left in
        // place would write 0 back over this on the very next frame, and the reset would look like
        // it had never happened.
        widthNow = 1f
        widthHandle = null
        if (!shown) return
        val wm = windowManager ?: return
        val v = view ?: return
        runCatching { wm.removeViewImmediate(v) }
            .onFailure { Log.e(TAG, "removeView failed", it) }
        shown = false
        lastFrameNanos = 0
        volume?.notifyVisible(false)
    }

    private companion object {
        const val TAG = "AuroraVolume"

        /** A slim vertical bar. One shape, one number, per Sprint 09's third criterion. */
        const val SLIM_DP = 10f

        /** Wide enough to hold a legible icon, which is the only reason the wide form exists. */
        const val WIDE_DP = 34f

        const val HEIGHT_DP = 160f
        const val MARGIN_DP = 12f

        /**
         * How long the overlay stays after the last change.
         *
         * Aurora's number, not AOSP's, and deliberately not read from
         * `config_volumeDialogTimeout`: that resource governs a dialog Aurora replaced, and
         * inheriting it would make this look like a value someone chose for this surface.
         *
         * 1.5s rather than 2.5s, because 2.5s was tried on a device and read as slow.
         */
        const val DISMISS_DELAY_MS = 1_500L

        /** How long the wide form is held before narrowing. Long enough to read the icon. */
        const val NARROW_DELAY_MS = 550L

        /** Makes the icon finish fading before the pill finishes narrowing. */
        const val ICON_FADE_GAIN = 1.8f
    }
}
