package com.yourname.torproxychain

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    /** Only the user's own hops. Tor is controlled by the switch and always goes first. */
    private val chain = mutableListOf<ProxyHop>()
    private lateinit var adapter: ProxyAdapter
    private lateinit var statusText: TextView
    private lateinit var torSwitch: Switch

    private val ui = Handler(Looper.getMainLooper())
    private val statusPoller = object : Runnable {
        override fun run() {
            refreshStatus()
            ui.postDelayed(this, 1000)
        }
    }

    private val vpnPrepareLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService()
        } else {
            statusText.text = "Status: VPN permission denied"
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* the VPN works either way; the notification just may be hidden */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val saved = ProxyStore.loadChain(this)
        chain.addAll(saved.filter { !it.isTor })

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 64, 32, 32)
        }

        statusText = TextView(this).apply { text = "Status: disconnected"; textSize = 16f }
        root.addView(statusText)

        torSwitch = Switch(this).apply {
            text = "Route through Tor first (requires Orbot)"
            isChecked = saved.any { it.isTor }
            setOnCheckedChangeListener { _, _ -> persist() }
        }
        root.addView(torSwitch)

        root.addView(TextView(this).apply {
            text = "Order: Tor (if on) → hop 1 → hop 2 → … → destination.\nLong-press a hop to move or remove it."
            textSize = 12f
            setPadding(0, 16, 0, 16)
        })

        adapter = ProxyAdapter(chain) { pos -> showHopMenu(pos) }
        val recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }
        root.addView(recycler, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(Button(this).apply {
            text = "Add private / custom proxy"
            setOnClickListener { showAddProxyDialog() }
        })

        root.addView(Button(this).apply {
            text = "Add free open-source proxy preset"
            setOnClickListener { showPresetDialog() }
        })

        root.addView(Button(this).apply {
            text = "Connect"
            setOnClickListener { onConnectClicked() }
        })

        root.addView(Button(this).apply {
            text = "Disconnect"
            setOnClickListener {
                stopService(Intent(this@MainActivity, TorVpnService::class.java))
                statusText.text = "Status: disconnected"
            }
        })

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        ui.post(statusPoller)
    }

    override fun onPause() {
        ui.removeCallbacks(statusPoller)
        super.onPause()
    }

    // ---- connect / status ----

    private fun onConnectClicked() {
        if (torSwitch.isChecked && !OrbotManager.isOrbotInstalled(this)) {
            Toast.makeText(this, "Install Orbot first", Toast.LENGTH_LONG).show()
            OrbotManager.openOrbotInstallPage(this)
            return
        }
        persist()

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) vpnPrepareLauncher.launch(prepareIntent) else startVpnService()
    }

    private fun startVpnService() {
        ContextCompat.startForegroundService(this, Intent(this, TorVpnService::class.java))
        statusText.text = "Status: starting…"
    }

    private fun refreshStatus() {
        statusText.text = when (TorVpnService.state) {
            TorVpnService.State.STOPPED -> "Status: disconnected"
            TorVpnService.State.STARTING ->
                if (torSwitch.isChecked) "Status: starting (waiting for Tor, can take a minute)…"
                else "Status: starting…"
            TorVpnService.State.RUNNING -> "Status: connected"
            TorVpnService.State.ERROR -> "Status: error - ${TorVpnService.lastError ?: "unknown"}"
        }
    }

    /** Saves the full chain (Tor first if enabled) so the service sees exactly what the UI shows. */
    private fun persist() {
        val full = (if (torSwitch.isChecked) listOf(ProxyHop.torHop()) else emptyList()) + chain
        ProxyStore.saveChain(this, full)
    }

    // ---- hop editing ----

    private fun showHopMenu(pos: Int) {
        if (pos !in chain.indices) return
        AlertDialog.Builder(this)
            .setTitle(chain[pos].label)
            .setItems(arrayOf("Move up", "Move down", "Remove")) { _, which ->
                when (which) {
                    0 -> if (pos > 0) { chain.add(pos - 1, chain.removeAt(pos)) }
                    1 -> if (pos < chain.lastIndex) { chain.add(pos + 1, chain.removeAt(pos)) }
                    2 -> chain.removeAt(pos)
                }
                adapter.notifyDataSetChanged()
                persist()
            }
            .show()
    }

    private fun showAddProxyDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }
        val label = EditText(this).apply { hint = "Label (e.g. My VPS)" }
        val host = EditText(this).apply { hint = "Host / IP" }
        val port = EditText(this).apply { hint = "Port"; inputType = InputType.TYPE_CLASS_NUMBER }
        val user = EditText(this).apply { hint = "Username (optional)" }
        val pass = EditText(this).apply {
            hint = "Password (optional)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val typeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                ProxyType.values().map { it.name }
            )
        }
        listOf(label, host, port, typeSpinner, user, pass).forEach { layout.addView(it) }

        AlertDialogHelper.show(this, "Add proxy", layout) {
            val h = host.text.toString().trim()
            val p = port.text.toString().toIntOrNull()
            if (h.isEmpty() || p == null || p !in 1..65535) {
                Toast.makeText(this, "Enter a host and a port between 1 and 65535", Toast.LENGTH_LONG).show()
                return@show
            }
            chain.add(
                ProxyHop(
                    label = label.text.toString().ifBlank { h },
                    type = ProxyType.valueOf(typeSpinner.selectedItem as String),
                    host = h,
                    port = p,
                    username = user.text.toString().ifBlank { null },
                    password = pass.text.toString().ifBlank { null }
                )
            )
            adapter.notifyDataSetChanged()
            persist()
        }
    }

    private fun showPresetDialog() {
        // Free public proxy lists rotate constantly and are often unreliable or malicious
        // (logging / injecting into your traffic). Wire this to a source you trust, or skip it.
        Toast.makeText(
            this,
            "Hook this button up to a proxy-list provider you trust, or use 'Add private / custom proxy'.",
            Toast.LENGTH_LONG
        ).show()
    }
}

class ProxyAdapter(
    private val items: List<ProxyHop>,
    private val onLongPress: (Int) -> Unit
) : RecyclerView.Adapter<ProxyAdapter.VH>() {
    class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val tv = TextView(parent.context).apply {
            setPadding(24, 24, 24, 24)
            textSize = 15f
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        return VH(tv)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val hop = items[position]
        holder.tv.text = "${position + 1}. ${hop.label} — ${hop.type} ${hop.host}:${hop.port}"
        // Use the live adapter position: `position` is stale after items are moved or removed.
        holder.tv.setOnLongClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onLongPress(pos)
            true
        }
    }

    override fun getItemCount() = items.size
}

object AlertDialogHelper {
    fun show(activity: Activity, title: String, view: android.view.View, onOk: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(view)
            .setPositiveButton("Add") { _, _ -> onOk() }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
