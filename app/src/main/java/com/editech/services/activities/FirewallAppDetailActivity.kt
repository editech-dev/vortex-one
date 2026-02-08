package com.editech.services.activities

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Bundle
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
        val isBlocking = currentState == FirewallState.BLOCKING_ALL || currentState == FirewallState.BLOCKING_PORTS
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

    private fun setupViewPager() {
        val adapter = DetailPagerAdapter(this)
        viewPager.adapter = adapter
        
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "Ports"
                1 -> "Logs"
                else -> ""
            }
        }.attach()
    }

    inner class DetailPagerAdapter(activity: AppCompatActivity) : androidx.viewpager2.adapter.FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = 2

        override fun createFragment(position: Int): androidx.fragment.app.Fragment {
            return when (position) {
                0 -> BaseDetailFragment.newInstance(packageName, isPorts = true)
                1 -> BaseDetailFragment.newInstance(packageName, isPorts = false)
                else -> throw IllegalStateException("Invalid position")
            }
        }
    }
}

// Simple Fragment to avoid creating separate files for now
class BaseDetailFragment : androidx.fragment.app.Fragment() {
    companion object {
        private const val ARG_PACKAGE = "pkg"
        private const val ARG_IS_PORTS = "is_ports"
        
        fun newInstance(packageName: String, isPorts: Boolean): BaseDetailFragment {
            return BaseDetailFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_PACKAGE, packageName)
                    putBoolean(ARG_IS_PORTS, isPorts)
                }
            }
        }
    }

    private lateinit var recyclerView: RecyclerView
    private var isPorts = true
    private var packageName = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        // We can reuse a simple recyclerview layout. item_connection_log.xml is item.
        // Let's create a simple frame layout with recyclerview programmatically to avoid another xml file
        val rv = RecyclerView(requireContext())
        rv.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        rv.layoutManager = LinearLayoutManager(requireContext())
        recyclerView = rv
        return rv
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        packageName = arguments?.getString(ARG_PACKAGE) ?: ""
        isPorts = arguments?.getBoolean(ARG_IS_PORTS) ?: true
        
        loadData()
    }
    
    private fun loadData() {
        CoroutineScope(Dispatchers.IO).launch {
            if (isPorts) {
                val usedPorts = FirewallManager.getInstance().getUsedPorts(packageName)
                // Get existing rules to check if blocked
                val rules = FirewallManager.getInstance().getRulesForPackage(packageName)
                
                val items = usedPorts.map { (port, protocol) ->
                    val isBlocked = rules.any { 
                        it.port == port && 
                        (it.protocol.name == protocol || it.protocol == Protocol.BOTH) &&
                        it.ruleType == com.editech.services.firewall.RuleType.BLOCK_PORT
                    }
                    PortItemModel(port, protocol, isBlocked)
                }
                
                withContext(Dispatchers.Main) {
                    recyclerView.adapter = PortsAdapter(items) { portItem, blocked ->
                        togglePortBlock(portItem, blocked)
                    }
                }
            } else {
                val logs = FirewallManager.getInstance().getRecentLogs(packageName)
                withContext(Dispatchers.Main) {
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
                            wasBlocked = log.wasBlocked
                        )
                    }
                    adapter.submitList(logItems)
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
                    try { Protocol.valueOf(item.protocol) } catch(e:Exception) { Protocol.BOTH }
                )
            } else {
                // Find and remove rule
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
}

data class PortItemModel(val port: Int, val protocol: String, var isBlocked: Boolean)

class PortsAdapter(
    private val items: List<PortItemModel>,
    private val onToggle: (PortItemModel, Boolean) -> Unit
) : RecyclerView.Adapter<PortsAdapter.ViewHolder>() {

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

        fun bind(item: PortItemModel) {
            tvPort.text = item.port.toString()
            tvProtocol.text = item.protocol
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
