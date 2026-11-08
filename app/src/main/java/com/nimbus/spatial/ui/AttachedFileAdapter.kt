package com.nimbus.spatial.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import androidx.core.content.ContextCompat
import com.nimbus.spatial.R
import com.nimbus.spatial.data.AttachedFile

/** The files in one place. Locked files are dimmed, show a padlock, and can't be opened. */
class AttachedFileAdapter(
    private val files: List<AttachedFile>,
    private val onOpen: (AttachedFile) -> Unit,
    private val onRemove: (AttachedFile) -> Unit
) : RecyclerView.Adapter<AttachedFileAdapter.ViewHolder>() {

    /** All files in one place share the same lock state. */
    var unlocked: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val typeIcon: ImageView = view.findViewById(R.id.iv_file_type)
        val name: TextView = view.findViewById(R.id.tv_file_name)
        val details: TextView = view.findViewById(R.id.tv_file_details)
        val lockIcon: View = view.findViewById(R.id.iv_lock_icon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_attached_file, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file = files[position]
        holder.name.text = file.name
        holder.typeIcon.setImageResource(iconFor(file.mimeType))

        // Files attached before encryption existed are still readable outside North: say so, in red.
        holder.details.setText(if (file.isEncrypted) R.string.file_encrypted else R.string.file_not_encrypted)
        val detailsColor = if (file.isEncrypted) R.color.north_on_surface_variant else R.color.north_error
        holder.details.setTextColor(ContextCompat.getColor(holder.itemView.context, detailsColor))

        holder.itemView.alpha = if (unlocked) 1f else 0.6f
        holder.lockIcon.isVisible = !unlocked
        holder.itemView.setOnClickListener {
            if (unlocked) onOpen(file) else it.context.showMessage("You're outside this place")
        }
        holder.itemView.setOnLongClickListener {
            onRemove(file)
            true
        }
    }

    override fun getItemCount(): Int = files.size

    private fun iconFor(mimeType: String): Int = when {
        mimeType.startsWith("image/") -> R.drawable.ic_image
        mimeType.startsWith("video/") -> R.drawable.ic_movie
        else -> R.drawable.ic_description
    }
}
