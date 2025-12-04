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

    // TFLite 模型推理和特征提取的处理器（现在使用非简化版）
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