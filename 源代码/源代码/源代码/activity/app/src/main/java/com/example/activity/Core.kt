package com.example.harapp

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.os.Handler
import android.os.Looper
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.channels.FileChannel

class Core(private val context: Context, val onResult: (String) -> Unit) : SensorEventListener {

    private var interpreter: Interpreter? = null

    // 状态标签定义
    // 注意：请务必确认两个模型导出的标签索引是否一致，如果不一致，需在 runInference 中根据模型动态切换 labels
    private val labelsUCI = listOf("走路", "上楼", "下楼", "静坐", "站立", "躺下")
    private val labelsWISDM = listOf("站立", "静坐", "跑步", "走路", "上楼", "下楼")

    private val WINDOW_SIZE = 128
    private val buffer = mutableListOf<FloatArray>()

    // 传感器缓存
    private var accValues = FloatArray(3)      // 原始加速度 (含重力)
    private var gyroValues = FloatArray(3)     // 陀螺仪
    private var linearAccValues = FloatArray(3) // 线性加速度 (不含重力)

    private var currentModelName = ""
    @Volatile private var isModelLoaded = false
    private var lastSampleTime: Long = 0
    private var targetIntervalMs: Long = 20

    // 识别控制参数
    private val CONFIDENCE_THRESHOLD = 0.75f
    private var lastValidResult = ""
    private val mainHandler = Handler(Looper.getMainLooper())

    fun switchModel(fileName: String) {
        isModelLoaded = false
        interpreter?.close()
        currentModelName = fileName

        // 采样频率差异化处理
        targetIntervalMs = when (fileName) {
            "wisdm.tflite" -> 50L    // 20Hz (WISDM 标准)
            "har.tflite" -> 20L      // 50Hz (UCI-HAR 标准)
            else -> 20L
        }

        try {
            val options = Interpreter.Options().setNumThreads(4)
            val fileDescriptor = context.assets.openFd(fileName)
            val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
            val modelBuffer = inputStream.channel.map(
                FileChannel.MapMode.READ_ONLY,
                fileDescriptor.startOffset,
                fileDescriptor.declaredLength
            )

            interpreter = Interpreter(modelBuffer, options)
            buffer.clear()
            lastValidResult = ""
            isModelLoaded = true
            postResult("等待识别...")
        } catch (e: Exception) {
            postResult("加载失败: ${e.message}")
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!isModelLoaded) return
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> accValues = event.values.clone()
            Sensor.TYPE_GYROSCOPE -> gyroValues = event.values.clone()
            Sensor.TYPE_LINEAR_ACCELERATION -> linearAccValues = event.values.clone()
        }

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastSampleTime >= targetIntervalMs) {
            lastSampleTime = currentTime

            // 组合 9 轴顺序 (严格对齐训练脚本):
            // 0-2: body_acc (线性), 3-5: body_gyro (陀螺仪), 6-8: total_acc (含重力)
            val combined = linearAccValues + gyroValues + accValues
            buffer.add(combined)

            if (buffer.size >= WINDOW_SIZE) {
                runInference()

                // 窗口滑动策略差异化
                val step = when (currentModelName) {
                    "har.tflite" -> 32  // 75% 重叠，提高腰部动态捕捉精度
                    else -> 64          // 50% 重叠 (WISDM 常用)
                }
                repeat(step) { if (buffer.isNotEmpty()) buffer.removeAt(0) }
            }
        }
    }

    private fun runInference() {
        val tflite = interpreter ?: return
        try {
            when (currentModelName) {
                "har.tflite" -> {
                    val input = Array(1) { Array(128) { FloatArray(9) } }
                    val output = Array(1) { FloatArray(labelsUCI.size) }
                    for (i in 0 until 128) {
                        for (j in 0 until 9) {
                            // UCI-HAR 训练通常使用原始 m/s^2，如需对齐单位 'g' 请除以 9.8
                            input[0][i][j] = buffer[i][j]
                        }
                    }
                    tflite.run(input, output)
                    updateUI(output[0], labelsUCI)
                }
                "wisdm.tflite" -> {
                    val input = Array(1) { Array(128) { FloatArray(3) } }
                    val output = Array(1) { FloatArray(labelsWISDM.size) }
                    for (i in 0 until 128) {
                        // WISDM 裤兜模式修正：对齐 StandardScaler 缩放量级
                        input[0][i][0] = buffer[i][6] / 9.8f
                        input[0][i][1] = buffer[i][7] / 9.8f
                        input[0][i][2] = buffer[i][8] / 9.8f
                    }
                    tflite.run(input, output)
                    updateUI(output[0], labelsWISDM)
                }
            }
        } catch (e: Exception) {
            postResult("推理出错: ${e.message}")
        }
    }

    private fun updateUI(output: FloatArray, currentLabels: List<String>) {
        val maxIdx = output.indices.maxByOrNull { output[it] } ?: -1

        if (maxIdx != -1 && output[maxIdx] >= CONFIDENCE_THRESHOLD) {
            val currentResult = currentLabels[maxIdx]

            // 结果平滑消抖逻辑 (针对腰部上下楼跳变优化)
            if (currentModelName == "har.tflite") {
                if (currentResult == lastValidResult) {
                    postResult(currentResult)
                }
                lastValidResult = currentResult
            } else {
                // 裤兜模式直接输出
                postResult(currentResult)
            }
        } else {
            postResult("无法识别")
        }
    }

    private fun postResult(text: String) {
        mainHandler.post { onResult(text) }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

// 辅助扩展：数组加法
operator fun FloatArray.plus(other: FloatArray): FloatArray {
    val result = FloatArray(this.size + other.size)
    System.arraycopy(this, 0, result, 0, this.size)
    System.arraycopy(other, 0, result, this.size, other.size)
    return result
}