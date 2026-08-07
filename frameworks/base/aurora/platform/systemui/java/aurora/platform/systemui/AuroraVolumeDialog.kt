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

    private var levelHandle: AnimationHandle? = null
    private var fadeHandle: AnimationHandle? = null

    private var frameIndex: Long = 0
    private var lastFrameNanos: Long = 0
    private var framePending: Boolean = false
    private var animationSeq: Int = 0

    /** Where the level currently is, so a replacement starts from the truth rather than from 0. */
    private var levelNow: Float = 0f
    private var alphaNow: Float = 0f

    private val handler: Handler = Handler(Looper.getMainLooper())

    // ---------------------------------------------------------------- Plugin

    override fun onCreate(sysuiContext: Context, pluginContext: Context) {
        this.sysuiContext = sysuiContext
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

        PluginDependency.get(this, VolumeDialogController::class.java)
            .addCallback(callbacks, handler)

        Log.i(TAG, "init: windowType=$windowType, waiting for a show request")
    }

    override fun destroy() {
        teardown()
        Log.i(TAG, "destroy")
    }

    private fun teardown() {
        sysuiContext?.let {
            runCatching {
                PluginDependency.get(this, VolumeDialogController::class.java)
                    .removeCallback(callbacks)
            }
        }
        hideWindow()
        controller.stop()
        scheduler = null
        levelHandle = null
        fadeHandle = null
    }

    // ------------------------------------------------------ controller state

    private val callbacks = object : VolumeDialogController.Callbacks {

        override fun onShowRequested(reason: Int, keyguardLocked: Boolean, lockTaskModeState: Int) {
            showWindow()
            animate(target = levelNow, fadeTo = 1f)
        }

        override fun onDismissRequested(reason: Int) {
            animate(target = levelNow, fadeTo = 0f)
        }

        override fun onStateChanged(state: VolumeDialogController.State) {
            val stream = state.activeStream
            if (stream == VolumeDialogController.State.NO_ACTIVE_STREAM) return
            val s = state.states.get(stream) ?: return
            val span = (s.levelMax - s.levelMin).toFloat()
            val fraction = if (span > 0f) (s.level - s.levelMin) / span else 0f
            animate(target = fraction.coerceIn(0f, 1f), fadeTo = if (shown) 1f else alphaNow)
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

        view?.let {
            it.level = levelNow
            it.alpha01 = alphaNow
        }

        val running = (levelHandle?.isRunning == true) || (fadeHandle?.isRunning == true)
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
        val lp = WindowManager.LayoutParams(
            (WIDTH_DP * density).toInt(),
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
        if (!shown) return
        val wm = windowManager ?: return
        val v = view ?: return
        runCatching { wm.removeViewImmediate(v) }
            .onFailure { Log.e(TAG, "removeView failed", it) }
        shown = false
        lastFrameNanos = 0
    }

    private companion object {
        const val TAG = "AuroraVolume"

        /** A slim vertical bar. One shape, one number, per Sprint 09's third criterion. */
        const val WIDTH_DP = 10f
        const val HEIGHT_DP = 160f
        const val MARGIN_DP = 16f
    }
}
