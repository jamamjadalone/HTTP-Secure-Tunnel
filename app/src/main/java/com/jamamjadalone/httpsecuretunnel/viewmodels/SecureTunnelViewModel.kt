package com.jamamjadalone.httpsecuretunnel.viewmodels

import android.os.Bundle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ConnectionState(
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val showConfiguration: Boolean = true,
    val hasConnectedOnce: Boolean = false,
    val killSwitchActive: Boolean = false,
    val errorMessage: String? = null
)

data class ProxyConfig(
    val host: String = "192.168.1.100",
    val port: String = "8080",
    val username: String = "",
    val password: String = "",
    val alwaysOn: Boolean = false
)

class SecureTunnelViewModel : ViewModel() {
    private val _connectionState = MutableStateFlow(ConnectionState())
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _proxyConfig = MutableStateFlow(ProxyConfig())
    val proxyConfig: StateFlow<ProxyConfig> = _proxyConfig.asStateFlow()

    fun updateProxyConfig(
        host: String? = null,
        port: String? = null,
        username: String? = null,
        password: String? = null,
        alwaysOn: Boolean? = null
    ) {
        _proxyConfig.value = _proxyConfig.value.copy(
            host = host ?: _proxyConfig.value.host,
            port = port ?: _proxyConfig.value.port,
            username = username ?: _proxyConfig.value.username,
            password = password ?: _proxyConfig.value.password,
            alwaysOn = alwaysOn ?: _proxyConfig.value.alwaysOn
        )
    }

    fun setConnecting() {
        _connectionState.value = _connectionState.value.copy(
            isConnecting = true,
            isConnected = false,
            errorMessage = null
        )
    }

    fun setConnected() {
        _connectionState.value = _connectionState.value.copy(
            isConnected = true,
            isConnecting = false,
            hasConnectedOnce = true,
            showConfiguration = false,
            errorMessage = null
        )
    }

    fun setDisconnected(killSwitch: Boolean = false) {
        val currentState = _connectionState.value
        _connectionState.value = currentState.copy(
            isConnected = false,
            isConnecting = false,
            killSwitchActive = killSwitch && currentState.hasConnectedOnce,
            errorMessage = null
        )
    }

    fun setError(error: String) {
        _connectionState.value = _connectionState.value.copy(
            errorMessage = error,
            isConnecting = false,
            isConnected = false
        )
    }

    fun toggleConfigurationVisibility() {
        _connectionState.value = _connectionState.value.copy(
            showConfiguration = !_connectionState.value.showConfiguration
        )
    }

    fun disableKillSwitch() {
        _connectionState.value = _connectionState.value.copy(
            killSwitchActive = false
        )
    }

    fun clearError() {
        _connectionState.value = _connectionState.value.copy(
            errorMessage = null
        )
    }

    fun getProxyConfigForService(): Bundle {
        return Bundle().apply {
            putString("proxy_host", _proxyConfig.value.host)
            putInt("proxy_port", _proxyConfig.value.port.toIntOrNull() ?: 8080)
            putString("proxy_username", _proxyConfig.value.username)
            putString("proxy_password", _proxyConfig.value.password)
        }
    }
}