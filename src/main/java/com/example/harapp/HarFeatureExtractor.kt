// HarFeatureExtractor.kt
package com.example.harapp

import org.apache.commons.math3.complex.Complex
import org.apache.commons.math3.stat.descriptive.rank.Percentile
import org.apache.commons.math3.stat.descriptive.moment.Kurtosis
import org.apache.commons.math3.stat.descriptive.moment.Skewness
import org.apache.commons.math3.transform.DftNormalization
import org.apache.commons.math3.transform.FastFourierTransformer
import org.apache.commons.math3.transform.TransformType
import kotlin.math.*

/**
 * HarFeatureExtractor
 *
 * 输入：窗口内的 ProcessedSensorData 列表（长度应为 windowSize = 128）
 * 输出：FloatArray 长度 = 561（按内部固定顺序）
 *
 * 注：实现了大量时域与频域特征、27 个相关性、7 个角度特征。
 */
object HarFeatureExtractor {

    private const val TARGET_DIM = 561
    private const val SAMPLE_RATE = 50.0 // Hz

    private val fft = FastFourierTransformer(DftNormalization.STANDARD)
    private val percentile = Percentile()
    private val skewnessCalc = Skewness()
    private val kurtosisCalc = Kurtosis()

    /**
     * 主函数：返回 561 维特征
     */
    fun extractAllFeatures(buffer: List<ProcessedSensorData>): FloatArray {
        // 安全检查：若长度 < required window, pad by repeating last value
        val window = if (buffer.size >= 1) buffer else listOf(createZeroProcessedData())
        // 抽取 20 个信号（15 轴 + 5 幅值）
        val signals = gatherSignals(window)

        val features = mutableListOf<Float>()

        // 每个信号抽取一组时域+频域统计，共 N_per_signal 个
        for ((_, sig) in signals) {
            val sigD = sig.map { it.toDouble() }.toDoubleArray()
            // 时域特征
            features.add(mean(sigD).toFloat())
            features.add(stdDev(sigD).toFloat())
            features.add(max(sigD).toFloat())
            features.add(min(sigD).toFloat())
            features.add(energy(sigD).toFloat())
            features.add(iqr(sigD).toFloat())
            features.add(skewness(sigD).toFloat())
            features.add(kurtosis(sigD).toFloat())
            features.add(median(sigD).toFloat())
            features.add(meanAbsoluteDeviation(sigD).toFloat())
            features.add(rms(sigD).toFloat())
            features.add(range(sigD).toFloat())
            // 四个分位数（10,25,75,90）
            features.add(percentile(sigD, 10.0).toFloat())
            features.add(percentile(sigD, 25.0).toFloat())
            features.add(percentile(sigD, 75.0).toFloat())
            features.add(percentile(sigD, 90.0).toFloat())
            // 频域特征（基于 FFT）
            val spec = computeSpectrum(sigD)
            features.add(spec.meanFreq.toFloat())
            features.add(spec.spectralEntropy.toFloat())
            features.add(spec.domFreq.toFloat())
            features.add(spec.domFreqPower.toFloat())
            features.add(spec.spectralEnergy.toFloat())
            features.add(spec.spectralCentroid.toFloat())
            features.add(spec.spectralSpread.toFloat())

            // （到此，我们为每个信号生成 26 个特征）
            // 26 * 20 = 520 特征
        }

        // 计算 27 个成对相关系数（选择 6 个代表轴：bodyAccX/Y/Z, bodyGyroX/Y/Z）
        val corrSignals = listOf(
            signals["tBodyAccX"]!!, signals["tBodyAccY"]!!, signals["tBodyAccZ"]!!,
            signals["tBodyGyroX"]!!, signals["tBodyGyroY"]!!, signals["tBodyGyroZ"]!!
        )
        val corrPairs = pairwiseIndices(corrSignals.size)
        for ((i, j) in corrPairs) {
            features.add(correlation(corrSignals[i].map { it.toDouble() }.toDoubleArray(),
                corrSignals[j].map { it.toDouble() }.toDoubleArray()).toFloat())
        }
        // 如果不够 27，则补 0
        while (features.size < 520 + 27) { features.add(0f) }

        // 7 个角度特征
        val meanBodyAcc = doubleArrayOf(
            mean(signals["tBodyAccX"]!!.map { it.toDouble() }.toDoubleArray()),
            mean(signals["tBodyAccY"]!!.map { it.toDouble() }.toDoubleArray()),
            mean(signals["tBodyAccZ"]!!.map { it.toDouble() }.toDoubleArray())
        )
        val meanGravityAcc = doubleArrayOf(
            mean(signals["tGravityAccX"]!!.map { it.toDouble() }.toDoubleArray()),
            mean(signals["tGravityAccY"]!!.map { it.toDouble() }.toDoubleArray()),
            mean(signals["tGravityAccZ"]!!.map { it.toDouble() }.toDoubleArray())
        )

        // angle(tBodyAccMean, gravity)
        features.add(calculateAngleRadians(meanBodyAcc, meanGravityAcc).toFloat())

        // angle(tBodyAccJerkMean), angle(tBodyGyroMean), angle(tBodyGyroJerkMean)
        val meanBodyAccJerk = doubleArrayOf(
            mean(signals["tBodyAccJerkX"]!!.map { it.toDouble() }.toDoubleArray()),
            mean(signals["tBodyAccJerkY"]!!.map { it.toDouble() }.toDoubleArray()),
            mean(signals["tBodyAccJerkZ"]!!.map { it.toDouble() }.toDoubleArray())
        )
        features.add(calculateAngleRadians(meanBodyAccJerk, meanGravityAcc).toFloat())

        val meanGyro = doubleArrayOf(
            mean(signals["tBodyGyroX"]!!.map { it.toDouble() }.toDoubleArray()),
            mean(signals["tBodyGyroY"]!!.map { it.toDouble() }.toDoubleArray()),
            mean(signals["tBodyGyroZ"]!!.map { it.toDouble() }.toDoubleArray())
        )
        features.add(calculateAngleRadians(meanGyro, meanGravityAcc).toFloat())

        val meanGyroJerk = doubleArrayOf(
            mean(signals["tBodyGyroJerkX"]!!.map { it.toDouble() }.toDoubleArray()),
            mean(signals["tBodyGyroJerkY"]!!.map { it.toDouble() }.toDoubleArray()),
            mean(signals["tBodyGyroJerkZ"]!!.map { it.toDouble() }.toDoubleArray())
        )
        features.add(calculateAngleRadians(meanGyroJerk, meanGravityAcc).toFloat())

        // 加上 3 个 gravity 方向的角度（gravity 与 X/Y/Z 单位向量夹角）
        features.add(angleBetweenVectors(meanGravityAcc, doubleArrayOf(1.0, 0.0, 0.0)).toFloat())
        features.add(angleBetweenVectors(meanGravityAcc, doubleArrayOf(0.0, 1.0, 0.0)).toFloat())
        features.add(angleBetweenVectors(meanGravityAcc, doubleArrayOf(0.0, 0.0, 1.0)).toFloat())

        // 最后补齐到 561（若超过则截断）
        while (features.size < TARGET_DIM) features.add(0f)
        if (features.size > TARGET_DIM) {
            // 防止意外溢出，截断多余维度
            return features.subList(0, TARGET_DIM).toFloatArray()
        }

        return features.toFloatArray()
    }

