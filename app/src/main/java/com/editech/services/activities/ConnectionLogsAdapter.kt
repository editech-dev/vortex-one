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
    val wasBlocked: Boolean
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
            tvDestination.text = log.hostname ?: log.destinationIp
            tvPort.text = ":${log.destinationPort}"
            tvProtocol.text = "${log.protocol} ${if (log.wasBlocked) "• BLOCKED" else ""}"

            statusIndicator.setBackgroundColor(
                if (log.wasBlocked) 0xFFE57373.toInt() else 0xFF81C784.toInt()
            )
        }
    }
}
