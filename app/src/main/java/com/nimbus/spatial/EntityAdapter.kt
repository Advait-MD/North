package com.nimbus.spatial

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class EntityAdapter(
    private val items: MutableList<EntityItem>,
    private val onOpen: (EntityItem) -> Unit,
    private val onLongPress: (EntityItem) -> Unit
) : RecyclerView.Adapter<EntityAdapter.EntityViewHolder>() {

    inner class EntityViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val entityName: TextView = view.findViewById(R.id.tv_entity_name)
        val meta: TextView = view.findViewById(R.id.tv_entity_meta)
        val lockIcon: ImageView = view.findViewById(R.id.iv_lock_icon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EntityViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_entity, parent, false)
        return EntityViewHolder(view)
    }

    override fun onBindViewHolder(holder: EntityViewHolder, position: Int) {
        val item = items[position]
        holder.entityName.text = item.file.displayName
        holder.meta.text = listOf(
            if (item.file.isPublic) "Public" else "Private",
            formatSize(item.file.sizeBytes)
        ).joinToString(" · ")

        // The lock is only a hint; the server makes the real decision when the file is opened.
        holder.itemView.alpha = if (item.isAccessible) 1.0f else 0.5f
        holder.lockIcon.visibility = if (item.isAccessible) View.GONE else View.VISIBLE
        holder.itemView.setOnClickListener { onOpen(item) }
        holder.itemView.setOnLongClickListener { onLongPress(item); true }
    }

    override fun getItemCount(): Int = items.size

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }
}
