package com.editech.services.activities

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.editech.services.R
import com.editech.services.firewall.ConnectionLog
import com.editech.services.firewall.FirewallManager
import com.editech.services.firewall.FirewallState
import com.editech.services.firewall.Protocol
import com.editech.services.firewall.ThreatType
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.niunaijun.blackbox.BlackBoxCore

class FirewallAppDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
        const val EXTRA_APP_NAME = "extra_app_name"
    }

    private lateinit var packageName: String
    private lateinit var appName: String
    private lateinit var ivIcon: ImageView
    private lateinit var tvAppName: TextView
    private lateinit var tvPackageName: TextView
    private lateinit var switchBlockAll: SwitchMaterial
    private lateinit var switchMonitor: SwitchMaterial
    private lateinit var tabLayout: TabLayout
    private lateinit var viewPager: ViewPager2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_firewall_app_detail)

        packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: return finish()
        appName = intent.getStringExtra(EXTRA_APP_NAME) ?: packageName

        initViews()
        setupHeader()
        setupViewPager()
    }

    private fun initViews() {
        ivIcon = findViewById(R.id.ivAppIcon)
        tvAppName = findViewById(R.id.tvAppName)
        tvPackageName = findViewById(R.id.tvPackageName)
        switchBlockAll = findViewById(R.id.switchBlockAll)
        switchMonitor = findViewById(R.id.switchMonitor)
        tabLayout = findViewById(R.id.tabLayout)
        viewPager = findViewById(R.id.viewPager)
    }

    private fun setupHeader() {
        tvAppName.text = appName
        tvPackageName.text = packageName
        
        // Load icon
        CoroutineScope(Dispatchers.IO).launch {
            val pm = packageManager
            val icon = try {
                 BlackBoxCore.get().getInstalledPackages(0, 0)
                     .find { it.packageName == packageName }
                     ?.applicationInfo?.loadIcon(pm)
            } catch (e: Exception) {
                null
            }
            withContext(Dispatchers.Main) {
                icon?.let { ivIcon.setImageDrawable(it) }
            }
        }

        // Initialize state
        val currentState = FirewallManager.getInstance().getState(packageName)
        val isMonitoring = currentState != FirewallState.DISABLED

        switchBlockAll.isChecked = currentState == FirewallState.BLOCKING_ALL
        // Monitor is checked if we are monitoring OR blocking (since blocking implies active firewall)
        switchMonitor.isChecked = isMonitoring
        
        // Monitor Toggle Listener
        switchMonitor.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // Enabled monitoring. If block was checked, it remains checked? 
                // Let's just set to MONITORING if block is off.
                if (!switchBlockAll.isChecked) {
                    FirewallManager.getInstance().setState(packageName, FirewallState.MONITORING)
                } else {
                    // If block is checked, state is already BLOCKING_ALL, so monitor enable does nothing effectively
                }
            } else {
                // Disable everything
                switchBlockAll.isChecked = false // This triggers block listener? No, usually distinct if programmatically set unless we use specialized listener
                // We should suppress listener or handle it.
                FirewallManager.getInstance().setState(packageName, FirewallState.DISABLED)
            }
        }

        // Block Toggle Listener
        switchBlockAll.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // Block All implies Monitoring is ON
                if (!switchMonitor.isChecked) {
                    switchMonitor.isChecked = true
                }
                FirewallManager.getInstance().setState(packageName, FirewallState.BLOCKING_ALL)
            } else {
                // Unblocking checks monitor state
                if (switchMonitor.isChecked) {
                    FirewallManager.getInstance().setState(packageName, FirewallState.MONITORING)
                } else {
                    FirewallManager.getInstance().setState(packageName, FirewallState.DISABLED)
                }
            }
        }
    }

    enum class DetailType {
        PORTS, LOGS, ENDPOINTS, THREATS, BANDWIDTH
    }

    private fun setupViewPager() {
        val adapter = DetailPagerAdapter(this)
        viewPager.adapter = adapter
        
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "Ports"
                1 -> "Endpoints"
                2 -> "Logs"
                3 -> "Threats"
                4 -> "Speed"
                else -> ""
            }
        }.attach()

        // Fix for "Impossible to return": Force focus to content when tab is selected
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                // Delay slightly to allow ViewPager to switch
                viewPager.postDelayed({
                    val currentFragment = supportFragmentManager.findFragmentByTag("f" + viewPager.currentItem)
                    if (currentFragment is BaseDetailFragment) {
                        currentFragment.requestFocus()
                    }
                }, 100)
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {
                 // Also force focus on reselect
                 onTabSelected(tab)
            }
        })
    }

    inner class DetailPagerAdapter(activity: AppCompatActivity) : androidx.viewpager2.adapter.FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = 5

        override fun createFragment(position: Int): androidx.fragment.app.Fragment {
            return when (position) {
                0 -> BaseDetailFragment.newInstance(packageName, DetailType.PORTS)
                1 -> BaseDetailFragment.newInstance(packageName, DetailType.ENDPOINTS)
                2 -> BaseDetailFragment.newInstance(packageName, DetailType.LOGS)
                3 -> BaseDetailFragment.newInstance(packageName, DetailType.THREATS)
                4 -> BaseDetailFragment.newInstance(packageName, DetailType.BANDWIDTH)
                else -> throw IllegalStateException("Invalid position")
            }
        }
    }
}

