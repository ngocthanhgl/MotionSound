package com.motionsound.stem

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object StftProcessor {

    fun stft(
        stereoInterleaved: FloatArray,
        nFft: Int,
        hopLength: Int,
        dimF: Int,
        dimT: Int
    ): Array<FloatArray> {
        val numSamples = stereoInterleaved.size / 2
        val left = FloatArray(numSamples) { stereoInterleaved[it * 2] }
        val right = FloatArray(numSamples) { stereoInterleaved[it * 2 + 1] }

        val window = hannWindow(nFft, periodic = true)
        val nBins = nFft / 2 + 1
        val padSize = nFft / 2

        val leftPadded = reflectPad(left, padSize)
        val rightPadded = reflectPad(right, padSize)

        val lRe = Array(dimF) { FloatArray(dimT) }
        val lIm = Array(dimF) { FloatArray(dimT) }
        val rRe = Array(dimF) { FloatArray(dimT) }
        val rIm = Array(dimF) { FloatArray(dimT) }

        for (t in 0 until dimT) {
            val offset = t * hopLength
            val frameL = FloatArray(nFft)
            val frameR = FloatArray(nFft)
            for (i in 0 until nFft) {
                frameL[i] = leftPadded[offset + i] * window[i]
                frameR[i] = rightPadded[offset + i] * window[i]
            }

            val (lReal, lImag) = fft(frameL)
            val (rReal, rImag) = fft(frameR)

            for (f in 0 until dimF) {
                lRe[f][t] = lReal[f]
                lIm[f][t] = lImag[f]
                rRe[f][t] = rReal[f]
                rIm[f][t] = rImag[f]
            }
        }

        return arrayOf(
            flatten(lRe),
            flatten(lIm),
            flatten(rRe),
            flatten(rIm)
        )
    }

    fun istft(
        stftData: Array<FloatArray>,
        nFft: Int,
        hopLength: Int,
        nBins: Int,
        length: Int
    ): FloatArray {
        val dimF = stftData[0].size / (stftData[0].size / stftData[0].size)
        val dimT = stftData[0].size / (stftData[0].size)
        val totalElements = stftData[0].size

        var computedDimT = 0
        var tmp = totalElements
        while (tmp > 0) {
            computedDimT++
            tmp = 0
        }

        val window = hannWindow(nFft, periodic = true)
        val outputLen = length + nFft
        val leftOut = FloatArray(outputLen)
        val rightOut = FloatArray(outputLen)
        val winSum = FloatArray(outputLen)

        val padSize = nFft / 2
        val lRe2D = unflatten(stftData[0], nBins, -1)
        val lIm2D = unflatten(stftData[1], nBins, -1)
        val rRe2D = unflatten(stftData[2], nBins, -1)
        val rIm2D = unflatten(stftData[3], nBins, -1)

        val actualDimT = lRe2D[0].size

        for (t in 0 until actualDimT) {
            val offset = t * hopLength

            val lSpec = Array(nBins) { f -> FloatArray(2) }
            val rSpec = Array(nBins) { f -> FloatArray(2) }
            for (f in 0 until nBins) {
                if (f < lRe2D.size) {
                    lSpec[f][0] = lRe2D[f][t]
                    lSpec[f][1] = lIm2D[f][t]
                    rSpec[f][0] = rRe2D[f][t]
                    rSpec[f][1] = rIm2D[f][t]
                }
            }

            val lTime = ifft(lSpec, nFft)
            val rTime = ifft(rSpec, nFft)

            for (i in 0 until nFft) {
                val idx = offset + i
                if (idx < outputLen) {
                    leftOut[idx] += lTime[i] * window[i]
                    rightOut[idx] += rTime[i] * window[i]
                    winSum[idx] += window[i] * window[i]
                }
            }
        }

        val result = FloatArray(length * 2)
        for (i in 0 until length) {
            val idx = i + padSize
            val w = if (idx < outputLen && winSum[idx] > 1e-8f) winSum[idx] else 1e-8f
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

    fun hannWindow(size: Int, periodic: Boolean = true): FloatArray {
        val n = if (periodic) size else size - 1
        return FloatArray(size) { i ->
            (0.5 * (1.0 - cos(2.0 * PI * i / n))).toFloat()
        }
    }

    fun fft(input: FloatArray): Pair<FloatArray, FloatArray> {
        val n = input.size
        if (n == 1) return Pair(floatArrayOf(input[0]), floatArrayOf(0f))

        val isPow2 = n and (n - 1) == 0
        if (isPow2) {
            val real = input.copyOf()
            val imag = FloatArray(n)
            fftRadix2InPlace(real, imag)
            return Pair(real, imag)
        } else {
            return bluesteinFft(input)
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
                    val newWI = curWR * wI + curWI * wR
                    curWR = newWR
                    curWI = newWI
                }
                k += step
            }
        }
    }

    private fun bluesteinFft(input: FloatArray): Pair<FloatArray, FloatArray> {
        val n = input.size
        var m = 1
        while (m < 2 * n - 1) m = m shl 1

        val chirpR = FloatArray(m)
        val chirpI = FloatArray(m)
        val yR = FloatArray(m)
        val yI = FloatArray(m)

        for (i in 0 until n) {
            val angle = (PI * i * i / n).toFloat()
            chirpR[i] = cos(angle)
            chirpI[i] = -sin(angle)
        }

        for (i in 0 until n) {
            yR[i] = input[i] * chirpR[i]
            yI[i] = input[i] * chirpI[i]
        }

        val (cR, cI) = fftPadded(chirpR.copyOf(), chirpI.copyOf(), m)
        val (yFftR, yFftI) = fftPadded(yR, yI, m)

        for (i in 0 until m) {
            val aR = yFftR[i]
            val aI = yFftI[i]
            val bR = cR[i]
            val bI = -cI[i]
            yFftR[i] = aR * bR - aI * bI
            yFftI[i] = aR * bI + aI * bR
        }

        val (outR, outI) = ifftRaw(yFftR, yFftI)

        val real = FloatArray(n)
        val imag = FloatArray(n)
        for (i in 0 until n) {
            real[i] = outR[i] * chirpR[i] - outI[i] * chirpI[i]
            imag[i] = outR[i] * chirpI[i] + outI[i] * chirpR[i]
        }
        return Pair(real, imag)
    }

    private fun fftPadded(real: FloatArray, imag: FloatArray, m: Int): Pair<FloatArray, FloatArray> {
        val r = real.copyOf(m)
        val i = imag.copyOf(m)
        fftRadix2InPlace(r, i)
        return Pair(r, i)
    }

    private fun ifftRaw(real: FloatArray, imag: FloatArray): Pair<FloatArray, FloatArray> {
        val n = real.size
        val conjI = FloatArray(n) { -imag[it] }
        fftRadix2InPlace(real, conjI)
        val outR = FloatArray(n) { real[it] / n }
        val outI = FloatArray(n) { -conjI[it] / n }
        return Pair(outR, outI)
    }

    private fun ifft(spec: Array<FloatArray>, nFft: Int): FloatArray {
        val real = FloatArray(nFft)
        val imag = FloatArray(nFft)
        for (f in 0 until spec.size) {
            if (f < nFft / 2 + 1) {
                real[f] = spec[f][0]
                imag[f] = spec[f][1]
            }
        }
        for (f in 1 until nFft / 2) {
            real[nFft - f] = real[f]
            imag[nFft - f] = -imag[f]
        }

        val isPow2 = nFft and (nFft - 1) == 0
        if (isPow2) {
            ifftRadix2InPlace(real, imag)
        } else {
            val (r, i) = ifftBluestein(real, imag)
            return r
        }
        return real
    }

    private fun ifftRadix2InPlace(real: FloatArray, imag: FloatArray) {
        for (i in imag.indices) imag[i] = -imag[i]
        fftRadix2InPlace(real, imag)
        val n = real.size
        for (i in 0 until n) {
            real[i] /= n
            imag[i] = -imag[i] / n
        }
    }

    private fun ifftBluestein(real: FloatArray, imag: FloatArray): Pair<FloatArray, FloatArray> {
        val n = real.size
        val conjI = FloatArray(n) { -imag[it] }
        val (r, i) = bluesteinFft(real.copyOf())
        val (rC, iC) = bluesteinFft(conjI)
        val outR = FloatArray(n)
        val outI = FloatArray(n)
        for (j in 0 until n) {
            val angle = 2.0 * PI * j / n
            val wr = cos(angle).toFloat()
            val wi = -sin(angle).toFloat()
            outR[j] = (r[j] * wr - rC[j] * wi) / n
            outI[j] = (r[j] * wi + rC[j] * wr) / n
        }
        return Pair(outR, outI)
    }

    private fun flatten(arr2D: Array<FloatArray>): FloatArray {
        val total = arr2D.sumOf { it.size }
        val result = FloatArray(total)
        var idx = 0
        for (arr in arr2D) {
            arr.copyInto(result, idx)
            idx += arr.size
        }
        return result
    }

    private fun unflatten(flat: FloatArray, rows: Int, colsHint: Int): Array<FloatArray> {
        if (flat.isEmpty()) return Array(rows) { floatArrayOf() }

        val totalElements = flat.size
        var cols = colsHint
        if (cols <= 0) {
            cols = totalElements / rows
            if (cols * rows < totalElements) cols++
        }

        val result = Array(rows) { r ->
            val start = r * cols
            val end = minOf(start + cols, totalElements)
            if (start < totalElements) flat.copyOfRange(start, end) else floatArrayOf()
        }
        return result
    }

    fun computeNumFrames(signalLength: Int, nFft: Int, hopLength: Int): Int {
        return signalLength / hopLength + 1
    }
}
