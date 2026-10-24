package com.yourname.torproxychain

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
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
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {

    /** Only the user's own hops. Tor is controlled by the switch and always goes first. */
    private val chain = mutableListOf<ProxyHop>()
    private lateinit var adapter: ProxyAdapter
    private lateinit var orb: TextView
    private lateinit var statusText: TextView
    private lateinit var subText: TextView
    private lateinit var emptyText: TextView
    private lateinit var torSwitch: Switch
    private var permissionDenied = false

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
            permissionDenied = true
            refreshStatus()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* the VPN works either way; the notification just may be hidden */ }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun col(id: Int) = ContextCompat.getColor(this, id)

    private fun roundedBg(fill: Int, radiusDp: Int = 16) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun ovalBg(fill: Int, stroke: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        setStroke(dp(4), stroke)
    }

    private fun pillButton(label: String, fill: Int, textColor: Int, onClick: () -> Unit) =
        MaterialButton(this).apply {
            text = label
            isAllCaps = false
            setTextColor(textColor)
            backgroundTintList = ColorStateList.valueOf(fill)
            cornerRadius = dp(24)
            setOnClickListener { onClick() }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val saved = ProxyStore.loadChain(this)
        chain.addAll(saved.filter { !it.isTor })

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(col(R.color.bg))
            setPadding(dp(20), dp(40), dp(20), dp(16))
        }

        // Title
        root.addView(TextView(this).apply {
            text = "Tor Proxy Chain"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(col(R.color.text))
        })

        // Big round connect button, Orbot style
        orb = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.12f
            isClickable = true
            isFocusable = true
            setOnClickListener { onOrbClicked() }
        }
        root.addView(orb, LinearLayout.LayoutParams(dp(170), dp(170)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(24)
            bottomMargin = dp(16)
        })

        statusText = TextView(this).apply {
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(col(R.color.text))
        }
        root.addView(statusText)

        subText = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(16))
            setTextColor(col(R.color.text_dim))
        }
        root.addView(subText)

        // Tor card
        val torCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(col(R.color.surface))
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        torSwitch = Switch(this).apply {
            text = "Route through Tor first"
            textSize = 16f
            setTextColor(col(R.color.text))
            isChecked = saved.any { it.isTor }
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(col(R.color.purple_light), col(R.color.text_dim)))
            trackTintList = ColorStateList(states, intArrayOf(col(R.color.purple), col(R.color.surface_high)))
            setOnCheckedChangeListener { _, _ -> persist(); refreshStatus() }
        }
        torCard.addView(torSwitch)
        torCard.addView(TextView(this).apply {
            text = "Requires the Orbot app. Order: Tor \u2192 hop 1 \u2192 hop 2 \u2192 destination."
            textSize = 12f
            setPadding(0, dp(4), 0, 0)
            setTextColor(col(R.color.text_dim))
        })
        root.addView(torCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // Chain list
        root.addView(TextView(this).apply {
            text = "PROXY CHAIN"
            textSize = 12f
            letterSpacing = 0.1f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), dp(20), 0, dp(8))
            setTextColor(col(R.color.purple_light))
        })

        emptyText = TextView(this).apply {
            text = "No proxies added yet. Long-press a hop to move or remove it."
            textSize = 14f
            setPadding(dp(4), 0, 0, dp(8))
            setTextColor(col(R.color.text_dim))
        }
        root.addView(emptyText)

        adapter = ProxyAdapter(chain) { pos -> showHopMenu(pos) }
        val recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
            clipToPadding = false
        }
        root.addView(recycler, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Bottom actions
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        actions.addView(
            pillButton("Add proxy", col(R.color.purple), col(R.color.text)) { showAddProxyDialog() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) }
        )
        actions.addView(
            pillButton("Preset", col(R.color.surface_high), col(R.color.text)) { showPresetDialog() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) }
        )
        root.addView(actions)

        setContentView(root)
        updateEmpty()
        refreshStatus()
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

    private fun onOrbClicked() {
        when (TorVpnService.state) {
            TorVpnService.State.RUNNING, TorVpnService.State.STARTING -> {
                stopService(Intent(this, TorVpnService::class.java))
                refreshStatus()
            }
            else -> {
                permissionDenied = false
                onConnectClicked()
            }
        }
    }

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
        refreshStatus()
    }

    private fun chainSummary(): String {
        val parts = (if (torSwitch.isChecked) listOf("Tor") else emptyList()) + chain.map { it.label }
        return if (parts.isEmpty()) "Direct connection" else parts.joinToString(" \u2192 ")
    }

    private fun refreshStatus() {
        when (TorVpnService.state) {
            TorVpnService.State.STOPPED -> {
                if (permissionDenied) {
                    statusText.text = "VPN permission denied"
                    subText.text = "Allow the VPN request to continue"
                } else {
                    statusText.text = "Not connected"
                    subText.text = "Tap the button to start"
                }
                orb.text = "START"
                orb.setTextColor(col(R.color.text))
                orb.background = ovalBg(col(R.color.surface_high), col(R.color.purple))
            }
            TorVpnService.State.STARTING -> {
                statusText.text = "Connecting\u2026"
                subText.text =
                    if (torSwitch.isChecked) "Waiting for Tor, this can take a minute" else "Starting\u2026"
                orb.text = "CANCEL"
                orb.setTextColor(col(R.color.amber))
                orb.background = ovalBg(col(R.color.surface_high), col(R.color.amber))
            }
            TorVpnService.State.RUNNING -> {
                statusText.text = "Connected"
                subText.text = chainSummary()
                orb.text = "STOP"
                orb.setTextColor(col(R.color.text))
                orb.background = ovalBg(col(R.color.purple), col(R.color.green))
            }
            TorVpnService.State.ERROR -> {
                statusText.text = "Connection error"
                subText.text = TorVpnService.lastError ?: "unknown"
                orb.text = "RETRY"
                orb.setTextColor(col(R.color.red))
                orb.background = ovalBg(col(R.color.surface_high), col(R.color.red))
            }
        }
    }

    private fun updateEmpty() {
        emptyText.visibility = if (chain.isEmpty()) View.VISIBLE else View.GONE
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
                updateEmpty()
                persist()
            }
            .show()
    }

    private fun showAddProxyDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(10))
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
            updateEmpty()
            persist()
        }
    }

    private fun showPresetDialog() {
        // Free public proxy lists rotate constantly and are often unreliable or malicious
        // (logging / injecting into your traffic). Wire this to a source you trust, or skip it.
        Toast.makeText(
            this,
            "Hook this button up to a proxy-list provider you trust, or use 'Add proxy'.",
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
        val ctx = parent.context
        val d = ctx.resources.displayMetrics.density
        val tv = TextView(ctx).apply {
            val pad = (16 * d).toInt()
            setPadding(pad, pad, pad, pad)
            textSize = 15f
            setTextColor(ContextCompat.getColor(ctx, R.color.text))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(ContextCompat.getColor(ctx, R.color.surface))
                cornerRadius = 16 * d
            }
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, (4 * d).toInt(), 0, (4 * d).toInt()) }
        }
        return VH(tv)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val hop = items[position]
        holder.tv.text = "${position + 1}. ${hop.label}\n${hop.type} ${hop.host}:${hop.port}"
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
