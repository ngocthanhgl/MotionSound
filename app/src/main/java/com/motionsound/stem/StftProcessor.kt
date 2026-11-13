package com.motionsound.stem

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * STFT/iSTFT for MDX-Net stem models.
 *
 * Key properties:
 *  - Spectrogram layout is [f * dimT + t] (frequency-major), matching the model
 *    input/output packing. iSTFT reads exactly this layout (bins above dimF are
 *    treated as zero because stft() truncates the full spectrum to dimF bins).
 *  - Left/right channels are packed into ONE complex FFT (z = L + i*R) and
 *    separated afterwards via conjugate symmetry, halving the transform count.
 *  - Hann window and Bluestein chirp tables are cached per n_fft; Bluestein
 *    kernels (needed for n_fft = 6144) are built once instead of per frame.
 */
object StftProcessor {

    /** Cached per-nFft FFT/window tables. */
    private class FftTables(val nFft: Int) {
        val window = FloatArray(nFft) { i ->
            (0.5 * (1.0 - cos(2.0 * PI * i / nFft))).toFloat() // periodic Hann
        }
        val isPow2: Boolean = nFft and (nFft - 1) == 0

        // Bluestein tables (only meaningful when !isPow2)
        val bM: Int
        val chirpR: FloatArray
        val chirpI: FloatArray
        val kernelR: FloatArray // FFT_m(conj(chirp)), cached
        val kernelI: FloatArray

        init {
            if (isPow2) {
                bM = 0
                chirpR = FloatArray(0); chirpI = chirpR
                kernelR = chirpR; kernelI = chirpR
            } else {
                var m = 1
                while (m < 2L * nFft - 1) m = m shl 1
                bM = m
                chirpR = FloatArray(nFft)
                chirpI = FloatArray(nFft)
                for (i in 0 until nFft) {
                    val angle = PI * i * i / nFft
                    chirpR[i] = cos(angle).toFloat()
                    chirpI[i] = -sin(angle).toFloat()
                }
                // kernel = FFT_m of the conjugate chirp, built once
                val kR = FloatArray(m)
                val kI = FloatArray(m)
                for (i in 0 until nFft) {
                    kR[i] = chirpR[i]
                    kI[i] = -chirpI[i]
                }
                fftRadix2InPlace(kR, kI)
                kernelR = kR
                kernelI = kI
            }
        }
    }

    private val tablesCache = ConcurrentHashMap<Int, FftTables>()

    private fun tables(nFft: Int): FftTables = tablesCache.getOrPut(nFft) { FftTables(nFft) }

    /**
     * Forward STFT of interleaved stereo audio.
     * Returns 4 flattened arrays [lRe, lIm, rRe, rIm], each of size dimF * dimT,
     * element (f, t) stored at f * dimT + t.
     */
    fun stft(
        stereoInterleaved: FloatArray,
        nFft: Int,
        hopLength: Int,
        dimF: Int,
        dimT: Int
    ): Array<FloatArray> {
        val t = tables(nFft)
        val window = t.window
        val padSize = nFft / 2
        val padded = reflectPadStereo(stereoInterleaved, padSize)

        val lRe = FloatArray(dimF * dimT)
        val lIm = FloatArray(dimF * dimT)
        val rRe = FloatArray(dimF * dimT)
        val rIm = FloatArray(dimF * dimT)

        val zRe = FloatArray(nFft)
        val zIm = FloatArray(nFft)

        for (frame in 0 until dimT) {
            val p = frame * hopLength * 2
            // packed complex input: z = L + i*R
            for (i in 0 until nFft) {
                val w = window[i]
                zRe[i] = padded[p + i * 2] * w
                zIm[i] = padded[p + i * 2 + 1] * w
            }
            fftComplexInPlace(zRe, zIm, t)

            // unpack via conjugate symmetry of real L and R:
            //   L(k)   = (Z(k) + conj(Z(N-k))) / 2
            //   R(k)   = (Z(k) - conj(Z(N-k))) / (2i)
            val base = frame
            lRe[base] = zRe[0]
            rRe[base] = zIm[0]
            for (f in 1 until dimF) {
                val f2 = nFft - f
                val zrK = zRe[f]
                val ziK = zIm[f]
                val zrN = zRe[f2]
                val ziN = zIm[f2]
                val idx = f * dimT + base
                lRe[idx] = 0.5f * (zrK + zrN)
                lIm[idx] = 0.5f * (ziK - ziN)
                rRe[idx] = 0.5f * (ziK + ziN)
                rIm[idx] = 0.5f * (zrN - zrK)
            }
        }
        return arrayOf(lRe, lIm, rRe, rIm)
    }

