package com.nimbus.spatial

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** One row in the place list: the user's own places and public places nearby. */
data class PlaceRow(val place: GeofenceArea, val isMine: Boolean, val distanceM: Double?)

class GeofenceAdapter(
    private val rows: MutableList<PlaceRow>,
    private val onDelete: (GeofenceArea) -> Unit,
    private val onClick: (GeofenceArea) -> Unit
) : RecyclerView.Adapter<GeofenceAdapter.GeofenceViewHolder>() {

    inner class GeofenceViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.tv_area_name)
        val subtitle: TextView = view.findViewById(R.id.tv_area_subtitle)
        val delete: ImageView = view.findViewById(R.id.iv_delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GeofenceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_geofence_area, parent, false)
        return GeofenceViewHolder(view)
    }

    override fun onBindViewHolder(holder: GeofenceViewHolder, position: Int) {
        val row = rows[position]
        holder.name.text = row.place.name
        holder.subtitle.text = listOfNotNull(
            if (row.isMine) "Yours" else "By someone else",
            if (row.place.isPublic) "Public" else "Private",
            row.distanceM?.let { formatDistance(it) }
        ).joinToString(" · ")

        holder.itemView.setOnClickListener { onClick(row.place) }
        holder.delete.visibility = if (row.isMine) View.VISIBLE else View.GONE
        holder.delete.setOnClickListener { onDelete(row.place) }
    }

    override fun getItemCount(): Int = rows.size

    private fun formatDistance(m: Double) =
        if (m < 1000) "${m.toInt()} m away" else "%.1f km away".format(m / 1000)
}
