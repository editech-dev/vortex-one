package com.editech.services.activities

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.editech.services.R

data class ConnectionLogItem(
    val packageName: String,
    val destinationIp: String,
    val destinationPort: Int,
    val hostname: String?,
    val protocol: String,
    val timestamp: Long,
    val wasBlocked: Boolean,
    val status: String // BLOCKED, ESTABLISHED, FAILED, UNKNOWN
)

class ConnectionLogsAdapter : RecyclerView.Adapter<ConnectionLogsAdapter.ViewHolder>() {

    private var logs = listOf<ConnectionLogItem>()

    fun submitList(newList: List<ConnectionLogItem>) {
        logs = newList
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_connection_log, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(logs[position])
    }

    override fun getItemCount() = logs.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTimestamp: TextView = itemView.findViewById(R.id.tvTimestamp)
        private val tvAppName: TextView = itemView.findViewById(R.id.tvAppName)
        private val tvDestination: TextView = itemView.findViewById(R.id.tvDestination)
        private val tvPort: TextView = itemView.findViewById(R.id.tvPort)
        private val tvProtocol: TextView = itemView.findViewById(R.id.tvProtocol)
        private val statusIndicator: View = itemView.findViewById(R.id.statusIndicator)

        fun bind(log: ConnectionLogItem) {
            val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            tvTimestamp.text = sdf.format(java.util.Date(log.timestamp))

            tvAppName.text = log.packageName.substringAfterLast('.')
            
            val destText = log.hostname?.let { "$it" } ?: log.destinationIp
            tvDestination.text = destText
            
            tvPort.text = ":${log.destinationPort}"
            
            // Format: TCP • BLOCKED / ESTABLISHED / FAILED
            val statusColor = when (log.status) {
                "BLOCKED" -> 0xFFE57373.toInt() // Red
                "ESTABLISHED" -> 0xFF81C784.toInt() // Green
                "FAILED" -> 0xFFFFB74D.toInt() // Orange
                else -> 0xFF90A4AE.toInt() // Grey
            }
            
            tvProtocol.text = "${log.protocol} • ${log.status}"
            tvProtocol.setTextColor(statusColor)

            statusIndicator.setBackgroundColor(statusColor)
        }
    }
}
