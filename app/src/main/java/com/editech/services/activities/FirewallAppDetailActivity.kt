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
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.editech.services.R
import com.editech.services.firewall.ConnectionLog
import com.editech.services.firewall.FirewallManager
import com.editech.services.firewall.FirewallState
import com.editech.services.firewall.Protocol
import com.editech.services.firewall.ThreatType
import com.editech.services.utils.LocaleHelper
import com.google.android.material.card.MaterialCardView
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

    // Guard to prevent switch listeners from triggering each other (Bug #1)
    private var isProgrammaticChange = false

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(base))
    }

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
        findViewById<android.view.View>(R.id.btnBackDetail)?.setOnClickListener { finish() }
    }

    private fun setupHeader() {
        tvAppName.text = appName
        tvPackageName.text = packageName

        // Load icon in background
        CoroutineScope(Dispatchers.IO).launch {
            val pm = packageManager
            val icon = try {
                BlackBoxCore.get().getInstalledPackages(0, 0)
                    .find { it.packageName == packageName }
                    ?.applicationInfo?.loadIcon(pm)
            } catch (e: Exception) { null }
            withContext(Dispatchers.Main) {
                icon?.let { ivIcon.setImageDrawable(it) }
            }
        }

        // Sync UI to current state without triggering listeners
        syncSwitchesToState(FirewallManager.getInstance().getState(packageName))

        // Bug #1 fix: use isProgrammaticChange guard to prevent listener loops
        switchMonitor.setOnCheckedChangeListener { _, isChecked ->
            if (isProgrammaticChange) return@setOnCheckedChangeListener
            val newState = if (isChecked) {
                if (switchBlockAll.isChecked) FirewallState.BLOCKING_ALL else FirewallState.MONITORING
            } else {
                // Turning monitor off → also disable block
                isProgrammaticChange = true
                switchBlockAll.isChecked = false
                isProgrammaticChange = false
                FirewallState.DISABLED
            }
            applyFirewallState(newState)
        }

        switchBlockAll.setOnCheckedChangeListener { _, isChecked ->
            if (isProgrammaticChange) return@setOnCheckedChangeListener
            if (isChecked) {
                // Block All → also enable monitor switch visually
                isProgrammaticChange = true
                switchMonitor.isChecked = true
                isProgrammaticChange = false
                applyFirewallState(FirewallState.BLOCKING_ALL)
            } else {
                val newState = if (switchMonitor.isChecked) FirewallState.MONITORING
                               else FirewallState.DISABLED
                applyFirewallState(newState)
            }
        }
    }

    /** Set both switches without triggering their listeners */
    private fun syncSwitchesToState(state: FirewallState) {
        isProgrammaticChange = true
        switchMonitor.isChecked = state != FirewallState.DISABLED
        switchBlockAll.isChecked = state == FirewallState.BLOCKING_ALL
        isProgrammaticChange = false
    }

    /** Single point of truth for state changes */
    private fun applyFirewallState(state: FirewallState) {
        try {
            FirewallManager.getInstance().setState(packageName, state)
        } catch (e: Exception) {
            android.util.Log.e("FirewallDetail", "Failed to set state: ${e.message}")
        }
    }

    enum class DetailType {
        PORTS, LOGS, ENDPOINTS, THREATS, BANDWIDTH, TOR
    }

    private fun setupViewPager() {
        val adapter = DetailPagerAdapter(this)
        viewPager.adapter = adapter
        (viewPager.getChildAt(0) as? RecyclerView)?.apply {
            clipChildren = true
            clipToPadding = true
        }

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.tab_ports)
                1 -> getString(R.string.tab_endpoints)
                2 -> getString(R.string.tab_logs)
                3 -> getString(R.string.tab_threats)
                4 -> getString(R.string.tab_speed)
                5 -> getString(R.string.tab_tor)
                else -> ""
            }
        }.attach()

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                tab?.position?.let {
                    if (viewPager.currentItem != it) {
                        viewPager.setCurrentItem(it, false)
                    }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        // Auto-select tab when focused via TV D-pad without stealing focus to list
        tabLayout.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val tabStrip = tabLayout.getChildAt(0) as? ViewGroup ?: return
                if (tabStrip.childCount >= 6) {
                    tabLayout.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    for (i in 0 until tabStrip.childCount) {
                        val tabView = tabStrip.getChildAt(i)
                        tabView.isFocusable = true
                        tabView.isFocusableInTouchMode = true
                        tabView.setOnFocusChangeListener { _, hasFocus ->
                            if (hasFocus) {
                                tabLayout.getTabAt(i)?.select()
                            }
                        }
                    }
                }
            }
        })
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            val focused = currentFocus
            val tabStrip = tabLayout.getChildAt(0) as? ViewGroup

            // 1. DOWN from Header (Back button, Switches) -> Jump to the active TabView
            if (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                if (focused === switchMonitor || focused === switchBlockAll || focused?.id == R.id.btnBackDetail) {
                    val activeTabView = tabStrip?.getChildAt(viewPager.currentItem)
                    if (activeTabView?.requestFocus() == true) {
                        return true
                    }
                }

                // 2. DOWN from Tabs -> Jump into the current Fragment's first item
                if (focused != null && (focused.parent === tabStrip || focused.parent?.parent === tabLayout)) {
                    val tag = "f${viewPager.currentItem}"
                    val fragment = supportFragmentManager.findFragmentByTag(tag)
                    val handled = when (fragment) {
                        is BaseDetailFragment -> fragment.focusFirstItemSynchronous()
                        is TorFragment -> fragment.focusFirstItemSynchronous()
                        else -> false
                    }
                    if (handled) return true
                }
            }

            // 3. UP from Tabs -> Jump to header switch
            if (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP) {
                if (focused != null && (focused.parent === tabStrip || focused.parent?.parent === tabLayout)) {
                    if (switchMonitor.requestFocus()) {
                        return true
                    }
                }

                // 4. UP from Fragment / List items -> Jump back to active Tab
                val isHeader = focused === switchMonitor || focused === switchBlockAll || focused?.id == R.id.btnBackDetail
                if (!isHeader) {
                    val tag = "f${viewPager.currentItem}"
                    val fragment = supportFragmentManager.findFragmentByTag(tag)
                    val isFirstItem = when (fragment) {
                        is BaseDetailFragment -> fragment.isFirstItemFocused()
                        is TorFragment -> fragment.isFirstItemFocused()
                        else -> false
                    }
                    if (isFirstItem) {
                        val activeTabView = tabStrip?.getChildAt(viewPager.currentItem)
                        if (activeTabView?.requestFocus() == true) {
                            return true
                        }
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    inner class DetailPagerAdapter(activity: AppCompatActivity) :
        androidx.viewpager2.adapter.FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = 6

        override fun createFragment(position: Int): androidx.fragment.app.Fragment {
            return when (position) {
                0 -> BaseDetailFragment.newInstance(packageName, DetailType.PORTS)
                1 -> BaseDetailFragment.newInstance(packageName, DetailType.ENDPOINTS)
                2 -> BaseDetailFragment.newInstance(packageName, DetailType.LOGS)
                3 -> BaseDetailFragment.newInstance(packageName, DetailType.THREATS)
                4 -> BaseDetailFragment.newInstance(packageName, DetailType.BANDWIDTH)
                5 -> TorFragment.newInstance(packageName)
                else -> throw IllegalStateException("Invalid position")
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// BaseDetailFragment
// ─────────────────────────────────────────────────────────────────────────────

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

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val rv = RecyclerView(requireContext())
        rv.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        rv.layoutManager = LinearLayoutManager(requireContext())

        // D-pad logic: list itself must NOT be focusable, only its children
        rv.isFocusable = false
        rv.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        rv.setPadding(0, 8, 0, 120)
        rv.clipToPadding = false

        recyclerView = rv
        return rv
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        packageName = arguments?.getString(ARG_PACKAGE) ?: ""
        type = FirewallAppDetailActivity.DetailType.valueOf(
            arguments?.getString(ARG_TYPE) ?: "PORTS"
        )
        loadData()
    }

    private fun loadData() {
        CoroutineScope(Dispatchers.IO).launch {
            when (type) {
                FirewallAppDetailActivity.DetailType.PORTS      -> loadPorts()
                FirewallAppDetailActivity.DetailType.ENDPOINTS  -> loadEndpoints()
                FirewallAppDetailActivity.DetailType.LOGS       -> loadLogs()
                FirewallAppDetailActivity.DetailType.THREATS    -> loadThreats()
                FirewallAppDetailActivity.DetailType.BANDWIDTH  -> loadBandwidth()
                FirewallAppDetailActivity.DetailType.TOR        -> { /* TorFragment handles TOR */ }
            }
        }
    }

    // ── Ports ────────────────────────────────────────────────────────────────

    private suspend fun loadPorts() {
        val usedPorts = FirewallManager.getInstance().getUsedPorts(packageName)
        val rules     = FirewallManager.getInstance().getRulesForPackage(packageName)

        val newItems = usedPorts.map { (port, protocol) ->
            val blocked = rules.any {
                it.port == port &&
                (it.protocol.name == protocol || it.protocol == Protocol.BOTH) &&
                it.ruleType == com.editech.services.firewall.RuleType.BLOCK_PORT
            }
            PortItemModel(port, protocol, blocked)
        }

        withContext(Dispatchers.Main) {
            val adapter = recyclerView.adapter as? PortsAdapter
            if (adapter == null) {
                recyclerView.adapter = PortsAdapter(newItems) { item, blocked ->
                    togglePortBlock(item, blocked)
                }
            } else {
                adapter.updateData(newItems) // DiffUtil inside
            }
        }
    }

    // ── Endpoints ────────────────────────────────────────────────────────────

    private suspend fun loadEndpoints() {
        val endpoints = FirewallManager.getInstance().getUsedEndpoints(packageName)
        val rules     = FirewallManager.getInstance().getRulesForPackage(packageName)

        val newItems = endpoints.map { endpoint ->
            val blocked = rules.any {
                it.endpoint == endpoint &&
                it.ruleType == com.editech.services.firewall.RuleType.BLOCK_ENDPOINT
            }
            EndpointItemModel(endpoint, blocked)
        }

        withContext(Dispatchers.Main) {
            val adapter = recyclerView.adapter as? EndpointsAdapter
            if (adapter == null) {
                recyclerView.adapter = EndpointsAdapter(newItems) { item, blocked ->
                    toggleEndpointBlock(item, blocked)
                }
            } else {
                adapter.updateData(newItems) // DiffUtil inside
            }
        }
    }

    // ── Logs ─────────────────────────────────────────────────────────────────

    private suspend fun loadLogs() {
        val logs = FirewallManager.getInstance().getRecentLogs(packageName, limit = 20)
        withContext(Dispatchers.Main) {
            val logItems = logs.map { log ->
                ConnectionLogItem(
                    packageName   = log.packageName,
                    destinationIp = log.destinationIp,
                    destinationPort = log.destinationPort,
                    hostname      = log.hostname,
                    protocol      = log.protocol,
                    timestamp     = log.timestamp,
                    wasBlocked    = log.wasBlocked,
                    status        = log.status,
                    failureReason = log.failureReason,
                    method        = log.method,
                    path          = log.path
                )
            }
            val adapter = recyclerView.adapter as? ConnectionLogsAdapter
            if (adapter == null) {
                val a = ConnectionLogsAdapter()
                recyclerView.adapter = a
                a.submitList(logItems)
            } else {
                adapter.submitList(logItems)
            }
        }
    }

    // ── Threats ──────────────────────────────────────────────────────────────

    private suspend fun loadThreats() {
        val manager       = FirewallManager.getInstance()
        val threatLogs    = manager.getThreatLogs(packageName, limit = 20)
        val adbBlocked    = manager.isThreatBlocked(packageName, ThreatType.ADB_ACCESS)
        val localBlocked  = manager.isThreatBlocked(packageName, ThreatType.LOCAL_NETWORK)

        val items = mutableListOf<ThreatItemModel>()
        items.add(ThreatItemModel(
            threatType = ThreatType.ADB_ACCESS, isHeader = true, isBlocked = adbBlocked,
            count = threatLogs.count {
                ThreatType.fromTag(it.failureReason?.split("|")?.firstOrNull()) == ThreatType.ADB_ACCESS
            }
        ))
        items.add(ThreatItemModel(
            threatType = ThreatType.LOCAL_NETWORK, isHeader = true, isBlocked = localBlocked,
            count = threatLogs.count {
                val t = ThreatType.fromTag(it.failureReason?.split("|")?.firstOrNull())
                t == ThreatType.LOCAL_NETWORK || t == ThreatType.LOCALHOST_PROBE
            }
        ))

        withContext(Dispatchers.Main) {
            recyclerView.adapter = ThreatsAdapter(items) { threatType, blocked ->
                toggleThreatBlock(threatType, blocked)
            }
        }
    }

    // ── Bandwidth ────────────────────────────────────────────────────────────

    private fun loadBandwidth() {
        CoroutineScope(Dispatchers.IO).launch {
            val limits        = FirewallManager.getInstance().getBandwidthLimit(packageName)
            val items = listOf(
                BandwidthItemModel("Límite de Subida",   "Velocidad máxima de upload",    limits.first,  true),
                BandwidthItemModel("Límite de Bajada",   "Velocidad máxima de download",  limits.second, false)
            )
            withContext(Dispatchers.Main) {
                recyclerView.adapter = BandwidthAdapter(items) { isUpload, limitBytes ->
                    updateBandwidthLimit(isUpload, limitBytes)
                }
            }
        }
    }

    // ── Focus helper (Bug #5 fix) ─────────────────────────────────────────────

    fun isFirstItemFocused(): Boolean {
        if (!::recyclerView.isInitialized) return true
        val focused = recyclerView.findFocus() ?: return true
        val directChild = recyclerView.findContainingItemView(focused) ?: focused
        val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return true
        val pos = lm.getPosition(directChild)
        return pos == 0 || (pos == androidx.recyclerview.widget.RecyclerView.NO_POSITION && lm.findFirstVisibleItemPosition() == 0)
    }

    /**
     * Executes the TV D-pad focus attempt synchronously.
     * Returns true if it successfully landed focus inside the list.
     */
    fun focusFirstItemSynchronous(): Boolean {
        val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return false
        
        // Try focused on the first item physically visible on screen
        val firstVisiblePos = lm.findFirstVisibleItemPosition()
        if (firstVisiblePos != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
            val firstVisibleView = lm.findViewByPosition(firstVisiblePos)
            if (firstVisibleView?.requestFocus() == true) {
                return true
            }
        }
        
        // Backup: logical item 0 if layout calculation is fresh or incomplete
        val firstView = lm.findViewByPosition(0)
        return firstView?.requestFocus() == true
    }

    /** Always move focus to first visible item asynchronously — called after every tab switch */
    fun focusFirstItem() {
        recyclerView.post {
            focusFirstItemSynchronous()
        }
    }

    // ── Toggle actions ───────────────────────────────────────────────────────

    private fun togglePortBlock(item: PortItemModel, blocked: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            if (blocked) {
                FirewallManager.getInstance().addBlockPortRule(
                    packageName, item.port,
                    try { Protocol.valueOf(item.protocol) } catch (e: Exception) { Protocol.BOTH }
                )
            } else {
                val rule = FirewallManager.getInstance().getRulesForPackage(packageName)
                    .find { it.port == item.port && it.ruleType == com.editech.services.firewall.RuleType.BLOCK_PORT }
                rule?.let { FirewallManager.getInstance().removeRule(it.id, packageName) }
            }
        }
    }

    private fun toggleEndpointBlock(item: EndpointItemModel, blocked: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            if (blocked) {
                FirewallManager.getInstance().addBlockEndpointRule(packageName, item.endpoint)
            } else {
                val rule = FirewallManager.getInstance().getRulesForPackage(packageName)
                    .find { it.endpoint == item.endpoint && it.ruleType == com.editech.services.firewall.RuleType.BLOCK_ENDPOINT }
                rule?.let { FirewallManager.getInstance().removeRule(it.id, packageName) }
            }
        }
    }

    private fun toggleThreatBlock(threatType: ThreatType, blocked: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            FirewallManager.getInstance().setThreatBlocking(packageName, threatType, blocked)
        }
    }

    private fun updateBandwidthLimit(isUpload: Boolean, limitBytes: Long) {
        CoroutineScope(Dispatchers.IO).launch {
            val current = FirewallManager.getInstance().getBandwidthLimit(packageName)
            val newUp   = if (isUpload)  limitBytes else current.first
            val newDown = if (!isUpload) limitBytes else current.second
            FirewallManager.getInstance().setBandwidthLimit(packageName, newUp, newDown)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Data models
// ─────────────────────────────────────────────────────────────────────────────

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

// ─────────────────────────────────────────────────────────────────────────────
// EndpointsAdapter  (Bug #8: DiffUtil)
// ─────────────────────────────────────────────────────────────────────────────

class EndpointsAdapter(
    private var items: List<EndpointItemModel>,
    private val onToggle: (EndpointItemModel, Boolean) -> Unit
) : RecyclerView.Adapter<EndpointsAdapter.ViewHolder>() {

    init { setHasStableIds(true) }

    override fun getItemId(position: Int) = items[position].endpoint.hashCode().toLong()

    /** Bug #8 fix: DiffUtil prevents full-list flicker */
    fun updateData(newItems: List<EndpointItemModel>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = items.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(o: Int, n: Int) = items[o].endpoint == newItems[n].endpoint
            override fun areContentsTheSame(o: Int, n: Int) = items[o] == newItems[n]
        })
        items = newItems
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_firewall_port, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])
    override fun getItemCount() = items.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvPort: TextView    = view.findViewById(R.id.tvPort)
        val tvProtocol: TextView = view.findViewById(R.id.tvProtocol)
        val tvStatus: TextView  = view.findViewById(R.id.tvStatus)
        val switchBlock: SwitchMaterial = view.findViewById(R.id.switchBlock)

        init {
            switchBlock.isClickable = false
            switchBlock.isFocusable = false
            tvProtocol.visibility = View.GONE
        }

        fun bind(item: EndpointItemModel) {
            val uri = try {
                android.net.Uri.parse(if (item.endpoint.startsWith("http://") || item.endpoint.startsWith("https://")) item.endpoint else "https://${item.endpoint}")
            } catch (e: Exception) { null }

            val host = uri?.host ?: item.endpoint.substringBefore("?").substringBefore("/")
            val path = uri?.path?.takeIf { it.isNotEmpty() && it != "/" } ?: ""
            val queryPreview = if (path.isEmpty() && !uri?.query.isNullOrEmpty()) "?${uri?.query?.take(25)}..." else ""

            tvPort.text = host
            tvProtocol.text = if (path.isNotEmpty() || queryPreview.isNotEmpty()) "$path$queryPreview" else "Raíz"
            tvProtocol.visibility = View.VISIBLE

            switchBlock.setOnCheckedChangeListener(null)
            switchBlock.isChecked = item.isBlocked
            updateStatus(item.isBlocked)

            itemView.setOnClickListener {
                val newBlocked = !item.isBlocked
                item.isBlocked = newBlocked
                switchBlock.isChecked = newBlocked
                updateStatus(newBlocked)
                onToggle(item, newBlocked)
            }
        }

        private fun updateStatus(blocked: Boolean) {
            if (blocked) {
                tvStatus.text = "Bloqueado"
                tvStatus.setTextColor(0xFFEF4444.toInt())
            } else {
                tvStatus.text = "Permitido"
                tvStatus.setTextColor(0xFF10B981.toInt())
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// PortsAdapter  (Bug #8: DiffUtil)
// ─────────────────────────────────────────────────────────────────────────────

class PortsAdapter(
    private var items: List<PortItemModel>,
    private val onToggle: (PortItemModel, Boolean) -> Unit
) : RecyclerView.Adapter<PortsAdapter.ViewHolder>() {

    init { setHasStableIds(true) }

    override fun getItemId(position: Int) = items[position].port.toLong()

    /** Bug #8 fix: DiffUtil */
    fun updateData(newItems: List<PortItemModel>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = items.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(o: Int, n: Int) = items[o].port == newItems[n].port
            override fun areContentsTheSame(o: Int, n: Int) = items[o] == newItems[n]
        })
        items = newItems
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_firewall_port, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])
    override fun getItemCount() = items.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvPort: TextView     = view.findViewById(R.id.tvPort)
        val tvProtocol: TextView = view.findViewById(R.id.tvProtocol)
        val tvStatus: TextView   = view.findViewById(R.id.tvStatus)
        val switchBlock: SwitchMaterial = view.findViewById(R.id.switchBlock)

        init {
            switchBlock.isClickable = false
            switchBlock.isFocusable = false
        }

        fun bind(item: PortItemModel) {
            tvPort.text     = "Puerto ${item.port}"
            tvProtocol.text = item.protocol
            tvProtocol.visibility = View.VISIBLE
            switchBlock.setOnCheckedChangeListener(null)
            switchBlock.isChecked = item.isBlocked
            updateStatus(item.isBlocked)

            itemView.setOnClickListener {
                val newBlocked = !item.isBlocked
                item.isBlocked = newBlocked
                switchBlock.isChecked = newBlocked
                updateStatus(newBlocked)
                onToggle(item, newBlocked)
            }
        }

        private fun updateStatus(blocked: Boolean) {
            if (blocked) {
                tvStatus.text = "Bloqueado"
                tvStatus.setTextColor(0xFFEF4444.toInt())
            } else {
                tvStatus.text = "Permitido"
                tvStatus.setTextColor(0xFF10B981.toInt())
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ThreatsAdapter
// ─────────────────────────────────────────────────────────────────────────────

class ThreatsAdapter(
    private var items: List<ThreatItemModel>,
    private val onToggle: (ThreatType, Boolean) -> Unit
) : RecyclerView.Adapter<ThreatsAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_firewall_port, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])
    override fun getItemCount() = items.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvPort: TextView     = view.findViewById(R.id.tvPort)
        val tvProtocol: TextView = view.findViewById(R.id.tvProtocol)
        val tvStatus: TextView   = view.findViewById(R.id.tvStatus)
        val switchBlock: SwitchMaterial = view.findViewById(R.id.switchBlock)
        val ivRuleIcon: ImageView? = view.findViewById(R.id.ivRuleIcon)

        fun bind(item: ThreatItemModel) {
            if (item.isHeader) bindHeader(item) else bindEntry(item)
        }

        private fun bindHeader(item: ThreatItemModel) {
            ivRuleIcon?.apply {
                setImageResource(R.drawable.ic_shield)
                imageTintList = when (item.threatType) {
                    ThreatType.ADB_ACCESS -> android.content.res.ColorStateList.valueOf(0xFFEF4444.toInt())
                    ThreatType.LOCAL_NETWORK -> android.content.res.ColorStateList.valueOf(0xFFF59E0B.toInt())
                    ThreatType.LOCALHOST_PROBE -> android.content.res.ColorStateList.valueOf(0xFFFB923C.toInt())
                }
            }

            tvPort.text = when (item.threatType) {
                ThreatType.ADB_ACCESS -> "Acceso ADB"
                ThreatType.LOCAL_NETWORK -> "Acceso a Red Local"
                ThreatType.LOCALHOST_PROBE -> "Sondeo Localhost"
            }
            tvPort.textSize = 15f
            tvProtocol.text = "${item.count} ${if (item.count == 1) "detección" else "detecciones"}"
            tvProtocol.visibility = View.VISIBLE

            switchBlock.visibility = View.VISIBLE
            switchBlock.isClickable = false
            switchBlock.isFocusable = false
            switchBlock.setOnCheckedChangeListener(null)
            switchBlock.isChecked = item.isBlocked

            tvStatus.text = if (item.isBlocked) "Bloqueando" else "Monitoreando"
            tvStatus.setTextColor(
                if (item.isBlocked) 0xFFEF4444.toInt() else 0xFFF59E0B.toInt()
            )

            itemView.isFocusable = true
            itemView.isFocusableInTouchMode = true
            itemView.setOnClickListener {
                val newBlocked = !item.isBlocked
                item.isBlocked = newBlocked
                switchBlock.isChecked = newBlocked
                tvStatus.text = if (newBlocked) "Bloqueando" else "Monitoreando"
                tvStatus.setTextColor(
                    if (newBlocked) 0xFFEF4444.toInt() else 0xFFF59E0B.toInt()
                )
                onToggle(item.threatType, newBlocked)
            }
        }

        private fun bindEntry(item: ThreatItemModel) {
            val dest = item.hostname?.let { "$it (${item.ip})" } ?: item.ip ?: "desconocido"
            tvPort.text = "$dest:${item.port}"
            tvPort.textSize = 13f

            val elapsed = System.currentTimeMillis() - item.timestamp
            tvProtocol.text = when {
                elapsed < 60_000     -> "ahora"
                elapsed < 3_600_000  -> "${elapsed / 60_000}m atrás"
                elapsed < 86_400_000 -> "${elapsed / 3_600_000}h atrás"
                else                 -> "${elapsed / 86_400_000}d atrás"
            }
            tvProtocol.visibility = View.VISIBLE
            switchBlock.visibility = View.GONE

            tvStatus.text = if (item.wasBlocked) "Bloqueado" else "Detectado"
            tvStatus.setTextColor(
                if (item.wasBlocked) 0xFFEF4444.toInt() else 0xFFF59E0B.toInt()
            )

            itemView.isFocusable = true
            itemView.isFocusableInTouchMode = true
            itemView.setOnClickListener(null)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// BandwidthAdapter  (Bug #6: seekBar.max programático)
// ─────────────────────────────────────────────────────────────────────────────

class BandwidthAdapter(
    private var items: List<BandwidthItemModel>,
    private val onLimitChanged: (Boolean, Long) -> Unit
) : RecyclerView.Adapter<BandwidthAdapter.ViewHolder>() {

    private val steps = listOf(
        0L,                    // Ilimitado
        64  * 1024L,           // 64 KB/s
        128 * 1024L,           // 128 KB/s
        256 * 1024L,           // 256 KB/s
        512 * 1024L,           // 512 KB/s
        1_024 * 1024L,         // 1 MB/s
        2 * 1_024 * 1024L,     // 2 MB/s
        5 * 1_024 * 1024L,     // 5 MB/s
        10 * 1_024 * 1024L     // 10 MB/s
    )

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_bandwidth_limit, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])
    override fun getItemCount() = items.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView       = view.findViewById(R.id.tvTitle)
        val tvValue: TextView       = view.findViewById(R.id.tvValue)
        val tvDescription: TextView = view.findViewById(R.id.tvDescription)
        val seekBar: android.widget.SeekBar = view.findViewById(R.id.seekBar)

        fun bind(item: BandwidthItemModel) {
            tvTitle.text       = item.title
            tvDescription.text = item.description

            // Bug #6 fix: always sync max to steps array size
            seekBar.max = steps.size - 1

            val progress = steps.indexOf(item.limitBytes).takeIf { it >= 0 } ?: 0
            seekBar.progress = progress
            updateValueText(item.limitBytes, itemView.context)

            // Focus feedback for TV D-pad — draw a colored stroke when focused (issue #2)
            val card = itemView as? MaterialCardView
            itemView.setOnFocusChangeListener { _, hasFocus ->
                card?.strokeWidth = if (hasFocus) 3 else 0
                card?.strokeColor = if (hasFocus) 0xFF38BDF8.toInt() else android.graphics.Color.TRANSPARENT
            }

            seekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val limit = steps[p]
                        item.limitBytes = limit
                        updateValueText(limit, itemView.context)
                        onLimitChanged(item.isUpload, limit)
                    }
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            })

            // D-pad ◀▶ navigation for TV — adjust speed steps cleanly
            itemView.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT  -> {
                        val currentP = steps.indexOf(item.limitBytes).takeIf { it >= 0 } ?: 0
                        if (currentP > 0) {
                            val newP = currentP - 1
                            val limit = steps[newP]
                            item.limitBytes = limit
                            seekBar.progress = newP
                            updateValueText(limit, itemView.context)
                            onLimitChanged(item.isUpload, limit)
                        }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        val currentP = steps.indexOf(item.limitBytes).takeIf { it >= 0 } ?: 0
                        if (currentP < steps.size - 1) {
                            val newP = currentP + 1
                            val limit = steps[newP]
                            item.limitBytes = limit
                            seekBar.progress = newP
                            updateValueText(limit, itemView.context)
                            onLimitChanged(item.isUpload, limit)
                        }
                        true
                    }
                    else -> false
                }
            }

            itemView.setOnClickListener {
                val currentP = steps.indexOf(item.limitBytes).takeIf { it >= 0 } ?: 0
                val newP = (currentP + 1) % steps.size
                val limit = steps[newP]
                item.limitBytes = limit
                seekBar.progress = newP
                updateValueText(limit, itemView.context)
                onLimitChanged(item.isUpload, limit)
            }
        }

        private fun updateValueText(limitBytes: Long, ctx: android.content.Context) {
            tvValue.text = if (limitBytes <= 0) ctx.getString(R.string.speed_unlimited)
                           else formatSpeed(limitBytes)
            tvValue.setTextColor(
                if (limitBytes <= 0) 0xFF81C784.toInt() else 0xFFFFB74D.toInt()
            )
        }

        private fun formatSpeed(bps: Long) = when {
            bps < 1024       -> "$bps B/s"
            bps < 1_048_576  -> "${bps / 1024} KB/s"
            else             -> "${bps / 1_048_576} MB/s"
        }
    }
}
