package com.example.harapp

import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import kotlin.math.sqrt

/**
 * SensorService 是一个后台服务，用于以 50Hz 的稳定速率采集加速度计和陀螺仪数据。
 * UCI HAR 数据集要求 50Hz 采样，对应 20,000 微秒 (μs) 的延迟。
 */
class SensorService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accSensor: Sensor? = null
    private var gyroSensor: Sensor? = null

    // 50Hz 采样率对应的延迟 (20,000 μs)
    private val SAMPLING_PERIOD_US = 20000

    // 用于计算实际采样率
    private var lastAccTimestamp: Long = 0
    private var lastGyroTimestamp: Long = 0
    private var accCount: Int = 0
    private var gyroCount: Int = 0
    private var accTotalDelta: Long = 0
    private var gyroTotalDelta: Long = 0

    // 传感器数据存储，用于发送给主界面或特征提取
    data class SensorData(
        val accX: Float, val accY: Float, val accZ: Float,
        val gyroX: Float, val gyroY: Float, val gyroZ: Float
    )
    private var latestData = SensorData(0f, 0f, 0f, 0f, 0f, 0f)

    // TFLite 模型推理和特征提取的处理器（简化版）
    private lateinit var harProcessor: HarProcessor

    override fun onCreate() {
        super.onCreate()
        Log.d("HAR_SERVICE", "Service created, initializing sensors.")
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) // 线性加速度
        gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)     // 角速度

        // 初始化 HAR 处理器
        harProcessor = HarProcessor(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        registerSensors()
        return START_STICKY // 服务被杀死后尝试重启
    }

    private fun registerSensors() {
        // 注册加速度计，设置延迟为 20,000 μs (50Hz)
        accSensor?.let {
            sensorManager.registerListener(
                this,
                it,
                SAMPLING_PERIOD_US,
                Handler(Looper.getMainLooper()) // 在主线程处理事件
            )
            Log.d("HAR_SERVICE", "Accelerometer listener registered at 50Hz.")
        } ?: run {
            Log.e("HAR_SERVICE", "Accelerometer sensor not found.")
        }

        // 注册陀螺仪，设置延迟为 20,000 μs (50Hz)
        gyroSensor?.let {
            sensorManager.registerListener(
                this,
                it,
                SAMPLING_PERIOD_US,
                Handler(Looper.getMainLooper())
            )
            Log.d("HAR_SERVICE", "Gyroscope listener registered at 50Hz.")
        } ?: run {
            Log.e("HAR_SERVICE", "Gyroscope sensor not found.")
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val sensorType = event.sensor.type

        when (sensorType) {
            Sensor.TYPE_ACCELEROMETER -> {
                // 1. 采集加速度数据
                latestData = latestData.copy(
                    accX = event.values[0],
                    accY = event.values[1],
                    accZ = event.values[2]
                )

                // 2. 采样率测量 (加速度计)
                if (lastAccTimestamp != 0L) {
                    val delta = event.timestamp - lastAccTimestamp // 纳秒
                    accTotalDelta += delta
                    accCount++
                }
                lastAccTimestamp = event.timestamp
            }
            Sensor.TYPE_GYROSCOPE -> {
                // 1. 采集陀螺仪数据 (单位: rad/s，符合 UCI HAR 要求)
                latestData = latestData.copy(
                    gyroX = event.values[0],
                    gyroY = event.values[1],
                    gyroZ = event.values[2]
                )

                // 2. 采样率测量 (陀螺仪)
                if (lastGyroTimestamp != 0L) {
                    val delta = event.timestamp - lastGyroTimestamp // 纳秒
                    gyroTotalDelta += delta
                    gyroCount++
                }
                lastGyroTimestamp = event.timestamp
            }
        }

        // 3. 将数据传递给处理器和主界面
        harProcessor.processData(latestData)
        sendBroadcastToActivity()
    }

    private fun sendBroadcastToActivity() {
        val intent = Intent(MainActivity.ACTION_SENSOR_UPDATE)

        // 计算平均采样率 (每 100 个样本计算一次)
        val accFreq = if (accCount > 100) 1e9 / (accTotalDelta / accCount).toFloat() else 0f
        val gyroFreq = if (gyroCount > 100) 1e9 / (gyroTotalDelta / gyroCount).toFloat() else 0f

        intent.putExtra("ACC_X", latestData.accX)
        intent.putExtra("ACC_Y", latestData.accY)
        intent.putExtra("ACC_Z", latestData.accZ)
        intent.putExtra("GYRO_X", latestData.gyroX)
        intent.putExtra("GYRO_Y", latestData.gyroY)
        intent.putExtra("GYRO_Z", latestData.gyroZ)
        intent.putExtra("ACC_FREQ", accFreq)
        intent.putExtra("GYRO_FREQ", gyroFreq)
        intent.putExtra("ACTIVITY", harProcessor.currentActivity) // 传递识别结果

        sendBroadcast(intent)

        // 重置计数器以持续测量
        if (accCount > 100) { accCount = 0; accTotalDelta = 0L; }
        if (gyroCount > 100) { gyroCount = 0; gyroTotalDelta = 0L; }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // 传感器精度变化时调用
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
        Log.d("HAR_SERVICE", "Service stopped and listeners unregistered.")
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}

/**
 * 简化版 HAR 数据处理器
 * 在实际应用中，这里需要实现 561 维特征提取和 TFLite 模型加载/推理。
 */
class HarProcessor(private val context: Context) {
    // 存储传感器数据，用于窗口切分和特征提取
    private val dataBuffer = mutableListOf<SensorService.SensorData>()
    private val windowSize = 128 // 128个样本，对应 2.56 秒 @ 50Hz

    // 当前识别结果
    var currentActivity: String = "未启动"

    /**
     * TFLite 模型推理的占位符方法。
     * 实际实现：加载 TFLite 模型并传入 561 维特征向量，返回识别结果。
     */
    private fun runModelInference(features: FloatArray): String {
        // TODO: 实际应用中，这里应加载 har_model.tflite 并进行推理

        // 简化规则识别（仅用于演示）：根据加速度大小判断
        val accMagnitude = sqrt(features[0] * features[0] + features[1] * features[1] + features[2] * features[2])
        return when {
            accMagnitude > 15 -> "跑步/跳跃"
            accMagnitude > 10 -> "走路"
            accMagnitude > 9.5 && accMagnitude < 10.5 -> "站立/静止"
            else -> "未知活动"
        }
    }

    /**
     * 实时接收和处理传感器数据
     */
    fun processData(data: SensorService.SensorData) {
        dataBuffer.add(data)

        // 模拟 50% 重叠的滑动窗口 (每 64 个新样本处理一次)
        if (dataBuffer.size >= windowSize) {

            // 1. 模拟特征提取 (使用窗口内 128 个样本的平均值作为简化特征)
            val meanAccX = dataBuffer.map { it.accX }.average().toFloat()
            val meanAccY = dataBuffer.map { it.accY }.average().toFloat()
            val meanAccZ = dataBuffer.map { it.accZ }.average().toFloat()

            // 实际应用中，需要实现所有 561 个特征的计算！
            val simplifiedFeatures = floatArrayOf(meanAccX, meanAccY, meanAccZ)

            // 2. 模型推理
            currentActivity = runModelInference(simplifiedFeatures)

            // 3. 滑动窗口: 移除前 64 个样本
            val overlap = windowSize / 2
            for (i in 0 until overlap) {
                dataBuffer.removeAt(0)
            }
        }
    }
}