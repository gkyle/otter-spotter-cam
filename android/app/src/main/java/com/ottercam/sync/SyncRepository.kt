package com.kylescholz.otterspotter.sync

import android.content.Context
import android.net.Uri
import android.util.Log
import com.kylescholz.otterspotter.OtterPhoto
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

class SyncRepository(private val context: Context) {

    private val db = SyncDatabase.getInstance(context)

    companion object {
        private const val TAG = "SyncRepository"

        @Volatile
        private var instance: SyncRepository? = null

        fun getInstance(context: Context): SyncRepository {
            return instance ?: synchronized(this) {
                instance ?: SyncRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    fun indexPhoto(
        uri: Uri,
        fileName: String,
        filePath: String?,
        dateTaken: Long,
        size: Long,
        clientPhotoId: String? = null
    ): SyncRecord {
        val existing = db.getByFileName(fileName)
        if (existing != null) {
            return existing
        }

        val id = clientPhotoId ?: "otter_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val record = SyncRecord(
            fileName = fileName,
            contentUri = uri.toString(),
            filePath = filePath,
            clientPhotoId = id,
            sha256 = null,
            fileSize = size,
            captureTime = dateTaken,
            syncStatus = SyncStatus.PENDING
        )
        db.upsert(record)
        Log.d(TAG, "Indexed photo for sync: $fileName (ID: $id)")
        return record
    }

    fun getPendingPhotos(): List<SyncRecord> {
        return db.getPending()
    }

    fun getAllSyncRecords(): Map<String, SyncRecord> {
        return db.getAll().associateBy { it.fileName }
    }

    fun getSyncSummary(): SyncSummary {
        val all = db.getAll()
        val total = all.size
        val uploaded = all.count { it.syncStatus == SyncStatus.UPLOADED }
        val pending = all.count { it.syncStatus == SyncStatus.PENDING || it.syncStatus == SyncStatus.UPLOADING }
        val failed = all.count { it.syncStatus == SyncStatus.FAILED }
        return SyncSummary(total = total, uploaded = uploaded, pending = pending, failed = failed)
    }

    fun markUploading(fileName: String) {
        db.markUploading(fileName)
    }

    fun markUploaded(fileName: String, sha256: String?, encounterId: String?) {
        db.markUploaded(fileName, sha256, encounterId)
    }

    fun markFailed(fileName: String, error: String) {
        db.markFailed(fileName, error)
    }

    fun reconcileWithServer(uploadedHashes: Set<String>, uploadedClientIds: Set<String>) {
        db.reconcileUploaded(uploadedHashes, uploadedClientIds)
    }

    fun ensureSha256(record: SyncRecord): String? {
        if (!record.sha256.isNullOrBlank()) {
            return record.sha256
        }

        val sha = computeSha256(record)
        if (sha != null) {
            record.sha256 = sha
            db.updateSha256(record.fileName, sha)
        }
        return sha
    }

    fun computeSha256(record: SyncRecord): String? {
        // Try file path first
        if (!record.filePath.isNullOrBlank()) {
            val file = File(record.filePath)
            if (file.exists() && file.isFile) {
                try {
                    return hashInputStream(file.inputStream())
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to hash file path: ${record.filePath}", e)
                }
            }
        }

        // Try content URI
        try {
            val uri = Uri.parse(record.contentUri)
            context.contentResolver.openInputStream(uri)?.use { stream ->
                return hashInputStream(stream)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to hash content uri: ${record.contentUri}", e)
        }

        return null
    }

    private fun hashInputStream(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(16384)
        var bytesRead: Int
        while (input.read(buffer).also { bytesRead = it } != -1) {
            digest.update(buffer, 0, bytesRead)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun scanAndSeedGalleryPhotos(photos: List<OtterPhoto>) {
        for (photo in photos) {
            val existing = db.getByFileName(photo.name)
            if (existing == null) {
                indexPhoto(
                    uri = photo.uri,
                    fileName = photo.name,
                    filePath = photo.filePath,
                    dateTaken = photo.dateTaken,
                    size = photo.size
                )
            }
        }
    }
}

data class SyncSummary(
    val total: Int,
    val uploaded: Int,
    val pending: Int,
    val failed: Int
)
