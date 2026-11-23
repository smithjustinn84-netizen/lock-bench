package com.lockpicking.bench.ui.bench

import com.lockpicking.bench.data.glb.GlbInfo
import com.lockpicking.bench.domain.model.LockGeometry

/**
 * Everything the bench screen shows that changes at gesture speed. Pin travel, which changes
 * every frame, is not here: it is read straight from the lock in the draw phase.
 */
data class BenchUiState(
  /** Mesh stats of the model file, once read. */
  val modelInfo: GlbInfo? = null,
  /** The lock's dimensions, once the 3D model has bound. Null until then. */
  val geometry: LockGeometry? = null,
  val loadError: String? = null,
  val tension: Boolean = false,
  val opened: Boolean = false,
  val setCount: Int = 0,
  val bindingPin: Int = -1,
  val oversetPin: Int = -1,
  val selectedPin: Int = -1,
  /** Bumped when a pin sets, to flash the shear line. */
  val setPulse: Int = 0,
  val explode: Float = 0f,
  val ghost: Boolean = false,
  val preset: CameraPreset = CameraPreset.Isometric,
  /** Bumped on every preset tap, so re-tapping the current one snaps an orbited camera back. */
  val presetResetKey: Int = 0,
) {
  val pinCount: Int get() = geometry?.pinCount ?: LockGeometry.PIN_COUNT
  val exploded: Boolean get() = explode > EXPLODED_AT

  companion object {
    const val EXPLODED_AT = 0.5f
  }
}

/** The one-line headline the status block shows. Pin numbers here are 1-based, as labelled. */
sealed interface BenchStatus {
  data object Open : BenchStatus
  data class Overset(val pinNumber: Int) : BenchStatus
  data object Locked : BenchStatus

  /** Tension on and picking; [bindingPinNumber] is null when no pin is binding. */
  data class Picking(val setCount: Int, val pinCount: Int, val bindingPinNumber: Int?) : BenchStatus
}

fun BenchUiState.status(): BenchStatus = when {
  opened -> BenchStatus.Open
  oversetPin >= 0 -> BenchStatus.Overset(oversetPin + 1)
  !tension -> BenchStatus.Locked
  else -> BenchStatus.Picking(setCount, pinCount, bindingPin.takeIf { it >= 0 }?.plus(1))
}

/** The live numbers the stats pill polls a couple of times a second. */
data class BenchTelemetry(
  /** Model node name of the selected key pin, or null when none is selected. */
  val selectedPinNode: String?,
  /** Rise of the selected key pin, in millimetres. */
  val selectedLiftMm: Float,
  val plugDegrees: Float,
)
