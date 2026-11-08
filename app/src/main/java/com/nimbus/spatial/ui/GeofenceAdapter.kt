package com.nimbus.spatial.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.nimbus.spatial.R
import com.nimbus.spatial.data.GeofenceArea

/** List of the user's geofences on the home screen. */
class GeofenceAdapter(
    private val geofences: List<GeofenceArea>,
    private val onClick: (GeofenceArea) -> Unit,
    private val onDelete: (GeofenceArea) -> Unit
) : RecyclerView.Adapter<GeofenceAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.tv_area_name)
        val deleteButton: ImageView = view.findViewById(R.id.iv_delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_geofence_area, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val area = geofences[position]
        holder.name.text = area.name
        holder.itemView.setOnClickListener { onClick(area) }
        holder.deleteButton.setOnClickListener { onDelete(area) }
    }

    override fun getItemCount(): Int = geofences.size
}
