package com.aurora.music.playback.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sin

class VocalBusTest {
    private val rate = 48_000
    private val frames = rate * 6

    // a sung vowel: 200 hz with vibrato, formants at 700, 1200 and 2600 hz, in 0.4 s syllables, dead centre
    private val voice = DoubleArray(frames).also { v ->
        var phase = 0.0
        for (i in v.indices) {
            val t = i.toDouble() / rate
            val f0 = 200 + 12 * sin(2 * PI * 5.5 * t)
            phase += 2 * PI * f0 / rate
            val inSyllable = t % 0.55
            val env = when { inSyllable < 0.03 -> inSyllable / 0.03; inSyllable < 0.4 -> 1.0; inSyllable < 0.43 -> (0.43 - inSyllable) / 0.03; else -> 0.0 }
            var x = 0.0
            for (h in 1..30) {
                val hz = h * f0
                if (hz > 8000) break
                val formant = listOf(700.0 to 1.0, 1200.0 to 0.6, 2600.0 to 0.35).sumOf { (fc, a) -> a * exp(-(ln(hz / fc) / 0.25).let { it * it }) }
                x += (0.08 / h + formant) * sin(h * phase)
            }
            v[i] = 0.25 * env * x
        }
    }

    // a wide pad (different noise per side) and a centred snare every quarter second
    private fun background(): Pair<DoubleArray, DoubleArray> {
        val rnd = java.util.Random(3)
        val l = DoubleArray(frames); val r = DoubleArray(frames)
        var pl = 0.0; var pr = 0.0; var snare = 0.0
        for (i in 0 until frames) {
            pl += 0.2 * (rnd.nextGaussian() - pl); pr += 0.2 * (rnd.nextGaussian() - pr)
            val hit = i % (rate / 4)
            if (hit == 0) snare = 1.0 else snare *= exp(-1 / (0.05 * rate))
            val s = snare * rnd.nextGaussian() * 0.35
            l[i] = 0.35 * pl + s; r[i] = 0.35 * pr + s
        }
        return l to r
    }

    private fun run(l: DoubleArray, r: DoubleArray, bus: VocalBus = VocalBus().apply { configure(rate) }) =
        DoubleArray(frames) { bus.next(l[it], r[it]) }

    private fun dot(a: DoubleArray, b: DoubleArray, from: Int) = (from until a.size).sumOf { a[it] * b[it] }

    @Test fun theMaskFavoursACentredVoiceOverAWideAndPercussiveBackground() {
        val (bl, br) = background()
        val from = rate / 2
        fun passDb(l: DoubleArray, r: DoubleArray): Double {
            val out = run(l, r); val mid = DoubleArray(frames) { (l[it] + r[it]) / 2 }
            return 10 * log10(dot(out, out, from) / dot(mid, mid, from))
        }
        val mixL = DoubleArray(frames) { voice[it] + bl[it] }; val mixR = DoubleArray(frames) { voice[it] + br[it] }
        val out = run(mixL, mixR)
        fun heldDb(singing: Boolean): Double {
            var o = 0.0; var m = 0.0
            for (i in from until frames) {
                val t = i.toDouble() / rate % 0.55
                if (if (singing) t in 0.05..0.38 else t > 0.45) { o += out[i] * out[i]; val x = (mixL[i] + mixR[i]) / 2; m += x * x }
            }
            return 10 * log10(o / m)
        }
        val voiceDb = passDb(voice, voice); val backgroundDb = passDb(bl, br)
        val singing = heldDb(true); val between = heldDb(false)
        println("voice alone ${"%.1f".format(voiceDb)} dB, background alone ${"%.1f".format(backgroundDb)} dB, " +
            "in the mix ${"%.1f".format(singing)} dB while singing and ${"%.1f".format(between)} dB between syllables")
        assertTrue(voiceDb > -3 && backgroundDb < -15 && singing - between > 12)
    }

    @Test fun chunkingAndStateCopiesDoNotChangeTheEstimate() {
        val (bl, br) = background()
        val whole = run(bl, br)
        val first = VocalBus().apply { configure(rate) }
        val split = DoubleArray(frames)
        for (i in 0 until frames / 2) split[i] = first.next(bl[i], br[i])
        val second = VocalBus().apply { configure(rate); copyStateFrom(first) }
        for (i in frames / 2 until frames) split[i] = second.next(bl[i], br[i])
        assertArrayEquals(whole, split, 0.0)
    }
}
