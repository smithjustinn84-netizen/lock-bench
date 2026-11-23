#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.10"
# dependencies = [
#   "numpy>=1.26",
#   "scipy>=1.11",
# ]
# ///
"""Generate the Lock Bench sound-effect pack into app/src/main/res/raw/.

    uv run scripts/generate_lock_sfx.py [--out DIR]

Every sound is the same physical recipe with different numbers: a 1-3 ms filtered
noise burst (the impact) drives a bank of two-pole resonators (the metal's modes),
and the burst itself is mixed back in as the transient. The shared inharmonic mode
family (2.2 / 4.8 / 7.9 kHz) is what makes the pack sound like one lock. There is
no reverb anywhere: close-mic brass and steel only.

Output: 48 kHz, 16-bit mono WAV, peak-normalised to -3.0 dBFS, 2 ms raised-cosine
fades, rendered to exactly the specified length. Fully deterministic (seeded).
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np
from scipy.io import wavfile
from scipy.signal import butter, lfilter, sosfilt

SR = 48_000
PEAK_DBFS = -3.0
FADE_MS = 2.0
# Impacts land just after the fade-in so the fade never rounds off a transient.
ONSET_MS = FADE_MS

# The lock's signature modes (Hz). Ratios 1 : 2.18 : 3.59, so inharmonic, bell-like.
M1, M2, M3 = 2200.0, 4800.0, 7900.0


# ----------------------------------------------------------------------------- DSP


def samples(ms: float) -> int:
    return int(round(ms * 1e-3 * SR))


def filtered_noise(n: int, band: tuple[float | None, float | None], rng) -> np.ndarray:
    """White noise through a 2nd-order Butterworth. band=(lo, hi); None leaves a side open."""
    lo, hi = band
    x = rng.standard_normal(n + 256)
    if lo and hi:
        sos = butter(2, [lo, hi], btype="bandpass", fs=SR, output="sos")
    elif lo:
        sos = butter(2, lo, btype="highpass", fs=SR, output="sos")
    else:
        sos = butter(2, hi, btype="lowpass", fs=SR, output="sos")
    return sosfilt(sos, x)[256:]  # drop the filter's start-up


def burst(n: int, at_ms: float, len_ms: float, band, rng, level: float = 1.0) -> np.ndarray:
    """A mechanical impact: filtered noise, hard onset, exponential decay, len_ms long."""
    out = np.zeros(n)
    start = samples(ONSET_MS + at_ms)
    m = min(samples(len_ms), n - start)
    if m <= 0:
        return out
    t = np.arange(m) / SR
    seg = filtered_noise(m, band, rng) * np.exp(-t / (len_ms * 1e-3 / 3.5))
    out[start : start + m] = level * seg / np.max(np.abs(seg))
    return out


def resonate(exc: np.ndarray, modes, pitch: float = 1.0) -> np.ndarray:
    """Run an excitation through modal resonators. modes = [(freq_hz, decay_ms, amp), ...]."""
    out = np.zeros_like(exc)
    for freq, decay_ms, amp in modes:
        r = np.exp(-1.0 / (decay_ms * 1e-3 * SR))
        w = 2 * np.pi * min(freq * pitch, 0.45 * SR) / SR
        y = lfilter([1.0], [1.0, -2 * r * np.cos(w), r * r], exc)
        out += amp * y / np.max(np.abs(y))
    return out


def stick_slip(n: int, start_ms: float, end_ms: float, rate_hz, rng, jitter: float = 0.25) -> np.ndarray:
    """A train of tiny irregular ticks: metal creeping over metal. rate_hz(u) with u in 0..1."""
    out = np.zeros(n)
    t = start_ms
    while t < end_ms:
        u = (t - start_ms) / (end_ms - start_ms)
        i = samples(ONSET_MS + t)
        m = min(samples(0.6), n - i)
        if m > 0:
            out[i : i + m] += rng.uniform(0.5, 1.0) * rng.standard_normal(m) * np.hanning(m)
        t += 1000.0 / rate_hz(u) * (1 + jitter * rng.uniform(-1, 1))
    return out


def swell(n: int, attack_ms: float, hold_until_ms: float, release_ms: float) -> np.ndarray:
    """Attack / hold / exponential-release envelope for friction sounds."""
    t = np.arange(n) / SR * 1e3 - ONSET_MS
    env = np.clip(t / attack_ms, 0, 1) ** 1.5
    return env * np.where(t > hold_until_ms, np.exp(-(t - hold_until_ms) / release_ms), 1.0)


def finalize(x: np.ndarray) -> np.ndarray:
    """2 ms fades, then peak-normalise to PEAK_DBFS and quantise to int16."""
    x = x - np.mean(x)
    f = samples(FADE_MS)
    ramp = 0.5 - 0.5 * np.cos(np.pi * np.arange(f) / f)
    x[:f] *= ramp
    x[-f:] *= ramp[::-1]
    target = 10 ** (PEAK_DBFS / 20) * 32768
    return np.round(x / np.max(np.abs(x)) * target).astype(np.int16)


# -------------------------------------------------------------------------- sounds
# Each takes the sample count, a variation index v (0-based) and a seeded rng.

# Per-variation pitch offsets: about +-50 cents, enough to tell apart, still one object.
PITCH = (1.0, 0.972, 1.031)


def pin_touch(n, v, rng):
    """Steel hook tip tapping a brass key pin: almost all transient, a whisper of ring."""
    p = PITCH[v]
    hit = burst(n, 0, 1.0, (5000, None), rng)
    amps = [(0.25, 0.9, 0.7), (0.18, 0.7, 0.9), (0.32, 0.8, 0.6)][v]
    ring = resonate(hit, [(M1, 6.5, amps[0]), (M2, 5.5, amps[1]), (M3, 4.5, amps[2]), (11300, 3.0, 0.35)], p)
    return 0.55 * hit + ring


def pin_slide(n, v, rng):
    """Brass pin scraping up its chamber: gritty friction that brightens as it rises."""
    p = PITCH[v]
    env = swell(n, 6, 34, 9)
    u = np.linspace(0, 1, n)
    low = filtered_noise(n, (1400 * p, 3600 * p), rng)
    high = filtered_noise(n, (3200 * p, 7500 * p), rng)
    bed = (1 - u) * low + u * 0.8 * high  # upward travel: the scrape climbs in pitch
    grains = stick_slip(n, 0, 50, lambda q: 520 + 380 * q + 60 * v, rng, jitter=0.6)
    chatter = resonate(grains, [(M1, 1.2, 0.8), (M2, 0.9, 0.6), (3400, 1.0, 0.4)], p)
    return env * (0.5 * bed / np.max(np.abs(bed)) + chatter)


def pin_set(n, v, rng):
    """Driver clears the shear line; the plug gives a hair and fetches up on the next pin."""
    p = PITCH[v]
    hit = burst(n, 0, 2.0, (2000, 9500), rng)
    click = resonate(hit, [(M1, 16, 0.9), (M2, 11, 0.75), (M3, 7, 0.5), (3350, 9, 0.3 + 0.1 * v)], p)
    # The plug's own weight: a short brass knock under the click.
    body = resonate(burst(n, 0.3, 3.0, (150, 1500), rng), [(640, 9, 0.55), (1180, 7, 0.3)], p)
    # ...and it stopping against the next binding pin a few ms later.
    stop_at = 7.5 + 1.5 * v
    stop = burst(n, stop_at, 1.2, (3000, None), rng, level=0.3)
    stop_ring = resonate(stop, [(M1, 8, 0.25), (M2, 6, 0.3)], p * 1.04)
    return 0.8 * hit + click + body + 0.5 * stop + stop_ring


def spool_counter_rotate(n, v, rng):
    """The plug creeping backwards over a spool's lip, then the lip letting go."""
    creak = stick_slip(n, 0, 72, lambda q: 95 + 110 * q * q, rng, jitter=0.3)
    groan = resonate(creak, [(380, 6, 0.9), (760, 4, 0.6), (1450, 3, 0.35), (M1, 2, 0.15)])
    groan *= swell(n, 10, 70, 6)
    tick = burst(n, 76, 1.5, (1500, 7000), rng)
    tick_ring = resonate(tick, [(M1, 9, 0.7), (M2, 6, 0.35), (820, 9, 0.5)])
    return 0.75 * groan + 0.5 * tick + tick_ring


