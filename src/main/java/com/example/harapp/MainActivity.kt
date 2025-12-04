package com.example.harapp

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * MainActivity 负责 UI 交互、权限管理和启动/停止 SensorService。
 * 它通过 BroadcastReceiver 接收来自 SensorService 的实时数据更新。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tvCurrentActivity: TextView
    private lateinit var tvAccData: TextView
    private lateinit var tvGyroData: TextView
    private lateinit var tvSamplingRate: TextView
    private lateinit var btnStartService: Button
    private lateinit var btnStopService: Button

    private var isServiceRunning = false

    // 定义广播动作，用于从服务接收数据
    companion object {
        const val ACTION_SENSOR_UPDATE = "com.example.harapp.SENSOR_UPDATE"
        private const val PERMISSION_REQUEST_CODE = 100
    }

    // 接收来自 SensorService 的数据广播
    private val sensorDataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_SENSOR_UPDATE) {
                // 提取传感器数据
                val accX = intent.getFloatExtra("ACC_X", 0f)
                val accY = intent.getFloatExtra("ACC_Y", 0f)
                val accZ = intent.getFloatExtra("ACC_Z", 0f)
                val gyroX = intent.getFloatExtra("GYRO_X", 0f)
                val gyroY = intent.getFloatExtra("GYRO_Y", 0f)
                val gyroZ = intent.getFloatExtra("GYRO_Z", 0f)
                val accFreq = intent.getFloatExtra("ACC_FREQ", 0f)
                val gyroFreq = intent.getFloatExtra("GYRO_FREQ", 0f)
                val activity = intent.getStringExtra("ACTIVITY") ?: "未知"

                // 更新 UI
                tvAccData.text = String.format("加速度 (m/s²): X=%.2f, Y=%.2f, Z=%.2f", accX, accY, accZ)
                tvGyroData.text = String.format("角速度 (rad/s): X=%.2f, Y=%.2f, Z=%.2f", gyroX, gyroY, gyroZ)
                tvSamplingRate.text = String.format("实际频率: Acc=%.2f Hz, Gyro=%.2f Hz", accFreq, gyroFreq)
                tvCurrentActivity.text = activity

                // 根据活动调整颜色 (简单图形化反馈)
                when (activity) {
                    "走路" -> tvCurrentActivity.setTextColor(ContextCompat.getColor(context!!, android.R.color.holo_blue_dark))
                    "跑步/跳跃" -> tvCurrentActivity.setTextColor(ContextCompat.getColor(context!!, android.R.color.holo_red_dark))
                    "站立/静止" -> tvCurrentActivity.setTextColor(ContextCompat.getColor(context!!, android.R.color.holo_green_dark))
                    else -> tvCurrentActivity.setTextColor(ContextCompat.getColor(context!!, android.R.color.darker_gray))
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 初始化视图元素
        tvCurrentActivity = findViewById(R.id.tv_current_activity)
        tvAccData = findViewById(R.id.tv_acc_data)
        tvGyroData = findViewById(R.id.tv_gyro_data)
        tvSamplingRate = findViewById(R.id.tv_sampling_rate)
        btnStartService = findViewById(R.id.btn_start_service)
        btnStopService = findViewById(R.id.btn_stop_service)

        // 设置按钮点击事件
        btnStartService.setOnClickListener {
            if (checkPermissions()) {
                startSensorService()
            } else {
                requestPermissions()
            }
        }

        btnStopService.setOnClickListener {
            stopSensorService()
        }

        updateButtonsState()
    }

    override fun onResume() {
        super.onResume()
        // 注册广播接收器，用于接收传感器数据更新
        val filter = IntentFilter(ACTION_SENSOR_UPDATE)
        registerReceiver(sensorDataReceiver, filter, RECEIVER_EXPORTED)
    }

    override fun onPause() {
        super.onPause()
        // 取消注册广播接收器
        unregisterReceiver(sensorDataReceiver)
    }

    /**
     * 启动 SensorService
     */
    private fun startSensorService() {
        if (!isServiceRunning) {
            val intent = Intent(this, SensorService::class.java)
            // 启动前台服务以确保后台持续运行 (在 Android O+ 需要)
            ContextCompat.startForegroundService(this, intent)
            isServiceRunning = true
            Toast.makeText(this, "50Hz 传感器服务已启动", Toast.LENGTH_SHORT).show()
            updateButtonsState()
        }
    }

    /**
     * 停止 SensorService
     */
    private fun stopSensorService() {
        if (isServiceRunning) {
            val intent = Intent(this, SensorService::class.java)
            stopService(intent)
            isServiceRunning = false
            Toast.makeText(this, "传感器服务已停止", Toast.LENGTH_SHORT).show()
            updateButtonsState()
            tvCurrentActivity.text = "等待开始..."
            tvCurrentActivity.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark))
        }
    }

    /**
     * 检查所需的权限 (例如后台服务运行)
     */
    private fun checkPermissions(): Boolean {
        // 在 Android 14+ 检查 POST_NOTIFICATIONS 权限
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true // 旧版本无需权限
        }
    }

    /**
     * 请求权限
     */
    private fun requestPermissions() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                PERMISSION_REQUEST_CODE
            )
        }
        // 如果是 Android Q+，启动前台服务还需要额外的权限，但通常在 Manifest 中声明即可。
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startSensorService()
            } else {
                Toast.makeText(this, "权限被拒绝，无法启动服务。", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 更新按钮状态
     */
    private fun updateButtonsState() {
        btnStartService.isEnabled = !isServiceRunning
        btnStopService.isEnabled = isServiceRunning
    }
}