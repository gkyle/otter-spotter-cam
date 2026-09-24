package com.kylescholz.otterspotter

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.kylescholz.otterspotter.databinding.ItemFullscreenImageBinding
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
            return BitmapUtils.decodeSampledBitmap(context, photo, maxW, maxH)
        }
    }
}