def serrated_click(n, v, rng):
    """A serration catching: the set click with the body taken out. Thin, bright, short."""
    p = (1.12, 1.19)[v]
    hit = burst(n, 0, 1.0, (6000, None), rng)
    ring = resonate(hit, [(M2, 7.5, 0.9), (M3, 6.5, 0.8), (10600, 4.0, 0.4), (M1, 5.0, 0.12)], p)
    return 0.6 * hit + ring


def pin_overshoot(n, v, rng):
    """Key pin shoved past the shear line and wedged: a dead brass thud, no ring to speak of."""
    hit = burst(n, 0, 3.0, (90, 1100), rng)
    thud = resonate(hit, [(240, 20, 1.0), (410, 16, 0.7), (690, 11, 0.45), (M1, 3, 0.06)])
    sos = butter(2, 2600, btype="lowpass", fs=SR, output="sos")
    return sosfilt(sos, 0.6 * hit + thud)


def core_tension_creak(n, v, rng):
    """Tension wrench loading the plug: the wrench springs, the brass creaks, it seats."""
    creak = stick_slip(n, 4, 88, lambda q: 60 + 85 * q, rng, jitter=0.35)
    groan = resonate(creak, [(520, 5, 0.9), (1340, 3.5, 0.5), (M1, 2.5, 0.25)])
    groan *= swell(n, 30, 86, 8)
    # Spring steel flexing: one soft partial bending upward as the load comes on.
    t = np.arange(n) / SR
    flex = np.sin(2 * np.pi * (880 * t + 900 * t * t)) * swell(n, 18, 40, 22)
    seat = burst(n, 92, 1.5, (1200, 6000), rng)
    seat_ring = resonate(seat, [(M1, 8, 0.6), (980, 8, 0.5), (M2, 4, 0.2)])
    return 0.8 * groan + 0.12 * flex + 0.35 * seat + 0.8 * seat_ring


