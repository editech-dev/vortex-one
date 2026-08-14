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
    val status: String, // BLOCKED, ESTABLISHED, FAILED, UNKNOWN
    val failureReason: String?,
    val method: String? = null,
    val path: String? = null
)

class ConnectionLogsAdapter : RecyclerView.Adapter<ConnectionLogsAdapter.ViewHolder>() {

    private var logs = listOf<ConnectionLogItem>()
    
    init {
        setHasStableIds(true)
    }
    
    override fun getItemId(position: Int): Long {
        // Use timestamp or hashcode as stable ID. Timestamp might not be unique enough if fast?
        // Combining hashcode of unique fields is better.
        return logs[position].hashCode().toLong()
    }

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
            
            // Prioritize URL (Path) if available
            if (!log.path.isNullOrEmpty()) {
                 val methodPrefix = log.method?.let { "$it " } ?: ""
                 // e.g. "REQ https://api.google.com/home"
                 // Or if path is just path: "REQ /home"
                 // Construct full display: "METHOD hostname/path"
                 val displayPath = if (log.path.startsWith("http")) log.path else (log.hostname ?: log.destinationIp) + log.path
                 tvDestination.text = "$methodPrefix$displayPath"
            } else {
                val destText = log.hostname?.let { "$it" } ?: log.destinationIp
                tvDestination.text = destText
            }
            
            tvPort.text = ":${log.destinationPort}"
            
            // Format: TCP • BLOCKED / ESTABLISHED / FAILED (Tor connections use Purple for ESTABLISHED)
            val isTor = log.protocol.startsWith("TOR")
            val statusColor = when (log.status) {
                "BLOCKED" -> 0xFFE57373.toInt() // Red
                "ESTABLISHED" -> if (isTor) 0xFFB39DDB.toInt() else 0xFF81C784.toInt() // Purple for Tor, Green for Direct
                "FAILED" -> 0xFFFFB74D.toInt() // Orange
                else -> 0xFF90A4AE.toInt() // Grey
            }
            
            val statusText = StringBuilder()
            if (isTor) {
                statusText.append("🧅 ${log.protocol} • ${log.status}")
            } else {
                statusText.append("${log.protocol} • ${log.status}")
            }
            
            if (!log.failureReason.isNullOrEmpty()) {
                statusText.append(" (${log.failureReason})")
            }
            
            tvProtocol.text = statusText.toString()
            tvProtocol.setTextColor(statusColor)

            statusIndicator.backgroundTintList = android.content.res.ColorStateList.valueOf(statusColor)

            val card = itemView as? com.google.android.material.card.MaterialCardView
            itemView.setOnFocusChangeListener { _, hasFocus ->
                card?.strokeWidth = if (hasFocus) 3 else 0
                card?.strokeColor = if (hasFocus) 0xFF38BDF8.toInt() else android.graphics.Color.TRANSPARENT
            }
        }
    }
}
