package com.example.harapp

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter // 确保此导入在 Gradle 同步后可用
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.sqrt

/**
 * 简化版 HAR 数据处理器
 * 在实际应用中，这里需要实现 561 维特征提取和 TFLite 模型加载/推理。
 *
 * 注意：由于 TFLite 依赖和复杂的特征提取无法在 Canvas 环境中完全实现，
 * 这里的模型推理和特征提取逻辑是简化的，仅用于演示数据流和窗口切分。
 */
class HarProcessor(private val context: Context) {

    // --- 配置参数 (匹配 UCI HAR 数据集要求) ---
    private val TAG = "HarProcessor"
    private val SAMPLING_RATE_HZ = 50
    private val WINDOW_DURATION_S = 2.56
    private val OVERLAP_RATIO = 0.50

    // 窗口大小：2.56秒 * 50Hz = 128 个样本
    private val windowSize = (WINDOW_DURATION_S * SAMPLING_RATE_HZ).toInt()
    // 每次滑动：128 * 50% = 64 个样本
    private val overlap = (windowSize * OVERLAP_RATIO).toInt()

    // 存储传感器数据，用于窗口切分和特征提取
    private val dataBuffer = mutableListOf<SensorService.SensorData>()

    // TFLite 模型解释器，需要您将 har_model.tflite 放入 app/src/main/assets/
    private var tfliteInterpreter: Interpreter? = null
    private val modelFileName = "har_model.tflite"

    // 存储模型输出的活动标签 (WALKING, SITTING, LAYING, etc.)
    private val activityLabels = arrayOf(
        "WALKING", "WALKING_UPSTAIRS", "WALKING_DOWNSTAIRS",
        "SITTING", "STANDING", "LAYING"
    )

    // 当前识别结果
    var currentActivity: String = "未启动"
        private set

    init {
        // 尝试加载 TFLite 模型
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
            // 初始化 TFLite 解释器
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
        dataBuffer.add(data)

        // 检查缓冲区是否已满一个窗口
        if (dataBuffer.size >= windowSize) {
            Log.d(TAG, "Window ready. Size: ${dataBuffer.size}. Extracting features.")

            // 1. 特征提取
            // 在实际应用中，您需要将以下简化逻辑替换为完整的 561 维特征提取！
            val features = extractFeatures(dataBuffer)

            // 2. 模型推理
            currentActivity = runModelInference(features)

            // 3. 滑动窗口: 移除重叠部分，保持连续性
            for (i in 0 until overlap) {
                if (dataBuffer.isNotEmpty()) {
                    dataBuffer.removeAt(0)
                }
            }
        }
    }

    /**
     * [TODO: 关键步骤] 实现完整的 561 维特征提取逻辑。
     * 这包括：重力分离 (0.3Hz 滤波器)、Jerk 信号计算、幅值计算、FFT 变换、统计特征等。
     */
    private fun extractFeatures(buffer: List<SensorService.SensorData>): FloatArray {
        // --- 占位符实现：仅计算加速度幅值均值 ---
        val accMagnitudeValues = buffer.map { data ->
            sqrt(data.accX * data.accX + data.accY * data.accY + data.accZ * data.accZ)
        }
        val meanAccMagnitude = accMagnitudeValues.average().toFloat()

        // 实际应用中，您需要返回一个包含 561 个特征的 FloatArray
        // 例如：return FloatArray(561) { index -> /* calculation for feature 'index' */ }

        // 为了演示 TFLite 推理，我们返回一个占位符数组，其大小应与您的模型输入匹配。
        // 如果您的 CNN 模型输入是 (1, 561, 1)，这里需要返回一个 561 长度的数组
        val featureVector = FloatArray(561) { 0f }
        // 仅将第一个元素设置为幅值均值，作为简单测试
        if (featureVector.isNotEmpty()) {
            featureVector[0] = meanAccMagnitude
        }

        return featureVector
    }

    /**
     * [TODO: 关键步骤] 使用 TFLite 模型进行推理。
     */
    private fun runModelInference(features: FloatArray): String {
        // 使用 let 进行空安全调用，解决 'Smart cast' 和 'unresolved reference' 问题
        tfliteInterpreter?.let { interpreter ->
            try {
                // 模型期望的输入格式：(1, 561, 1) -> 样本数, 特征数, 通道数
                // TFLite run 方法需要一个 Array<*>
                val inputArray = Array(1) { Array(561) { FloatArray(1) } }

                // 将一维特征数组features 转换为模型期望的 (1, 561, 1) 形状
                for (i in 0 until 561) {
                    inputArray[0][i][0] = features[i]
                }

                // 输出数组：(1, 6) -> 样本数, 类别数 (6个活动)
                val outputArray = Array(1) { FloatArray(activityLabels.size) }

                // 运行推理。TFLite run 方法需要明确的输入数组和输出映射。
                interpreter.run(inputArray, outputArray)

                // 获取预测结果
                val probabilities = outputArray[0]
                // 查找概率最大的索引
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

        // 如果模型未加载（tfliteInterpreter 为 null），则使用简化规则识别
        return simplifiedRuleInference(features)
    }

    /**
     * 简化规则识别（模型未加载时的备用方案）
     */
    private fun simplifiedRuleInference(features: FloatArray): String {
        // 假设 features[0] 是加速度幅值均值 (SMV Mean)
        val accMagnitude = features.getOrElse(0) { 0f }

        return when {
            accMagnitude > 15 -> "跑步/跳跃 (规则)"
            accMagnitude > 10.5 -> "走路 (规则)"
            accMagnitude > 9.5 && accMagnitude < 10.5 -> "站立/静止 (规则)"
            else -> "未知活动 (规则)"
        }
    }
}