def plug_turn_open(n, v, rng):
    """A heavy brass plug turning a quarter turn on its bearing surfaces and hitting the stop."""
    t = np.arange(n) / SR
    start = burst(n, 0, 1.5, (1500, 7000), rng)
    start_ring = resonate(start, [(M1, 7, 0.5), (640, 9, 0.5)])
    # Rotation: low, smooth friction with a slow machining-mark flutter, slowing into the stop.
    rub = filtered_noise(n, (160, 1300), rng)
    rub = rub + 0.5 * resonate(rub, [(310, 4, 1.0), (640, 3, 0.6)])
    flutter = 1 + 0.3 * np.sin(2 * np.pi * (48 * t - 55 * t * t))
    turn = rub / np.max(np.abs(rub)) * flutter * swell(n, 28, 180, 10)
    stop = burst(n, 192, 3.0, (120, 4000), rng)
    stop_ring = resonate(stop, [(310, 13, 1.0), (640, 11, 0.7), (M1, 9, 0.4), (M2, 5, 0.15)])
    return 0.3 * start + 0.5 * start_ring + 0.55 * turn + 0.6 * stop + 1.1 * stop_ring


def shackle_pop(n, v, rng):
    """Hardened steel shackle thrown up by its spring: a hard snap and a real ring."""
    hit = burst(n, 0, 3.0, (700, 12000), rng)
    ring = resonate(hit, [(M1, 62, 1.0), (M2, 44, 0.7), (M3, 26, 0.45), (1260, 48, 0.5), (11900, 12, 0.2)])
    thump = resonate(burst(n, 0, 3.0, (80, 600), rng), [(185, 14, 0.8)])
    # The shackle reaching the top of its travel and knocking the body.
    top = burst(n, 36, 2.0, (1200, 9000), rng)
    top_ring = resonate(top, [(M1 * 1.03, 50, 0.4), (M2 * 0.98, 30, 0.3), (1260, 40, 0.25)])
    return 0.9 * hit + ring + thump + 0.4 * top + top_ring


