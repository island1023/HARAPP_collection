package com.example.harapp

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context // 确保 Context 被导入
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build // 确保 Build 被导入
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.widget.EditText // 新增引入

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
    private lateinit var btnExportData: Button
    private lateinit var etCustomLabel: EditText
    // 【新增】波形视图实例
    private lateinit var waveformView: WaveformView

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
                // ... (处理保存状态反馈, 保持不变)
                val saveStatus = intent.getStringExtra("SAVE_STATUS")
                if (saveStatus == "SUCCESS") {
                    val filePath = intent.getStringExtra("FILE_PATH")
                    Toast.makeText(context, "数据保存成功，位于: ${filePath?.substringAfterLast('/')}", Toast.LENGTH_LONG).show()
                } else if (saveStatus == "FAILURE") {
                    val message = intent.getStringExtra("MESSAGE") ?: "写入文件失败。"
                    Toast.makeText(context, "数据保存失败! $message", Toast.LENGTH_SHORT).show()
                }

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

                // 【新增】将加速度计数据传递给 WaveformView 进行绘制
                waveformView.addData(accX, accY, accZ)

                // 更新 UI
                tvAccData.text = String.format("加速度 (m/s²): X=%.2f, Y=%.2f, Z=%.2f", accX, accY, accZ)
                tvGyroData.text = String.format("角速度 (rad/s): X=%.2f, Y=%.2f, Z=%.2f", gyroX, gyroY, gyroZ)
                tvSamplingRate.text = String.format("实际频率: Acc=%.2f Hz, Gyro=%.2f Hz", accFreq, gyroFreq)
                tvCurrentActivity.text = activity

                // 根据活动调整颜色 (保持不变)
                when (activity.substringBefore(' ')) {
                    "WALKING", "走路" -> tvCurrentActivity.setTextColor(ContextCompat.getColor(context!!, android.R.color.holo_blue_dark))
                    "WALKING_UPSTAIRS", "WALKING_DOWNSTAIRS" -> tvCurrentActivity.setTextColor(ContextCompat.getColor(context!!, android.R.color.holo_blue_light))
                    "SITTING", "STANDING", "LAYING", "站立/静止" -> tvCurrentActivity.setTextColor(ContextCompat.getColor(context!!, android.R.color.holo_green_dark))
                    "跑步/跳跃" -> tvCurrentActivity.setTextColor(ContextCompat.getColor(context!!, android.R.color.holo_red_dark))
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
        btnExportData = findViewById(R.id.btn_export_data)
        etCustomLabel = findViewById(R.id.et_custom_label)
        // 【新增】初始化波形视图
        waveformView = findViewById(R.id.waveform_view)

        // 设置按钮点击事件 (保持不变)
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

        btnExportData.setOnClickListener {
            exportCollectedData()
        }

        updateButtonsState()
    }

    // 【最终修复】应用 LINT 抑制 (保持不变)
    @Suppress("InlinedApi", "UnspecifiedRegisterReceiverFlag", "DEPRECATION")
    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(ACTION_SENSOR_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(sensorDataReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(sensorDataReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(sensorDataReceiver)
    }

    /**
     * 启动 SensorService
     */
    private fun startSensorService() {
        if (!isServiceRunning) {
            val intent = Intent(this, SensorService::class.java)
            val customLabel = etCustomLabel.text.toString().trim()
            intent.putExtra("CUSTOM_LABEL", if (customLabel.isNotEmpty()) customLabel else "未命名活动")

            ContextCompat.startForegroundService(this, intent)
            isServiceRunning = true
            Toast.makeText(this, "50Hz 传感器服务已启动，标签: ${intent.getStringExtra("CUSTOM_LABEL")}", Toast.LENGTH_LONG).show()
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
     * 发送指令给 SensorService 导出数据
     */
    private fun exportCollectedData() {
        if (isServiceRunning) {
            if (SensorService.collectedData.isEmpty()) {
                Toast.makeText(this, "当前缓冲区没有采集到的数据。", Toast.LENGTH_SHORT).show()
                return
            }

            val intent = Intent(this, SensorService::class.java).apply {
                action = SensorService.ACTION_SAVE_DATA
            }
            startService(intent)
            Toast.makeText(this, "正在导出数据，请稍候...", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "请先启动传感器服务!", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 检查所需的权限 (保持不变)
     */
    private fun checkPermissions(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * 请求权限 (保持不变)
     */
    private fun requestPermissions() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                PERMISSION_REQUEST_CODE
            )
        }
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
        btnExportData.isEnabled = isServiceRunning
    }
}