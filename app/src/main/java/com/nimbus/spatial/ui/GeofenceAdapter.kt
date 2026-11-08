package com.nimbus.spatial.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.nimbus.spatial.R
import com.nimbus.spatial.data.GeofenceArea

/** The places on the home screen. Places the user is inside right now get a "You're here" label. */
class GeofenceAdapter(
    private val places: List<GeofenceArea>,
    private val onClick: (GeofenceArea) -> Unit,
    private val onDelete: (GeofenceArea) -> Unit
) : RecyclerView.Adapter<GeofenceAdapter.ViewHolder>() {

    var insideIds: Set<String> = emptySet()
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.tv_area_name)
        val details: TextView = view.findViewById(R.id.tv_area_details)
        val hereLabel: View = view.findViewById(R.id.tv_here)
        val deleteButton: View = view.findViewById(R.id.btn_delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_geofence_area, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val place = places[position]
        holder.name.text = place.name
        holder.details.text = holder.itemView.context.getString(R.string.place_radius, place.radius.toInt())
        holder.hereLabel.isVisible = place.id in insideIds
        holder.itemView.setOnClickListener { onClick(place) }
        holder.deleteButton.setOnClickListener { onDelete(place) }
    }

    override fun getItemCount(): Int = places.size
}
