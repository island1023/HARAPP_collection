package com.example.wisdmcollector

import android.os.Environment
import android.content.Context
import android.graphics.Color as GColor
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.sqrt

// 定义WISDM格式的数据结构
data class WISDMSensorData(
    val userId: String,           // WISDM需要用户ID
    val timestamp: Long,
    val activityLabel: String,
    val accX: Float,              // 加速度X轴(g)
    val accY: Float,              // 加速度Y轴(g)
    val accZ: Float,              // 加速度Z轴(g)
    val gyroX: Float,             // 陀螺仪X轴(rad/s)
    val gyroY: Float,             // 陀螺仪Y轴(rad/s)
    val gyroZ: Float              // 陀螺仪Z轴(rad/s)
)

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private val sensorDataList = mutableListOf<WISDMSensorData>()
    private var isLogging by mutableStateOf(false)
    private var currentActivity by mutableStateOf("")
    private var currentUserId by mutableStateOf("")  // WISDM需要用户ID
    private var lastAccTime: Long = 0
    private val sensorDataInterval = 20  // 保持50Hz采集频率

    // 实时传感器读数
    private var currentAcc by mutableStateOf(floatArrayOf(0f, 0f, 0f))  // 单位：g
    private var currentGyr by mutableStateOf(floatArrayOf(0f, 0f, 0f))  // 单位：rad/s

    // 波形图数据 - 仅保留加速度相关
    private val accHistory = mutableStateListOf<Float>()
    private val maxHistorySize = 150

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        registerSensors()

        setContent {
            MaterialTheme {
                WISDMSensorCollectorApp(
                    isLogging = isLogging,
                    currentActivity = currentActivity,
                    currentUserId = currentUserId,
                    accValues = currentAcc,
                    gyrValues = currentGyr,
                    accHistory = accHistory,
                    onStartLogging = ::startLogging,
                    onStopAndSave = ::stopAndSaveData,
                    onActivityChange = { label -> currentActivity = label },
                    onUserIdChange = { id -> currentUserId = id }
                )
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && isLogging) {
            stopAndSaveData()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun registerSensors() {
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        // WISDM使用约20Hz-50Hz的采集频率
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        if (accelerometer == null || gyroscope == null) {
            Toast.makeText(this, "设备缺少必要的传感器！", Toast.LENGTH_LONG).show()
        }
    }

    private fun unregisterSensors() {
        sensorManager.unregisterListener(this)
    }

    override fun onResume() {
        super.onResume()
        registerSensors()
    }

    override fun onPause() {
        super.onPause()
        unregisterSensors()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val currentTime = System.currentTimeMillis()

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                // 转换为g单位 (1g = 9.81 m/s²) - WISDM使用g作为单位
                val accInG = event.values.clone().map { it / 9.81f }.toFloatArray()
                currentAcc = accInG

                if (currentTime - lastAccTime >= sensorDataInterval) {
                    lastAccTime = currentTime

                    val magnitude = sqrt(
                        Math.pow(currentAcc[0].toDouble(), 2.0) +
                                Math.pow(currentAcc[1].toDouble(), 2.0) +
                                Math.pow(currentAcc[2].toDouble(), 2.0)
                    ).toFloat()

                    if (accHistory.size >= maxHistorySize) {
                        accHistory.removeAt(0)
                    }
                    accHistory.add(magnitude)

                    if (isLogging) {
                        sensorDataList.add(
                            WISDMSensorData(
                                userId = currentUserId,
                                timestamp = currentTime,
                                activityLabel = currentActivity,
                                accX = accInG[0],
                                accY = accInG[1],
                                accZ = accInG[2],
                                gyroX = currentGyr[0],
                                gyroY = currentGyr[1],
                                gyroZ = currentGyr[2]
                            )
                        )
                    }
                }
            }
            Sensor.TYPE_GYROSCOPE -> {
                currentGyr = event.values.clone()
            }
        }
    }


    // 更新历史数据列表，保持固定大小
    private fun updateHistory(history: SnapshotStateList<Float>, newValue: Float) {
        if (history.size >= maxHistorySize) {
            history.removeAt(0)
        }
        history.add(newValue)
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        // 传感器精度变化时显示提示
        val accuracyStr = when (accuracy) {
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "高精度"
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "中等精度"
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "低精度"
            else -> "未知精度"
        }
        Log.d("SensorAccuracy", "${sensor.name} 精度变化为: $accuracyStr")
    }

    private fun startLogging() {
        // 验证必要信息
        if (currentUserId.isBlank()) {
            Toast.makeText(this, "请输入用户ID", Toast.LENGTH_SHORT).show()
            return
        }
        if (currentActivity.isBlank()) {
            Toast.makeText(this, "请输入活动标签", Toast.LENGTH_SHORT).show()
            return
        }
        if (isLogging) return

        sensorDataList.clear()
        isLogging = true
        Log.d("WISDMApp", "开始采集 - 用户: $currentUserId, 活动: $currentActivity")
        Toast.makeText(this, "开始采集...\n按音量减键可停止", Toast.LENGTH_SHORT).show()
    }

    private fun stopAndSaveData() {
        if (!isLogging) {
            Toast.makeText(this, "未进行数据采集", Toast.LENGTH_SHORT).show()
            return
        }

        isLogging = false
        val count = sensorDataList.size
        Log.d("WISDMApp", "采集完成，共 $count 个样本")

        if (count == 0) {
            Toast.makeText(this, "数据为空，未保存", Toast.LENGTH_SHORT).show()
            return
        }

        val csvContent = convertToWISDMFormat()
        saveCsvFile(csvContent)
        sensorDataList.clear()
    }

    // 转换为WISDM数据集格式 (user,activity,timestamp,x,y,z,gyroX,gyroY,gyroZ)
    private fun convertToWISDMFormat(): String {
        val header = "user,activity,timestamp,x,y,z,gyroX,gyroY,gyroZ\n"

        val rows = sensorDataList.joinToString("\n") { data ->
            // WISDM使用6位小数精度
            listOf(
                data.userId,
                data.activityLabel.replace(",", "_"),
                data.timestamp.toString(),
                "%.6f".format(data.accX),
                "%.6f".format(data.accY),
                "%.6f".format(data.accZ),
                "%.6f".format(data.gyroX),
                "%.6f".format(data.gyroY),
                "%.6f".format(data.gyroZ)
            ).joinToString(",")
        }
        return header + rows
    }

    private fun saveCsvFile(content: String) {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        // 清理文件名中的非法字符
        val safeActivityLabel = currentActivity.replace(Regex("[^a-zA-Z0-9_]"), "")
        val fileName = "HAR_Data_${safeActivityLabel}_${timeStamp}.csv"

        // 关键修改：获取外部存储（SD卡）根目录
        val sdCardRoot = Environment.getExternalStorageDirectory()
        // 定向到您手动建立的 HAR 文件夹
        val harDir = File(sdCardRoot, "HAR")

        // 检查文件夹是否存在
        if (!harDir.exists()) {
            Toast.makeText(this, "未找到 SD卡/HAR 文件夹，请确认已手动创建！", Toast.LENGTH_LONG).show()
            // 尝试兜底创建（如果权限足够）
            harDir.mkdirs()
        }

        val file = File(harDir, fileName)

        try {
            FileOutputStream(file).use { outputStream ->
                outputStream.write(content.toByteArray())
            }
            Toast.makeText(
                this,
                "数据已存至：SD卡/HAR/\n文件名：$fileName",
                Toast.LENGTH_LONG
            ).show()
            Log.i("SensorApp", "文件保存成功：${file.absolutePath}")
        } catch (e: Exception) {
            // 如果报错 Permission Denied，请检查下方第2步的权限设置
            Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_LONG).show()
            Log.e("SensorApp", "文件保存失败", e)
        }
    }
}

