package com.example.harapp

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * 负责将传感器数据导出到 CSV 文件。
 */
class DataExporter(private val context: Context) {

    // 【修复】将私有属性名称 'TAG' 改为 'tag' 以遵循 Kotlin 规范
    private val tag = "DataExporter"

    /**
     * 异步将收集到的数据写入 CSV 文件。
     * 文件路径: /Android/data/com.example.harapp/files/Documents/
     * * @param data 要导出的原始数据列表。
     * @param callback 导出完成后的回调，返回 (是否成功, 文件绝对路径)。
     */
    fun exportData(
        // 【修复】移除 SensorService 前缀，直接引用包级类 RawSensorData
        data: List<RawSensorData>,
        callback: (Boolean, String?) -> Unit
    ) {
        if (data.isEmpty()) {
            callback(false, null)
            return
        }

        // 异步执行文件写入，避免阻塞服务线程
        thread {
            try {
                // 1. 定义文件路径：使用应用专属的外部存储中的 Document 目录
                // 这个路径不需要额外的运行时存储权限，但文件仍可被用户通过文件管理器访问
                val documentsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                if (documentsDir == null || !documentsDir.exists() && !documentsDir.mkdirs()) {
                    // 【修改】使用修复后的 tag
                    Log.e(tag, "Failed to create or access Documents directory.")
                    callback(false, null)
                    return@thread
                }

                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val fileName = "HAR_Data_$timestamp.csv"
                val file = File(documentsDir, fileName)

                // 2. 写入数据
                FileOutputStream(file).use { fos ->
                    OutputStreamWriter(fos).use { writer ->
                        // 写入 CSV 头部
                        writer.append("timestamp_ms,accX,accY,accZ,gyroX,gyroY,gyroZ,activity_label\n")

                        // 写入数据行
                        data.forEach { d ->
                            // 【修复】RawSensorData 属性可正常访问
                            writer.append("${d.timestampMs},")
                            writer.append(String.format(Locale.US, "%.6f,%.6f,%.6f,", d.accX, d.accY, d.accZ))
                            writer.append(String.format(Locale.US, "%.6f,%.6f,%.6f,", d.gyroX, d.gyroY, d.gyroZ))
                            writer.append("${d.activityLabel}\n")
                        }
                        writer.flush()
                    }
                }

                // 【修改】使用修复后的 tag
                Log.i(tag, "Data successfully written to ${file.absolutePath}")
                callback(true, file.absolutePath)

            } catch (e: Exception) {
                // 【修改】使用修复后的 tag
                Log.e(tag, "Error writing data to file", e)
                callback(false, null)
            }
        }
    }
}