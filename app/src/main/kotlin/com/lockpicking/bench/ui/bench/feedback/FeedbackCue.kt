package com.lockpicking.bench.ui.bench.feedback

import com.lockpicking.bench.domain.lock.LockEvent
import com.lockpicking.bench.domain.lock.LockMechanics
import com.lockpicking.bench.domain.lock.PinFeel

/** A lock event and the torque it happened under, which is how hard it lands in the hand. */
data class LockFeedback(val event: LockEvent, val torque: Float)

/** The shapes a vibration can take. What each is on a given phone is up to [LockHaptics]. */
enum class Haptic {
  /** A crisp single click: a pin setting. */
  Click,

  /** A heavier, duller knock: the plug dropping into a false set, or a pin jamming. */
  Thud,

  /** A light tick: one notch of the plug turning back. */
  Tick,

  /** The faintest tick there is: a binding pin scraping up its chamber. */
  Grain,

  /** Two dull knocks: pushing on something that will not move. */
  Refuse,

  /** A scatter of ticks: the drivers dropping. */
  Rattle,

  /** A click and then a thud: the plug turning and the shackle letting go. */
  Release,

  /** A soft, round bump: the pick's tip finding a pin. */
  Bump,

  /** One sharp, hard knock: the pick against solid metal. */
  Knock,

  /** A dry tick, harder than [Grain]: the tip dragged along a land. */
  Scrape,

  /** Barely there: a pin giving on its spring. */
  Spring,
}

/** True for the shapes that come in a stream and are felt as a texture, not counted. */
val Haptic.isTexture: Boolean get() = this == Haptic.Grain || this == Haptic.Scrape || this == Haptic.Spring

/** The sounds the lock makes. [LockSoundEngine] has the recording for each. */
enum class LockSound { SetClick, FalseSetClunk, CounterTick, Blocked, Grit, Rattle, Jam, SlipBack, Open, Relock, Touch, Knock, Scrape, Creak }

/** What one lock event feels and sounds like. [strength] is 0..1 and scales both. */
data class FeedbackCue(val haptic: Haptic?, val sound: LockSound?, val strength: Float)

/**
 * The cue for [event] under [torque], 0..1.
 *
 * Everything the plug does to the wrench is felt in proportion to how hard the wrench is being
 * pulled: a set under light tension is a faint click, the same set under heavy tension snaps.
 */
fun cueFor(event: LockEvent, torque: Float): FeedbackCue {
  val load = ((torque - LockMechanics.T_BIND) / (LockMechanics.T_HEAVY - LockMechanics.T_BIND)).coerceIn(0f, 1f)
  return when (event) {
    is LockEvent.PinSet -> FeedbackCue(Haptic.Click, LockSound.SetClick, 0.45f + 0.55f * load)

    is LockEvent.FalseSet -> FeedbackCue(Haptic.Thud, LockSound.FalseSetClunk, 0.6f + 0.4f * load)

    is LockEvent.CounterRotation -> {
      // The last notch, where the flange clears and the plug is back on the binding edge, is the firm one.
      val last = event.step >= LockMechanics.COUNTER_ROTATION_STEPS
      FeedbackCue(Haptic.Tick, LockSound.CounterTick, if (last) 0.8f else 0.5f)
    }

    is LockEvent.SpoolBlocked -> FeedbackCue(Haptic.Refuse, LockSound.Blocked, 0.5f + 0.5f * load)

    // A serration is meant to be mistaken for a set: the same click, a little short of it.
    is LockEvent.SerrationClick -> FeedbackCue(Haptic.Click, LockSound.SetClick, 0.35f + 0.45f * load)

    is LockEvent.SerrationHeld -> FeedbackCue(Haptic.Refuse, LockSound.Blocked, 0.5f + 0.5f * load)

    is LockEvent.Seized -> FeedbackCue(Haptic.Refuse, LockSound.Blocked, 1f)

    is LockEvent.Grit -> FeedbackCue(Haptic.Grain, LockSound.Grit, 0.2f + 0.8f * load)

    is LockEvent.PinOverset -> FeedbackCue(Haptic.Thud, LockSound.Jam, 0.9f)

    is LockEvent.OversetReleased -> FeedbackCue(Haptic.Tick, LockSound.SlipBack, 0.5f)

    // What the tip finds is the whole game: the binding pin pushes back, a set one barely registers.
    is LockEvent.PinTouched -> when (event.feel) {
      PinFeel.Binding -> FeedbackCue(Haptic.Bump, LockSound.Touch, 0.6f + 0.4f * load)
      PinFeel.Springy -> FeedbackCue(Haptic.Bump, LockSound.Touch, 0.3f)
      PinFeel.Loose -> FeedbackCue(Haptic.Bump, null, 0.12f)
    }

    is LockEvent.SpringTick -> FeedbackCue(Haptic.Spring, null, 0.1f + 0.3f * event.compression.coerceIn(0f, 1f))

    is LockEvent.HardStop -> FeedbackCue(Haptic.Knock, LockSound.Knock, 0.8f)

    LockEvent.WardHit -> FeedbackCue(Haptic.Knock, LockSound.Knock, 1f)

    LockEvent.WardScrape -> FeedbackCue(Haptic.Scrape, LockSound.Scrape, 0.45f)

    LockEvent.KeywayEnd -> FeedbackCue(Haptic.Thud, LockSound.Touch, 0.7f)

    is LockEvent.TensionBite ->
      if (event.heavy) FeedbackCue(Haptic.Thud, LockSound.Creak, 0.8f) else FeedbackCue(Haptic.Tick, LockSound.Creak, 0.35f)

    LockEvent.Dropped -> FeedbackCue(Haptic.Rattle, LockSound.Rattle, 0.7f)

    LockEvent.Opened -> FeedbackCue(Haptic.Release, LockSound.Open, 1f)

    LockEvent.Relocked -> FeedbackCue(null, LockSound.Relock, 0.7f)
  }
}
