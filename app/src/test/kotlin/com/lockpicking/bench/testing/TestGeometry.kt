package com.lockpicking.bench.testing

import com.lockpicking.bench.domain.lock.PinSpec
import com.lockpicking.bench.domain.model.LockGeometry

/** A four-pin lock with the model's proportions, for tests that need no 3D scene. */
val testGeometry = LockGeometry(
  pinSpecs = listOf(
    PinSpec(1, liftToShear = 0.032f, springRest = 0.152f),
    PinSpec(2, liftToShear = 0.048f, springRest = 0.168f, spool = true),
    PinSpec(3, liftToShear = 0.022f, springRest = 0.142f, spool = true),
    PinSpec(4, liftToShear = 0.040f, springRest = 0.160f),
  ),
  pinZ = listOf(-0.06f, -0.02f, 0.02f, 0.06f),
  pinNodeNames = listOf("KeyPin_1", "KeyPin_2", "KeyPin_3", "KeyPin_4"),
  pinRestY = 0f,
  shearY = 0.07f,
  chamberTop = 0.25f,
  driverLength = 0.06f,
  openDegrees = -90f,
  shackleTravel = 0.077f,
  pickParkedZ = 0.20f,
  pickParkedY = -0.01f,
)

/** The laminated lock as `master_no3_padlock.glb` authors it: four plain stacks. */
val master3Geometry = LockGeometry(
  pinSpecs = listOf(
    PinSpec(1, liftToShear = 0.042f, springRest = 0.162f),
    PinSpec(2, liftToShear = 0.030f, springRest = 0.150f),
    PinSpec(3, liftToShear = 0.050f, springRest = 0.170f),
    PinSpec(4, liftToShear = 0.036f, springRest = 0.156f),
  ),
  pinZ = listOf(0.066f, 0.022f, -0.022f, -0.066f),
  pinNodeNames = listOf("KeyPin_1", "KeyPin_2", "KeyPin_3", "KeyPin_4"),
  pinRestY = -0.03f,
  shearY = 0.07f,
  chamberTop = 0.25f,
  driverLength = 0.06f,
  openDegrees = -90f,
  shackleTravel = 0.077f,
  pickParkedZ = 0.165f,
  pickParkedY = -0.036f,
)

/** The aluminium block as `american_1100_padlock.glb` authors it: five stacks, serrated and spooled turn about. */
val american1100Geometry = LockGeometry(
  pinSpecs = listOf(
    PinSpec(1, liftToShear = 0.040f, springRest = 0.160f, serrated = true),
    PinSpec(2, liftToShear = 0.026f, springRest = 0.146f, spool = true),
    PinSpec(3, liftToShear = 0.048f, springRest = 0.168f, serrated = true),
    PinSpec(4, liftToShear = 0.032f, springRest = 0.152f, spool = true),
    PinSpec(5, liftToShear = 0.054f, springRest = 0.174f, serrated = true),
  ),
  pinZ = listOf(0.080f, 0.040f, 0f, -0.040f, -0.080f),
  pinNodeNames = listOf("KeyPin_1", "KeyPin_2", "KeyPin_3", "KeyPin_4", "KeyPin_5"),
  pinRestY = -0.03f,
  shearY = 0.07f,
  chamberTop = 0.25f,
  driverLength = 0.06f,
  openDegrees = -90f,
  shackleTravel = 0.077f,
  pickParkedZ = 0.185f,
  pickParkedY = -0.036f,
)
