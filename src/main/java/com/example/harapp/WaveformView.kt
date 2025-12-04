package com.example.harapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.min

/**
 * WaveformView 是一个自定义视图，用于实时绘制加速度计的 X, Y, Z 三轴波形。
 * 它使用一个线程安全的队列来存储最新的传感器数据。
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // 绘制波形使用的画笔
    private val paintX = Paint().apply {
        color = Color.RED
        strokeWidth = 3f
        isAntiAlias = true
    }
    private val paintY = Paint().apply {
        color = Color.GREEN
        strokeWidth = 3f
        isAntiAlias = true
    }
    private val paintZ = Paint().apply {
        color = Color.BLUE
        strokeWidth = 3f
        isAntiAlias = true
    }
    private val paintGrid = Paint().apply {
        color = Color.GRAY
        strokeWidth = 1f
        alpha = 100 // 设置透明度
    }

    // 存储传感器数据的队列，使用线程安全队列
    // 假设需要显示约 1 秒的数据，50Hz 采样率 -> 50 个点
    private val maxPoints = 100
    private val dataQueueX: ConcurrentLinkedQueue<Float> = ConcurrentLinkedQueue()
    private val dataQueueY: ConcurrentLinkedQueue<Float> = ConcurrentLinkedQueue()
    private val dataQueueZ: ConcurrentLinkedQueue<Float> = ConcurrentLinkedQueue()

    // 传感器的最大/最小范围 (g)，用于归一化。地球重力约 9.8m/s²
    private val sensorRange = 10f

    /**
     * 添加新的传感器数据点，并触发重绘。
     * @param x 加速度 X 轴数据
     * @param y 加速度 Y 轴数据
     * @param z 加速度 Z 轴数据
     */
    fun addData(x: Float, y: Float, z: Float) {
        // 保持队列长度不超过最大点数
        if (dataQueueX.size >= maxPoints) {
            dataQueueX.poll()
            dataQueueY.poll()
            dataQueueZ.poll()
        }
        dataQueueX.offer(x)
        dataQueueY.offer(y)
        dataQueueZ.offer(z)

        // 异步请求重新绘制视图
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = width.toFloat()
        val height = height.toFloat()

        if (width <= 0 || height <= 0 || dataQueueX.isEmpty()) return

        // 绘制中线 (零点)
        canvas.drawLine(0f, height / 2, width, height / 2, paintGrid)

        // 绘制水平网格线 (辅助观察幅度)
        val numGridLines = 4
        for (i in 1..numGridLines) {
            canvas.drawLine(0f, height / (numGridLines + 1) * i, width, height / (numGridLines + 1) * i, paintGrid)
        }

        // 计算每个数据点的横向间距
        val dataSize = dataQueueX.size
        val step = width / (maxPoints - 1).toFloat()

        // 将队列转换为列表进行迭代
        val listX = dataQueueX.toList()
        val listY = dataQueueY.toList()
        val listZ = dataQueueZ.toList()

        // 绘制波形
        drawWaveform(canvas, listX, paintX, dataSize, step, height)
        drawWaveform(canvas, listY, paintY, dataSize, step, height)
        drawWaveform(canvas, listZ, paintZ, dataSize, step, height)

        // 绘制图例，将 width 作为参数传递
        drawLegend(canvas, width, height)
    }

    private fun drawWaveform(
        canvas: Canvas,
        dataList: List<Float>,
        paint: Paint,
        dataSize: Int,
        step: Float,
        height: Float
    ) {
        for (i in 0 until dataSize - 1) {
            val value1 = dataList[i]
            val value2 = dataList[i + 1]

            // 将传感器值归一化到视图高度
            val y1 = height / 2 - (value1 / sensorRange).coerceIn(-1f, 1f) * (height / 2) * 0.9f
            val y2 = height / 2 - (value2 / sensorRange).coerceIn(-1f, 1f) * (height / 2) * 0.9f
            val x1 = i * step + (maxPoints - dataSize) * step // 调整起点使波形从右侧进入
            val x2 = (i + 1) * step + (maxPoints - dataSize) * step

            canvas.drawLine(x1, y1, x2, y2, paint)
        }
    }

    private fun drawLegend(canvas: Canvas, width: Float, height: Float) {
        val textSize = 24f
        val padding = 10f
        val startX = width - 150f

        // 直接创建 val，不会再对它重新赋值
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = textSize
        }

        // X
        textPaint.color = paintX.color
        canvas.drawText("X", startX, height - textSize - padding * 2, textPaint)

        // Y
        textPaint.color = paintY.color
        canvas.drawText("Y", startX + 50f, height - textSize - padding * 2, textPaint)

        // Z
        textPaint.color = paintZ.color
        canvas.drawText("Z", startX + 100f, height - textSize - padding * 2, textPaint)
    }
}