// Simple Fragment to avoid creating separate files for now
class BaseDetailFragment : androidx.fragment.app.Fragment() {
    companion object {
        private const val ARG_PACKAGE = "pkg"
        private const val ARG_TYPE = "type"
        
        fun newInstance(packageName: String, type: FirewallAppDetailActivity.DetailType): BaseDetailFragment {
            return BaseDetailFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_PACKAGE, packageName)
                    putString(ARG_TYPE, type.name)
                }
            }
        }
    }

    private lateinit var recyclerView: RecyclerView
    private var type = FirewallAppDetailActivity.DetailType.PORTS
    private var packageName = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        // We can reuse a simple recyclerview layout. item_connection_log.xml is item.
        // Let's create a simple frame layout with recyclerview programmatically to avoid another xml file
        val rv = RecyclerView(requireContext())
        rv.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        rv.layoutManager = LinearLayoutManager(requireContext())
        
        // Fix for TV D-pad navigation: Container should NOT take focus, items should.
        rv.isFocusable = false
        rv.isFocusableInTouchMode = false
        rv.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        rv.hasFixedSize()
        // Allow scaling animation to exceed bounds
        rv.clipChildren = false
        rv.clipToPadding = false
        rv.setPadding(0, 16, 0, 120) // Increased bottom padding to prevent focus edge cases
        
        recyclerView = rv
        return rv
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        packageName = arguments?.getString(ARG_PACKAGE) ?: ""
        type = FirewallAppDetailActivity.DetailType.valueOf(arguments?.getString(ARG_TYPE) ?: "PORTS")
        
        loadData()
    }
    
    private fun loadData() {
        CoroutineScope(Dispatchers.IO).launch {
            when (type) {
                FirewallAppDetailActivity.DetailType.PORTS -> loadPorts()
                FirewallAppDetailActivity.DetailType.ENDPOINTS -> loadEndpoints()
                FirewallAppDetailActivity.DetailType.LOGS -> loadLogs()
                FirewallAppDetailActivity.DetailType.THREATS -> loadThreats()
                FirewallAppDetailActivity.DetailType.BANDWIDTH -> loadBandwidth()
            }
        }
    }

    private suspend fun loadPorts() {
        val usedPorts = FirewallManager.getInstance().getUsedPorts(packageName)
        val rules = FirewallManager.getInstance().getRulesForPackage(packageName)
        
        val items = usedPorts.map { (port, protocol) ->
            val isBlocked = rules.any { 
                it.port == port && 
                (it.protocol.name == protocol || it.protocol == com.editech.services.firewall.Protocol.BOTH) &&
                it.ruleType == com.editech.services.firewall.RuleType.BLOCK_PORT
            }
            PortItemModel(port, protocol, isBlocked)
        }
        
        withContext(Dispatchers.Main) {
            if (recyclerView.adapter == null) {
                recyclerView.adapter = PortsAdapter(items) { portItem, blocked ->
                    togglePortBlock(portItem, blocked)
                }
            } else {
                (recyclerView.adapter as? PortsAdapter)?.updateData(items)
            }
            checkFocus()
        }
    }

    private suspend fun loadEndpoints() {
        // Load recent unique endpoints
        val endpoints = FirewallManager.getInstance().getUsedEndpoints(packageName)
        val rules = FirewallManager.getInstance().getRulesForPackage(packageName)
        
        val items = endpoints.map { endpoint ->
            val isBlocked = rules.any { 
                it.endpoint == endpoint && 
                it.ruleType == com.editech.services.firewall.RuleType.BLOCK_ENDPOINT
            }
            EndpointItemModel(endpoint, isBlocked)
        }

        withContext(Dispatchers.Main) {
            if (recyclerView.adapter == null) {
                recyclerView.adapter = EndpointsAdapter(items) { item, blocked ->
                    toggleEndpointBlock(item, blocked)
                }
            } else {
                (recyclerView.adapter as? EndpointsAdapter)?.updateData(items)
            }
            checkFocus()
        }
    }

    private suspend fun loadLogs() {
        val logs = FirewallManager.getInstance().getRecentLogs(packageName)
        withContext(Dispatchers.Main) {
            if (recyclerView.adapter == null) {
                val adapter = ConnectionLogsAdapter()
                recyclerView.adapter = adapter
                
                val logItems = logs.map { log ->
                    ConnectionLogItem(
                        packageName = log.packageName,
                        destinationIp = log.destinationIp,
                        destinationPort = log.destinationPort,
                        hostname = log.hostname,
                        protocol = log.protocol,
                        timestamp = log.timestamp,
                        wasBlocked = log.wasBlocked,
                        status = log.status,
                        failureReason = log.failureReason,
                        method = log.method,
                        path = log.path
                    )
                }
                adapter.submitList(logItems)
            } else {
                 val adapter = recyclerView.adapter as? ConnectionLogsAdapter
                 val logItems = logs.map { log ->
                    ConnectionLogItem(
                        packageName = log.packageName,
                        destinationIp = log.destinationIp,
                        destinationPort = log.destinationPort,
                        hostname = log.hostname,
                        protocol = log.protocol,
                        timestamp = log.timestamp,
                        wasBlocked = log.wasBlocked,
                        status = log.status,
                        failureReason = log.failureReason,
                        method = log.method,
                        path = log.path
                    )
                }
                adapter?.submitList(logItems)
            }
            checkFocus()
        }
    }
    
    private suspend fun loadThreats() {
        val manager = FirewallManager.getInstance()
        val threatLogs = manager.getThreatLogs(packageName)

        // Get blocking state for each threat type
        val adbBlocked = manager.isThreatBlocked(packageName, ThreatType.ADB_ACCESS)
        val localNetBlocked = manager.isThreatBlocked(packageName, ThreatType.LOCAL_NETWORK)

        // Group threat logs by type
        val items = mutableListOf<ThreatItemModel>()

        // Add category headers/toggles
        items.add(ThreatItemModel(
            threatType = ThreatType.ADB_ACCESS,
            isHeader = true,
            isBlocked = adbBlocked,
            count = threatLogs.count { ThreatType.fromTag(it.failureReason?.split("|")?.firstOrNull()) == ThreatType.ADB_ACCESS }
        ))

        items.add(ThreatItemModel(
            threatType = ThreatType.LOCAL_NETWORK,
            isHeader = true,
            isBlocked = localNetBlocked,
            count = threatLogs.count {
                val t = ThreatType.fromTag(it.failureReason?.split("|")?.firstOrNull())
                t == ThreatType.LOCAL_NETWORK || t == ThreatType.LOCALHOST_PROBE
            }
        ))

        // Add individual threat log entries
        for (log in threatLogs) {
            val tag = log.failureReason?.split("|")?.firstOrNull()
            val threat = ThreatType.fromTag(tag) ?: continue
            items.add(ThreatItemModel(
                threatType = threat,
                isHeader = false,
                isBlocked = false,
                ip = log.destinationIp,
                port = log.destinationPort,
                hostname = log.hostname,
                timestamp = log.timestamp,
                wasBlocked = log.wasBlocked
            ))
        }

        withContext(Dispatchers.Main) {
            recyclerView.adapter = ThreatsAdapter(items) { threatType, blocked ->
                toggleThreatBlock(threatType, blocked)
            }
            checkFocus()
        }
    }

    private fun toggleThreatBlock(threatType: ThreatType, blocked: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            FirewallManager.getInstance().setThreatBlocking(packageName, threatType, blocked)
        }
    }

    private fun loadBandwidth() {
        CoroutineScope(Dispatchers.IO).launch {
            // Get current limits
            val limits = FirewallManager.getInstance().getBandwidthLimit(packageName)
            val uploadLimit = limits.first
            val downloadLimit = limits.second

            val items = listOf(
                BandwidthItemModel("Upload Limit", "Limit maximum upload speed", uploadLimit, true),
                BandwidthItemModel("Download Limit", "Limit maximum download speed", downloadLimit, false)
            )

            withContext(Dispatchers.Main) {
                recyclerView.adapter = BandwidthAdapter(items) { isUpload, limitBytes ->
                     updateBandwidthLimit(isUpload, limitBytes)
                }
                checkFocus()
            }
        }
    }

    private fun updateBandwidthLimit(isUpload: Boolean, limitBytes: Long) {
         CoroutineScope(Dispatchers.IO).launch {
             val current = FirewallManager.getInstance().getBandwidthLimit(packageName)
             val newUp = if (isUpload) limitBytes else current.first
             val newDown = if (!isUpload) limitBytes else current.second
             FirewallManager.getInstance().setBandwidthLimit(packageName, newUp, newDown)
         }
    }

    private fun checkFocus() {
        // Only request focus if this is the FIRST load or list is empty
        if (recyclerView.adapter?.itemCount ?: 0 > 0 && recyclerView.findFocus() == null) {
            recyclerView.post { 
                 if (recyclerView.findFocus() == null) {
                      val firstView = recyclerView.layoutManager?.findViewByPosition(0)
                      if (firstView != null) {
                          firstView.requestFocus()
                      } else {
                          recyclerView.requestFocus()
                      }
                 }
            }
        }
    }
    
    private fun togglePortBlock(item: PortItemModel, blocked: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            if (blocked) {
                FirewallManager.getInstance().addBlockPortRule(
                    packageName, 
                    item.port, 
                    try { com.editech.services.firewall.Protocol.valueOf(item.protocol) } catch(e:Exception) { com.editech.services.firewall.Protocol.BOTH }
                )
            } else {
                val rules = FirewallManager.getInstance().getRulesForPackage(packageName)
                val ruleToRemove = rules.find { 
                    it.port == item.port && 
                    it.ruleType == com.editech.services.firewall.RuleType.BLOCK_PORT 
                }
                ruleToRemove?.let {
                    FirewallManager.getInstance().removeRule(it.id, packageName)
                }
            }
        }
    }

    private fun toggleEndpointBlock(item: EndpointItemModel, blocked: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            if (blocked) {
                FirewallManager.getInstance().addBlockEndpointRule(packageName, item.endpoint)
            } else {
                val rules = FirewallManager.getInstance().getRulesForPackage(packageName)
                val ruleToRemove = rules.find { 
                    it.endpoint == item.endpoint && 
                    it.ruleType == com.editech.services.firewall.RuleType.BLOCK_ENDPOINT 
                }
                ruleToRemove?.let {
                    FirewallManager.getInstance().removeRule(it.id, packageName)
                }
            }
        }
    }

    fun requestFocus() {
        recyclerView.post {
            val layoutManager = recyclerView.layoutManager
            val firstView = layoutManager?.findViewByPosition(0)
            if (firstView != null) {
                firstView.requestFocus()
            } else {
                recyclerView.requestFocus()
            }
        }
    }
}

