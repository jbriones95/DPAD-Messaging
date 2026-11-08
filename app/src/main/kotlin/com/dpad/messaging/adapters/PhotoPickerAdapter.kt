package com.dpad.messaging.adapters

import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.dpad.messaging.databinding.ItemPhotoBinding

/** Grid of photos from the device media database, sized for small screens. */
class PhotoPickerAdapter(
    private val onClick: (Uri) -> Unit
) : RecyclerView.Adapter<PhotoPickerAdapter.PhotoViewHolder>() {

    private val photos = mutableListOf<Uri>()

    fun submit(items: List<Uri>) {
        photos.clear()
        photos.addAll(items)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
        val binding = ItemPhotoBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return PhotoViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
        holder.bind(photos[position])
    }

    override fun onViewRecycled(holder: PhotoViewHolder) {
        Glide.with(holder.binding.ivPhoto).clear(holder.binding.ivPhoto)
        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int = photos.size

    inner class PhotoViewHolder(
        val binding: ItemPhotoBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(uri: Uri) {
            binding.root.setOnClickListener { onClick(uri) }
            Glide.with(binding.ivPhoto)
                .load(uri)
                .centerCrop()
                .into(binding.ivPhoto)
        }
    }
}
