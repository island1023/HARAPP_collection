package com.example.hardatacollector // <-- 您的应用包名

import android.os.Environment
import android.content.Context
import android.graphics.Color as GColor
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent // 【新增导入】用于按键事件
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
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

// 定义数据结构来存储每一帧的传感器数据
data class SensorDataPoint(
    val timestamp: Long,
    val activityLabel: String,
    val accX: Float,
    val accY: Float,
    val accZ: Float,
    val gyrX: Float,
    val gyrY: Float,
    val gyrZ: Float
)

// Main Activity - Android App 的入口
class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private val sensorDataList = mutableListOf<SensorDataPoint>() // 存储采集到的数据
    private var isLogging by mutableStateOf(false) // 记录是否正在采集
    private var currentActivity by mutableStateOf("") // 当前的活动标签 (改为用户输入)
    private var lastAccTime: Long = 0 // 用于平滑采集频率
    private val sensorDataInterval = 20 // 目标采集间隔 (毫秒), 对应 50Hz

    // 实时传感器读数状态
    private var currentAcc by mutableStateOf(floatArrayOf(0f, 0f, 0f))
    private var currentGyr by mutableStateOf(floatArrayOf(0f, 0f, 0f))

    // 波形图数据历史
    private val accHistory = mutableStateListOf<Float>() // 存储加速度向量大小的历史值
    private val maxHistorySize = 150 // 波形图显示的最大数据点数

    // 注意：已移除原有的 activities 列表

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        // 注册传感器监听器
        registerSensors()

        setContent {
            MaterialTheme {
                // 传递 accHistory 到 Composable
                SensorDataCollectorApp(
                    isLogging = isLogging,
                    currentActivity = currentActivity,
                    accValues = currentAcc,
                    gyrValues = currentGyr,
                    accHistory = accHistory, // 新增：传递加速度历史
                    onStartLogging = ::startLogging,
                    onStopAndSave = ::stopAndSaveData,
                    onActivityChange = { label -> currentActivity = label }
                )
            }
        }
    }

    // 【新增功能】监听音量键按下事件
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // 检查按下的键是否是音量减键 (KEYCODE_VOLUME_DOWN)
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            // 只有在数据采集中才执行停止操作
            if (isLogging) {
                stopAndSaveData()
                // 返回 true 表示我们已经处理了该事件，系统将不会执行默认的音量调节操作
                return true
            }
        }
        // 对于其他按键或未采集中，执行默认行为
        return super.onKeyDown(keyCode, event)
    }

    // 注册传感器监听器
    private fun registerSensors() {
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        // SENSOR_DELAY_GAME 约等于 50Hz，且不需要 HIGH_SAMPLING_RATE_SENSORS 权限
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        if (accelerometer == null || gyroscope == null) {
            Toast.makeText(this, "设备缺少加速度计或陀螺仪传感器！", Toast.LENGTH_LONG).show()
        }
    }

    // 停止传感器监听
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

    // 传感器数值变化时调用
    override fun onSensorChanged(event: SensorEvent) {
        val currentTime = System.currentTimeMillis()

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                currentAcc = event.values.clone() // 实时更新 UI

                // 波形图和数据记录逻辑（每隔 sensorDataInterval 毫秒记录一次，以接近 50Hz）
                if (currentTime - lastAccTime >= sensorDataInterval) {
                    lastAccTime = currentTime

                    // 1. 计算加速度向量大小 (Magnitude) 并更新历史用于波形图
                    val accX = event.values[0]
                    val accY = event.values[1]
                    val accZ = event.values[2]
                    val magnitude = sqrt(accX * accX + accY * accY + accZ * accZ)

                    // 保持波形历史大小
                    if (accHistory.size >= maxHistorySize) {
                        accHistory.removeAt(0)
                    }
                    accHistory.add(magnitude)

                    // 2. 如果正在采集，则记录数据点
                    if (isLogging) {
                        // 使用当前的陀螺仪值，保证数据点的完整性
                        sensorDataList.add(
                            SensorDataPoint(
                                timestamp = currentTime,
                                activityLabel = currentActivity,
                                accX = accX,
                                accY = accY,
                                accZ = accZ,
                                gyrX = currentGyr[0],
                                gyrY = currentGyr[1],
                                gyrZ = currentGyr[2]
                            )
                        )
                    }
                }
            }
            Sensor.TYPE_GYROSCOPE -> {
                // 仅更新陀螺仪的实时显示状态，并供加速度计事件中记录时使用
                currentGyr = event.values.clone()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        // 传感器精度变化时调用，本例中不处理
    }

    // 开始数据采集
    private fun startLogging() {
        // 检查活动标签是否有效
        if (currentActivity.isBlank()) {
            Toast.makeText(this, "请输入一个有效的活动标签才能开始采集！", Toast.LENGTH_SHORT).show()
            return
        }
        if (isLogging) return

        sensorDataList.clear() // 清空上一次采集的数据
        isLogging = true
        Log.d("SensorApp", "开始采集活动：$currentActivity")
        Toast.makeText(this, "开始采集数据... (活动: $currentActivity)", Toast.LENGTH_SHORT).show()
    }

    // 停止并保存数据
    private fun stopAndSaveData() {
        if (!isLogging) {
            Toast.makeText(this, "当前未进行数据采集。", Toast.LENGTH_SHORT).show()
            return
        }

        isLogging = false
        Log.d("SensorApp", "停止采集。共采集 ${sensorDataList.size} 个样本。")

        if (sensorDataList.isEmpty()) {
            Toast.makeText(this, "数据列表为空，未保存。", Toast.LENGTH_SHORT).show()
            return
        }

        // 转换为 CSV 格式
        val csvContent = convertDataToCsv(sensorDataList)
        // 保存文件
        saveCsvFile(csvContent)

        sensorDataList.clear() // 保存后清空内存数据
    }

    // 将采集到的数据列表转换为 CSV 格式字符串
    private fun convertDataToCsv(dataList: List<SensorDataPoint>): String {
        val header = "Timestamp,Activity,AccX,AccY,AccZ,GyrX,GyrY,GyrZ\n"

        // 【关键修改】使用 %.6f 格式化，确保浮点数精度与 UCI-HAR 数据集一致
        val rows = dataList.joinToString("\n") { data ->
            val accXStr = String.format(Locale.US, "%.6f", data.accX)
            val accYStr = String.format(Locale.US, "%.6f", data.accY)
            val accZStr = String.format(Locale.US, "%.6f", data.accZ)
            val gyrXStr = String.format(Locale.US, "%.6f", data.gyrX)
            val gyrYStr = String.format(Locale.US, "%.6f", data.gyrY)
            val gyrZStr = String.format(Locale.US, "%.6f", data.gyrZ)

            "${data.timestamp},${data.activityLabel.replace(",", "_")}," +
                    "$accXStr,$accYStr,$accZStr," +
                    "$gyrXStr,$gyrYStr,$gyrZStr"
        }
        return header + rows
    }

    // 保存 CSV 文件到 App 内部缓存目录
    private fun saveCsvFile(content: String) {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val safeActivityLabel = currentActivity.replace(Regex("[^a-zA-Z0-9_]"), "")
        val fileName = "HAR_Data_${safeActivityLabel}_${timeStamp}.csv"

        // 【修改点】改用 context.getExternalFilesDir，无需手动申请权限
        val harDir = File(getExternalFilesDir(null), "HAR")

        if (!harDir.exists()) {
            harDir.mkdirs()
        }

        val file = File(harDir, fileName)

        try {
            FileOutputStream(file).use { outputStream ->
                outputStream.write(content.toByteArray())
            }
            // 提示用户去哪里找文件
            Toast.makeText(
                this,
                "文件已存至内部存储：\nAndroid/data/${packageName}/files/HAR/",
                Toast.LENGTH_LONG
            ).show()
            Log.i("SensorApp", "保存路径：${file.absolutePath}")
        } catch (e: Exception) {
            Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}

// Jetpack Compose UI
@Composable
fun SensorDataCollectorApp(
    isLogging: Boolean,
    currentActivity: String,
    accValues: FloatArray,
    gyrValues: FloatArray,
    accHistory: SnapshotStateList<Float>, // 接收加速度历史
    onStartLogging: () -> Unit,
    onStopAndSave: () -> Unit,
    onActivityChange: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .background(Color(0xFFF0F4F8)), // 浅灰色背景
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "UCI-HAR 原始数据采集器",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 20.dp),
            color = Color(0xFF1E88E5) // 蓝色标题
        )

        LazyColumn(
            modifier = Modifier.weight(1f)
        ) {
            item {
                // 【新增】位置约束指导卡片
                PlacementGuidanceCard()

                Spacer(modifier = Modifier.height(16.dp))

                // 采集状态
                StatusCard(
                    title = "采集状态",
                    value = if (isLogging) "采集中... (${accHistory.size} 样本)" else "已停止",
                    valueColor = if (isLogging) Color.Red else Color.Green
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 用户输入活动标签
                ActivityInput(
                    currentActivity = currentActivity,
                    onActivityChange = onActivityChange,
                    isLogging = isLogging
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 实时传感器数据展示
                SensorDisplay(title = "加速度计 (m/s²)", values = accValues)
                Spacer(modifier = Modifier.height(8.dp))
                SensorDisplay(title = "陀螺仪 (rad/s)", values = gyrValues)

                Spacer(modifier = Modifier.height(16.dp))

                // 加速度波形图
                AccelerationWaveformDisplay(accHistory = accHistory)

                Spacer(modifier = Modifier.height(16.dp))
            }
        }


        // 控制按钮 (放在底部)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(
                onClick = onStartLogging,
                // 只有在未采集中且活动标签不为空时才能启动
                enabled = !isLogging && currentActivity.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)), // 绿色
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
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336)), // 红色
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

