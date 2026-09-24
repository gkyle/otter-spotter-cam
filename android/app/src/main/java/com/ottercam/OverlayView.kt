package com.kylescholz.otterspotter

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var detections: List<Detection> = emptyList()
    private var wholeBodyBoxes: List<RectF> = emptyList()
    private var allDetections: List<Detection> = emptyList()

    private var frameWidth: Int = 0
    private var frameHeight: Int = 0

    var showDebugOutlines: Boolean = false
    var showDetectionBoxes: Boolean = false
    var isAiEnabled: Boolean = true
    var lockStage: Int = 0

    private val greenBorderPaint = Paint().apply {
        color = Color.parseColor("#00E676")
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val cornerPaint = Paint().apply {
        color = Color.parseColor("#00E676")
        style = Paint.Style.STROKE
        strokeWidth = 10f
        isAntiAlias = true
    }

    private val otterBoxPaint = Paint().apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }

    private val throatBoxPaint = Paint().apply {
        color = Color.parseColor("#00E676")
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }

    private val textBgPaint = Paint().apply {
        color = Color.parseColor("#00E676")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val textBgCyanPaint = Paint().apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.parseColor("#080C14")
        textSize = 34f
        typeface = Typeface.DEFAULT_BOLD
        isAntiAlias = true
    }

    private val debugCyanPaint = Paint().apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 4f
        pathEffect = DashPathEffect(floatArrayOf(15f, 15f), 0f)
        isAntiAlias = true
    }

    private val debugAmberPaint = Paint().apply {
        color = Color.parseColor("#FF9100")
        style = Paint.Style.STROKE
        strokeWidth = 4f
        pathEffect = DashPathEffect(floatArrayOf(12f, 12f), 0f)
        isAntiAlias = true
    }

    fun setResults(
        detections: List<Detection>,
        wholeBodyBoxes: List<RectF>,
        allDetections: List<Detection>,
        frameWidth: Int,
        frameHeight: Int,
        lockStage: Int = 0
    ) {
        this.detections = detections
        this.wholeBodyBoxes = wholeBodyBoxes
        this.allDetections = allDetections
        this.frameWidth = frameWidth
        this.frameHeight = frameHeight
        this.lockStage = lockStage
        postInvalidate()
    }

    fun clear() {
        detections = emptyList()
        wholeBodyBoxes = emptyList()
        allDetections = emptyList()
        lockStage = 0
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isAiEnabled || frameWidth == 0 || frameHeight == 0) return

        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        // Match CameraX FILL_CENTER (object-fit: cover)
        val scale = maxOf(viewWidth / frameWidth, viewHeight / frameHeight)
        val offsetX = (viewWidth - frameWidth * scale) / 2f
        val offsetY = (viewHeight - frameHeight * scale) / 2f

        // Debug: Show all candidate detections in dashed amber
        if (showDebugOutlines) {
            for (det in allDetections) {
                val left = det.box.left * scale + offsetX
                val top = det.box.top * scale + offsetY
                val right = det.box.right * scale + offsetX
                val bottom = det.box.bottom * scale + offsetY
                val paint = if (det.className == "whole_body") debugCyanPaint else debugAmberPaint
                canvas.drawRect(left, top, right, bottom, paint)
            }
        }

        // Primary: Show detection boxes (Otters & Throat Portraits) when enabled from menu
        if (showDetectionBoxes) {
            // Draw whole_body boxes (Otters)
            for (box in wholeBodyBoxes) {
                val left = box.left * scale + offsetX
                val top = box.top * scale + offsetY
                val right = box.right * scale + offsetX
                val bottom = box.bottom * scale + offsetY
                canvas.drawRect(left, top, right, bottom, otterBoxPaint)

                val label = "Otter"
                val textWidth = textPaint.measureText(label)
                val labelHeight = 44f
                canvas.drawRect(left, top - labelHeight, left + textWidth + 24f, top, textBgCyanPaint)
                canvas.drawText(label, left + 12f, top - 12f, textPaint)
            }

            // Draw throat_portrait boxes
            val activeStageColor = when (lockStage) {
                1 -> Color.parseColor("#FF9100") // Amber (Hit 1)
                2 -> Color.parseColor("#00E5FF") // Cyan (Hit 2)
                3 -> Color.parseColor("#00E676") // Emerald (Hit 3)
                else -> Color.parseColor("#00E676")
            }
            cornerPaint.color = activeStageColor
            throatBoxPaint.color = activeStageColor
            textBgPaint.color = activeStageColor

            for (det in detections) {
                val left = det.box.left * scale + offsetX
                val top = det.box.top * scale + offsetY
                val right = det.box.right * scale + offsetX
                val bottom = det.box.bottom * scale + offsetY
                canvas.drawRect(left, top, right, bottom, throatBoxPaint)

                // Draw corner brackets
                val cornerLen = minOf((right - left) * 0.25f, (bottom - top) * 0.25f, 40f)
                canvas.drawLine(left, top, left + cornerLen, top, cornerPaint)
                canvas.drawLine(left, top, left, top + cornerLen, cornerPaint)
                canvas.drawLine(right, top, right - cornerLen, top, cornerPaint)
                canvas.drawLine(right, top, right, top + cornerLen, cornerPaint)
                canvas.drawLine(left, bottom, left + cornerLen, bottom, cornerPaint)
                canvas.drawLine(left, bottom, left, bottom - cornerLen, cornerPaint)
                canvas.drawLine(right, bottom, right - cornerLen, bottom, cornerPaint)
                canvas.drawLine(right, bottom, right, bottom - cornerLen, cornerPaint)

                val conf = (det.confidence * 100).toInt()
                val label = "Throat $conf%"
                val textWidth = textPaint.measureText(label)
                val labelHeight = 44f
                canvas.drawRect(left, top - labelHeight, left + textWidth + 24f, top, textBgPaint)
                canvas.drawText(label, left + 12f, top - 12f, textPaint)
            }
        }

        // Primary: Draw a border around the whole viewport when a throat_portrait is visible
        if (detections.isNotEmpty()) {
            val borderWidth = 10f * resources.displayMetrics.density
            val halfBorder = borderWidth / 2f
            val borderColor = when (lockStage) {
                1 -> Color.parseColor("#FF9100") // Amber (Hit 1: Otter spotted)
                2 -> Color.parseColor("#00E5FF") // Cyan (Hit 2: Locking in)
                3 -> Color.parseColor("#00E676") // Emerald (Hit 3: Capturing photo)
                else -> Color.parseColor("#00E676")
            }
            canvas.drawRect(
                halfBorder,
                halfBorder,
                viewWidth - halfBorder,
                viewHeight - halfBorder,
                greenBorderPaint.apply {
                    color = borderColor
                    strokeWidth = borderWidth
                }
            )
        }
    }
}
