package com.editech.services.activities

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.editech.services.R
import com.editech.services.tor.TorManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial

class TorFragment : Fragment() {

    private var packageName: String = ""
    private lateinit var switchTorEnable: SwitchMaterial
    private lateinit var tvTorStatus: TextView
    private lateinit var torStatusIndicator: View
    private lateinit var btnNewIdentity: MaterialButton

    companion object {
        private const val ARG_PKG = "pkg_name"

        fun newInstance(packageName: String): TorFragment {
            val fragment = TorFragment()
            val args = Bundle()
            args.putString(ARG_PKG, packageName)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        packageName = arguments?.getString(ARG_PKG) ?: ""
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_tor, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        switchTorEnable = view.findViewById(R.id.switchTorEnable)
        tvTorStatus = view.findViewById(R.id.tvTorStatus)
        torStatusIndicator = view.findViewById(R.id.torStatusIndicator)
        btnNewIdentity = view.findViewById(R.id.btnNewIdentity)

        // Initial switch state
        val isEnabled = TorManager.isTorEnabled(packageName)
        switchTorEnable.isChecked = isEnabled

        val appName = try {
            val pm = requireContext().packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            packageName.substringAfterLast('.')
        }
        switchTorEnable.text = getString(R.string.tor_enable_for_app, appName)

        val cardMain = view.findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardMain)
        switchTorEnable.setOnFocusChangeListener { _, hasFocus ->
            cardMain?.strokeWidth = if (hasFocus) 3 else 0
            cardMain?.strokeColor = if (hasFocus) 0xFF38BDF8.toInt() else android.graphics.Color.TRANSPARENT
        }

        val cardControls = view.findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardControls)
        btnNewIdentity.setOnFocusChangeListener { _, hasFocus ->
            cardControls?.strokeWidth = if (hasFocus) 3 else 0
            cardControls?.strokeColor = if (hasFocus) 0xFF38BDF8.toInt() else android.graphics.Color.TRANSPARENT
        }

        switchTorEnable.setOnCheckedChangeListener { _, checked ->
            TorManager.setTorEnabled(packageName, checked)
        }

        btnNewIdentity.setOnClickListener {
            TorManager.requestNewIdentity()
        }

        // Observe Tor status LiveData
        TorManager.status.observe(viewLifecycleOwner) { status ->
            updateStatusUi(status)
        }
    }

    /** TV D-pad focus helpers */
    fun focusFirstItemSynchronous(): Boolean {
        return if (::switchTorEnable.isInitialized) switchTorEnable.requestFocus() else false
    }

    fun focusFirstItem() {
        if (::switchTorEnable.isInitialized) {
            switchTorEnable.post {
                switchTorEnable.requestFocus()
            }
        }
    }

    private fun updateStatusUi(status: TorManager.TorStatus) {
        val (textRes, indicatorColor, textColor) = when (status) {
            TorManager.TorStatus.RUNNING -> Triple(
                R.string.tor_status_running,
                0xFF81C784.toInt(), // Green
                0xFF81C784.toInt()
            )
            TorManager.TorStatus.STARTING -> Triple(
                R.string.tor_status_starting,
                0xFFFFB74D.toInt(), // Orange
                0xFFFFB74D.toInt()
            )
            TorManager.TorStatus.STOPPED -> Triple(
                R.string.tor_status_stopped,
                0xFF90A4AE.toInt(), // Grey
                0xFF90A4AE.toInt()
            )
            TorManager.TorStatus.ERROR -> Triple(
                R.string.tor_status_error,
                0xFFE57373.toInt(), // Red
                0xFFE57373.toInt()
            )
        }

        tvTorStatus.setText(textRes)
        tvTorStatus.setTextColor(textColor)
        torStatusIndicator.setBackgroundColor(indicatorColor)
        btnNewIdentity.isEnabled = (status == TorManager.TorStatus.RUNNING)
    }
}