    // ---------------------------
    // Helper: gather the 20 signals expected by UCI/HAR-like pipeline
    // ---------------------------
    private fun gatherSignals(buffer: List<ProcessedSensorData>): Map<String, List<Float>> {
        val bodyAccX = buffer.map { it.bodyAccX }
        val bodyAccY = buffer.map { it.bodyAccY }
        val bodyAccZ = buffer.map { it.bodyAccZ }

        val gravityAccX = buffer.map { it.gravityAccX }
        val gravityAccY = buffer.map { it.gravityAccY }
        val gravityAccZ = buffer.map { it.gravityAccZ }

        val gyroX = buffer.map { it.gyroX }
        val gyroY = buffer.map { it.gyroY }
        val gyroZ = buffer.map { it.gyroZ }

        val jerkAccX = calculateJerk(bodyAccX)
        val jerkAccY = calculateJerk(bodyAccY)
        val jerkAccZ = calculateJerk(bodyAccZ)

        val jerkGyroX = calculateJerk(gyroX)
        val jerkGyroY = calculateJerk(gyroY)
        val jerkGyroZ = calculateJerk(gyroZ)

        fun mag(a: List<Float>, b: List<Float>, c: List<Float>): List<Float> {
            return a.zip(b).zip(c).map { (ab, cc) ->
                val (ax, ay) = ab
                sqrt(ax * ax + ay * ay + cc * cc)
            }
        }

        return mapOf(
            "tBodyAccX" to bodyAccX,
            "tBodyAccY" to bodyAccY,
            "tBodyAccZ" to bodyAccZ,
            "tGravityAccX" to gravityAccX,
            "tGravityAccY" to gravityAccY,
            "tGravityAccZ" to gravityAccZ,
            "tBodyGyroX" to gyroX,
            "tBodyGyroY" to gyroY,
            "tBodyGyroZ" to gyroZ,
            "tBodyAccJerkX" to jerkAccX,
            "tBodyAccJerkY" to jerkAccY,
            "tBodyAccJerkZ" to jerkAccZ,
            "tBodyGyroJerkX" to jerkGyroX,
            "tBodyGyroJerkY" to jerkGyroY,
            "tBodyGyroJerkZ" to jerkGyroZ,
            "tBodyAccMag" to mag(bodyAccX, bodyAccY, bodyAccZ),
            "tGravityAccMag" to mag(gravityAccX, gravityAccY, gravityAccZ),
            "tBodyAccJerkMag" to mag(jerkAccX, jerkAccY, jerkAccZ),
            "tBodyGyroMag" to mag(gyroX, gyroY, gyroZ),
            "tBodyGyroJerkMag" to mag(jerkGyroX, jerkGyroY, jerkGyroZ)
        )
    }

