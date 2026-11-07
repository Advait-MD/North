package com.nimbus.spatial

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class GeofenceAdapter(
    private val geofenceList: List<GeofenceArea>,
    private val onDelete: (GeofenceArea) -> Unit,
    private val onClick: (GeofenceArea) -> Unit
) : RecyclerView.Adapter<GeofenceAdapter.GeofenceViewHolder>() {

    class GeofenceViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.tv_area_name)
        val delete: ImageView = view.findViewById(R.id.iv_delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GeofenceViewHolder =
        GeofenceViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_geofence_area, parent, false))

    override fun onBindViewHolder(holder: GeofenceViewHolder, position: Int) {
        val item = geofenceList[position]
        holder.name.text = item.name
        holder.itemView.setOnClickListener { onClick(item) }
        holder.delete.setOnClickListener { onDelete(item) }
    }

    override fun getItemCount(): Int = geofenceList.size
}
