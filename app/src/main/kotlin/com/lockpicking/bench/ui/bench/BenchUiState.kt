package com.lockpicking.bench.ui.bench

import com.lockpicking.bench.data.glb.GlbInfo
import com.lockpicking.bench.domain.bench.PickTool
import com.lockpicking.bench.domain.game.GameResult
import com.lockpicking.bench.domain.game.GameTier
import com.lockpicking.bench.domain.lock.TorqueBand
import com.lockpicking.bench.domain.model.LockGeometry
import com.lockpicking.bench.domain.model.LockModel

/**
 * Everything the bench screen shows that changes at gesture speed. Pin travel, which changes
 * every frame, is not here: it is read straight from the lock in the draw phase.
 */
data class BenchUiState(
  /** Which padlock is on the bench. */
  val lock: LockModel = LockModel.Trainer,
  /** Whether [lock] is the one saved from last time yet. Until it is, there is nothing to show. */
  val restored: Boolean = false,
  /** Mesh stats of [lock]'s model file, once read. Null while a lock is being swapped in. */
  val modelInfo: GlbInfo? = null,
  /** The lock's dimensions, once the 3D model has bound. Null until then. */
  val geometry: LockGeometry? = null,
  val loadError: String? = null,
  /** Where the torque on the wrench sits. The torque itself changes every frame and is not here. */
  val band: TorqueBand = TorqueBand.Slack,
  val opened: Boolean = false,
  val setCount: Int = 0,
  val bindingPin: Int = -1,
  val oversetPin: Int = -1,
  /** The spool the plug is hanging on in a false set, or -1. */
  val falseSetPin: Int = -1,
  /** The pin the pick is pushing against a stop it cannot pass at this torque, or -1. */
  val blockedPin: Int = -1,
  /** Whether the bench names the binding pin and says what the lock is doing. */
  val hints: Boolean = true,
  val selectedPin: Int = -1,
  /** Bumped when a pin sets, to flash the shear line. */
  val setPulse: Int = 0,
  val explode: Float = 0f,
  val ghost: Boolean = false,
  /** The pick in hand. */
  val tool: PickTool = PickTool.Hook,
  /** Tension under the right thumb and the pick under the left, for a left-handed picker. */
  val swapHands: Boolean = false,
  /** From the side the keyway runs across the screen, the way the pick pad does. */
  val preset: CameraPreset = CameraPreset.ShearSide,
  /** Bumped on every preset tap, so re-tapping the current one snaps an orbited camera back. */
  val presetResetKey: Int = 0,
  /** The blind game in progress, or null on the open bench. */
  val game: GameUiState? = null,
) {
  val pinCount: Int get() = geometry?.pinCount ?: LockGeometry.DEFAULT_PIN_COUNT
  val exploded: Boolean get() = explode > EXPLODED_AT

  /** True if this lock's model came with a rake to pick up. */
  val hasRake: Boolean get() = geometry?.rake != null

  /** True while a blind round is being picked: the camera is held on the keyway and the pins are hidden. */
  val blind: Boolean get() = game != null && game.result == null

  /** True while the wrench is holding the plug against the pins. */
  val tension: Boolean get() = band != TorqueBand.Slack

  companion object {
    const val EXPLODED_AT = 0.5f
  }
}

/** A blind round, as the HUD shows it. The clock is polled, not held here. */
data class GameUiState(
  val tier: GameTier,
  /** Which lock of the run this is, counting from 1. */
  val lockNumber: Int,
  /** The tier's best time before this round, or null. */
  val bestMillis: Long?,
  val mistakes: Int = 0,
  /** The rake has been used on this lock: [bestMillis] is then the best raked time. */
  val raked: Boolean = false,
  /** Set once the lock opens. */
  val result: GameResult? = null,
)

/** The one-line headline the status block shows. Pin numbers here are 1-based, as labelled. */
sealed interface BenchStatus {
  data object Open : BenchStatus
  data class Overset(val pinNumber: Int) : BenchStatus
  data object Locked : BenchStatus

  /** So much torque that the binding pin will not lift. */
  data object Seized : BenchStatus

  /** The pick is on a spool that cannot turn the plug back until the wrench is eased. */
  data class EaseOff(val pinNumber: Int) : BenchStatus

  /** The pick is on a serrated driver whose groove has caught: it clicked, but it is not set. */
  data class Caught(val pinNumber: Int) : BenchStatus

  /** The plug has dropped into a false set and is hanging on this spool. With [rake] in hand it will not come out of it. */
  data class FalseSet(val pinNumber: Int, val rake: Boolean = false) : BenchStatus

  /** Tension on and picking; [bindingPinNumber] is null when no pin is named. */
  data class Picking(val setCount: Int, val pinCount: Int, val bindingPinNumber: Int?) : BenchStatus
}

/** With hints off the headline only counts sets: what the lock is doing is left to feel. */
fun BenchUiState.status(): BenchStatus = when {
  opened -> BenchStatus.Open

  !tension -> BenchStatus.Locked

  !hints -> BenchStatus.Picking(setCount, pinCount, null)

  oversetPin >= 0 -> BenchStatus.Overset(oversetPin + 1)

  band == TorqueBand.Heavy -> BenchStatus.Seized

  blockedPin >= 0 -> {
    val serrated = geometry?.pinSpecs?.getOrNull(blockedPin)?.serrated == true
    if (serrated) BenchStatus.Caught(blockedPin + 1) else BenchStatus.EaseOff(blockedPin + 1)
  }

  falseSetPin >= 0 -> BenchStatus.FalseSet(falseSetPin + 1, rake = tool == PickTool.Rake)

  else -> BenchStatus.Picking(setCount, pinCount, bindingPin.takeIf { it >= 0 }?.plus(1))
}

/** The live numbers the stats pill polls a couple of times a second. */
data class BenchTelemetry(
  /** Model node name of the selected key pin, or null when none is selected. */
  val selectedPinNode: String?,
  /** Rise of the selected key pin, in millimetres. */
  val selectedLiftMm: Float,
  val plugDegrees: Float,
  /** Torque on the wrench, 0..1. */
  val torque: Float,
)
