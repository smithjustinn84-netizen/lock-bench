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

/** Pin indices in the order the default lock binds them. */
val bindingOrder = listOf(2, 0, 3, 1)
