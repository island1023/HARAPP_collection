package com.example.harapp

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.sqrt
import kotlin.collections.List
import kotlin.collections.MutableList

/**
 * [非简化版] HAR 数据处理器
 * 实现了基于 UCI HAR 数据集标准的特征提取前置步骤 (重力分离、Jerk信号计算)
 * 并将完整的 561 维特征提取逻辑委托给 HarFeatureExtractor。
 */
class HarProcessor(private val context: Context) {

    // --- 配置参数 (匹配 UCI HAR 数据集要求) ---
    private val TAG = "HarProcessor"
    private val SAMPLING_RATE_HZ = 50
    private val WINDOW_DURATION_S = 2.56 // 窗口 2.56 秒
    private val OVERLAP_RATIO = 0.50     // 50% 重叠

    // 窗口大小：2.56秒 * 50Hz = 128 个样本
    private val windowSize = (WINDOW_DURATION_S * SAMPLING_RATE_HZ).toInt() // 128
    // 每次滑动：128 * 50% = 64 个样本
    private val overlap = (windowSize * OVERLAP_RATIO).toInt() // 64

    // 存储传感器数据，用于窗口切分和特征提取
    private val dataBuffer = mutableListOf<ProcessedSensorData>()

    // 用于重力分离的滤波器状态 (α=0.8 接近 0.3Hz 低通滤波器的效果)
    private val lowPassFilter = LowPassFilter(0.8f)

    // TFLite 模型解释器和配置
    private var tfliteInterpreter: Interpreter? = null
    private val modelFileName = "har_model.tflite"

    // 存储模型输出的活动标签 (6个类别)
    private val activityLabels = arrayOf(
        "WALKING", "WALKING_UPSTAIRS", "WALKING_DOWNSTAIRS",
        "SITTING", "STANDING", "LAYING"
    )

    // 当前识别结果
    var currentActivity: String = "未启动"
        private set

    init {
        loadTFLiteModel()
    }

    /**
     * 将 TFLite 模型文件从 assets 目录映射到内存。
     */
    private fun loadTFLiteModel() {
        try {
            val fileDescriptor = context.assets.openFd(modelFileName)
            val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
            val fileChannel: FileChannel = inputStream.channel
            val startOffset = fileDescriptor.startOffset
            val declaredLength = fileDescriptor.declaredLength
            val modelBuffer: MappedByteBuffer = fileChannel.map(
                FileChannel.MapMode.READ_ONLY,
                startOffset,
                declaredLength
            )
            tfliteInterpreter = Interpreter(modelBuffer)
            Log.i(TAG, "TFLite model loaded successfully: $modelFileName")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading TFLite model: $modelFileName. Did you put it in assets/?", e)
            tfliteInterpreter = null
        }
    }


    /**
     * 实时接收和处理传感器数据
     */
    fun processData(data: SensorService.SensorData) {
        // 1. 重力分离：更新重力分量和线性加速度分量
        val processedData = lowPassFilter.applyFilter(data)
        dataBuffer.add(processedData)

        // 2. 检查缓冲区是否已满一个窗口 (128个样本)
        if (dataBuffer.size >= windowSize) {
            Log.d(TAG, "Window ready. Size: ${dataBuffer.size}. Extracting features.")

            // 3. 特征提取 (完整的 561 维)
            val features = extractFeatures(dataBuffer)

            // 4. 模型推理
            currentActivity = runModelInference(features)

            // 5. 滑动窗口: 移除重叠部分 (64个样本)
            for (i in 0 until overlap) {
                if (dataBuffer.isNotEmpty()) {
                    dataBuffer.removeAt(0)
                }
            }
        }
    }

    /**
     * [关键步骤] 实现完整的 561 维特征提取逻辑。
     * 【修改】：委托给 HarFeatureExtractor
     */
    private fun extractFeatures(buffer: List<ProcessedSensorData>): FloatArray {
        // 调用外部 HarFeatureExtractor 来生成完整的 561 维特征向量
        return HarFeatureExtractor.extractAllFeatures(buffer)
    }

