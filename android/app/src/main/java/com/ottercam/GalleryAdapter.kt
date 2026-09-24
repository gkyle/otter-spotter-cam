package com.kylescholz.otterspotter

import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.kylescholz.otterspotter.databinding.ItemGalleryThumbnailBinding
import com.kylescholz.otterspotter.sync.SyncRecord
import com.kylescholz.otterspotter.sync.SyncStatus
import java.util.concurrent.Executors

class GalleryAdapter(
    private var photos: List<OtterPhoto>,
    private var syncRecords: Map<String, SyncRecord> = emptyMap(),
    private val onPhotoClick: (Int, OtterPhoto) -> Unit
) : RecyclerView.Adapter<GalleryAdapter.PhotoViewHolder>() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(4)

    // Memory cache for thumbnails
    private val memoryCache: LruCache<String, Bitmap> = run {
        val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        val cacheSize = maxMemory / 8
        object : LruCache<String, Bitmap>(cacheSize) {
            override fun sizeOf(key: String, bitmap: Bitmap): Int {
                return bitmap.byteCount / 1024
            }
        }
    }

    fun updatePhotos(newPhotos: List<OtterPhoto>, newSyncRecords: Map<String, SyncRecord> = syncRecords) {
        this.photos = newPhotos
        this.syncRecords = newSyncRecords
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
        val binding = ItemGalleryThumbnailBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return PhotoViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
        val photo = photos[position]
        holder.bind(photo, position)
    }

    override fun getItemCount(): Int = photos.size

    inner class PhotoViewHolder(
        private val binding: ItemGalleryThumbnailBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(photo: OtterPhoto, position: Int) {
            val key = photo.uri.toString()
            binding.root.tag = key

            // Show Auto badge if photo was captured by auto photo
            binding.tvAutoBadge.visibility = if (photo.isAuto) View.VISIBLE else View.GONE

            // Sync status badge
            val record = syncRecords[photo.name]
            binding.layoutSyncBadge.visibility = View.VISIBLE
            when (record?.syncStatus) {
                SyncStatus.UPLOADED -> {
                    binding.ivSyncStatus.setImageResource(R.drawable.ic_cloud_done)
                    binding.layoutSyncBadge.contentDescription = "Synced to Cloud"
                }
                SyncStatus.UPLOADING, SyncStatus.PENDING -> {
                    binding.ivSyncStatus.setImageResource(R.drawable.ic_cloud_upload)
                    binding.layoutSyncBadge.contentDescription = "Pending Cloud Upload"
                }
                SyncStatus.FAILED -> {
                    binding.ivSyncStatus.setImageResource(R.drawable.ic_cloud_alert)
                    binding.layoutSyncBadge.contentDescription = "Sync Alert: Retrying"
                }
                null -> {
                    binding.ivSyncStatus.setImageResource(R.drawable.ic_cloud_upload)
                    binding.layoutSyncBadge.contentDescription = "Pending Cloud Upload"
                }
            }

            binding.vRippleOverlay.setOnClickListener {
                onPhotoClick(position, photo)
            }

            // Check cache
            val cached = memoryCache.get(key)
            if (cached != null) {
                binding.ivThumbnail.setImageBitmap(cached)
                return
            }

            // Reset image view while loading
            binding.ivThumbnail.setImageDrawable(null)

            // Asynchronously load thumbnail
            executor.execute {
                val bitmap = loadThumbnailBitmap(photo)
                if (bitmap != null) {
                    memoryCache.put(key, bitmap)
                    mainHandler.post {
                        if (binding.root.tag == key) {
                            binding.ivThumbnail.setImageBitmap(bitmap)
                        }
                    }
                }
            }
        }

        private fun loadThumbnailBitmap(photo: OtterPhoto): Bitmap? {
            val context = binding.root.context
            try {
                // Android 10+ native content resolver thumbnail
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    return context.contentResolver.loadThumbnail(photo.uri, Size(300, 300), null)
                }
            } catch (_: Exception) {
                // Fall back to manual decoding below
            }

            return BitmapUtils.decodeSampledBitmap(context, photo, 300, 300)
        }
    }
}