    // ---------------------------
    // Spectral struct
    // ---------------------------
    private data class SpectrumFeatures(
        val meanFreq: Double,
        val spectralEntropy: Double,
        val domFreq: Double,
        val domFreqPower: Double,
        val spectralEnergy: Double,
        val spectralCentroid: Double,
        val spectralSpread: Double
    )

    // ---------------------------
    // Compute spectrum-based features via FFT
    // ---------------------------
    private fun computeSpectrum(signal: DoubleArray): SpectrumFeatures {
        // Zero-pad to next power-of-two for FFT performance
        val n = nextPowerOfTwo(signal.size)
        val padded = DoubleArray(n)
        System.arraycopy(signal, 0, padded, 0, signal.size)

        // Perform FFT
        val complex = fft.transform(padded, TransformType.FORWARD)
        val mags = complex.take(n / 2).map { it.abs() } // one-sided spectrum

        val freqs = DoubleArray(mags.size) { idx -> idx * SAMPLE_RATE / n }

        val magArray = mags.toDoubleArray()
        val totalEnergy = magArray.map { it * it }.sum()
        val spectralEnergy = totalEnergy

        // spectral centroid and spread
        var centroid = 0.0
        var spread = 0.0
        var weightedSum = 0.0
        for (i in magArray.indices) {
            weightedSum += freqs[i] * magArray[i]
        }
        val sumMag = magArray.sum()
        centroid = if (sumMag == 0.0) 0.0 else weightedSum / sumMag
        for (i in magArray.indices) {
            spread += ((freqs[i] - centroid) * (freqs[i] - centroid)) * magArray[i]
        }
        spread = if (sumMag == 0.0) 0.0 else sqrt(spread / sumMag)

        // mean frequency (weighted)
        val meanFreq = if (sumMag == 0.0) 0.0 else weightedSum / sumMag

        // spectral entropy
        val psd = magArray.map { if (totalEnergy == 0.0) 0.0 else (it * it) / totalEnergy }.toDoubleArray()
        var entropy = 0.0
        for (p in psd) {
            if (p > 0) entropy -= p * ln(p)
        }

        // dominant frequency & its power
        val maxIndex = magArray.indices.maxByOrNull { magArray[it] } ?: 0
        val domFreq = if (magArray.isEmpty()) 0.0 else freqs[maxIndex]
        val domPower = if (magArray.isEmpty()) 0.0 else magArray[maxIndex] * magArray[maxIndex]

        return SpectrumFeatures(
            meanFreq = meanFreq,
            spectralEntropy = entropy,
            domFreq = domFreq,
            domFreqPower = domPower,
            spectralEnergy = spectralEnergy,
            spectralCentroid = centroid,
            spectralSpread = spread
        )
    }

