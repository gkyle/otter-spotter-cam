package com.kylescholz.otterspotter.sync

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

class SyncDatabase(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val TAG = "SyncDatabase"
        private const val DATABASE_NAME = "otter_sync.db"
        private const val DATABASE_VERSION = 1

        const val TABLE_PHOTOS = "photo_sync"
        const val COL_FILE_NAME = "file_name"
        const val COL_CONTENT_URI = "content_uri"
        const val COL_FILE_PATH = "file_path"
        const val COL_CLIENT_PHOTO_ID = "client_photo_id"
        const val COL_SHA256 = "sha256"
        const val COL_FILE_SIZE = "file_size"
        const val COL_CAPTURE_TIME = "capture_time"
        const val COL_SYNC_STATUS = "sync_status"
        const val COL_ENCOUNTER_ID = "encounter_id"
        const val COL_UPLOADED_AT = "uploaded_at"
        const val COL_LAST_ATTEMPT_AT = "last_attempt_at"
        const val COL_LAST_ERROR = "last_error"
        const val COL_RETRY_COUNT = "retry_count"

        @Volatile
        private var instance: SyncDatabase? = null

        fun getInstance(context: Context): SyncDatabase {
            return instance ?: synchronized(this) {
                instance ?: SyncDatabase(context.applicationContext).also { instance = it }
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createTableSql = """
            CREATE TABLE $TABLE_PHOTOS (
                $COL_FILE_NAME TEXT PRIMARY KEY,
                $COL_CONTENT_URI TEXT NOT NULL,
                $COL_FILE_PATH TEXT,
                $COL_CLIENT_PHOTO_ID TEXT NOT NULL,
                $COL_SHA256 TEXT,
                $COL_FILE_SIZE INTEGER NOT NULL DEFAULT 0,
                $COL_CAPTURE_TIME INTEGER NOT NULL DEFAULT 0,
                $COL_SYNC_STATUS TEXT NOT NULL DEFAULT 'PENDING',
                $COL_ENCOUNTER_ID TEXT,
                $COL_UPLOADED_AT INTEGER,
                $COL_LAST_ATTEMPT_AT INTEGER,
                $COL_LAST_ERROR TEXT,
                $COL_RETRY_COUNT INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent()
        db.execSQL(createTableSql)
        db.execSQL("CREATE INDEX idx_sync_status ON $TABLE_PHOTOS ($COL_SYNC_STATUS)")
        db.execSQL("CREATE INDEX idx_sha256 ON $TABLE_PHOTOS ($COL_SHA256)")
        db.execSQL("CREATE INDEX idx_client_photo_id ON $TABLE_PHOTOS ($COL_CLIENT_PHOTO_ID)")
        Log.i(TAG, "Created $TABLE_PHOTOS table and indices")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_PHOTOS")
        onCreate(db)
    }

    @Synchronized
    fun upsert(record: SyncRecord) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_FILE_NAME, record.fileName)
            put(COL_CONTENT_URI, record.contentUri)
            put(COL_FILE_PATH, record.filePath)
            put(COL_CLIENT_PHOTO_ID, record.clientPhotoId)
            put(COL_SHA256, record.sha256)
            put(COL_FILE_SIZE, record.fileSize)
            put(COL_CAPTURE_TIME, record.captureTime)
            put(COL_SYNC_STATUS, record.syncStatus.name)
            put(COL_ENCOUNTER_ID, record.encounterId)
            put(COL_UPLOADED_AT, record.uploadedAt)
            put(COL_LAST_ATTEMPT_AT, record.lastAttemptAt)
            put(COL_LAST_ERROR, record.lastError)
            put(COL_RETRY_COUNT, record.retryCount)
        }
        db.insertWithOnConflict(TABLE_PHOTOS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun getByFileName(fileName: String): SyncRecord? {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_PHOTOS,
            null,
            "$COL_FILE_NAME = ?",
            arrayOf(fileName),
            null,
            null,
            null
        )
        return cursor.use {
            if (it.moveToFirst()) cursorToRecord(it) else null
        }
    }

    @Synchronized
    fun getPending(): List<SyncRecord> {
        val list = mutableListOf<SyncRecord>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_PHOTOS,
            null,
            "$COL_SYNC_STATUS IN (?, ?, ?)",
            arrayOf(SyncStatus.PENDING.name, SyncStatus.FAILED.name, SyncStatus.UPLOADING.name),
            null,
            null,
            "$COL_CAPTURE_TIME ASC"
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(cursorToRecord(it))
            }
        }
        return list
    }

    @Synchronized
    fun getAll(): List<SyncRecord> {
        val list = mutableListOf<SyncRecord>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_PHOTOS,
            null,
            null,
            null,
            null,
            null,
            "$COL_CAPTURE_TIME DESC"
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(cursorToRecord(it))
            }
        }
        return list
    }

    @Synchronized
    fun markUploading(fileName: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_SYNC_STATUS, SyncStatus.UPLOADING.name)
            put(COL_LAST_ATTEMPT_AT, System.currentTimeMillis())
        }
        db.update(TABLE_PHOTOS, values, "$COL_FILE_NAME = ?", arrayOf(fileName))
    }

    @Synchronized
    fun markUploaded(fileName: String, sha256: String?, encounterId: String?) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_SYNC_STATUS, SyncStatus.UPLOADED.name)
            put(COL_UPLOADED_AT, System.currentTimeMillis())
            put(COL_LAST_ERROR, null as String?)
            if (!sha256.isNullOrBlank()) {
                put(COL_SHA256, sha256)
            }
            if (!encounterId.isNullOrBlank()) {
                put(COL_ENCOUNTER_ID, encounterId)
            }
        }
        db.update(TABLE_PHOTOS, values, "$COL_FILE_NAME = ?", arrayOf(fileName))
    }

    @Synchronized
    fun markFailed(fileName: String, error: String) {
        val db = writableDatabase
        db.execSQL(
            """
            UPDATE $TABLE_PHOTOS 
            SET $COL_SYNC_STATUS = ?, 
                $COL_LAST_ERROR = ?, 
                $COL_LAST_ATTEMPT_AT = ?,
                $COL_RETRY_COUNT = $COL_RETRY_COUNT + 1
            WHERE $COL_FILE_NAME = ?
            """.trimIndent(),
            arrayOf(SyncStatus.FAILED.name, error, System.currentTimeMillis(), fileName)
        )
    }

    @Synchronized
    fun updateSha256(fileName: String, sha256: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_SHA256, sha256)
        }
        db.update(TABLE_PHOTOS, values, "$COL_FILE_NAME = ?", arrayOf(fileName))
    }

    @Synchronized
    fun reconcileUploaded(uploadedHashes: Set<String>, uploadedClientIds: Set<String>): Int {
        if (uploadedHashes.isEmpty() && uploadedClientIds.isEmpty()) return 0
        val db = writableDatabase
        var count = 0

        if (uploadedHashes.isNotEmpty()) {
            val placeholders = uploadedHashes.joinToString(",") { "?" }
            val args = mutableListOf<Any>()
            args.add(SyncStatus.UPLOADED.name)
            args.add(System.currentTimeMillis())
            args.addAll(uploadedHashes)
            db.execSQL(
                """
                UPDATE $TABLE_PHOTOS 
                SET $COL_SYNC_STATUS = ?, $COL_UPLOADED_AT = ? 
                WHERE $COL_SHA256 IN ($placeholders) AND $COL_SYNC_STATUS != 'UPLOADED'
                """.trimIndent(),
                args.toTypedArray()
            )
        }

        if (uploadedClientIds.isNotEmpty()) {
            val placeholders = uploadedClientIds.joinToString(",") { "?" }
            val args = mutableListOf<Any>()
            args.add(SyncStatus.UPLOADED.name)
            args.add(System.currentTimeMillis())
            args.addAll(uploadedClientIds)
            db.execSQL(
                """
                UPDATE $TABLE_PHOTOS 
                SET $COL_SYNC_STATUS = ?, $COL_UPLOADED_AT = ? 
                WHERE $COL_CLIENT_PHOTO_ID IN ($placeholders) AND $COL_SYNC_STATUS != 'UPLOADED'
                """.trimIndent(),
                args.toTypedArray()
            )
        }
        return count
    }

    private fun cursorToRecord(cursor: Cursor): SyncRecord {
        val fileName = cursor.getString(cursor.getColumnIndexOrThrow(COL_FILE_NAME))
        val contentUri = cursor.getString(cursor.getColumnIndexOrThrow(COL_CONTENT_URI))
        val filePath = cursor.getString(cursor.getColumnIndexOrThrow(COL_FILE_PATH))
        val clientPhotoId = cursor.getString(cursor.getColumnIndexOrThrow(COL_CLIENT_PHOTO_ID))
        val sha256 = cursor.getString(cursor.getColumnIndexOrThrow(COL_SHA256))
        val fileSize = cursor.getLong(cursor.getColumnIndexOrThrow(COL_FILE_SIZE))
        val captureTime = cursor.getLong(cursor.getColumnIndexOrThrow(COL_CAPTURE_TIME))
        val statusStr = cursor.getString(cursor.getColumnIndexOrThrow(COL_SYNC_STATUS))
        val syncStatus = try {
            SyncStatus.valueOf(statusStr)
        } catch (_: Exception) {
            SyncStatus.PENDING
        }
        val encounterId = cursor.getString(cursor.getColumnIndexOrThrow(COL_ENCOUNTER_ID))
        val uploadedAt = if (cursor.isNull(cursor.getColumnIndexOrThrow(COL_UPLOADED_AT))) null else cursor.getLong(cursor.getColumnIndexOrThrow(COL_UPLOADED_AT))
        val lastAttemptAt = if (cursor.isNull(cursor.getColumnIndexOrThrow(COL_LAST_ATTEMPT_AT))) null else cursor.getLong(cursor.getColumnIndexOrThrow(COL_LAST_ATTEMPT_AT))
        val lastError = cursor.getString(cursor.getColumnIndexOrThrow(COL_LAST_ERROR))
        val retryCount = cursor.getInt(cursor.getColumnIndexOrThrow(COL_RETRY_COUNT))

        return SyncRecord(
            fileName = fileName,
            contentUri = contentUri,
            filePath = filePath,
            clientPhotoId = clientPhotoId,
            sha256 = sha256,
            fileSize = fileSize,
            captureTime = captureTime,
            syncStatus = syncStatus,
            encounterId = encounterId,
            uploadedAt = uploadedAt,
            lastAttemptAt = lastAttemptAt,
            lastError = lastError,
            retryCount = retryCount
        )
    }
}