@Composable
fun WISDMSensorCollectorApp(
    isLogging: Boolean,
    currentActivity: String,
    currentUserId: String,
    accValues: FloatArray,
    gyrValues: FloatArray,
    accHistory: SnapshotStateList<Float>,
    onStartLogging: () -> Unit,
    onStopAndSave: () -> Unit,
    onActivityChange: (String) -> Unit,
    onUserIdChange: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .background(Color(0xFFF0F4F8)),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "WISDM 数据集采集器",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 20.dp),
            color = Color(0xFFE91E63)
        )

        LazyColumn(modifier = Modifier.weight(1f)) {
            item {
                // WISDM设备位置指导
                WISDMPlacementGuidance()

                Spacer(modifier = Modifier.height(16.dp))

                StatusCard(
                    title = "采集状态",
                    value = if (isLogging) "采集中... (${accHistory.size} 样本)" else "已停止",
                    valueColor = if (isLogging) Color.Red else Color.Green
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 用户ID输入 (WISDM必需)
                UserIdInput(currentUserId, onUserIdChange, isLogging)

                Spacer(modifier = Modifier.height(16.dp))

                // 活动标签输入
                ActivityInput(
                    currentActivity = currentActivity,
                    onActivityChange = onActivityChange,
                    isLogging = isLogging
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 传感器数据展示
                SensorDisplay(title = "加速度计 (g)", values = accValues)
                Spacer(modifier = Modifier.height(8.dp))
                SensorDisplay(title = "陀螺仪 (rad/s)", values = gyrValues)

                Spacer(modifier = Modifier.height(16.dp))

                // 加速度波形图
                AccelerationWaveformDisplay(accHistory = accHistory)
            }
        }

        // 控制按钮
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(
                onClick = onStartLogging,
                enabled = !isLogging && currentActivity.isNotBlank() && currentUserId.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("开始采集", fontSize = 16.sp, color = Color.White)
            }

            Button(
                onClick = onStopAndSave,
                enabled = isLogging,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336)),
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("停止并保存", fontSize = 16.sp, color = Color.White)
            }
        }
    }
}