data class PortItemModel(val port: Int, val protocol: String, var isBlocked: Boolean)
data class EndpointItemModel(val endpoint: String, var isBlocked: Boolean)
data class ThreatItemModel(
    val threatType: ThreatType,
    val isHeader: Boolean,
    var isBlocked: Boolean = false,
    val count: Int = 0,
    val ip: String? = null,
    val port: Int = 0,
    val hostname: String? = null,
    val timestamp: Long = 0,
    val wasBlocked: Boolean = false
)

data class BandwidthItemModel(
    val title: String,
    val description: String,
    var limitBytes: Long,
    val isUpload: Boolean
)

class EndpointsAdapter(
    private var items: List<EndpointItemModel>,
    private val onToggle: (EndpointItemModel, Boolean) -> Unit
) : RecyclerView.Adapter<EndpointsAdapter.ViewHolder>() {

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        return items[position].endpoint.hashCode().toLong()
    }
    
    fun updateData(newItems: List<EndpointItemModel>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        // Reuse item_firewall_port layout since it has text + switch
        // We might want to adjust text IDs
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_firewall_port, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvPort: TextView = view.findViewById(R.id.tvPort) // Use generic names? This is tvPort in XML
        val tvProtocol: TextView = view.findViewById(R.id.tvProtocol)
        val tvStatus: TextView = view.findViewById(R.id.tvStatus)
        val switchBlock: SwitchMaterial = view.findViewById(R.id.switchBlock)

        init {
            switchBlock.isClickable = false
            switchBlock.isFocusable = false
            // Hide protocol text view as it's not needed for Endpoints
            tvProtocol.visibility = View.GONE
            // Optional: Adjust layout params if needed
        }

        fun bind(item: EndpointItemModel) {
            tvPort.text = item.endpoint // Use "Port" TextView for Endpoint Text
            
            switchBlock.setOnCheckedChangeListener(null)
            switchBlock.isChecked = item.isBlocked
            
            updateStatus(item.isBlocked)
            
            itemView.setOnClickListener {
                switchBlock.isChecked = !switchBlock.isChecked
                item.isBlocked = switchBlock.isChecked
                updateStatus(item.isBlocked)
                onToggle(item, item.isBlocked)
            }
        }
        
        private fun updateStatus(blocked: Boolean) {
            if (blocked) {
                tvStatus.text = "Blocked"
                tvStatus.setTextColor(0xFFE57373.toInt()) // Red
            } else {
                tvStatus.text = "Allowed"
                tvStatus.setTextColor(0xFF81C784.toInt()) // Green
            }
        }
    }
}

