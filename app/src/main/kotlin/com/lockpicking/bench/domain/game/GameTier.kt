package com.lockpicking.bench.domain.game

import com.lockpicking.bench.domain.lock.LockMechanics

/**
 * How much the bench gives away while you pick blind. Every tier hides the pins; each one up
 * takes away one more aid.
 *
 * @property parSeconds open the trainer inside this, with no mistakes, for three stars. Other
 *   locks stretch it by their [com.lockpicking.bench.domain.model.LockModel.parFactor].
 * @property showSetCount whether the HUD counts sets. Without it the plug dial is all you have.
 * @property showWrenchZones whether the wrench marks where spools pass and pins seize.
 * @property seizeAt the torque at which the binding pin seizes: lower is less forgiving.
 */
enum class GameTier(
  val parSeconds: Int,
  val showSetCount: Boolean,
  val showWrenchZones: Boolean,
  val seizeAt: Float,
) {
  Apprentice(parSeconds = 45, showSetCount = true, showWrenchZones = true, seizeAt = LockMechanics.T_HEAVY),
  Journeyman(parSeconds = 60, showSetCount = false, showWrenchZones = true, seizeAt = LockMechanics.T_HEAVY),
  Master(parSeconds = 75, showSetCount = false, showWrenchZones = false, seizeAt = 0.55f),
}