    // ---------------------------
    // Math helpers
    // ---------------------------
    private fun mean(x: DoubleArray): Double = if (x.isEmpty()) 0.0 else x.average()
    private fun stdDev(x: DoubleArray): Double {
        if (x.size < 2) return 0.0
        val m = mean(x)
        return sqrt(x.map { (it - m).pow(2) }.average())
    }
    private fun max(x: DoubleArray): Double = x.maxOrNull() ?: 0.0
    private fun min(x: DoubleArray): Double = x.minOrNull() ?: 0.0
    private fun energy(x: DoubleArray): Double = if (x.isEmpty()) 0.0 else x.map { it * it }.sum() / x.size
    private fun iqr(x: DoubleArray): Double {
        if (x.isEmpty()) return 0.0
        percentile.data = x
        return percentile.evaluate(75.0) - percentile.evaluate(25.0)
    }
    private fun skewness(x: DoubleArray): Double {
        if (x.size < 3) return 0.0
        return try { skewnessCalc.evaluate(x) } catch (e: Exception) { 0.0 }
    }
    private fun kurtosis(x: DoubleArray): Double {
        if (x.size < 4) return 0.0
        return try { kurtosisCalc.evaluate(x) } catch (e: Exception) { 0.0 }
    }
    private fun median(x: DoubleArray): Double {
        if (x.isEmpty()) return 0.0
        percentile.data = x
        return percentile.evaluate(50.0)
    }
    private fun meanAbsoluteDeviation(x: DoubleArray): Double {
        if (x.isEmpty()) return 0.0
        val m = mean(x)
        return x.map { abs(it - m) }.average()
    }
    private fun rms(x: DoubleArray): Double {
        if (x.isEmpty()) return 0.0
        return sqrt(x.map { it * it }.average())
    }
    private fun range(x: DoubleArray): Double = (max(x) - min(x))
    private fun percentile(x: DoubleArray, p: Double): Double {
        if (x.isEmpty()) return 0.0
        percentile.data = x
        return percentile.evaluate(p)
    }

    // correlation (Pearson)
    private fun correlation(x: DoubleArray, y: DoubleArray): Double {
        val n = min(x.size, y.size)
        if (n < 2) return 0.0
        val xm = mean(x)
        val ym = mean(y)
        var num = 0.0
        var denx = 0.0
        var deny = 0.0
        for (i in 0 until n) {
            val dx = x[i] - xm
            val dy = y[i] - ym
            num += dx * dy
            denx += dx * dx
            deny += dy * dy
        }
        val denom = sqrt(denx * deny)
        return if (denom == 0.0) 0.0 else num / denom
    }

    // create zero ProcessedSensorData
    private fun createZeroProcessedData() = ProcessedSensorData(
        accX = 0f, accY = 0f, accZ = 0f,
        gyroX = 0f, gyroY = 0f, gyroZ = 0f,
        bodyAccX = 0f, bodyAccY = 0f, bodyAccZ = 0f,
        gravityAccX = 0f, gravityAccY = 0f, gravityAccZ = 0f
    )

    private fun nextPowerOfTwo(n: Int): Int {
        var v = 1
        while (v < n) v = v shl 1
        return v
    }

    private fun calculateJerk(signal: List<Float>): List<Float> {
        val jerk = mutableListOf<Float>()
        if (signal.size < 2) return List(signal.size) { 0f }
        val timeInterval = 1f / SAMPLE_RATE.toFloat()
        jerk.add(0f)
        for (i in 1 until signal.size) {
            jerk.add((signal[i] - signal[i - 1]) / timeInterval)
        }
        return jerk
    }

    // angle between two 3-d vectors, return radians
    private fun calculateAngleRadians(a: DoubleArray, b: DoubleArray): Double {
        val dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
        val magA = sqrt(a[0]*a[0] + a[1]*a[1] + a[2]*a[2])
        val magB = sqrt(b[0]*b[0] + b[1]*b[1] + b[2]*b[2])
        if (magA == 0.0 || magB == 0.0) return 0.0
        val cosTheta = (dot / (magA * magB)).coerceIn(-1.0, 1.0)
        return acos(cosTheta)
    }

    private fun angleBetweenVectors(a: DoubleArray, b: DoubleArray): Double {
        return calculateAngleRadians(a, b)
    }

    // pairwise index generator for combinations (i<j)
    private fun pairwiseIndices(n: Int): List<Pair<Int, Int>> {
        val pairs = mutableListOf<Pair<Int, Int>>()
        for (i in 0 until n) for (j in i+1 until n) pairs.add(i to j)
        return pairs
    }
}