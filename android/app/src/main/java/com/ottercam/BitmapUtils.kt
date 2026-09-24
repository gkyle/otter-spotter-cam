package com.kylescholz.otterspotter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File

object BitmapUtils {

    fun getExifOrientation(context: Context, photo: OtterPhoto): Int {
        if (photo.filePath != null) {
            try {
                val file = File(photo.filePath)
                if (file.exists()) {
                    val orientation = ExifInterface(file).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                    if (orientation != ExifInterface.ORIENTATION_UNDEFINED) {
                        return orientation
                    }
                }
            } catch (_: Exception) {
            }
        }

        try {
            context.contentResolver.openFileDescriptor(photo.uri, "r")?.use { pfd ->
                val orientation = ExifInterface(pfd.fileDescriptor).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
                if (orientation != ExifInterface.ORIENTATION_UNDEFINED) {
                    return orientation
                }
            }
        } catch (_: Exception) {
        }

        try {
            context.contentResolver.openInputStream(photo.uri)?.use { stream ->
                val orientation = ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
                if (orientation != ExifInterface.ORIENTATION_UNDEFINED) {
                    return orientation
                }
            }
        } catch (_: Exception) {
        }

        return ExifInterface.ORIENTATION_NORMAL
    }

    fun rotateBitmapIfNeeded(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }

        return try {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) {
                bitmap.recycle()
            }
            rotated
        } catch (_: OutOfMemoryError) {
            bitmap
        }
    }

    fun decodeSampledBitmap(
        context: Context,
        photo: OtterPhoto,
        reqWidth: Int,
        reqHeight: Int
    ): Bitmap? {
        try {
            val orientation = getExifOrientation(context, photo)
            val isRotated90Or270 = orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
                    orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
                    orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
                    orientation == ExifInterface.ORIENTATION_TRANSVERSE

            var rawBitmap: Bitmap? = null

            // 1. Try decoding from direct filePath if available
            if (photo.filePath != null && File(photo.filePath).exists()) {
                try {
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(photo.filePath, options)
                    options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight, isRotated90Or270)
                    options.inJustDecodeBounds = false
                    rawBitmap = BitmapFactory.decodeFile(photo.filePath, options)
                } catch (_: Exception) {
                }
            }

            // 2. Fallback: decode via ContentResolver input stream
            if (rawBitmap == null) {
                try {
                    context.contentResolver.openInputStream(photo.uri)?.use { stream ->
                        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeStream(stream, null, options)
                        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight, isRotated90Or270)
                        options.inJustDecodeBounds = false

                        context.contentResolver.openInputStream(photo.uri)?.use { stream2 ->
                            rawBitmap = BitmapFactory.decodeStream(stream2, null, options)
                        }
                    }
                } catch (_: Exception) {
                }
            }

            val finalBitmap = rawBitmap ?: return null
            return rotateBitmapIfNeeded(finalBitmap, orientation)
        } catch (_: Exception) {
            return null
        }
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int,
        isRotated90Or270: Boolean
    ): Int {
        val rawHeight = options.outHeight
        val rawWidth = options.outWidth
        val height = if (isRotated90Or270) rawWidth else rawHeight
        val width = if (isRotated90Or270) rawHeight else rawWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2

            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}