def padlock_reset(n, v, rng):
    """Shackle pushed home (clack), latch catching (snap), drivers dropping back on their springs."""
    clack = burst(n, 0, 3.0, (200, 5000), rng)
    clack_ring = resonate(clack, [(300, 16, 1.0), (910, 13, 0.7), (M1, 12, 0.5), (1260, 14, 0.35)])
    snap = burst(n, 84, 2.0, (2000, 11000), rng)
    snap_ring = resonate(snap, [(M1, 27, 0.9), (M2, 20, 0.7), (M3, 13, 0.45), (640, 15, 0.4)])
    drops = np.zeros(n)
    for k, at in enumerate((101, 113, 122, 136, 147)):
        tick = burst(n, at, 1.0, (3500, None), rng, level=0.5 - 0.06 * k)
        drops += 0.5 * tick + resonate(tick, [(M2 * (0.92 + 0.045 * k), 5, 0.3), (M1 * (0.95 + 0.03 * k), 6, 0.25)])
    return 0.7 * clack + clack_ring + 0.75 * snap + 0.95 * snap_ring + 0.45 * drops


# name, duration ms, number of variations, recipe
PACK = [
    ("pin_touch", 35, 3, pin_touch),
    ("pin_slide", 60, 3, pin_slide),
    ("pin_set", 85, 3, pin_set),
    ("spool_counter_rotate", 110, 1, spool_counter_rotate),
    ("serrated_click", 45, 2, serrated_click),
    ("pin_overshoot", 95, 1, pin_overshoot),
    ("core_tension_creak", 120, 1, core_tension_creak),
    ("plug_turn_open", 240, 1, plug_turn_open),
    ("shackle_pop", 280, 1, shackle_pop),
    ("padlock_reset", 220, 1, padlock_reset),
]


# ---------------------------------------------------------------------------- main


def main() -> int:
    default_out = Path(__file__).resolve().parent.parent / "app/src/main/res/raw"
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", type=Path, default=default_out)
    out_dir: Path = parser.parse_args().out
    out_dir.mkdir(parents=True, exist_ok=True)

    print(f"{'file':<28}{'ms':>8}{'peak dBFS':>11}{'tail dB':>9}  check")
    failures = 0
    seed = 0
    for name, ms, variations, recipe in PACK:
        for v in range(variations):
            seed += 1
            n = samples(ms)
            pcm = finalize(recipe(n, v, np.random.default_rng(seed)))
            filename = f"{name}_{v + 1}.wav" if variations > 1 else f"{name}.wav"
            path = out_dir / filename
            wavfile.write(path, SR, pcm)

            # Verify what is actually on disk, not what we meant to write.
            rate, data = wavfile.read(path)
            duration_ms = len(data) / rate * 1e3
            peak = 20 * np.log10(np.max(np.abs(data.astype(np.int32))) / 32768)
            # Level of the last 8 ms before the fade-out, relative to peak: shows the sound is
            # still decaying at the cut rather than sitting on dead air.
            f = samples(FADE_MS)
            tail = data[-samples(8) - f : -f].astype(np.float64)
            tail_db = 20 * np.log10(max(np.sqrt(np.mean(tail**2)), 1e-9) / 32768) - peak
            ok = (
                rate == SR
                and data.dtype == np.int16
                and data.ndim == 1
                and len(data) == n
                and abs(peak - PEAK_DBFS) < 0.01
                and data[0] == 0
                and tail_db > -58
            )
            failures += not ok
            print(f"{filename:<28}{duration_ms:>8.1f}{peak:>11.2f}{tail_db:>9.1f}  {'ok' if ok else 'FAIL'}")

    total = sum(v for _, _, v, _ in PACK)
    print(f"\n{total} files -> {out_dir}  ({SR} Hz, 16-bit mono)")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
