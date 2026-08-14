package com.editech.services.activities

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AlertDialog
import com.editech.services.utils.LocaleHelper
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.editech.services.R
import com.editech.services.databinding.ActivityFirewallBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.niunaijun.blackbox.BlackBoxCore
import com.editech.services.firewall.FirewallManager
import com.editech.services.firewall.FirewallState

/**
 * Firewall Activity
 * Lists all virtualized apps and their firewall state.
 * Each item opens FirewallAppDetailActivity for per-app control.
 */
class FirewallActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "firewall_states"
        private const val KEY_PREFIX = "state_"
    }

    private lateinit var binding: ActivityFirewallBinding
    private lateinit var appsAdapter: FirewallAppsAdapter
    private lateinit var prefs: SharedPreferences

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFirewallBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
        appsAdapter = FirewallAppsAdapter(prefs) { app, newState ->
            onFirewallStateChanged(app, newState)
        }

        binding.rvFirewallApps.apply {
            layoutManager = LinearLayoutManager(this@FirewallActivity)
            adapter = appsAdapter
            // TV: allow items to get focus
            isFocusable = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
    }

    private fun setupButtons() {
        binding.btnClose.setOnClickListener { finish() }
    }



    private fun loadVirtualizedApps() {
        binding.progressBar.visibility = View.VISIBLE
        binding.rvFirewallApps.visibility = View.GONE

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val installedPackages = BlackBoxCore.get().getInstalledPackages(0, 0)
                val appList = mutableListOf<FirewallAppItem>()

                installedPackages?.forEach { packageInfo ->
                    try {
                        val pkg  = packageInfo.packageName ?: return@forEach

                        // Ocultar paquetes de infraestructura de Google del Firewall
                        if (top.niunaijun.blackbox.core.GmsCore.isGoogleAppOrService(pkg)) {
                            return@forEach
                        }

                        val pm   = packageManager
                        val icon = try { packageInfo.applicationInfo?.loadIcon(pm) } catch (e: Exception) { null }
                        val name = try { packageInfo.applicationInfo?.loadLabel(pm)?.toString() ?: pkg } catch (e: Exception) { pkg }
                        val state = try { FirewallManager.getInstance().getState(pkg) } catch (e: Exception) { FirewallState.DISABLED }

                        appList.add(FirewallAppItem(pkg, name, icon, state))
                    } catch (e: Exception) { /* skip */ }
                }

                appList.sortBy { it.appName }

                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.rvFirewallApps.visibility = View.VISIBLE
                    // Bug #10 fix: DiffUtil prevents scroll reset on resume
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
        prefs.edit().putInt(KEY_PREFIX + app.packageName, newState.ordinal).apply()
        try {
            FirewallManager.getInstance().setState(app.packageName, newState)
        } catch (e: Exception) {
            android.util.Log.e("FirewallActivity", "Failed to set state: ${e.message}")
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Data class
// ─────────────────────────────────────────────────────────────────────────────

data class FirewallAppItem(
    val packageName: String,
    val appName: String,
    val icon: Drawable?,
    var firewallState: FirewallState
)

// ─────────────────────────────────────────────────────────────────────────────
// FirewallAppsAdapter — Bug #10: DiffUtil + visual state badge
// ─────────────────────────────────────────────────────────────────────────────

class FirewallAppsAdapter(
    private val prefs: SharedPreferences,
    private val onStateChanged: (FirewallAppItem, FirewallState) -> Unit
) : RecyclerView.Adapter<FirewallAppsAdapter.ViewHolder>() {

    private var items = listOf<FirewallAppItem>()

    /** DiffUtil prevents scroll reset and reduces flicker (Bug #10) */
    fun submitList(newList: List<FirewallAppItem>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = items.size
            override fun getNewListSize() = newList.size
            override fun areItemsTheSame(o: Int, n: Int) =
                items[o].packageName == newList[n].packageName
            override fun areContentsTheSame(o: Int, n: Int) =
                items[o].firewallState == newList[n].firewallState &&
                items[o].appName == newList[n].appName
        })
        items = newList
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_firewall_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(items[position])

    override fun getItemCount() = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivIcon: ImageView   = itemView.findViewById(R.id.ivAppIcon)
        private val tvName: TextView    = itemView.findViewById(R.id.tvAppName)
        private val tvPackage: TextView = itemView.findViewById(R.id.tvPackageName)
        private val tvState: TextView   = itemView.findViewById(R.id.tvStateButton)

        fun bind(item: FirewallAppItem) {
            tvName.text    = item.appName
            tvPackage.text = item.packageName
            item.icon?.let { ivIcon.setImageDrawable(it) }

            // Visual state badge with color (visible indicator)
            val (label, bgColor, textColor) = when (item.firewallState) {
                FirewallState.DISABLED       -> Triple(itemView.context.getString(R.string.state_unprotected), 0xFF374151.toInt(), 0xFF94A3B8.toInt())
                FirewallState.MONITORING     -> Triple(itemView.context.getString(R.string.status_monitoring),  0xFF1E3A5F.toInt(), 0xFF60A5FA.toInt())
                FirewallState.BLOCKING_PORTS -> Triple(itemView.context.getString(R.string.state_active_ports), 0xFF3D2000.toInt(), 0xFFFFB74D.toInt())
                FirewallState.BLOCKING_ALL   -> Triple(itemView.context.getString(R.string.status_blocked),     0xFF3B0000.toInt(), 0xFFEF9A9A.toInt())
            }
            tvState.text = label
            tvState.setBackgroundColor(bgColor)
            tvState.setTextColor(textColor)

            // Full card opens detail activity
            itemView.setOnClickListener {
                val intent = android.content.Intent(
                    itemView.context, FirewallAppDetailActivity::class.java
                ).apply {
                    putExtra(FirewallAppDetailActivity.EXTRA_PACKAGE_NAME, item.packageName)
                    putExtra(FirewallAppDetailActivity.EXTRA_APP_NAME, item.appName)
                }
                itemView.context.startActivity(intent)
            }
        }
    }
}
