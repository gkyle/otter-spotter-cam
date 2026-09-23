package com.kylescholz.otterspotter.sync

enum class SyncStatus {
    PENDING,
    UPLOADING,
    UPLOADED,
    FAILED
}

data class SyncRecord(
    val fileName: String,
    val contentUri: String,
    val filePath: String?,
    val clientPhotoId: String,
    var sha256: String?,
    val fileSize: Long,
    val captureTime: Long,
    var syncStatus: SyncStatus,
    var encounterId: String? = null,
    var uploadedAt: Long? = null,
    var lastAttemptAt: Long? = null,
    var lastError: String? = null,
    var retryCount: Int = 0
)
