package com.jamamjadalone.httpsecuretunnel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jamamjadalone.httpsecuretunnel.service.ProxyVpnService
import com.jamamjadalone.httpsecuretunnel.ui.theme.HTTPSecureTunnelTheme
import com.jamamjadalone.httpsecuretunnel.viewmodels.SecureTunnelViewModel

class MainActivity : ComponentActivity() {
    private val vpnPreparationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startProxyVpnService()
        } else {
            viewModel?.setDisconnected()
            viewModel?.setError("VPN permission denied")
        }
    }

    private var viewModel: SecureTunnelViewModel? = null
    private val vpnReceiver = VpnStatusReceiver()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val filter = IntentFilter().apply {
            addAction("VPN_CONNECTION_STATUS")
        }
        registerReceiver(vpnReceiver, filter)

        setContent {
            HTTPSecureTunnelTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val vm: SecureTunnelViewModel = viewModel()
                    viewModel = vm

                    SecureTunnelScreen(
                        viewModel = vm,
                        onConnectClick = { prepareVpn() },
                        onDisconnectClick = { disconnectVpn() }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(vpnReceiver)
    }

    private fun prepareVpn() {
        viewModel?.setConnecting()
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPreparationLauncher.launch(intent)
        } else {
            startProxyVpnService()
        }
    }

    private fun startProxyVpnService() {
        val config = viewModel?.getProxyConfigForService()
        val intent = Intent(this, ProxyVpnService::class.java).apply {
            action = ProxyVpnService.ACTION_CONNECT
            putExtras(config ?: Bundle())
        }
        startService(intent)
    }

    private fun disconnectVpn() {
        val intent = Intent(this, ProxyVpnService::class.java).apply {
            action = ProxyVpnService.ACTION_DISCONNECT
        }
        startService(intent)
        viewModel?.setDisconnected(killSwitch = true)
    }

    inner class VpnStatusReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "VPN_CONNECTION_STATUS" -> {
                    val connected = intent.getBooleanExtra("connected", false)
                    if (connected) {
                        viewModel?.setConnected()
                    } else {
                        val error = intent.getStringExtra("error")
                        viewModel?.setDisconnected(killSwitch = true)
                        error?.let { viewModel?.setError(it) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecureTunnelScreen(
    viewModel: SecureTunnelViewModel,
    onConnectClick: () -> Unit = {},
    onDisconnectClick: () -> Unit = {}
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val proxyConfig by viewModel.proxyConfig.collectAsState()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "HTTP Secure Tunnel",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    connectionState.isConnected -> MaterialTheme.colorScheme.primaryContainer
                    connectionState.isConnecting -> MaterialTheme.colorScheme.secondaryContainer
                    else -> MaterialTheme.colorScheme.errorContainer
                }
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Status:",
                    style = MaterialTheme.typography.labelMedium
                )
                Text(
                    text = when {
                        connectionState.isConnected -> "Connected - All Traffic Secured"
                        connectionState.isConnecting -> "Connecting…"
                        else -> "Disconnected"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    color = when {
                        connectionState.isConnected -> MaterialTheme.colorScheme.onPrimaryContainer
                        connectionState.isConnecting -> MaterialTheme.colorScheme.onSecondaryContainer
                        else -> MaterialTheme.colorScheme.onErrorContainer
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                if (connectionState.isConnected) {
                    onDisconnectClick()
                } else {
                    onConnectClick()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ),
            enabled = !connectionState.isConnecting
        ) {
            if (connectionState.isConnecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Connecting...")
            } else {
                Text(
                    text = if (connectionState.isConnected) "DISCONNECT" else "CONNECT",
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        connectionState.errorMessage?.let { error ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(16.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        if (connectionState.hasConnectedOnce) {
            TextButton(
                onClick = { viewModel.toggleConfigurationVisibility() }
            ) {
                Text(
                    text = if (connectionState.showConfiguration) "Hide Configuration" else "Show Configuration",
                    color = MaterialTheme.colorScheme.primary
                )
                Icon(
                    imageVector = if (connectionState.showConfiguration) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        if (connectionState.showConfiguration || !connectionState.hasConnectedOnce) {
            ConfigurationSection(
                proxyConfig = proxyConfig,
                onConfigUpdate = viewModel::updateProxyConfig
            )
        }

        if (connectionState.killSwitchActive) {
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                border = CardDefaults.cardBorder(
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "🔒 Kill Switch Active",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Internet Connection Blocked for Security. Connect to Proxy to Restore Internet.",
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { viewModel.disableKillSwitch() },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("OK")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "✓ Captures ALL Android app traffic\n✓ Always-on VPN supported\n✓ Kill switch enabled\n✓ HTTP/HTTPS proxy tunneling",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigurationSection(
    proxyConfig: com.jamamjadalone.httpsecuretunnel.viewmodels.ProxyConfig,
    onConfigUpdate: (
        host: String?,
        port: String?,
        username: String?,
        password: String?,
        alwaysOn: Boolean?
    ) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Proxy Configuration",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            OutlinedTextField(
                value = proxyConfig.host,
                onValueChange = { onConfigUpdate(it, null, null, null, null) },
                label = { Text("Proxy Host") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("e.g., 192.168.1.100 or proxy.example.com") }
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = proxyConfig.port,
                onValueChange = { onConfigUpdate(null, it, null, null, null) },
                label = { Text("Proxy Port") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                placeholder = { Text("e.g., 8080") }
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = proxyConfig.username,
                onValueChange = { onConfigUpdate(null, null, it, null, null) },
                label = { Text("Username (Optional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = proxyConfig.password,
                onValueChange = { onConfigUpdate(null, null, null, it, null) },
                label = { Text("Password (Optional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Switch(
                    checked = proxyConfig.alwaysOn,
                    onCheckedChange = { onConfigUpdate(null, null, null, null, it) }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Always-on VPN (Enable in Android Settings)",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}