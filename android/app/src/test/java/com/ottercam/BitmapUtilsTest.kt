package com.kylescholz.otterspotter

import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class BitmapUtilsTest {

    @Test
    fun testGetExifOrientationFromFile() {
        val testFile = File.createTempFile("test_orientation_", ".jpg")
        try {
            val sampleBytes = File("/tmp/sample_photo.jpg").let { if (it.exists()) it.readBytes() else null }
            if (sampleBytes != null) {
                testFile.writeBytes(sampleBytes)
            } else {
                val exif = ExifInterface(testFile)
                exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                exif.saveAttributes()
            }

            val orientation = ExifInterface(testFile).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            assertEquals(ExifInterface.ORIENTATION_ROTATE_90, orientation)
        } finally {
            testFile.delete()
        }
    }
}