    // 注意：所有旧的特征计算辅助函数 (calculateJerk, calculateMagnitude, calculateStdDev,
    // calculateMax, calculateMin, calculateEnergy, calculateAngle) 已被移除，因为它们现在位于 HarFeatureExtractor.kt 中。


    /**
     * 使用 TFLite 模型进行推理。(保持与原版相同，因为输入现在是 561 维)
     */
    private fun runModelInference(features: FloatArray): String {
        tfliteInterpreter?.let { interpreter ->
            try {
                // 模型期望的输入格式：(1, 561, 1)
                val inputArray = Array(1) { Array(561) { FloatArray(1) } }

                // 将一维特征数组features 转换为模型期望的 (1, 561, 1) 形状
                for (i in 0 until 561) {
                    inputArray[0][i][0] = features[i]
                }

                // 输出数组：(1, 6)
                val outputArray = Array(1) { FloatArray(activityLabels.size) }

                interpreter.run(inputArray, outputArray)

                // 获取预测结果
                val probabilities = outputArray[0]
                val maxIndex = probabilities.indices.maxByOrNull { probabilities[it] } ?: -1
                val confidence = if (maxIndex != -1) probabilities[maxIndex] else 0f

                return if (maxIndex != -1) {
                    "${activityLabels[maxIndex]} (${String.format("%.1f%%", confidence * 100)})"
                } else {
                    "识别失败"
                }

            } catch (e: Exception) {
                Log.e(TAG, "TFLite inference failed.", e)
                return "推理错误"
            }
        }

        // 如果模型未加载，则使用简化规则识别
        return simplifiedRuleInference(features)
    }

    /**
     * 简化规则识别（模型未加载时的备用方案）
     */
    private fun simplifiedRuleInference(features: FloatArray): String {
        // 使用第一个特征（tBodyAccMeanX）作为判断依据
        val accMeanX = features.getOrElse(0) { 0f }

        return when {
            accMeanX > 15 -> "跑步/跳跃 (规则)"
            accMeanX > 10.5 -> "走路 (规则)"
            accMeanX > 9.5 && accMeanX < 10.5 -> "站立/静止 (规则)"
            else -> "未知活动 (规则)"
        }
    }
}


/**
 * 用于 HAR 预处理的自定义数据结构：包含分离后的重力分量和线性加速度分量
 */
data class ProcessedSensorData(
    val accX: Float, val accY: Float, val accZ: Float, // 原始加速度
    val gyroX: Float, val gyroY: Float, val gyroZ: Float, // 原始角速度
    // 分离后的分量
    val bodyAccX: Float, val bodyAccY: Float, val bodyAccZ: Float,
    val gravityAccX: Float, val gravityAccY: Float, val gravityAccZ: Float
)

/**
 * [非简化版] 一阶低通滤波器，用于将重力分量 (Gravity) 从总加速度中分离。
 * 真正的 UCI HAR 使用 3rd-order Butterworth filter。
 */
class LowPassFilter(private val alpha: Float) {
    // 滤波器状态：上一次的重力分量估计值
    private var gravityX = 0f
    private var gravityY = 0f
    private var gravityZ = 0f

    fun applyFilter(data: SensorService.SensorData): ProcessedSensorData {
        // 估计重力 (低频信号)
        gravityX = alpha * gravityX + (1 - alpha) * data.accX
        gravityY = alpha * gravityY + (1 - alpha) * data.accY
        gravityZ = alpha * gravityZ + (1 - alpha) * data.accZ

        // 线性加速度 (身体运动，高频信号) = 总加速度 - 重力
        val bodyAccX = data.accX - gravityX
        val bodyAccY = data.accY - gravityY
        val bodyAccZ = data.accZ - gravityZ

        return ProcessedSensorData(
            accX = data.accX, accY = data.accY, accZ = data.accZ,
            gyroX = data.gyroX, gyroY = data.gyroY, gyroZ = data.gyroZ,
            bodyAccX = bodyAccX, bodyAccY = bodyAccY, bodyAccZ = bodyAccZ,
            gravityAccX = gravityX, gravityAccY = gravityY, gravityAccZ = gravityZ
        )
    }
}