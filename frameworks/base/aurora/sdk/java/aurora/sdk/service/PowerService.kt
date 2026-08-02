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

package aurora.sdk.service

/** How the device is being charged, if at all. */
enum class ChargingState { NOT_CHARGING, AC, USB, WIRELESS, FULL }

/**
 * Battery and power state.
 *
 * ## Why animations must consult this
 *
 * Power save mode is not only a battery setting; on most devices it is also an instruction to
 * stop animating. [animationsAllowed] exists so the animation engine has one place to ask,
 * rather than every caller separately discovering the rule and half of them forgetting.
 * Ignoring it drains the battery of a user who explicitly asked the system not to.
 */
interface PowerService : AuroraService {

    /** Charge remaining, 0.0..1.0. */
    val batteryLevel: Float

    /** How the device is charging. */
    val chargingState: ChargingState

    /** Whether the platform's battery saver is on. */
    val isPowerSaveMode: Boolean

    /**
     * Whether non-essential animation should run.
     *
     * False when battery saver is on, or when the user has turned animations off for
     * accessibility reasons. Motion that conveys meaning may still be shown, but it should be
     * instant rather than animated.
     */
    val animationsAllowed: Boolean

    /** Estimated milliseconds until empty, or -1 when the platform cannot say. */
    val estimatedMillisRemaining: Long

    /** Observes battery level and charging changes. */
    fun addOnPowerChangedListener(listener: (level: Float, state: ChargingState) -> Unit)

    /** Removes a previously registered listener. */
    fun removeOnPowerChangedListener(listener: (level: Float, state: ChargingState) -> Unit)
}