class PortsAdapter(
    private var items: List<PortItemModel>,
    private val onToggle: (PortItemModel, Boolean) -> Unit
) : RecyclerView.Adapter<PortsAdapter.ViewHolder>() {

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        return items[position].port.toLong()
    }
    
    fun updateData(newItems: List<PortItemModel>) {
        // Simple notify data set changed for now, can be improved with DiffUtil later
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_firewall_port, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvPort: TextView = view.findViewById(R.id.tvPort)
        val tvProtocol: TextView = view.findViewById(R.id.tvProtocol)
        val tvStatus: TextView = view.findViewById(R.id.tvStatus)
        val switchBlock: SwitchMaterial = view.findViewById(R.id.switchBlock)

        init {
            // Ensure switch doesn't steal focus/clicks
            switchBlock.isClickable = false
            switchBlock.isFocusable = false
        }

        fun bind(item: PortItemModel) {
            tvPort.text = item.port.toString()
            tvProtocol.text = item.protocol
            // Avoid triggering listener during binding
            switchBlock.setOnCheckedChangeListener(null)
            switchBlock.isChecked = item.isBlocked
            
            updateStatus(item.isBlocked)
            
            // Handle click on item to toggle switch
            itemView.setOnClickListener {
                switchBlock.isChecked = !switchBlock.isChecked
                item.isBlocked = switchBlock.isChecked
                updateStatus(item.isBlocked)
                onToggle(item, item.isBlocked)
            }
        }
        
        private fun updateStatus(blocked: Boolean) {
            if (blocked) {
                tvStatus.text = "Blocked"
                tvStatus.setTextColor(0xFFE57373.toInt()) // Red
            } else {
                tvStatus.text = "Allowed"
                tvStatus.setTextColor(0xFF81C784.toInt()) // Green
            }
        }
    }
}

