package com.example.webtrafficmonitor.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.webtrafficmonitor.R
import com.example.webtrafficmonitor.data.MonitorEntry
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Shows the monitored entries in the scrollable list. */
class MonitorAdapter : ListAdapter<MonitorEntry, MonitorAdapter.ViewHolder>(DIFF) {

    private val timeFormat = SimpleDateFormat("MMM d  HH:mm:ss", Locale.getDefault())

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumbnail: ImageView = view.findViewById(R.id.thumbnail)
        val primary: TextView = view.findViewById(R.id.primary)
        val secondary: TextView = view.findViewById(R.id.secondary)
        val meta: TextView = view.findViewById(R.id.meta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_entry, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = getItem(position)
        val time = timeFormat.format(Date(entry.timestamp))

        if (entry.kind == MonitorEntry.KIND_SCREEN) {
            holder.thumbnail.visibility = View.VISIBLE
            entry.screenshotPath?.let { holder.thumbnail.load(File(it)) }
            holder.primary.text = entry.packageName ?: "Screen"
            holder.secondary.text = "Screenshot"
            holder.meta.text = "$time  ·  screen"
        } else {
            holder.thumbnail.visibility = View.GONE
            holder.thumbnail.setImageDrawable(null)
            holder.primary.text = entry.domain ?: entry.packageName ?: "Page"
            holder.secondary.text = entry.title.orEmpty()
            val snippet = entry.text?.replace('\n', ' ')?.take(80).orEmpty()
            holder.meta.text = "$time  ·  ${entry.packageName.orEmpty()}  ·  $snippet"
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<MonitorEntry>() {
            override fun areItemsTheSame(old: MonitorEntry, new: MonitorEntry) = old.id == new.id
            override fun areContentsTheSame(old: MonitorEntry, new: MonitorEntry) = old == new
        }
    }
}
