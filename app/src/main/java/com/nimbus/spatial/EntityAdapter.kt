package com.nimbus.spatial

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView

class EntityAdapter(
    private val items: List<EntityItem>,
    private val onOpen: (EntityItem) -> Unit
) : RecyclerView.Adapter<EntityAdapter.EntityViewHolder>() {

    /** Every file in one geofence shares the same lock state. */
    var unlocked: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    class EntityViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val entityName: TextView = view.findViewById(R.id.tv_entity_name)
        val lockIcon: ImageView = view.findViewById(R.id.iv_lock_icon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EntityViewHolder =
        EntityViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_entity, parent, false))

    override fun onBindViewHolder(holder: EntityViewHolder, position: Int) {
        val item = items[position]
        holder.entityName.text = item.fileName
        holder.itemView.alpha = if (unlocked) 1.0f else 0.5f
        holder.lockIcon.visibility = if (unlocked) View.GONE else View.VISIBLE
        holder.itemView.setOnClickListener {
            if (unlocked) onOpen(item)
            else Toast.makeText(it.context, "You're outside the geofence", Toast.LENGTH_SHORT).show()
        }
    }

    override fun getItemCount(): Int = items.size
}
