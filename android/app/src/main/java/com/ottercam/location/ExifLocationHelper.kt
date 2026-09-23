package com.kylescholz.otterspotter.location

import android.content.Context
import android.location.Location
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.roundToLong

object ExifLocationHelper {

    private const val TAG = "ExifLocationHelper"

    /**
     * Writes high-accuracy location metadata (coordinates, altitude, timestamp, and
     * horizontal accuracy as GPSHPositioningError) to the specified JPEG file.
     */
    fun writeLocationToExif(file: File, location: Location) {
        try {
            val exif = ExifInterface(file)
            applyLocationAttributes(exif, location)
            exif.saveAttributes()
            Log.i(TAG, "Successfully wrote GPS EXIF tags (including GPSHPositioningError) to ${file.name}: " +
                    "(${location.latitude}, ${location.longitude}, acc=${location.accuracy}m)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write GPS EXIF tags to ${file.absolutePath}: ${e.message}", e)
        }
    }

    /**
     * Writes high-accuracy location metadata using ContentResolver and FileDescriptor.
     * Useful for Android 10+ MediaStore content URIs where direct file access may be restricted.
     */
    fun writeLocationToExif(context: Context, uri: Uri, location: Location) {
        try {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                applyLocationAttributes(exif, location)
                exif.saveAttributes()
                Log.i(TAG, "Successfully wrote GPS EXIF tags (including GPSHPositioningError) to URI $uri: " +
                        "(${location.latitude}, ${location.longitude}, acc=${location.accuracy}m)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write GPS EXIF tags to URI $uri: ${e.message}", e)
        }
    }

    /**
     * High-level helper that attempts to write directly to the DCIM/Otter Spotter file first,
     * and seamlessly falls back to ContentResolver if direct file access fails.
     */
    fun writeLocationToExif(context: Context, uri: Uri, fileName: String, location: Location) {
        var written = false
        try {
            val dcimDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Otter Spotter")
            val savedFile = File(dcimDir, fileName)
            if (savedFile.exists() && savedFile.canWrite()) {
                writeLocationToExif(savedFile, location)
                written = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Direct file EXIF write failed for $fileName, attempting ContentResolver fallback: ${e.message}")
        }

        if (!written) {
            writeLocationToExif(context, uri, location)
        }
    }

    /**
     * Applies coordinates, altitude, timestamp, and GPSHPositioningError to the ExifInterface.
     */
    fun applyLocationAttributes(exif: ExifInterface, location: Location) {
        // Standard coordinates, altitude, and timestamps
        exif.setGpsInfo(location)

        // EXIF 2.3 Tag 0x001F (31): GPSHPositioningError (horizontal positioning error in meters)
        // Stored as URATIONAL (numerator/denominator).
        if (location.hasAccuracy() && location.accuracy > 0f && !location.accuracy.isNaN()) {
            val accuracyCm = (location.accuracy * 100f).roundToLong().coerceAtLeast(1L)
            exif.setAttribute(
                ExifInterface.TAG_GPS_H_POSITIONING_ERROR,
                "$accuracyCm/100"
            )
            Log.d(TAG, "Set GPSHPositioningError to $accuracyCm/100 (${location.accuracy}m)")
        }
    }

    fun hasGpsCoordinates(file: File): Boolean {
        return try {
            val exif = ExifInterface(file)
            exif.latLong != null
        } catch (e: Exception) {
            false
        }
    }

    fun getGpsCoordinates(file: File): kotlin.Pair<Double, Double>? {
        return try {
            val exif = ExifInterface(file)
            val coords = exif.latLong
            if (coords != null && coords.size >= 2) {
                kotlin.Pair(coords[0], coords[1])
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun hasHPositioningError(file: File): Boolean {
        return getHPositioningError(file) != null
    }

    fun getHPositioningError(file: File): Double? {
        return try {
            val exif = ExifInterface(file)
            val error = exif.getAttributeDouble(ExifInterface.TAG_GPS_H_POSITIONING_ERROR, -1.0)
            if (error >= 0.0) error else null
        } catch (e: Exception) {
            null
        }
    }
}
