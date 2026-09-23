package com.kylescholz.otterspotter.sync

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.MessageDigest

class SyncUnitTest {

    @Test
    fun testSha256DigestCalculation() {
        val testContent = "Otter Spotter Test Photo Content 2026"
        val expectedDigest = MessageDigest.getInstance("SHA-256")
            .digest(testContent.toByteArray())
            .joinToString("") { "%02x".format(it) }

        val inputStream = ByteArrayInputStream(testContent.toByteArray())
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024)
        var bytesRead: Int
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            digest.update(buffer, 0, bytesRead)
        }
        val computedDigest = digest.digest().joinToString("") { "%02x".format(it) }

        assertEquals(expectedDigest, computedDigest)
        assertEquals(64, computedDigest.length)
    }

    @Test
    fun testSyncRecordStateTransitions() {
        val record = SyncRecord(
            fileName = "Otter_20260923_120000.jpg",
            contentUri = "content://media/external/images/media/42",
            filePath = "/sdcard/DCIM/Otter Spotter/Otter_20260923_120000.jpg",
            clientPhotoId = "client_photo_test_123",
            sha256 = null,
            fileSize = 2048000L,
            captureTime = 1758600000000L,
            syncStatus = SyncStatus.PENDING
        )

        assertEquals(SyncStatus.PENDING, record.syncStatus)
        assertNull(record.sha256)
        assertNull(record.encounterId)

        // Simulating hash computation
        record.sha256 = "a5e3f362b7194685934f826359e13b860b298492048572948572948572948572"
        assertEquals(64, record.sha256?.length)

        // Simulating upload in progress
        record.syncStatus = SyncStatus.UPLOADING
        record.lastAttemptAt = System.currentTimeMillis()
        assertEquals(SyncStatus.UPLOADING, record.syncStatus)
        assertNotNull(record.lastAttemptAt)

        // Simulating successful upload
        record.syncStatus = SyncStatus.UPLOADED
        record.encounterId = "upload-20260923-a5e3f362"
        record.uploadedAt = System.currentTimeMillis()
        assertEquals(SyncStatus.UPLOADED, record.syncStatus)
        assertEquals("upload-20260923-a5e3f362", record.encounterId)
    }

    @Test
    fun testSyncCheckJsonParsing() {
        val jsonStr = """
            {
                "user_id": "104857294857294857294",
                "uploaded_hashes": ["hash_1", "hash_2"],
                "missing_hashes": ["hash_3"],
                "uploaded_client_ids": ["client_id_1"],
                "missing_client_ids": ["client_id_2", "client_id_3"]
            }
        """.trimIndent()

        val obj = JSONObject(jsonStr)
        val uploadedHashes = mutableSetOf<String>()
        val arr = obj.optJSONArray("uploaded_hashes")
        if (arr != null) {
            for (i in 0 until arr.length()) uploadedHashes.add(arr.getString(i))
        }

        val missingHashes = mutableSetOf<String>()
        val missingArr = obj.optJSONArray("missing_hashes")
        if (missingArr != null) {
            for (i in 0 until missingArr.length()) missingHashes.add(missingArr.getString(i))
        }

        assertEquals(2, uploadedHashes.size)
        assertTrue(uploadedHashes.contains("hash_1"))
        assertTrue(uploadedHashes.contains("hash_2"))
        assertEquals(1, missingHashes.size)
        assertTrue(missingHashes.contains("hash_3"))
    }

    @Test
    fun testSyncSummaryCounts() {
        val summary = SyncSummary(
            total = 10,
            uploaded = 8,
            pending = 2,
            failed = 0
        )
        assertEquals(10, summary.total)
        assertEquals(8, summary.uploaded)
        assertEquals(2, summary.pending)
        assertEquals(0, summary.failed)
    }

    @Test
    fun testGpsHPositioningErrorExif() {
        val testFile = java.io.File.createTempFile("test_exif_", ".jpg")
        try {
            val sourceBytes = java.io.File("/tmp/test_exif.jpg").readBytes()
            testFile.writeBytes(sourceBytes)
            val exif = androidx.exifinterface.media.ExifInterface(testFile)

            val accuracy = 3.5f
            val accuracyCm = (accuracy * 100f).toLong()
            exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_GPS_H_POSITIONING_ERROR, "$accuracyCm/100")
            exif.saveAttributes()

            val readExif = androidx.exifinterface.media.ExifInterface(testFile)
            val readVal = readExif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_GPS_H_POSITIONING_ERROR)
            val readDouble = readExif.getAttributeDouble(androidx.exifinterface.media.ExifInterface.TAG_GPS_H_POSITIONING_ERROR, -1.0)

            println("Read GPSHPositioningError string: $readVal, double: $readDouble")
            assertEquals("350/100", readVal)
            assertEquals(3.5, readDouble, 0.001)
        } finally {
            testFile.delete()
        }
    }
}
