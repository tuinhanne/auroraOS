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
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import aurora.runtime.animation.DefaultAnimationController
import aurora.sdk.animation.Animation
import aurora.sdk.animation.AnimationHandle
import aurora.sdk.animation.SpringSpec
import aurora.sdk.design.Spring
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
 * no second overlay to suppress and no upstream patch  -  the seam is AOSP's own.
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
 * [VolumeDialogController] rather than from Aurora's own `VolumeService`  -  which is the honest
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
    private var slashHandle: AnimationHandle? = null

    /** Where the plugin's own drawables live. Not [sysuiContext] - that resolves SystemUI's. */
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

    /**
     * The largest frame time already handed to the controller.
     *
     * Separate from [lastFrameNanos], which is reset when the window hides so the next appearance
     * starts a fresh delta. This one must NOT reset: the controller's timeline outlives the window,
     * and handing it an older stamp after a hide/show cycle is the same violation.
     */
    private var lastTickNanos: Long = 0

    /** When the current fade began, for the log that measures it. 0 when no fade is running. */
    private var dismissStartedAt: Long = 0L
    private var framePending: Boolean = false
    private var animationSeq: Int = 0

    /** Where the level currently is, so a replacement starts from the truth rather than from 0. */
    private var levelNow: Float = 0f
    private var alphaNow: Float = 0f

    private var slimWPx: Float = 0f
    private var wideWPx: Float = 0f
    private var slimHPx: Float = 0f
    private var wideHPx: Float = 0f

    /** True from ACTION_DOWN to ACTION_UP. While it is set, the finger owns the level, not a spring. */
    private var dragging: Boolean = false

    /** The stream being dragged, and the range to map a y coordinate into. From onStateChanged. */
    private var activeStream: Int = -1
    private var levelMin: Int = 0
    private var levelMax: Int = 0

    /** Mirrors the active stream's mute state, so a change in it can be told from a repeat of it. */
    private var mutedNow: Boolean = false

    /** 0 = no slash, 1 = fully struck through. Animated so muting reads as a stroke being drawn. */
    private var slashNow: Float = 0f

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
            // The touch listener lives here rather than in the view, so the view stays a surface that
            // draws what it is told and holds no policy. It has no opinion about volume; giving it
            // one would put the level in two places.
            it.setOnTouchListener { _, event -> onTouch(event) }
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
            // Wide on arrival, then narrow  -  and only on arrival. A press while the bar is already
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
            activeStream = stream
            levelMin = s.levelMin
            levelMax = s.levelMax
            view?.icon = iconFor(stream)

            // Mute is a state, so the indicator for it has to be one that persists. The bar's wide
            // form is where indicators live, so a muted bar stays wide instead of narrowing away from
            // the only thing saying it is silent. section 6, and the reason it is not simply an icon swap:
            // the icon fades out below 35% width, which is where the bar spends most of its life.
            val wasMuted = mutedNow
            mutedNow = s.muted
            applyMuteToView()
            if (mutedNow != wasMuted) {
                if (mutedNow) {
                    handler.removeCallbacks(narrowRunnable)
                    animateWidth(1f)
                } else if (shown) {
                    scheduleNarrow()
                }
            }

            // While a finger is down it owns the level. Every setStreamVolume during a drag comes back
            // here as a state change, and re-animating toward it would put a spring between the finger
            // and the bar - which is exactly the lag a direct control must not have.
            if (dragging) {
                // Deliberately does NOT arm the dismiss timer.
                //
                // It used to. Every setStreamVolume during a drag returns here, so the timer was
                // re-armed while the finger was still down - and a finger that then held still for
                // 1.5s got the overlay fading underneath it. Releasing afterwards looked like the
                // bar vanishing instantly, because it had already been fading for a while.
                //
                // ACTION_DOWN cancels the timer and ACTION_UP arms it. Nothing between them should:
                // a finger on the bar is an interaction for as long as it is there, however still
                // it is.
                return
            }

            val span = (s.levelMax - s.levelMin).toFloat()
            val fraction = if (span > 0f) (s.level - s.levelMin) / span else 0f
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

    // ----------------------------------------------------------------- touch

    /**
     * The finger sets the level directly.
     *
     * `volume-overlay.md` section 9 deferred touch as *"a second feature and a different set of questions"*.
     * Four of those questions are answered here, and each answer is a decision rather than a default:
     *
     * - **The width goes wide, as on a first press.** Requested behaviour: a touch is the start of an
     *   interaction, and the wide form is what says which stream it is about to change.
     * - **No spring while dragging.** The level is assigned, not animated. A spring chasing a finger
     *   is lag, and lag in a direct control reads as the phone being slow rather than as motion.
     * - **The dismiss delay stops, rather than restarting.** A finger held still for three seconds is
     *   still an interaction; a restarting timer would take the overlay away underneath it.
     * - **Out of range clamps**, and the overlay stays up. section 7's reason applies to a drag as much as to
     *   a press: no feedback at all is indistinguishable from failure.
     *
     * Touches outside the window fall through to whatever is behind, which `FLAG_NOT_TOUCH_MODAL`
     * already arranged - the overlay is a control now, but only where it is drawn.
     */
    /**
     * The muted styling is suppressed for as long as a finger is down.
     *
     * Dragging to the bottom sets the level to zero, and Android mutes a stream that reaches zero.
     * So a drag passing through the bottom made the bar switch to its outlined muted form
     * mid-gesture and switch back on the way up - a shape change the person did not ask for, caused
     * by their own drag. Reported as a border appearing while dragging quickly.
     *
     * While a finger is down the finger owns the display. The state is still tracked in [mutedNow]
     * and applied the moment the drag ends, so nothing is lost - only deferred until it is the
     * device's statement rather than an artefact of the gesture.
     */
    /**
     * The glyph for a stream, or `null` for media.
     *
     * Null means *draw the speaker*, whose waves follow the level. Ring, alarm and call are fixed
     * pictures and stay as drawables. section 5 wants a change of subject to be legible, and one
     * speaker for every stream would have made a ringer adjustment look exactly like a media one.
     */
    private fun iconFor(stream: Int): Drawable? {
        val ctx = pluginContext ?: return null
        val id = when (stream) {
            AudioManager.STREAM_RING, AudioManager.STREAM_NOTIFICATION -> R.drawable.ic_aurora_ring
            AudioManager.STREAM_ALARM -> R.drawable.ic_aurora_alarm
            AudioManager.STREAM_VOICE_CALL -> R.drawable.ic_aurora_call
            else -> return null
        }
        return ctx.getDrawable(id)
    }

    private fun applyMuteToView() {
        animateSlash(if (mutedNow && !dragging) 1f else 0f)
    }

    private fun onTouch(event: MotionEvent): Boolean {
        val v = view ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                handler.removeCallbacks(dismissRunnable)
                handler.removeCallbacks(narrowRunnable)
                // The spring is dropped, not paused. Leaving the handle in place would let onFrame
                // write its value over the finger's on the next tick.
                levelHandle = null
                volume?.userActivity()
                applyMuteToView()
                animateWidth(1f)
                applyDrag(v, event.y)
            }
            MotionEvent.ACTION_MOVE -> {
                volume?.userActivity()
                applyDrag(v, event.y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                applyMuteToView()
                // Narrow on a real lift only. ACTION_CANCEL means the finger left the window
                // while still adjusting - the touch was taken away, not given up - and shrinking
                // then pulls the bar out from under a gesture in progress. The dismiss timer
                // still runs in both cases, so nothing is left wide forever.
                if (event.actionMasked == MotionEvent.ACTION_UP) scheduleNarrow()
                scheduleDismiss()
            }
            else -> return false
        }
        return true
    }

    /**
     * Maps a y coordinate inside the view onto the track, then onto the stream's own range.
     *
     * **Against the WIDE geometry, not the current one, and that is a fix rather than a shortcut.**
     * The first version read `v.trackHeightPx`, which is animating: `ACTION_DOWN` calls
     * `animateWidth(1f)` and then this, before any frame has run, so the first sample used the slim
     * height and every later sample used a taller one. The same finger position meant a different
     * level at different moments of one gesture  measured as a one-step discrepancy against a
     * computed target.
     *
     * A drag always forces the wide form, so the wide form is the honest coordinate space for it.
     * Mapping against a value that is mid-animation is the same mistake as reading `widthNow` before
     * a frame had written it, which cost Sprint 09 three build cycles.
     */
    private fun applyDrag(v: VolumeIndicatorView, y: Float) {
        val trackH = if (wideHPx > 0f) wideHPx else v.trackHeightPx
        if (trackH <= 0f) return
        val top = (v.height - trackH) / 2f
        val bottom = top + trackH

        // Bottom is loud-down, top is loud-up, matching the direction the fill grows.
        val fraction = ((bottom - y) / trackH).coerceIn(0f, 1f)

        levelNow = fraction
        alphaNow = 1f
        v.level = fraction
        v.alpha01 = 1f

        val span = levelMax - levelMin
        if (activeStream >= 0 && span > 0) {
            val userLevel = levelMin + Math.round(fraction * span)
            // sync=false: let the audio system settle asynchronously. The bar is already where the
            // finger is, so waiting for confirmation would only add latency to a value we set.
            volume?.setStreamVolume(activeStream, userLevel, false)
        }
    }

    // ------------------------------------------------------------- animation

    /**
     * Replaces whatever is running, from where it is.
     *
     * `volume-overlay.md` section 4 requires that a run of presses leaves the overlay visibly one object:
     * no replayed entrance, the indicator moving from where it stands, no blink when a press
     * interrupts a fade. That is why `from` is [levelNow] and [alphaNow] rather than a constant  - 
     * the continuity is in the `from`, not in a special case.
     */
    private fun animate(target: Float, fadeTo: Float) {
        val seq = ++animationSeq
        // Same rule as animateWidth, and the dismiss path is where it bit. beginDismiss() asks for
        // `target = levelNow` - the level is not meant to move while the overlay fades - which is a
        // zero-displacement spring, which never reports itself finished. `running` in onFrame stayed
        // true forever, so the branch that removes the window once the fade completes never ran.
        // Assigned directly ONLY when there is no animation. Assigning first and animating too would
        // jump to the target on the frame before the spring's first sample, which is exactly the
        // discontinuity section 4 forbids.
        // LEVEL_SPRING rather than the gentle default. The level answers "did my press register",
        // and a spring that takes 400ms to arrive is still travelling when the next press lands, so
        // a run of presses shows a bar permanently behind the keys. Critically damped, because a
        // volume bar that overshoots is showing a loudness the device is not at.
        val lh = springOrNull(
            levelHandle, "aurora.volume.level.$seq", levelNow, target,
            SpringSpec(spring = LEVEL_SPRING),
        )
        if (lh == null) levelNow = target
        levelHandle = lh

        // Leaving is slower than arriving, and that asymmetry is the spec's rather than a preference.
        //
        // volume-overlay.md section 3: it *leaves by fading rather than vanishing, so peripheral vision
        // registers it as leaving rather than as having blinked* - and section 3 also says it *appears on
        // the change, not after it*. An entrance that took as long as an exit would be a delay to
        // confirm intent, which that section explicitly refuses.
        //
        // Critically damped, unlike the default: alpha overshooting below zero would be clipped by
        // the view anyway, so the overshoot would only make the fade look like it stopped early.
        val fadeSpec = if (fadeTo < alphaNow) SpringSpec(spring = FADE_OUT_SPRING) else SpringSpec()
        val fh = springOrNull(fadeHandle, "aurora.volume.fade.$seq", alphaNow, fadeTo, fadeSpec)
        if (fh == null) alphaNow = fadeTo
        fadeHandle = fh

        view?.let {
            it.level = levelNow
            it.alpha01 = alphaNow
        }
        requestFrame()
    }

    /**
     * Cancels [previous], then returns a fresh spring - or `null` when there is nothing to travel.
     *
     * **The cancellation is the point.** Every press replaced the handle without cancelling the one
     * it replaced, so each one stayed in the registry and kept being ticked. A run of presses left
     * dozens of finished animations behind, every frame ticked all of them, and the bar visibly
     * lagged behind the keys - worse the longer the run, which is exactly the case
     * `volume-overlay.md` §4 calls the normal one rather than an edge case.
     */
    private fun springOrNull(
        previous: AnimationHandle?,
        name: String,
        from: Float,
        to: Float,
        spec: SpringSpec = SpringSpec(),
    ): AnimationHandle? {
        previous?.cancel()
        if (kotlin.math.abs(to - from) < AT_REST) return null
        return controller.animator.play(
            Animation(name = name, spec = spec, from = from, to = to)
        )
    }

    /**
     * Width has its own life, and this separation is a bug fix rather than tidiness.
     *
     * The first version passed `widthTo` through [animate] and every state change re-targeted the
     * width to `widthNow`  -  "leave it where it is". That reads as harmless and is not: five key
     * presses arrive within a few milliseconds, **before any frame has ticked**, so `widthNow` is
     * still 0 and the entrance's 0 -> 1 gets replaced four times by 0 -> 0. The bar never widened, and
     * the code that did it looked like the code that preserved it.
     *
     * *Leave it alone* and *re-target it to its current value* are different instructions. Only one
     * of them survives a value that has not been sampled yet.
     */
    /**
     * Drives the mute slash.
     *
     * A fourth animated property, on the same controller and the same frame as the other three.
     * Snappy rather than gentle: a slash that eases in slowly reads as the icon being uncertain
     * about whether the stream is muted.
     */
    private fun animateSlash(to: Float) {
        slashHandle = springOrNull(
            slashHandle, "aurora.volume.slash.${++animationSeq}", slashNow, to,
            SpringSpec(spring = SLASH_SPRING),
        )
        if (slashHandle == null) {
            slashNow = to
            view?.slash01 = to
        }
        requestFrame()
    }

    private fun animateWidth(to: Float) {
        // A spring with no displacement is at rest, not running - so do not start one.
        //
        // This mattered more than it looks. ACTION_DOWN calls animateWidth(1f) unconditionally, and
        // on an already-wide bar that is a 1 -> 1 animation. Measured behaviour of such a handle:
        // isRunning stays true indefinitely. So `running` in onFrame never went false, the loop never
        // reached the branch that removes the window after a fade, and a bar dismissed while still
        // wide vanished by some other path instead of fading out.
        //
        // Reported from a device as "it just hides instead of animating away", and the evidence for
        // the cause was already sitting in a log from three hours earlier: handle=1.0/true, on every
        // frame.
        widthHandle?.cancel()
        if (kotlin.math.abs(to - widthNow) < AT_REST) {
            widthNow = to
            widthHandle = null
            return
        }
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
     * the overlay simply never went away. It does not: the controller sends that for *reasons*  - 
     * screen off, a touch outside, an explicit dismissal  -  and `VolumeDialogImpl` runs its own
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
        // The controller can ask for a dismissal at any time, including while a finger is on the
        // bar. Refusing it here rather than only cancelling the timer covers that path too.
        if (dragging) return
        // Two lines per dismissal, kept rather than removed. Four separate attempts to time this
        // fade from outside the process failed - a tight dumpsys poll perturbed the main thread it
        // was measuring, a sparse one lacked resolution, screencap costs most of a second per frame,
        // and a shell clock silently produced negative durations. Sprint 03 Task 4.4b made the same
        // argument for AuroraSystemService: a thing that runs silently cannot be told from a thing
        // that never ran.
        dismissStartedAt = SystemClock.uptimeMillis()
        Log.d(TAG, "dismiss: fading from alpha=$alphaNow")
        volume?.notifyVisible(false)
        // Fades from where it is, not from 1. A dismissal that interrupts an entrance must not jump
        // to full opacity first - volume-overlay.md section 4's "no blink when a press interrupts a fade".
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
        // A muted bar has nowhere to narrow to: the mute indicator only exists in the wide form.
        if (mutedNow) return
        handler.postDelayed(narrowRunnable, NARROW_DELAY_MS)
    }

    private val narrowRunnable = Runnable { if (!mutedNow) animateWidth(0f) }


    private fun requestFrame() {
        if (framePending) return
        val s = scheduler ?: return
        framePending = true
        s.postFrame { nanos -> onFrame(nanos) }
    }

    private fun onFrame(nanos: Long) {
        framePending = false

        // Monotonic guard, and it is not defensive programming - it is this adapter's job.
        //
        // Aurora's runtime enforces RULE-006: a timeline may not be advanced backwards, and
        // ExecutionTimeline throws rather than silently reordering. Animations here are created
        // OUTSIDE a frame - in a key callback, a touch event or a timer - so their timeline starts
        // from a clock reading, while Choreographer.frameTimeNanos is the time the frame BEGAN.
        // Those differ by microseconds, and the first frame after a handle is created can carry a
        // stamp a few microseconds earlier than the handle's own start.
        //
        // Measured on a device: 94596033776 after 94596039329, backwards by 5.5us, and it killed
        // SystemUI. The runtime was right to refuse it; supplying a monotonic stream is the caller's
        // responsibility, which is exactly what the exception message says.
        val t = if (nanos < lastTickNanos) lastTickNanos else nanos
        val delta = if (lastFrameNanos == 0L) 0L else (t - lastFrameNanos).coerceAtLeast(0L)
        lastFrameNanos = t
        lastTickNanos = t

        // A throw here reaches Choreographer, then the main Looper, and takes SystemUI down with the
        // whole system UI. PluginActionManager contains crashes that happen inside plugin API calls
        // by disabling the plugin; a frame callback is outside that containment, so Aurora provides
        // its own - the shape AOSP uses for config_deviceSpecificSystemServices, where a failure
        // degrades instead of bootlooping.
        //
        // It must never be the reason a bug goes unnoticed, so it logs the whole exception and takes
        // the overlay down rather than continuing in an unknown state.
        try {
            controller.tick(FrameTime(t, delta, frameIndex++))
        } catch (e: Throwable) {
            Log.e(TAG, "frame failed; taking the overlay down rather than SystemUI", e)
            hideWindow()
            controller.stop()
            return
        }

        // levelHandle is null while dragging, but the guard is explicit as well: a state change that
        // arrives between ACTION_DOWN and the next frame could have started one.
        if (!dragging) {
            levelHandle?.let { levelNow = it.value }
            fadeHandle?.let { alphaNow = it.value }
        }
        widthHandle?.let { widthNow = it.value }
        slashHandle?.let { slashNow = it.value }

        view?.let {
            it.level = levelNow
            it.alpha01 = alphaNow
            it.trackWidthPx = slimWPx + (wideWPx - slimWPx) * widthNow
            it.trackHeightPx = slimHPx + (wideHPx - slimHPx) * widthNow
            // The icon belongs to the wide form and must be fully gone by the time the pill is too
            // narrow to hold it. A plain proportional fade left a faint mark at rest, because a
            // spring settles near its target rather than exactly on it - so this reaches zero at
            // ICON_GONE_BELOW and stays there.
            it.slash01 = slashNow
            it.iconAlpha01 =
                ((widthNow - ICON_GONE_BELOW) / (1f - ICON_GONE_BELOW)).coerceIn(0f, 1f)
        }

        val running = (levelHandle?.isRunning == true) ||
            (fadeHandle?.isRunning == true) ||
            (widthHandle?.isRunning == true) ||
            (slashHandle?.isRunning == true)
        if (running) {
            requestFrame()
        } else if (dragging) {
            // Nothing is animating, but a finger is down and the window must not be taken away.
            // No frame is requested either: the drag draws on touch events, not on a clock.
        } else if (alphaNow <= FADED_OUT) {
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
        // The frame loop's failure path stops the controller. Without this, one transient fault would
        // leave the volume overlay dead until SystemUI restarts - a recovery worse than the fault.
        if (!controller.isRunning) controller.start()
        val density = v.resources.displayMetrics.density
        slimWPx = SLIM_W_DP * density
        wideWPx = WIDE_W_DP * density
        slimHPx = SLIM_H_DP * density
        wideHPx = WIDE_H_DP * density
        v.trackWidthPx = slimWPx + (wideWPx - slimWPx) * widthNow
        v.trackHeightPx = slimHPx + (wideHPx - slimHPx) * widthNow
        val lp = WindowManager.LayoutParams(
            // The window is always the WIDE size, in both directions. Shrinking happens inside it,
            // so no layout pass and no surface resize lands on the animation's critical path.
            (WINDOW_W_DP * density).toInt(),
            (WIDE_H_DP * density).toInt(),
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
        // A window removed mid-drag takes the finger's owner with it. Without this, dragging would
        // stay true and the next appearance would refuse to animate its level.
        dragging = false
        // Reset to the wide form so the next appearance is an entrance again rather than resuming
        // wherever the last one stopped. The handle goes with it: a finished 1 -> 0 animation left in
        // place would write 0 back over this on the very next frame, and the reset would look like
        // it had never happened.
        widthNow = 1f
        widthHandle = null
        if (!shown) return
        val wm = windowManager ?: return
        val v = view ?: return
        runCatching { wm.removeViewImmediate(v) }
            .onFailure { Log.e(TAG, "removeView failed", it) }
        if (dismissStartedAt != 0L) {
            Log.d(
                TAG,
                "hidden ${SystemClock.uptimeMillis() - dismissStartedAt} ms after the fade began, " +
                    "alpha=$alphaNow frames=$frameIndex",
            )
            dismissStartedAt = 0L
        } else {
            Log.d(TAG, "hidden without a fade having started, alpha=$alphaNow")
        }
        shown = false
        lastFrameNanos = 0
        volume?.notifyVisible(false)
    }

    private companion object {
        const val TAG = "AuroraVolume"

        /**
         * The resting shape: a slim vertical bar, the size the overlay had before the wide entrance
         * existed. One shape, one number, per Sprint 09's third criterion.
         */
        const val SLIM_W_DP = 10f
        const val SLIM_H_DP = 160f

        /**
         * The entrance shape. Wider so an icon fits, and taller so the growth reads as one gesture
         * rather than a bar that only got fatter.
         */
        const val WIDE_W_DP = 34f
        const val WIDE_H_DP = 210f

        /**
         * The window is wider than anything drawn in it, and that margin is touch slack.
         *
         * A drag is vertical, but fingers wander sideways, and leaving the window mid-gesture
         * arrives as ACTION_CANCEL - the touch is taken away rather than given up. Reported from
         * a device as the bar shrinking while still being adjusted.
         *
         * The track stays right-aligned inside it, so nothing moves on screen; the extra width is
         * invisible and only exists to catch the finger. It does swallow touches in a 72dp strip
         * at the edge - for the two-and-a-half seconds the overlay is up, which is the trade.
         */
        const val WINDOW_W_DP = 72f

        /**
         * Distance from the screen edge, and it belongs to the WINDOW rather than to either shape.
         *
         * The track is right-aligned inside the window, so this gap is the same wide or slim: the
         * bar grows inward and never appears to slide.
         */
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

        /**
         * Below this displacement an animation would have nothing to do.
         *
         * Not a tolerance for sloppiness: a spring asked to travel zero distance from zero velocity
         * reports itself as running forever, and anything waiting for it to finish waits forever.
         */
        const val AT_REST = 0.001f

        /**
         * Alpha at which the window is worth removing.
         *
         * Deliberately looser than [AT_REST]: a spring settles *near* its target, so waiting for a
         * true zero would leave an invisible window holding a surface indefinitely. One part in a
         * hundred of opacity is not visible on any display this will run on.
         */
        const val FADED_OUT = 0.01f

        /**
         * The spring the overlay leaves on. Slower than it arrives on, deliberately.
         *
         * Stiffness 110 against the default 400, so the settle is roughly 0.9 s rather than 0.6 s,
         * and critically damped so it does not overshoot into the clamp and appear to stop early.
         *
         * A number felt on a device, which is the only way `volume-overlay.md` section 3 allows one to be
         * chosen: *exact durations are not fixed here the kind of number that has to be felt on a
         * device.* The first value was measured at 595 ms and read as too quick.
         */
        @JvmField
        val FADE_OUT_SPRING = Spring(stiffness = 110f, dampingRatio = 1f)

        /**
         * The spring the level travels on. Much stiffer than the default, and critically damped.
         *
         * The level's job is answering *did my press register* - the first of the three questions
         * `volume-overlay.md` section 1 lists, and the reason the overlay exists at all. A gentle
         * spring is still arriving when the next press lands, so a run of presses leaves the bar
         * permanently behind the keys.
         *
         * Damping 1 rather than the default 0.85: a volume bar that overshoots is briefly showing a
         * loudness the device is not at, which is a different statement from a card that bounces.
         *
         * 3600 rather than the 1400 tried first, and the number came from arithmetic against the
         * input rate rather than from taste. Holding a volume key auto-repeats at roughly 20 events
         * a second, so a step arrives every ~50ms; a critically damped spring settles in about
         * `9/sqrt(stiffness)` seconds, which is 240ms at 1400 and 150ms at 3600. Anything slower than
         * the repeat interval means the bar is still travelling toward one step when the next lands,
         * and it falls further behind for as long as the key is held.
         */
        @JvmField
        val LEVEL_SPRING = Spring(stiffness = 3600f, dampingRatio = 1f)

        /** The mute slash. Quick and critically damped: a state change, not a flourish. */
        @JvmField
        val SLASH_SPRING = Spring(stiffness = 900f, dampingRatio = 1f)

        /**
         * Below this width fraction the icon is fully gone, not merely faint.
         *
         * A proportional fade leaves a residue at rest, because a spring settles *near* its target
         * rather than exactly on it  -  and a barely-visible icon on a 10dp bar reads as dirt.
         */
        const val ICON_GONE_BELOW = 0.35f
    }
}