// 【新增】位置约束指导卡片
@Composable
fun PlacementGuidanceCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(4.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFDE7)) // 浅黄色背景，用于突出提醒
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "⚠️ 采集设备位置与方向指南 (UCI-HAR 标准)",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = Color(0xFFC62828) // 深红色
            )
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                "为确保数据质量与 UCI-HAR 标准兼容，请**严格遵守**以下要求：",
                fontSize = 14.sp,
                color = Color(0xFF37474F)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                "1. **位置固定：** 手机必须牢固地**固定在腰部（如皮带或臂带）或裤兜/大腿根部**。避免在采集过程中自由晃动。",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "2. **屏幕方向：** 手机屏幕必须**朝外**（远离身体），且与身体**垂直对齐**。",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "3. **坐标系：** 保持手机与身体的对齐方式一致，以确保 X/Y/Z 轴与身体的运动轴（垂直、前后、左右）保持相对一致。",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

// 状态信息卡片
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

// 实时传感器数据展示组件
@Composable
fun SensorDisplay(title: String, values: FloatArray) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(2.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Color(0xFF37474F)) // 深灰色
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

// 单个传感器轴值展示
@Composable
fun SensorValueBox(axis: String, value: Float) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(axis, fontSize = 14.sp, color = Color.Gray)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            String.format("%.3f", value),
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFF00695C) // 深绿色
        )
    }
}