class ThreatsAdapter(
    private var items: List<ThreatItemModel>,
    private val onToggle: (ThreatType, Boolean) -> Unit
) : RecyclerView.Adapter<ThreatsAdapter.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ENTRY = 1
    }

    override fun getItemViewType(position: Int): Int {
        return if (items[position].isHeader) TYPE_HEADER else TYPE_ENTRY
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_firewall_port, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvPort: TextView = view.findViewById(R.id.tvPort)
        val tvProtocol: TextView = view.findViewById(R.id.tvProtocol)
        val tvStatus: TextView = view.findViewById(R.id.tvStatus)
        val switchBlock: SwitchMaterial = view.findViewById(R.id.switchBlock)

        fun bind(item: ThreatItemModel) {
            if (item.isHeader) {
                bindHeader(item)
            } else {
                bindEntry(item)
            }
        }

        private fun bindHeader(item: ThreatItemModel) {
            tvPort.text = "${item.threatType.icon}  ${item.threatType.label}"
            tvPort.textSize = 16f
            tvProtocol.text = "${item.count} detection${if (item.count != 1) "s" else ""}"
            tvProtocol.visibility = View.VISIBLE

            switchBlock.visibility = View.VISIBLE
            switchBlock.isClickable = false
            switchBlock.isFocusable = false
            switchBlock.setOnCheckedChangeListener(null)
            switchBlock.isChecked = item.isBlocked

            if (item.isBlocked) {
                tvStatus.text = "Blocking"
                tvStatus.setTextColor(0xFFE57373.toInt())
            } else {
                tvStatus.text = "Monitoring"
                tvStatus.setTextColor(0xFFFFB74D.toInt())
            }

            itemView.setOnClickListener {
                switchBlock.isChecked = !switchBlock.isChecked
                item.isBlocked = switchBlock.isChecked
                if (item.isBlocked) {
                    tvStatus.text = "Blocking"
                    tvStatus.setTextColor(0xFFE57373.toInt())
                } else {
                    tvStatus.text = "Monitoring"
                    tvStatus.setTextColor(0xFFFFB74D.toInt())
                }
                onToggle(item.threatType, item.isBlocked)
            }
        }

        private fun bindEntry(item: ThreatItemModel) {
            val dest = item.hostname?.let { "$it (${item.ip})" } ?: item.ip ?: "unknown"
            tvPort.text = "    ${item.threatType.icon}  $dest:${item.port}"
            tvPort.textSize = 14f

            // Show time ago
            val elapsed = System.currentTimeMillis() - item.timestamp
            val timeAgo = when {
                elapsed < 60_000 -> "just now"
                elapsed < 3_600_000 -> "${elapsed / 60_000}m ago"
                elapsed < 86_400_000 -> "${elapsed / 3_600_000}h ago"
                else -> "${elapsed / 86_400_000}d ago"
            }
            tvProtocol.text = timeAgo
            tvProtocol.visibility = View.VISIBLE

            switchBlock.visibility = View.GONE

            if (item.wasBlocked) {
                tvStatus.text = "Blocked"
                tvStatus.setTextColor(0xFFE57373.toInt())
            } else {
                tvStatus.text = "Detected"
                tvStatus.setTextColor(0xFFFFB74D.toInt())
            }

            itemView.setOnClickListener(null)
            itemView.isFocusable = true
            itemView.isFocusableInTouchMode = true
        }
    }
}