// WISDM特有的设备放置指导
@Composable
fun WISDMPlacementGuidance() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(4.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "⚠️ WISDM 设备放置规范",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = Color(0xFF1B5E20)
            )
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                "WISDM数据集标准放置要求：",
                fontSize = 14.sp,
                color = Color(0xFF37474F)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                "1. 手机放在**前裤兜**中，屏幕朝向身体",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "2. 保持自然行走姿势，避免刻意调整手机位置",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "3. 加速度数据已转换为g单位 (1g = 9.81m/s²)",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "4. 支持的典型活动：Walking, Jogging, Upstairs, Downstairs, Sitting, Standing",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

// 用户ID输入组件
@Composable
fun UserIdInput(
    currentUserId: String,
    onUserIdChange: (String) -> Unit,
    isLogging: Boolean
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        OutlinedTextField(
            value = currentUserId,
            onValueChange = onUserIdChange,
            label = { Text("用户ID (WISDM必需)") },
            placeholder = { Text("例如: user1, subject3") },
            enabled = !isLogging,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            "每个用户使用唯一ID，用于区分不同参与者数据",
            fontSize = 12.sp,
            color = Color.Gray
        )
    }
}

@Composable
fun StatusCard(title: String, value: String, valueColor: Color) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(4.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(value, color = valueColor, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
    }
}

@Composable
fun SensorDisplay(title: String, values: FloatArray) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(2.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Color(0xFF37474F))
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                SensorValueBox("X轴", values[0])
                SensorValueBox("Y轴", values[1])
                SensorValueBox("Z轴", values[2])
            }
        }
    }
}

@Composable
fun SensorValueBox(axis: String, value: Float) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(axis, fontSize = 14.sp, color = Color.Gray)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            String.format("%.3f", value),
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFF00695C)
        )
    }
}

@Composable
fun ActivityInput(
    currentActivity: String,
    onActivityChange: (String) -> Unit,
    isLogging: Boolean
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        OutlinedTextField(
            value = currentActivity,
            onValueChange = onActivityChange,
            label = { Text("活动标签") },
            placeholder = { Text("例如: Walking, Jogging, Sitting") },
            enabled = !isLogging,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        )
    }
}

@Composable
fun AccelerationWaveformDisplay(accHistory: List<Float>) {
    val minVal = (accHistory.minOrNull() ?: 0f) * 0.9f
    val maxVal = (accHistory.maxOrNull() ?: 1f) * 1.1f
    val range = maxVal - minVal

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(2.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "加速度总向量大小波形图 (|A| in g)",
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                color = Color(0xFF37474F)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .background(Color(0xFFE8F5E9), RoundedCornerShape(8.dp))
                    .padding(4.dp)
            ) {
                if (accHistory.isEmpty()) return@Canvas

                val data = accHistory.toList()
                val canvasWidth = size.width
                val canvasHeight = size.height
                val stepX = canvasWidth / (data.size - 1).coerceAtLeast(1)

                val path = Path()

                // 1g参考线
                val gLineY = if (range > 0) {
                    canvasHeight * (1f - (1f - minVal) / range)
                } else {
                    canvasHeight / 2f
                }.coerceIn(0f, canvasHeight)

                drawLine(
                    color = Color.Gray.copy(alpha = 0.5f),
                    start = androidx.compose.ui.geometry.Offset(0f, gLineY),
                    end = androidx.compose.ui.geometry.Offset(canvasWidth, gLineY),
                    strokeWidth = 1.dp.toPx()
                )

                data.forEachIndexed { index, value ->
                    val x = index * stepX
                    val normalizedY = if (range > 0) (value - minVal) / range else 0.5f
                    val y = canvasHeight * (1f - normalizedY)

                    if (index == 0) {
                        path.moveTo(x, y)
                    } else {
                        path.lineTo(x, y)
                    }
                }

                drawPath(
                    path = path,
                    color = Color(0xFFE91E63),
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            Text(
                "范围: %.2f - %.2f g (参考线: 1g)".format(minVal, maxVal),
                fontSize = 12.sp,
                color = Color.Gray,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}