package com.example.harapp

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var sensorManager: SensorManager
    private var core: Core? = null

    // 默认不设状态，显示等待识别
    private var currentActivityName by mutableStateOf("等待识别...")
    private var currentState by mutableStateOf("准备就绪")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        core = Core(this) { result -> currentActivityName = result }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    HARScreen()
                }
            }
        }
    }

    @Composable
    fun HARScreen() {
        val scope = rememberCoroutineScope()
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(text = "实时识别结果", style = MaterialTheme.typography.labelMedium)
            Text(
                text = currentActivityName,
                style = MaterialTheme.typography.displayMedium,
                color = if (currentActivityName == "无法识别") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "状态：$currentState", style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(48.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Button(onClick = { scope.launch { switchModelTask("har.tflite") } }, modifier = Modifier.fillMaxWidth(0.8f).height(56.dp)) {
                    Text("腰部模式 (50Hz)")
                }
                Button(onClick = { scope.launch { switchModelTask("wisdm.tflite") } }, modifier = Modifier.fillMaxWidth(0.8f).height(56.dp)) {
                    Text("裤兜模式 (20Hz)")
                }
            }
        }
    }

    private suspend fun switchModelTask(modelName: String) {
        currentState = "正在加载..."
        currentActivityName = "正在分析..."
        withContext(Dispatchers.IO) { core?.switchModel(modelName) }
        currentState = "模型 $modelName 已就绪"
    }

    override fun onResume() {
        super.onResume()
        listOf(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE, Sensor.TYPE_LINEAR_ACCELERATION).forEach { type ->
            sensorManager.getDefaultSensor(type)?.let {
                sensorManager.registerListener(core, it, SensorManager.SENSOR_DELAY_GAME)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(core)
    }
}