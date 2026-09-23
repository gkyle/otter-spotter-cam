package com.kylescholz.otterspotter

import ai.onnxruntime.*
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.FileChannel

enum class ModelEngine(val label: String) {
    TFLITE_640("TFLite 640"),
    ONNX_256("ONNX 256")
}

class OtterDetector(
    private val context: Context,
    private val onDetectionResult: (
        detections: List<Detection>,
        wholeBodyBoxes: List<RectF>,
        allDetections: List<Detection>,
        inferMs: Long,
        engineLabel: String,
        frameWidth: Int,
        frameHeight: Int
    ) -> Unit
) {
    companion object {
        private const val TAG = "OtterDetector"
        const val ONNX_SIZE = 256
        const val TFLITE_SIZE = 640
    }

    var engine: ModelEngine = ModelEngine.TFLITE_640
        set(value) {
            field = value
            if (value == ModelEngine.ONNX_256 && onnxSession == null) {
                try {
                    initOnnxModel()
                } catch (t: Throwable) {
                    Log.w(TAG, "Could not load ONNX model: ${t.message}")
                }
            }
        }
    var otterConfidence: Float = 0.25f
    var throatConfidence: Float = 0.25f
    var minConfidence: Float
        get() = throatConfidence
        set(value) { throatConfidence = value }
    var isEnabled: Boolean = true
    @Volatile private var isClosed: Boolean = false

    // ==========================================
    // ONNX Runtime Resources (256x256)
    // ==========================================
    private var env: OrtEnvironment? = null
    private var onnxSession: OrtSession? = null
    private val onnxPlaneSize = ONNX_SIZE * ONNX_SIZE
    private val onnxInputBuffer: FloatBuffer = ByteBuffer.allocateDirect(1 * 3 * onnxPlaneSize * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
    private val onnxLetterboxBitmap = Bitmap.createBitmap(ONNX_SIZE, ONNX_SIZE, Bitmap.Config.ARGB_8888)
    private val onnxCanvas = android.graphics.Canvas(onnxLetterboxBitmap)

    private val onnxPixels = IntArray(onnxPlaneSize)
    private val onnxR = FloatArray(onnxPlaneSize)
    private val onnxG = FloatArray(onnxPlaneSize)
    private val onnxB = FloatArray(onnxPlaneSize)

    // ==========================================
    // TensorFlow Lite Resources (640x640)
    // ==========================================
    private var tfliteSession: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private val tflitePlaneSize = TFLITE_SIZE * TFLITE_SIZE
    private val tfliteInputBuffer: ByteBuffer = ByteBuffer.allocateDirect(1 * 3 * tflitePlaneSize * 4)
        .order(ByteOrder.nativeOrder())
    private val tfliteOutputArray = Array(1) { Array(300) { FloatArray(6) } }
    private val tfliteLetterboxBitmap = Bitmap.createBitmap(TFLITE_SIZE, TFLITE_SIZE, Bitmap.Config.ARGB_8888)
    private val tfliteCanvas = android.graphics.Canvas(tfliteLetterboxBitmap)
    private val tflitePixels = IntArray(tflitePlaneSize)
    private val tfliteR = FloatArray(tflitePlaneSize)
    private val tfliteG = FloatArray(tflitePlaneSize)
    private val tfliteB = FloatArray(tflitePlaneSize)

    private val bgPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.rgb(114, 114, 114)
    }

    init {
        initTfLiteModel()
    }

    private fun initOnnxModel() {
        val modelBytes = try {
            context.assets.open("best.onnx").use { it.readBytes() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read best.onnx asset", e)
            return
        }

        try {
            if (env == null) {
                env = OrtEnvironment.getEnvironment()
            }
            val nnapiOptions = OrtSession.SessionOptions().apply {
                addNnapi()
                setIntraOpNumThreads(4)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            onnxSession = env?.createSession(modelBytes, nnapiOptions)
            Log.i(TAG, "ONNX NNAPI hardware acceleration enabled!")
        } catch (e: Throwable) {
            Log.w(TAG, "ONNX NNAPI session creation failed (${e.message}), falling back to CPU")
            try {
                val cpuOptions = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(4)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                }
                onnxSession = env?.createSession(modelBytes, cpuOptions)
                Log.i(TAG, "ONNX CPU session initialized successfully!")
            } catch (cpuEx: Throwable) {
                Log.e(TAG, "Failed to initialize ONNX CPU session: ${cpuEx.message}", cpuEx)
            }
        }
    }

    private fun initTfLiteModel() {
        try {
            val assetFd = context.assets.openFd("best.tflite")
            val fileInputStream = FileInputStream(assetFd.fileDescriptor)
            val fileChannel = fileInputStream.channel
            val modelBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, assetFd.startOffset, assetFd.declaredLength)

            val options = Interpreter.Options().apply {
                setNumThreads(4)
                useXNNPACK = true
                Log.i(TAG, "TFLite CPU XNNPACK with 4 threads enabled!")
            }
            tfliteSession = Interpreter(modelBuffer, options)
            Log.i(TAG, "TFLite 640x640 model initialized successfully!")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load TFLite model: ${e.message}", e)
        }
    }

    fun processImage(imageProxy: ImageProxy) {
        if (!isEnabled || isClosed) {
            imageProxy.close()
            return
        }

        try {
            val bitmap = imageProxy.toBitmap()
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees

            val rotatedBitmap = if (rotationDegrees != 0) {
                val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            } else {
                bitmap
            }

            synchronized(this) {
                if (isClosed) return@synchronized
                if (engine == ModelEngine.TFLITE_640 && tfliteSession != null) {
                    processWithTfLite(rotatedBitmap)
                } else if (onnxSession != null) {
                    processWithOnnx(rotatedBitmap)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error in detection analysis: ${e.message}", e)
        } finally {
            imageProxy.close()
        }
    }

    private fun processWithTfLite(rotatedBitmap: Bitmap) {
        val origW = rotatedBitmap.width
        val origH = rotatedBitmap.height
        val t0 = SystemClock.uptimeMillis()

        // Letterbox into 640x640
        val scale = minOf(TFLITE_SIZE.toFloat() / origW, TFLITE_SIZE.toFloat() / origH)
        val nw = Math.round(origW * scale)
        val nh = Math.round(origH * scale)
        val dx = (TFLITE_SIZE - nw) / 2f
        val dy = (TFLITE_SIZE - nh) / 2f

        val tPre0 = SystemClock.uptimeMillis()
        tfliteCanvas.drawRect(0f, 0f, TFLITE_SIZE.toFloat(), TFLITE_SIZE.toFloat(), bgPaint)
        val drawMatrix = Matrix().apply {
            postScale(scale, scale)
            postTranslate(dx, dy)
        }
        tfliteCanvas.drawBitmap(rotatedBitmap, drawMatrix, null)

        // Extract planar RGB normalized Float32 [0..1]
        tfliteLetterboxBitmap.getPixels(tflitePixels, 0, TFLITE_SIZE, 0, 0, TFLITE_SIZE, TFLITE_SIZE)

        tfliteInputBuffer.rewind()
        val floatBuf = tfliteInputBuffer.asFloatBuffer()

        for (i in 0 until tflitePlaneSize) {
            val p = tflitePixels[i]
            tfliteR[i] = ((p shr 16) and 0xFF) / 255f
            tfliteG[i] = ((p shr 8) and 0xFF) / 255f
            tfliteB[i] = (p and 0xFF) / 255f
        }

        floatBuf.put(tfliteR)
        floatBuf.put(tfliteG)
        floatBuf.put(tfliteB)
        tfliteInputBuffer.rewind()
        val preMs = SystemClock.uptimeMillis() - tPre0

        // Run TFLite inference
        val tInfer0 = SystemClock.uptimeMillis()
        tfliteSession!!.run(tfliteInputBuffer, tfliteOutputArray)
        val modelMs = SystemClock.uptimeMillis() - tInfer0
        val totalMs = SystemClock.uptimeMillis() - t0

        Log.i(TAG, "TFLite timing: total=${totalMs}ms, preprocess=${preMs}ms, model_run=${modelMs}ms")

        val wholeBodyBoxes = mutableListOf<RectF>()
        val throatCandidates = mutableListOf<Detection>()
        val allDetections = mutableListOf<Detection>()

        val preds = tfliteOutputArray[0]
        for (i in 0 until 300) {
            val row = preds[i]
            val score = row[4]
            if (score < 0.15f) continue

            val classId = Math.round(row[5])
            val rawX1 = row[0]
            val rawY1 = row[1]
            val rawX2 = row[2]
            val rawY2 = row[3]

            val bx1 = maxOf(0f, (rawX1 - dx) / scale)
            val by1 = maxOf(0f, (rawY1 - dy) / scale)
            val bx2 = minOf(origW.toFloat(), (rawX2 - dx) / scale)
            val by2 = minOf(origH.toFloat(), (rawY2 - dy) / scale)
            val box = RectF(bx1, by1, bx2, by2)

            val className = when (classId) {
                0 -> "whole_body"
                1 -> "throat"
                2 -> "throat_portrait"
                else -> "class_$classId"
            }

            val det = Detection(classId, className, score, box)
            allDetections.add(det)

            if (classId == 0 && score >= otterConfidence) {
                wholeBodyBoxes.add(box)
            } else if (classId == 2 && score >= throatConfidence) {
                throatCandidates.add(det)
            }
        }

        // Strict Enclosure Filter: keep ONLY throat_portrait boxes fully enclosed by whole_body
        val enclosedPortraits = throatCandidates.filter { cand ->
            wholeBodyBoxes.any { wb -> isBoxFullyEnclosed(cand.box, wb) }
        }

        onDetectionResult(enclosedPortraits, wholeBodyBoxes, allDetections, modelMs, "TFLite 640", origW, origH)
    }

    private fun processWithOnnx(rotatedBitmap: Bitmap) {
        val origW = rotatedBitmap.width
        val origH = rotatedBitmap.height
        val t0 = SystemClock.uptimeMillis()

        // Letterbox into 256x256
        val scale = minOf(ONNX_SIZE.toFloat() / origW, ONNX_SIZE.toFloat() / origH)
        val nw = Math.round(origW * scale)
        val nh = Math.round(origH * scale)
        val dx = (ONNX_SIZE - nw) / 2f
        val dy = (ONNX_SIZE - nh) / 2f

        onnxCanvas.drawRect(0f, 0f, ONNX_SIZE.toFloat(), ONNX_SIZE.toFloat(), bgPaint)
        val drawMatrix = Matrix().apply {
            postScale(scale, scale)
            postTranslate(dx, dy)
        }
        onnxCanvas.drawBitmap(rotatedBitmap, drawMatrix, null)

        onnxLetterboxBitmap.getPixels(onnxPixels, 0, ONNX_SIZE, 0, 0, ONNX_SIZE, ONNX_SIZE)

        onnxInputBuffer.rewind()

        for (i in 0 until onnxPlaneSize) {
            val p = onnxPixels[i]
            onnxR[i] = ((p shr 16) and 0xFF) / 255f
            onnxG[i] = ((p shr 8) and 0xFF) / 255f
            onnxB[i] = (p and 0xFF) / 255f
        }

        onnxInputBuffer.put(onnxR)
        onnxInputBuffer.put(onnxG)
        onnxInputBuffer.put(onnxB)
        onnxInputBuffer.rewind()

        val currEnv = env ?: return
        val currSession = onnxSession ?: return

        val inputTensor = OnnxTensor.createTensor(
            currEnv,
            onnxInputBuffer,
            longArrayOf(1, 3, ONNX_SIZE.toLong(), ONNX_SIZE.toLong())
        )

        val tInfer0 = SystemClock.uptimeMillis()
        val results = currSession.run(mapOf("images" to inputTensor))
        val modelMs = SystemClock.uptimeMillis() - tInfer0

        val outputTensor = results[0] as OnnxTensor
        val floatBuffer = outputTensor.floatBuffer
        val numDetections = outputTensor.info.shape[1].toInt()

        val wholeBodyBoxes = mutableListOf<RectF>()
        val throatCandidates = mutableListOf<Detection>()
        val allDetections = mutableListOf<Detection>()

        for (i in 0 until numDetections) {
            val offset = i * 6
            val score = floatBuffer.get(offset + 4)
            if (score < 0.15f) continue

            val classId = Math.round(floatBuffer.get(offset + 5))
            val rawX1 = floatBuffer.get(offset)
            val rawY1 = floatBuffer.get(offset + 1)
            val rawX2 = floatBuffer.get(offset + 2)
            val rawY2 = floatBuffer.get(offset + 3)

            val bx1 = maxOf(0f, (rawX1 - dx) / scale)
            val by1 = maxOf(0f, (rawY1 - dy) / scale)
            val bx2 = minOf(origW.toFloat(), (rawX2 - dx) / scale)
            val by2 = minOf(origH.toFloat(), (rawY2 - dy) / scale)
            val box = RectF(bx1, by1, bx2, by2)

            val className = when (classId) {
                0 -> "whole_body"
                1 -> "throat"
                2 -> "throat_portrait"
                else -> "class_$classId"
            }

            val det = Detection(classId, className, score, box)
            allDetections.add(det)

            if (classId == 0 && score >= otterConfidence) {
                wholeBodyBoxes.add(box)
            } else if (classId == 2 && score >= throatConfidence) {
                throatCandidates.add(det)
            }
        }

        val enclosedPortraits = throatCandidates.filter { cand ->
            wholeBodyBoxes.any { wb -> isBoxFullyEnclosed(cand.box, wb) }
        }

        onDetectionResult(enclosedPortraits, wholeBodyBoxes, allDetections, modelMs, "ONNX 256", origW, origH)

        inputTensor.close()
        results.close()
    }

    private fun isBoxFullyEnclosed(inner: RectF, outer: RectF, tolerancePct: Float = 0.05f): Boolean {
        if (outer.left <= inner.left && outer.top <= inner.top &&
            inner.right <= outer.right && inner.bottom <= outer.bottom) {
            return true
        }

        val iw = inner.width()
        val ih = inner.height()
        val tolX = iw * tolerancePct
        val tolY = ih * tolerancePct

        if ((outer.left - tolX <= inner.left) && (outer.top - tolY <= inner.top) &&
            (inner.right <= outer.right + tolX) && (inner.bottom <= outer.bottom + tolY)) {
            return true
        }

        val interLeft = maxOf(inner.left, outer.left)
        val interTop = maxOf(inner.top, outer.top)
        val interRight = minOf(inner.right, outer.right)
        val interBottom = minOf(inner.bottom, outer.bottom)

        val interW = maxOf(0f, interRight - interLeft)
        val interH = maxOf(0f, interBottom - interTop)
        val innerArea = iw * ih

        if (innerArea > 0f && (interW * interH) / innerArea >= 0.90f) {
            return true
        }

        return false
    }

    fun close() {
        isClosed = true
        synchronized(this) {
            try {
                onnxSession?.close()
                onnxSession = null
                env?.close()
                env = null
                tfliteSession?.close()
                tfliteSession = null
                gpuDelegate?.close()
                gpuDelegate = null
            } catch (e: Exception) {
                Log.w(TAG, "Error closing detector resources: ${e.message}")
            }
        }
    }
}
