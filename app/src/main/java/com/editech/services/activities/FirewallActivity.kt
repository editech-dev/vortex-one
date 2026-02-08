package com.editech.services.activities

import android.content.Context
import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.editech.services.R
import com.editech.services.databinding.ActivityFirewallBinding
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.niunaijun.blackbox.BlackBoxCore
import com.editech.services.firewall.FirewallManager
import com.editech.services.firewall.FirewallState

/**
 * Firewall Activity
 * Manages network access control for virtualized applications.
 * Uses SharedPreferences for reliable state persistence.
 */
class FirewallActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "firewall_states"
        private const val KEY_PREFIX = "state_"
    }

    private lateinit var binding: ActivityFirewallBinding
    private lateinit var appsAdapter: FirewallAppsAdapter
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFirewallBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Initialize SharedPreferences for state persistence
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        setupRecyclerViews()
        setupButtons()
        loadBanner()
    }

    override fun onResume() {
        super.onResume()
        loadVirtualizedApps()
    }
    
    private fun loadBanner() {
        val bannerContainer = findViewById<RelativeLayout>(R.id.bannerContainer)
        if (bannerContainer != null) {
            com.editech.services.utils.AdManager.loadBanner(this, bannerContainer)
        }
    }

    private fun setupRecyclerViews() {
        appsAdapter = FirewallAppsAdapter(
            prefs = prefs,
            onStateChanged = { app, newState ->
                onFirewallStateChanged(app, newState)
            }
        )

        binding.rvFirewallApps.apply {
            layoutManager = LinearLayoutManager(this@FirewallActivity)
            adapter = appsAdapter
        }
    }

    private fun setupButtons() {
        binding.btnClose.setOnClickListener {
            finish()
        }
    }

    private fun loadVirtualizedApps() {
        binding.progressBar.visibility = View.VISIBLE
        binding.rvFirewallApps.visibility = View.GONE

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Use BlackBox directly
                val installedPackages = BlackBoxCore.get().getInstalledPackages(0, 0)
                val appList = mutableListOf<FirewallAppItem>()

                installedPackages?.forEach { packageInfo ->
                    try {
                        val pkg = packageInfo.packageName ?: return@forEach
                        val pm = packageManager
                        
                        val icon = try {
                            packageInfo.applicationInfo?.loadIcon(pm)
                        } catch (e: Exception) {
                            null
                        }
                        
                        val name = try {
                            packageInfo.applicationInfo?.loadLabel(pm)?.toString() ?: pkg
                        } catch (e: Exception) {
                            pkg
                        }

                        // Load state from FirewallManager directly, fallback to SharedPreferences
                        // Prioritize Manager state if available
                        val managerState = try {
                             FirewallManager.getInstance().getState(pkg)
                        } catch (e: Exception) {
                             FirewallState.DISABLED 
                        }
                        
                        // Also check prefs (UI persistence backup)
                        val prefOrdinal = prefs.getInt(KEY_PREFIX + pkg, -1)
                        
                        // Use manager state if not DISABLED, or if prefs matches
                        // If manager says DISABLED but prefs says MONITOR, we might want to sync?
                        // For display, use manager state as truth.
                        
                        appList.add(FirewallAppItem(
                            packageName = pkg,
                            appName = name,
                            icon = icon,
                            firewallState = managerState
                        ))
                    } catch (e: Exception) {
                        // Skip apps that fail to load
                    }
                }

                appList.sortBy { it.appName }

                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.rvFirewallApps.visibility = View.VISIBLE
                    appsAdapter.submitList(appList)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                }
            }
        }
    }

    private fun onFirewallStateChanged(app: FirewallAppItem, newState: FirewallState) {
        // Save to SharedPreferences (reliable persistence)
        prefs.edit().putInt(KEY_PREFIX + app.packageName, newState.ordinal).apply()
        
        // Call FirewallManager directly
        try {
            FirewallManager.getInstance().setState(app.packageName, newState)
            android.util.Log.d("FirewallActivity", "Set state ${newState} for ${app.packageName}")
        } catch (e: Exception) {
            android.util.Log.e("FirewallActivity", "Failed to set firewall state: ${e.message}")
        }
    }
}

/**
 * Data class for firewall app item
 */
data class FirewallAppItem(
    val packageName: String,
    val appName: String,
    val icon: Drawable?,
    var firewallState: FirewallState
)

/**
 * Adapter for virtualized apps with firewall controls
 */
class FirewallAppsAdapter(
    private val prefs: SharedPreferences,
    private val onStateChanged: (FirewallAppItem, FirewallState) -> Unit
) : RecyclerView.Adapter<FirewallAppsAdapter.ViewHolder>() {

    private var items = listOf<FirewallAppItem>()

    fun submitList(newList: List<FirewallAppItem>) {
        items = newList
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_firewall_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivIcon: ImageView = itemView.findViewById(R.id.ivAppIcon)
        private val tvName: TextView = itemView.findViewById(R.id.tvAppName)
        private val tvPackage: TextView = itemView.findViewById(R.id.tvPackageName)
        private val tvStateButton: TextView = itemView.findViewById(R.id.tvStateButton)

        fun bind(item: FirewallAppItem) {
            tvName.text = item.appName
            tvPackage.text = item.packageName

            item.icon?.let { ivIcon.setImageDrawable(it) }

            val states = arrayOf(
                itemView.context.getString(R.string.firewall_state_off),
                itemView.context.getString(R.string.firewall_state_monitor),
                itemView.context.getString(R.string.firewall_state_block)
            )

            // Map Bcore state to UI index
            // 0=Off, 1=Monitor, 2=Block
            val uiIndex = when (item.firewallState) {
                FirewallState.DISABLED -> 0
                FirewallState.MONITORING -> 1
                FirewallState.BLOCKING_ALL -> 2
                FirewallState.BLOCKING_PORTS -> 2 // Treat partial block as block for simple UI
            }

            // Update button text with current state
            tvStateButton.text = states.getOrElse(uiIndex) { states[0] }

            // State button is just an indicator now, clicking anywhere opens details
            // tvStateButton.setOnClickListener { ... } - REMOVED

            // Click on card body opens Detail Activity
            val clickListener = View.OnClickListener {
                android.widget.Toast.makeText(itemView.context, "Opening ${item.appName} details...", android.widget.Toast.LENGTH_SHORT).show()
                val intent = android.content.Intent(itemView.context, FirewallAppDetailActivity::class.java).apply {
                    putExtra(FirewallAppDetailActivity.EXTRA_PACKAGE_NAME, item.packageName)
                    putExtra(FirewallAppDetailActivity.EXTRA_APP_NAME, item.appName)
                }
                itemView.context.startActivity(intent)
            }
            
            // Set listener on the card itself, but exclude the button area logically (button handles its own click)
            itemView.setOnClickListener(clickListener)
        }
    }
}



/**
 * Adapter for connection logs
 */

