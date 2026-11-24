package com.lockpicking.bench.domain.model

/**
 * The padlocks on the bench. Each is a 3D model of its own, and brings its own pins: how many
 * stacks, how far each lifts, and which drivers are spools or serrated all come from the model.
 *
 * @property id the lock's name in the model script (`tools/build_padlock.py`) and in saved records.
 * @property parFactor how a blind tier's par time stretches for this lock.
 */
enum class LockModel(val id: String, val parFactor: Float) {
  /** The anodized training block: four pins, two of them spools. */
  Trainer("trainer", parFactor = 1f),

  /** Laminated steel in the style of a Master Lock No. 3: four plain pins. The easy one. */
  Master3("master3", parFactor = 0.75f),

  /** Solid aluminium in the style of an American Lock 1100: five pins, all serrated or spooled. */
  American1100("american1100", parFactor = 1.75f),
}
