package com.nimbus.spatial.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.nimbus.spatial.R
import com.nimbus.spatial.data.AttachedFile

/** List of attached files. Locked files are dimmed, show a padlock, and can't be opened. */
class AttachedFileAdapter(
    private val files: List<AttachedFile>,
    private val onOpen: (AttachedFile) -> Unit,
    private val onRemove: (AttachedFile) -> Unit
) : RecyclerView.Adapter<AttachedFileAdapter.ViewHolder>() {

    /** All files in one geofence share the same lock state. */
    var unlocked: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.tv_file_name)
        val lockIcon: ImageView = view.findViewById(R.id.iv_lock_icon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_attached_file, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file = files[position]
        holder.name.text = file.name
        holder.itemView.alpha = if (unlocked) 1.0f else 0.5f
        holder.lockIcon.visibility = if (unlocked) View.GONE else View.VISIBLE
        holder.itemView.setOnClickListener {
            if (unlocked) onOpen(file) else it.context.toast("You're outside the geofence")
        }
        holder.itemView.setOnLongClickListener {
            onRemove(file)
            true
        }
    }

    override fun getItemCount(): Int = files.size
}
