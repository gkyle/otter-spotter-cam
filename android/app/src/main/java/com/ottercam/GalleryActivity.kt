package com.kylescholz.otterspotter

import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.updatePadding
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.viewpager2.widget.ViewPager2
import com.kylescholz.otterspotter.databinding.ActivityGalleryBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.widget.Toast
import com.kylescholz.otterspotter.sync.OtterSyncWorker
import com.kylescholz.otterspotter.sync.SyncRecord
import com.kylescholz.otterspotter.sync.SyncRepository
import com.kylescholz.otterspotter.sync.SyncScheduler
import com.kylescholz.otterspotter.sync.SyncStatus

class GalleryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGalleryBinding
    private val executor = Executors.newSingleThreadExecutor()
    private val syncRepository by lazy { SyncRepository.getInstance(this) }

    private val photos = mutableListOf<OtterPhoto>()
    private var syncRecords = mapOf<String, SyncRecord>()
    private lateinit var galleryAdapter: GalleryAdapter
    private lateinit var fullscreenAdapter: FullscreenPagerAdapter

    private var isTopBarVisible = true

    private val syncReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: android.content.Intent?) {
            runOnUiThread {
                loadPhotos()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupWindowInsets()
        setupUI()
        setupBackNavigation()
        loadPhotos()
    }

    private fun setupWindowInsets() {
        val initialHeaderPadding = binding.layoutHeader.paddingTop
        val initialFullscreenTopBarPadding = binding.layoutFullscreenTopBar.paddingTop

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            binding.layoutHeader.updatePadding(
                top = insets.top + initialHeaderPadding,
                left = insets.left,
                right = insets.right
            )
            binding.rvGallery.updatePadding(
                bottom = insets.bottom,
                left = insets.left,
                right = insets.right
            )
            binding.layoutFullscreenTopBar.updatePadding(
                top = insets.top + initialFullscreenTopBarPadding,
                left = insets.left,
                right = insets.right
            )
            windowInsets
        }
    }

    private fun calculateSpanCount(): Int {
        val widthDp = resources.configuration.screenWidthDp
        return maxOf(3, widthDp / 120)
    }

    override fun onResume() {
        super.onResume()
        hideNavigationBars()
        val filter = IntentFilter(OtterSyncWorker.ACTION_SYNC_COMPLETED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(syncReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(syncReceiver, filter)
        }
        loadPhotos()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideNavigationBars()
        }
    }

    private fun hideNavigationBars() {
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController.hide(WindowInsetsCompat.Type.navigationBars())
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(syncReceiver)
        } catch (_: Exception) {
        }
    }

    private fun setupUI() {
        // Back Button
        binding.btnBack.setOnClickListener {
            finish()
        }

        // RecyclerView with dynamic columns (responsive for tablets & foldables)
        galleryAdapter = GalleryAdapter(photos) { position, _ ->
            openFullscreenPhoto(position)
        }
        binding.rvGallery.apply {
            layoutManager = GridLayoutManager(this@GalleryActivity, calculateSpanCount())
            adapter = galleryAdapter
        }

        // Fullscreen ViewPager2
        fullscreenAdapter = FullscreenPagerAdapter(photos) {
            toggleFullscreenTopBar()
        }
        binding.vpFullscreen.adapter = fullscreenAdapter
        binding.vpFullscreen.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                updateFullscreenHeader(position)
            }
        })

        // Fullscreen Close Button
        binding.btnCloseFullscreen.setOnClickListener {
            closeFullscreen()
        }

        // Fullscreen Share Button
        binding.btnSharePhoto.setOnClickListener {
            shareCurrentPhoto()
        }

        // Sync Now Button
        binding.btnSyncNow.setOnClickListener {
            binding.ivSyncNowIcon.animate().rotationBy(360f).setDuration(600).start()
            Toast.makeText(this, "Checking connection & syncing photos...", Toast.LENGTH_SHORT).show()
            SyncScheduler.triggerImmediateSync(this)
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.layoutFullscreenViewer.visibility == View.VISIBLE) {
                    closeFullscreen()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun loadPhotos() {
        binding.pbGalleryLoading.visibility = View.VISIBLE
        binding.layoutEmptyState.visibility = View.GONE

        executor.execute {
            val loadedPhotos = scanOtterPhotos()
            syncRepository.scanAndSeedGalleryPhotos(loadedPhotos)
            val records = syncRepository.getAllSyncRecords()
            val summary = syncRepository.getSyncSummary()

            runOnUiThread {
                binding.pbGalleryLoading.visibility = View.GONE
                photos.clear()
                photos.addAll(loadedPhotos)
                syncRecords = records

                galleryAdapter.updatePhotos(photos, syncRecords)
                fullscreenAdapter.updatePhotos(photos)

                if (photos.isEmpty()) {
                    binding.layoutEmptyState.visibility = View.VISIBLE
                    binding.tvCount.text = "0 photos"
                } else {
                    binding.layoutEmptyState.visibility = View.GONE
                    val countPrefix = if (photos.size == 1) "1 photo" else "${photos.size} photos"
                    val syncStatusText = when {
                        summary.pending > 0 -> "$countPrefix  •  ${summary.uploaded} synced, ${summary.pending} pending"
                        summary.uploaded > 0 -> "$countPrefix  •  All ${summary.uploaded} synced"
                        else -> countPrefix
                    }
                    binding.tvCount.text = syncStatusText
                }
            }
        }
    }

    private fun scanOtterPhotos(): List<OtterPhoto> {
        val result = mutableListOf<OtterPhoto>()
        val seenNames = mutableSetOf<String>()

        // 1. Query MediaStore
        try {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.DATE_MODIFIED,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.DATA
            )

            val selection = "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ? OR ${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("Otter_%", "Otter_AutoPhoto_%")
            val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"

            val queryUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            contentResolver.query(queryUri, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateTakenCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val dateModCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val dataCol = cursor.getColumnIndex(MediaStore.Images.Media.DATA)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "Otter_Photo_$id.jpg"
                    var dateTaken = cursor.getLong(dateTakenCol)
                    if (dateTaken <= 0) {
                        dateTaken = cursor.getLong(dateModCol) * 1000
                    }
                    val size = cursor.getLong(sizeCol)
                    val filePath = if (dataCol >= 0) cursor.getString(dataCol) else null
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

                    seenNames.add(name)
                    result.add(
                        OtterPhoto(
                            uri = uri,
                            name = name,
                            dateTaken = dateTaken,
                            size = size,
                            filePath = filePath,
                            isAuto = name.contains("Auto", ignoreCase = true)
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }

        // 2. Fallback / Direct directory scan of DCIM/Otter Spotter & DCIM/OtterCam
        val candidateDirs = listOf(
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Otter Spotter"),
            File("/sdcard/DCIM/Otter Spotter"),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Otter Spotter"),
            File("/sdcard/Pictures/Otter Spotter"),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "OtterCam"),
            File("/sdcard/DCIM/OtterCam"),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "OtterCam"),
            File("/sdcard/Pictures/OtterCam")
        )

        for (dir in candidateDirs) {
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles { file -> file.isFile && (file.name.endsWith(".jpg", ignoreCase = true) || file.name.endsWith(".jpeg", ignoreCase = true)) }?.forEach { file ->
                    if (!seenNames.contains(file.name)) {
                        seenNames.add(file.name)
                        val uri = try {
                            FileProvider.getUriForFile(this, "${applicationContext.packageName}.fileprovider", file)
                        } catch (_: Exception) {
                            Uri.fromFile(file)
                        }
                        result.add(
                            OtterPhoto(
                                uri = uri,
                                name = file.name,
                                dateTaken = file.lastModified(),
                                size = file.length(),
                                filePath = file.absolutePath,
                                isAuto = file.name.contains("Auto", ignoreCase = true)
                            )
                        )
                    }
                }
            }
        }

        // Sort descending by date taken / modified
        result.sortByDescending { it.dateTaken }
        return result
    }

    private fun openFullscreenPhoto(position: Int) {
        if (position in photos.indices) {
            binding.layoutFullscreenViewer.visibility = View.VISIBLE
            binding.vpFullscreen.setCurrentItem(position, false)
            updateFullscreenHeader(position)
            isTopBarVisible = true
            binding.layoutFullscreenTopBar.visibility = View.VISIBLE
        }
    }

    private fun closeFullscreen() {
        binding.layoutFullscreenViewer.visibility = View.GONE
    }

    private fun updateFullscreenHeader(position: Int) {
        if (position in photos.indices) {
            val photo = photos[position]
            binding.tvFullscreenTitle.text = photo.name

            val dateStr = if (photo.dateTaken > 0) {
                SimpleDateFormat("MMM d, yyyy  h:mm a", Locale.getDefault()).format(Date(photo.dateTaken))
            } else ""

            val infoText = "${position + 1} of ${photos.size}  •  $dateStr"
            binding.tvFullscreenInfo.text = infoText
        }
    }

    private fun toggleFullscreenTopBar() {
        isTopBarVisible = !isTopBarVisible
        binding.layoutFullscreenTopBar.animate()
            .alpha(if (isTopBarVisible) 1f else 0f)
            .setDuration(200)
            .withEndAction {
                binding.layoutFullscreenTopBar.visibility = if (isTopBarVisible) View.VISIBLE else View.GONE
            }
            .start()
    }

    private fun shareCurrentPhoto() {
        val position = binding.vpFullscreen.currentItem
        if (position in photos.indices) {
            val photo = photos[position]
            try {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, photo.uri)
                    putExtra(Intent.EXTRA_SUBJECT, photo.name)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "Share Otter Photo"))
            } catch (e: Exception) {
            }
        }
    }
}