    /**
     * Inverse STFT. [stftData] is [lRe, lIm, rRe, rIm] flattened as f * dimT + t.
     * Bins >= dimF are assumed zero (stft truncated the spectrum there).
     */
    fun istft(
        stftData: Array<FloatArray>,
        nFft: Int,
        hopLength: Int,
        nBins: Int,
        length: Int,
        dimF: Int,
        dimT: Int
    ): FloatArray {
        val lRe = stftData[0]
        val lIm = stftData[1]
        val rRe = stftData[2]
        val rIm = stftData[3]
        val t = tables(nFft)
        val window = t.window
        val padSize = nFft / 2
        val outputLen = length + nFft
        val leftOut = FloatArray(outputLen)
        val rightOut = FloatArray(outputLen)
        val winSum = FloatArray(outputLen)

        val zRe = FloatArray(nFft)
        val zIm = FloatArray(nFft)
        val maxBin = minOf(dimF, nBins)

        for (frame in 0 until dimT) {
            java.util.Arrays.fill(zRe, 0f)
            java.util.Arrays.fill(zIm, 0f)
            // rebuild packed spectrum: Z = X + iY (X = L spectrum, Y = R spectrum)
            for (f in 0 until maxBin) {
                val idx = f * dimT + frame
                val lre = lRe[idx]
                val lim = lIm[idx]
                val rre = rRe[idx]
                val rim = rIm[idx]
                // Z(k)     = (Lre - Rim) + i(Lim + Rre)
                zRe[f] = lre - rim
                zIm[f] = lim + rre
                if (f > 0) {
                    // Z(N-k) = conj(L(k)) + i*conj(R(k)) = (Lre + Rim) + i(Rre - Lim)
                    val f2 = nFft - f
                    zRe[f2] = lre + rim
                    zIm[f2] = rre - lim
                }
            }
            ifftComplexInPlace(zRe, zIm, t)

            val off = frame * hopLength
            for (i in 0 until nFft) {
                val o = off + i
                if (o < outputLen) {
                    val w = window[i]
                    leftOut[o] += zRe[i] * w
                    rightOut[o] += zIm[i] * w
                    winSum[o] += w * w
                }
            }
        }

        val result = FloatArray(length * 2)
        for (i in 0 until length) {
            val idx = i + padSize
            val w = if (winSum[idx] > 1e-8f) winSum[idx] else 1e-8f
            result[i * 2] = leftOut[idx] / w
            result[i * 2 + 1] = rightOut[idx] / w
        }
        return result
    }

    fun reflectPad(signal: FloatArray, padSize: Int): FloatArray {
        val result = FloatArray(signal.size + padSize * 2)
        for (i in 0 until padSize) {
            result[i] = signal[padSize - i]
        }
        signal.copyInto(result, padSize)
        for (i in 0 until padSize) {
            val srcIdx = signal.size - 2 - i
            if (srcIdx >= 0) {
                result[padSize + signal.size + i] = signal[srcIdx]
            } else {
                result[padSize + signal.size + i] = signal[0]
            }
        }
        return result
    }

    /** Reflect-pads interleaved stereo audio (2 samples per frame). */
    private fun reflectPadStereo(stereoInterleaved: FloatArray, padSize: Int): FloatArray {
        val frames = stereoInterleaved.size / 2
        val out = FloatArray((frames + padSize * 2) * 2)
        // left pad: reflect, mirroring samples 1..padSize
        for (i in 0 until padSize) {
            val src = (padSize - i).coerceIn(0, frames - 1)
            out[i * 2] = stereoInterleaved[src * 2]
            out[i * 2 + 1] = stereoInterleaved[src * 2 + 1]
        }
        System.arraycopy(stereoInterleaved, 0, out, padSize * 2, frames * 2)
        // right pad: reflect, mirroring samples frames-2 .. frames-1-padSize
        for (i in 0 until padSize) {
            val src = frames - 2 - i
            val dst = (padSize + frames + i) * 2
            if (src >= 0) {
                out[dst] = stereoInterleaved[src * 2]
                out[dst + 1] = stereoInterleaved[src * 2 + 1]
            } else {
                out[dst] = stereoInterleaved[0]
                out[dst + 1] = stereoInterleaved[1]
            }
        }
        return out
    }