class BandwidthAdapter(
    private var items: List<BandwidthItemModel>,
    private val onLimitChanged: (Boolean, Long) -> Unit
) : RecyclerView.Adapter<BandwidthAdapter.ViewHolder>() {

    // Scale: 0 (Unlimited) -> 8 (10 MB/s)
    private val steps = listOf(
        0L,             // Unlimited
        64 * 1024L,     // 64 KB/s
        128 * 1024L,    // 128 KB/s
        256 * 1024L,    // 256 KB/s
        512 * 1024L,    // 512 KB/s
        1024 * 1024L,   // 1 MB/s
        2 * 1024 * 1024L, // 2 MB/s
        5 * 1024 * 1024L, // 5 MB/s
        10 * 1024 * 1024L // 10 MB/s
    )

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_bandwidth_limit, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView = view.findViewById(R.id.tvTitle)
        val tvValue: TextView = view.findViewById(R.id.tvValue)
        val tvDescription: TextView = view.findViewById(R.id.tvDescription)
        val seekBar: android.widget.SeekBar = view.findViewById(R.id.seekBar)

        fun bind(item: BandwidthItemModel) {
            tvTitle.text = item.title
            tvDescription.text = item.description

            // Convert limitBytes to progress index
            var progress = steps.indexOf(item.limitBytes)
            if (progress < 0) {
                 // Use closest match if custom value
                 progress = 0
            }
            seekBar.progress = progress
            
            updateValueText(item.limitBytes)

            seekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val limit = steps[progress]
                        item.limitBytes = limit
                        updateValueText(limit)
                        onLimitChanged(item.isUpload, limit)
                    }
                }

                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
            })
            
            // Handle TV D-pad navigation on the ITEM VIEW
            itemView.setOnKeyListener { v, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            if (seekBar.progress > 0) {
                                seekBar.progress = seekBar.progress - 1
                                // Manually trigger update since setProgress(x) doesn't set fromUser=true
                                val limit = steps[seekBar.progress]
                                item.limitBytes = limit
                                updateValueText(limit)
                                onLimitChanged(item.isUpload, limit)
                                return@setOnKeyListener true
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (seekBar.progress < seekBar.max) {
                                seekBar.progress = seekBar.progress + 1
                                // Manually trigger update
                                val limit = steps[seekBar.progress]
                                item.limitBytes = limit
                                updateValueText(limit)
                                onLimitChanged(item.isUpload, limit)
                                return@setOnKeyListener true
                            }
                        }
                    }
                }
                false
            }
        }

        private fun updateValueText(limitBytes: Long) {
            val text = if (limitBytes <= 0) {
                "Unlimited"
            } else {
                formatSpeed(limitBytes)
            }
            tvValue.text = text
            
            if (limitBytes <= 0) {
                tvValue.setTextColor(0xFF81C784.toInt()) // Green
            } else {
                tvValue.setTextColor(0xFFFFB74D.toInt()) // Orange
            }
        }

        private fun formatSpeed(bytesPerSec: Long): String {
            return when {
                bytesPerSec < 1024 -> "$bytesPerSec B/s"
                bytesPerSec < 1_048_576 -> "${bytesPerSec / 1024} KB/s"
                else -> "${bytesPerSec / 1_048_576} MB/s"
            }
        }
    }
}