// 活动标签输入组件 (替换 ActivitySelector)
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
            label = { Text("自定义活动标签 (例如: fast_walking_userA)") },
            placeholder = { Text("输入您的活动名称") },
            enabled = !isLogging,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            "提示：请使用小写字母、数字或下划线，用于模型训练。",
            fontSize = 12.sp,
            color = Color.Gray
        )
    }
}

// 加速度波形图显示组件
@Composable
fun AccelerationWaveformDisplay(accHistory: List<Float>) {
    // ➤ 提前计算 min/max，Canvas 和 Text 可以共用
    val minVal = accHistory.minOrNull() ?: 0f
    val maxVal = accHistory.maxOrNull() ?: 10f

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(2.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "加速度总向量大小波形图 (|A| in m/s²)",
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                color = Color(0xFF37474F)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .background(Color(0xFFE3F2FD), RoundedCornerShape(8.dp))
                    .padding(4.dp)
            ) {
                if (accHistory.isEmpty()) return@Canvas

                val data = accHistory.toList()
                val range = maxVal - minVal
                val canvasWidth = size.width
                val canvasHeight = size.height
                val stepX = canvasWidth / (data.size - 1).coerceAtLeast(1)

                val path = Path()

                // ► 标准重力线位置
                val gravityY = if (range > 0) {
                    canvasHeight * (1f - (9.8f - minVal) / range)
                } else {
                    canvasHeight / 2f
                }

                drawLine(
                    color = Color.Gray.copy(alpha = 0.5f),
                    start = androidx.compose.ui.geometry.Offset(0f, gravityY.coerceIn(0f, canvasHeight)),
                    end = androidx.compose.ui.geometry.Offset(canvasWidth, gravityY.coerceIn(0f, canvasHeight)),
                    strokeWidth = 1.dp.toPx()
                )

                data.forEachIndexed { index, value ->
                    val x = index * stepX
                    val normalizedY = if (range > 0) {
                        (value - minVal) / range
                    } else {
                        0.5f
                    }
                    val y = canvasHeight * (1f - normalizedY)

                    if (index == 0) {
                        path.moveTo(x, y)
                    } else {
                        path.lineTo(x, y)
                    }
                }

                drawPath(
                    path = path,
                    color = Color(0xFF1E88E5),
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            // ➤ 这里使用的 minVal/maxVal 就不会报错
            Text(
                "Y轴范围: %.2f - %.2f m/s² (中线: 9.8 m/s²)".format(minVal, maxVal),
                fontSize = 12.sp,
                color = Color.Gray,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}