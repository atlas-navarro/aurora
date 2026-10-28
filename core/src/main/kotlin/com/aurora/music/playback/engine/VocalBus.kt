package com.aurora.music.playback.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// a rough vocal estimate for the concert pa: half-octave bands of the mid, each kept by a wiener-style soft mask
// ((mid - side) / (mid + side) energy) times a vocal-band prior, pulled down on hits and when the mid has no pitch,
// smoothed over time and frequency. the bands are differences of low-passes, so there is no latency
class VocalBus {
    private val coef = DoubleArray(EDGES.size * 5)
    private val midState = DoubleArray(EDGES.size * 2)
    private val sideState = DoubleArray(EDGES.size * 2)
    private val midLow = DoubleArray(EDGES.size)
    private val sideLow = DoubleArray(EDGES.size)
    private val midEnergy = DoubleArray(BANDS)
    private val sideEnergy = DoubleArray(BANDS)
    private val fast = DoubleArray(BANDS)
    private val slow = DoubleArray(BANDS)
    private val raw = DoubleArray(BANDS)
    private val mask = DoubleArray(BANDS)
    private var energyK = 0.0; private var fastK = 0.0; private var slowK = 0.0; private var maskK = 0.0
    private val pitch = DoubleArray(RING)
    private val scratch = DoubleArray(RING)
    private val squares = DoubleArray(RING + 1)
    private var pitchPos = 0; private var tick = 0; private var pushes = 0; private var voiced = 0.0
    private var step = 4; private var lagMin = 24; private var lagMax = 150; private var window = 360

    fun configure(rate: Int) {
        for (i in EDGES.indices) {
            val w = 2 * PI * min(EDGES[i], rate * 0.45) / rate
            val alpha = sin(w) / (2 * 0.7071); val cw = cos(w); val a0 = 1 + alpha; val c = i * 5
            coef[c] = (1 - cw) / 2 / a0; coef[c + 1] = (1 - cw) / a0; coef[c + 2] = coef[c]
            coef[c + 3] = -2 * cw / a0; coef[c + 4] = (1 - alpha) / a0
        }
        energyK = smoothing(0.02, rate); fastK = smoothing(0.004, rate); slowK = smoothing(0.06, rate); maskK = smoothing(0.03, rate)
        // pitch is tracked on the 1.6 khz low-pass at about 12 khz, for voices from 80 to 500 hz
        step = max(1, Math.round(rate / 12_000.0).toInt())
        val decimated = rate.toDouble() / step
        lagMin = (decimated / 500).toInt(); lagMax = (decimated / 80).toInt(); window = (0.03 * decimated).toInt()
        reset()
    }

    fun reset() {
        midState.fill(0.0); sideState.fill(0.0); midEnergy.fill(0.0); sideEnergy.fill(0.0)
        fast.fill(0.0); slow.fill(0.0); mask.fill(0.0)
        pitch.fill(0.0); pitchPos = 0; tick = 0; pushes = 0; voiced = 0.0
    }

    fun copyStateFrom(previous: VocalBus) {
        previous.midState.copyInto(midState); previous.sideState.copyInto(sideState)
        previous.midEnergy.copyInto(midEnergy); previous.sideEnergy.copyInto(sideEnergy)
        previous.fast.copyInto(fast); previous.slow.copyInto(slow); previous.mask.copyInto(mask)
        previous.pitch.copyInto(pitch); pitchPos = previous.pitchPos; tick = previous.tick; pushes = previous.pushes; voiced = previous.voiced
    }

    // mono vocal estimate for this frame
    fun next(left: Double, right: Double): Double {
        val mid = (left + right) * 0.5; val side = (left - right) * 0.5
        for (i in EDGES.indices) { midLow[i] = lowPass(i, midState, mid); sideLow[i] = lowPass(i, sideState, side) }
        if (++tick == step) {
            tick = 0
            pitch[pitchPos] = midLow[PITCH_EDGE]; pitchPos = (pitchPos + 1) and (RING - 1)
            if (++pushes == HOP) { pushes = 0; voiced = periodicity() }
        }
        val pitched = 0.3 + 0.7 * ((voiced - 0.35) / 0.4).coerceIn(0.0, 1.0)
        for (b in 0 until BANDS) {
            val m = midLow[b + 1] - midLow[b]; val s = sideLow[b + 1] - sideLow[b]
            midEnergy[b] += energyK * (m * m - midEnergy[b]); sideEnergy[b] += energyK * (s * s - sideEnergy[b])
            fast[b] += fastK * (m * m - fast[b]); slow[b] += slowK * (m * m - slow[b])
            // a voice holds its level through a syllable; hits jump up and then die away
            val ratio = (fast[b] + 1e-18) / (slow[b] + 1e-18)
            val unsteady = ((max(ratio, 1 / ratio) - 1.5) / 2.5).coerceIn(0.0, 1.0)
            val centred = max(0.0, (midEnergy[b] - sideEnergy[b]) / (midEnergy[b] + sideEnergy[b] + 1e-18))
            raw[b] = PRIOR[b] * centred * (1 - 0.9 * unsteady) * pitched
        }
        var vocal = 0.0
        for (b in 0 until BANDS) {
            val spread = 0.5 * raw[b] + 0.25 * (raw[max(0, b - 1)] + raw[min(BANDS - 1, b + 1)])
            mask[b] += maskK * (spread - mask[b])
            vocal += mask[b] * (midLow[b + 1] - midLow[b])
        }
        return vocal
    }

    // best normalised autocorrelation over the voice's pitch range, 0 for noise and near 1 for a held note
    private fun periodicity(): Double {
        val n = window + lagMax
        for (i in 0 until n) scratch[i] = pitch[(pitchPos - n + i) and (RING - 1)]
        for (i in 0 until n) squares[i + 1] = squares[i] + scratch[i] * scratch[i]
        val e0 = squares[n] - squares[n - window]
        if (e0 < 1e-12) return 0.0
        var best = 0.0
        for (lag in lagMin..lagMax) {
            var num = 0.0
            for (i in n - window until n) num += scratch[i] * scratch[i - lag]
            best = max(best, num / sqrt(e0 * (squares[n - lag] - squares[n - window - lag]) + 1e-30))
        }
        return best
    }

    private fun lowPass(i: Int, st: DoubleArray, x: Double): Double {
        val c = i * 5; val s = i * 2
        val y = coef[c] * x + st[s]
        st[s] = coef[c + 1] * x - coef[c + 3] * y + st[s + 1]
        st[s + 1] = coef[c + 2] * x - coef[c + 4] * y
        return y
    }

    private fun smoothing(sec: Double, rate: Int) = 1 - exp(-1 / (sec * rate))

    companion object {
        // half-octave band edges from 100 hz to 12.8 khz
        private val EDGES = DoubleArray(15) { 100.0 * Math.pow(2.0, it / 2.0) }
        private val BANDS = EDGES.size - 1
        private const val PITCH_EDGE = 8
        private const val RING = 1024
        private const val HOP = 128
        // where a voice lives: little below 200 hz, most from 800 hz to 4 khz, tapering off above 7 khz
        private val PRIOR = DoubleArray(BANDS) { b ->
            val hz = kotlin.math.sqrt(EDGES[b] * EDGES[b + 1])
            when { hz < 200 -> 0.2; hz < 800 -> 0.6; hz < 4000 -> 1.0; hz < 7500 -> 0.6; else -> 0.2 }
        }
    }
}
