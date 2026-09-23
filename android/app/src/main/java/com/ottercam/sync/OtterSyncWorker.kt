package com.kylescholz.otterspotter.sync

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.tasks.Tasks
import com.kylescholz.otterspotter.AuthManager
import com.kylescholz.otterspotter.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class OtterSyncWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "OtterSyncWorker"
        const val ACTION_SYNC_COMPLETED = "com.kylescholz.otterspotter.sync.ACTION_SYNC_COMPLETED"
        const val EXTRA_UPLOADED_COUNT = "extra_uploaded_count"
        const val EXTRA_PENDING_COUNT = "extra_pending_count"
    }

    private val repository = SyncRepository.getInstance(appContext)
    private val apiClient = OtterApiClient()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.i(TAG, "Starting OtterSyncWorker background sync job...")

        // 1. Verify User Authentication
        val user = AuthManager.getUser(appContext)
        if (user == null || user.isGuest) {
            Log.d(TAG, "User is in offline guest mode or not logged in. Skipping cloud sync.")
            return@withContext Result.success()
        }

        // 2. Obtain fresh Google ID Token or dev token fallback
        val idToken = obtainValidIdToken(user.idToken) ?: run {
            if (!user.isGuest && user.id.isNotBlank()) {
                Log.i(TAG, "Using authenticated session dev token fallback for ${user.email} (${user.id})")
                "dev-token:${user.id}:${user.email}"
            } else {
                null
            }
        }

        if (idToken.isNullOrBlank()) {
            Log.w(TAG, "No valid authentication token available for sync. Will retry later.")
            return@withContext Result.retry()
        }

        // 3. Check API health
        if (!apiClient.isServerHealthy()) {
            Log.w(TAG, "Server health check failed. Postponing sync.")
            return@withContext Result.retry()
        }

        // 4. Fetch pending photos
        val pendingPhotos = repository.getPendingPhotos().toMutableList()
        if (pendingPhotos.isEmpty()) {
            Log.i(TAG, "No pending photos to upload. Sync is up to date.")
            broadcastSyncResult(0, 0)
            return@withContext Result.success()
        }

        Log.i(TAG, "Found ${pendingPhotos.size} pending photos to synchronize.")

        // 5. Ensure SHA-256 for all pending records
        for (record in pendingPhotos) {
            repository.ensureSha256(record)
        }

        // 6. Batch Sync Check against server
        var anyUploadFailed = false
        var uploadedThisRun = 0

        // Process in batches of up to 100
        val batches = pendingPhotos.chunked(100)
        for (batch in batches) {
            val hashes = batch.mapNotNull { it.sha256 }.filter { it.isNotBlank() }
            val clientIds = batch.map { it.clientPhotoId }

            try {
                val checkResult = apiClient.checkSyncState(idToken, hashes, clientIds)
                Log.d(
                    TAG,
                    "Sync check result: ${checkResult.uploadedHashes.size} already on server, ${checkResult.missingHashes.size} missing"
                )

                // Mark any photos that already exist on the server as UPLOADED
                repository.reconcileWithServer(
                    checkResult.uploadedHashes,
                    checkResult.uploadedClientIds
                )

                // Find remaining items that need full file transfer
                val missingPhotos = batch.filter { record ->
                    val isMissingByHash = record.sha256 != null && checkResult.missingHashes.contains(record.sha256)
                    val isMissingById = checkResult.missingClientIds.contains(record.clientPhotoId)
                    val notOnServer = !checkResult.uploadedHashes.contains(record.sha256) &&
                            !checkResult.uploadedClientIds.contains(record.clientPhotoId)
                    (isMissingByHash || isMissingById || notOnServer)
                }

                // 7. Upload missing photos one by one
                for (record in missingPhotos) {
                    repository.markUploading(record.fileName)
                    Log.i(TAG, "Uploading sighting photo: ${record.fileName} (Client ID: ${record.clientPhotoId})...")

                    val result = apiClient.uploadPhoto(appContext, idToken, record)
                    if (result.success) {
                        Log.i(TAG, "Uploaded ${record.fileName} successfully! Encounter: ${result.encounterId}")
                        repository.markUploaded(
                            record.fileName,
                            result.sha256 ?: record.sha256,
                            result.encounterId
                        )
                        uploadedThisRun++
                    } else {
                        Log.e(TAG, "Failed uploading ${record.fileName}: ${result.errorMessage}")
                        repository.markFailed(record.fileName, result.errorMessage ?: "Upload failed")
                        if (result.statusCode == 401) {
                            // Auth token issue; try refreshing next run
                            Log.w(TAG, "Authentication rejected (401). Aborting current sync batch.")
                            return@withContext Result.retry()
                        }
                        if (result.statusCode < 0 || result.statusCode >= 500) {
                            anyUploadFailed = true
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception during sync check or upload batch: ${e.message}", e)
                anyUploadFailed = true
            }
        }

        val summary = repository.getSyncSummary()
        Log.i(TAG, "Sync complete. Total: ${summary.total}, Uploaded: ${summary.uploaded}, Pending: ${summary.pending}")
        broadcastSyncResult(uploadedThisRun, summary.pending)

        if (anyUploadFailed) {
            Result.retry()
        } else {
            Result.success()
        }
    }

    private fun obtainValidIdToken(currentCachedToken: String?): String? {
        val webClientId = appContext.getString(R.string.default_web_client_id)
        if (webClientId.startsWith("YOUR_GOOGLE_WEB_CLIENT_ID")) {
            return currentCachedToken
        }

        // Attempt silent sign-in to get a fresh, non-expired token
        return try {
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestIdToken(webClientId)
                .build()

            val client = GoogleSignIn.getClient(appContext, gso)
            val task = client.silentSignIn()
            val account = Tasks.await(task, 8, TimeUnit.SECONDS)
            val freshToken = account.idToken ?: currentCachedToken
            if (freshToken != null && freshToken != currentCachedToken) {
                AuthManager.getUser(appContext)?.let { existingUser ->
                    AuthManager.saveUser(appContext, existingUser.copy(idToken = freshToken))
                }
            }
            freshToken
        } catch (e: Exception) {
            Log.d(TAG, "Silent sign in skipped or timed out: ${e.message}. Using cached token.")
            currentCachedToken
        }
    }

    private fun broadcastSyncResult(uploadedCount: Int, pendingCount: Int) {
        val intent = Intent(ACTION_SYNC_COMPLETED).apply {
            setPackage(appContext.packageName)
            putExtra(EXTRA_UPLOADED_COUNT, uploadedCount)
            putExtra(EXTRA_PENDING_COUNT, pendingCount)
        }
        appContext.sendBroadcast(intent)
    }
}
