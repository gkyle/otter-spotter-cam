package com.kylescholz.otterspotter.sync

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

data class SyncCheckResult(
    val uploadedHashes: Set<String>,
    val missingHashes: Set<String>,
    val uploadedClientIds: Set<String>,
    val missingClientIds: Set<String>
)

data class UploadResult(
    val success: Boolean,
    val isAlreadyExists: Boolean = false,
    val sha256: String? = null,
    val encounterId: String? = null,
    val photoId: String? = null,
    val errorMessage: String? = null,
    val statusCode: Int = 0
)

class OtterApiClient(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val client: OkHttpClient = defaultHttpClient()
) {

    companion object {
        private const val TAG = "OtterApiClient"
        const val DEFAULT_BASE_URL = "https://otterspotter.kylescholz.com/api"

        private fun defaultHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
    }

    fun isServerHealthy(): Boolean {
        val url = "$baseUrl/health"
        val request = Request.Builder().url(url).get().build()
        return try {
            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.w(TAG, "Health check failed against $url: ${e.message}")
            false
        }
    }

    fun checkSyncState(
        idToken: String,
        hashes: List<String>,
        clientPhotoIds: List<String>
    ): SyncCheckResult {
        val url = "$baseUrl/sync/check"
        val jsonBody = JSONObject().apply {
            put("hashes", JSONArray(hashes))
            put("client_photo_ids", JSONArray(clientPhotoIds))
        }

        val requestBody = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $idToken")
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errBody = response.body?.string() ?: ""
                throw IOException("Sync check failed with HTTP ${response.code}: $errBody")
            }

            val respString = response.body?.string() ?: "{}"
            val obj = JSONObject(respString)

            return SyncCheckResult(
                uploadedHashes = jsonArrayToStringSet(obj.optJSONArray("uploaded_hashes")),
                missingHashes = jsonArrayToStringSet(obj.optJSONArray("missing_hashes")),
                uploadedClientIds = jsonArrayToStringSet(obj.optJSONArray("uploaded_client_ids")),
                missingClientIds = jsonArrayToStringSet(obj.optJSONArray("missing_client_ids"))
            )
        }
    }

    fun uploadPhoto(
        context: Context,
        idToken: String,
        record: SyncRecord
    ): UploadResult {
        val url = "$baseUrl/upload"
        val imageBytes = readPhotoBytes(context, record)
            ?: return UploadResult(
                success = false,
                errorMessage = "Could not read image file bytes for ${record.fileName}",
                statusCode = -1
            )

        val imageRequestBody = imageBytes.toRequestBody("image/jpeg".toMediaTypeOrNull())

        val multipartBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", record.fileName, imageRequestBody)
            .addFormDataPart("client_photo_id", record.clientPhotoId)
            .build()

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $idToken")
            .post(multipartBody)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                if (response.code == 201 || response.code == 200) {
                    val obj = JSONObject(bodyStr)
                    val status = obj.optString("status")
                    val isAlreadyExists = (status == "already_exists" || response.code == 200)
                    val sha = obj.optString("sha256", record.sha256 ?: "")
                    val encounterId = obj.optString("encounter_id", "")
                    val photoId = obj.optString("photo_id", "")

                    UploadResult(
                        success = true,
                        isAlreadyExists = isAlreadyExists,
                        sha256 = sha,
                        encounterId = encounterId,
                        photoId = photoId,
                        statusCode = response.code
                    )
                } else {
                    Log.w(TAG, "Upload failed HTTP ${response.code}: $bodyStr")
                    UploadResult(
                        success = false,
                        errorMessage = "HTTP ${response.code}: $bodyStr",
                        statusCode = response.code
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during upload of ${record.fileName}: ${e.message}", e)
            UploadResult(
                success = false,
                errorMessage = e.message ?: "Network error during upload",
                statusCode = -2
            )
        }
    }

    private fun readPhotoBytes(context: Context, record: SyncRecord): ByteArray? {
        if (!record.filePath.isNullOrBlank()) {
            val file = File(record.filePath)
            if (file.exists() && file.isFile) {
                return try {
                    file.readBytes()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed reading file: ${record.filePath}", e)
                    null
                }
            }
        }

        return try {
            val baseUri = Uri.parse(record.contentUri)
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    MediaStore.setRequireOriginal(baseUri)
                } catch (_: Exception) {
                    baseUri
                }
            } else {
                baseUri
            }
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading uri: ${record.contentUri}", e)
            null
        }
    }

    private fun jsonArrayToStringSet(arr: JSONArray?): Set<String> {
        if (arr == null) return emptySet()
        val set = mutableSetOf<String>()
        for (i in 0 until arr.length()) {
            set.add(arr.getString(i))
        }
        return set
    }
}