    fun hannWindow(size: Int, periodic: Boolean = true): FloatArray {
        val n = if (periodic) size else size - 1
        return FloatArray(size) { i ->
            (0.5 * (1.0 - cos(2.0 * PI * i / n))).toFloat()
        }
    }

    /** Complex in-place forward FFT; radix-2 for power-of-two sizes, Bluestein otherwise. */
    private fun fftComplexInPlace(re: FloatArray, im: FloatArray, t: FftTables) {
        if (t.isPow2) {
            fftRadix2InPlace(re, im)
        } else {
            bluesteinFftInPlace(re, im, t)
        }
    }

    /** Complex in-place inverse FFT (1/n normalized). */
    private fun ifftComplexInPlace(re: FloatArray, im: FloatArray, t: FftTables) {
        val n = re.size
        for (i in 0 until n) im[i] = -im[i]
        fftComplexInPlace(re, im, t)
        val inv = 1f / n
        for (i in 0 until n) {
            im[i] = -im[i] * inv
            re[i] *= inv
        }
    }

    private fun bluesteinFftInPlace(re: FloatArray, im: FloatArray, t: FftTables) {
        val n = t.nFft
        val m = t.bM
        val chirpR = t.chirpR
        val chirpI = t.chirpI

        val yR = FloatArray(m)
        val yI = FloatArray(m)
        for (i in 0 until n) {
            val xr = re[i]
            val xi = im[i]
            yR[i] = xr * chirpR[i] - xi * chirpI[i]
            yI[i] = xr * chirpI[i] + xi * chirpR[i]
        }
        fftRadix2InPlace(yR, yI)

        val kR = t.kernelR
        val kI = t.kernelI
        for (i in 0 until m) {
            val ar = yR[i]
            val ai = yI[i]
            yR[i] = ar * kR[i] - ai * kI[i]
            yI[i] = ar * kI[i] + ai * kR[i]
        }

        // inverse FFT of size m (power of two)
        for (i in 0 until m) yI[i] = -yI[i]
        fftRadix2InPlace(yR, yI)
        val inv = 1f / m
        for (i in 0 until n) {
            val outR = yR[i] * inv
            val outI = -yI[i] * inv
            re[i] = outR * chirpR[i] - outI * chirpI[i]
            im[i] = outR * chirpI[i] + outI * chirpR[i]
        }
    }

    private fun fftRadix2InPlace(real: FloatArray, imag: FloatArray) {
        val n = real.size
        var j = 0
        for (i in 0 until n) {
            if (i < j) {
                var temp = real[i]; real[i] = real[j]; real[j] = temp
                temp = imag[i]; imag[i] = imag[j]; imag[j] = temp
            }
            var m = n shr 1
            while (m >= 1 && j >= m) {
                j -= m
                m = m shr 1
            }
            j += m
        }

        var step = 1
        while (step < n) {
            val halfStep = step
            step = step shl 1
            val angle = -PI.toFloat() / halfStep
            val wR = cos(angle)
            val wI = sin(angle)
            var k = 0
            while (k < n) {
                var curWR = 1f
                var curWI = 0f
                for (m in 0 until halfStep) {
                    val idx1 = k + m
                    val idx2 = k + m + halfStep
                    val tR = curWR * real[idx2] - curWI * imag[idx2]
                    val tI = curWR * imag[idx2] + curWI * real[idx2]
                    real[idx2] = real[idx1] - tR
                    imag[idx2] = imag[idx1] - tI
                    real[idx1] = real[idx1] + tR
                    imag[idx1] = imag[idx1] + tI
                    val newWR = curWR * wR - curWI * wI
                    val newWI = curWI * wI + curWI * wR
                    curWR = newWR
                    curWI = newWI
                }
                k += step
            }
        }
    }

    fun computeNumFrames(signalLength: Int, nFft: Int, hopLength: Int): Int {
        return signalLength / hopLength + 1
    }
}
