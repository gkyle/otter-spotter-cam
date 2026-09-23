package com.kylescholz.otterspotter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.kylescholz.otterspotter.databinding.ItemFullscreenImageBinding
import java.io.File
import java.util.concurrent.Executors

class FullscreenPagerAdapter(
    private var photos: List<OtterPhoto>,
    private val onImageTap: () -> Unit
) : RecyclerView.Adapter<FullscreenPagerAdapter.FullscreenViewHolder>() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(2)

    fun updatePhotos(newPhotos: List<OtterPhoto>) {
        this.photos = newPhotos
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FullscreenViewHolder {
        val binding = ItemFullscreenImageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return FullscreenViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FullscreenViewHolder, position: Int) {
        holder.bind(photos[position])
    }

    override fun getItemCount(): Int = photos.size

    inner class FullscreenViewHolder(
        private val binding: ItemFullscreenImageBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(photo: OtterPhoto) {
            val key = photo.uri.toString()
            binding.root.tag = key

            binding.ivFullscreenImage.setImageDrawable(null)
            binding.pbLoading.visibility = View.VISIBLE

            binding.ivFullscreenImage.setOnClickListener {
                onImageTap()
            }

            executor.execute {
                val bitmap = loadHighResBitmap(photo)
                mainHandler.post {
                    if (binding.root.tag == key) {
                        binding.pbLoading.visibility = View.GONE
                        if (bitmap != null) {
                            binding.ivFullscreenImage.setImageBitmap(bitmap)
                        }
                    }
                }
            }
        }

        private fun loadHighResBitmap(photo: OtterPhoto): Bitmap? {
            val context = binding.root.context
            val displayMetrics = context.resources.displayMetrics
            val maxW = displayMetrics.widthPixels
            val maxH = displayMetrics.heightPixels

            try {
                if (photo.filePath != null && File(photo.filePath).exists()) {
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(photo.filePath, options)
                    options.inSampleSize = calculateInSampleSize(options, maxW, maxH)
                    options.inJustDecodeBounds = false
                    return BitmapFactory.decodeFile(photo.filePath, options)
                }

                context.contentResolver.openInputStream(photo.uri)?.use { stream ->
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(stream, null, options)
                    options.inSampleSize = calculateInSampleSize(options, maxW, maxH)
                    options.inJustDecodeBounds = false

                    context.contentResolver.openInputStream(photo.uri)?.use { stream2 ->
                        return BitmapFactory.decodeStream(stream2, null, options)
                    }
                }
            } catch (_: Exception) {
            }
            return null
        }

        private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
            val (height: Int, width: Int) = options.outHeight to options.outWidth
            var inSampleSize = 1

            if (height > reqHeight || width > reqWidth) {
                val halfHeight: Int = height / 2
                val halfWidth: Int = width / 2

                while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                    inSampleSize *= 2
                }
            }
            return inSampleSize
        }
    }
}
