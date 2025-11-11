package com.jamamjadalone.httpsecuretunnel.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.jamamjadalone.httpsecuretunnel.MainActivity
import com.jamamjadalone.httpsecuretunnel.R
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class ProxyVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null
    private val isRunning = AtomicBoolean(false)
    private var packetHandlerJob: Job? = null
    private var notificationManager: NotificationManager? = null
    
    private val activeConnections = ConcurrentHashMap<String, ConnectionHandler>()
    
    private var proxyHost: String = "192.168.1.100"
    private var proxyPort: Int = 8080
    private var proxyUsername: String = ""
    private var proxyPassword: String = ""

    companion object {
        const val SESSION_NAME = "HTTP Secure Tunnel"
        const val VPN_ADDRESS = "10.8.0.2"
        const val VPN_ROUTE = "0.0.0.0"
        const val VPN_PREFIX_LENGTH = 0
        const val DNS_SERVER = "8.8.8.8"
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "vpn_service_channel"
        const val MTU = 1500
        const val TAG = "ProxyVpnService"
        
        const val ACTION_CONNECT = "connect"
        const val ACTION_DISCONNECT = "disconnect"
        const val ACTION_UPDATE_CONFIG = "update_config"
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        Log.d(TAG, "VPN Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> connect()
            ACTION_DISCONNECT -> disconnect()
            ACTION_UPDATE_CONFIG -> updateConfig(intent)
        }
        return START_STICKY
    }

    private fun updateConfig(intent: Intent) {
        proxyHost = intent.getStringExtra("proxy_host") ?: proxyHost
        proxyPort = intent.getIntExtra("proxy_port", proxyPort)
        proxyUsername = intent.getStringExtra("proxy_username") ?: proxyUsername
        proxyPassword = intent.getStringExtra("proxy_password") ?: proxyPassword
        Log.d(TAG, "Updated config: $proxyHost:$proxyPort")
    }

    private fun connect() {
        if (isRunning.get()) {
            Log.d(TAG, "Already running")
            return
        }

        Log.d(TAG, "Starting VPN connection")
        val builder = Builder()
        try {
            vpnInterface = builder
                .setSession(SESSION_NAME)
                .addAddress(VPN_ADDRESS, 24)
                .addRoute(VPN_ROUTE, VPN_PREFIX_LENGTH)
                .addDnsServer(DNS_SERVER)
                .setMtu(MTU)
                .setBlocking(true)
                .establish()

            startForeground(NOTIFICATION_ID, createNotification())
            isRunning.set(true)
            startPacketHandling()

            sendBroadcast(Intent("VPN_CONNECTION_STATUS").apply {
                putExtra("connected", true)
            })
            Log.d(TAG, "VPN started successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN: ${e.message}", e)
            sendBroadcast(Intent("VPN_CONNECTION_STATUS").apply {
                putExtra("connected", false)
                putExtra("error", e.message)
            })
            stopSelf()
        }
    }

    private fun disconnect() {
        Log.d(TAG, "Disconnecting VPN")
        isRunning.set(false)
        
        activeConnections.values.forEach { it.close() }
        activeConnections.clear()
        
        packetHandlerJob?.cancel()
        try {
            vpnInterface?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing VPN interface", e)
        }
        vpnInterface = null
        stopForeground(true)
        stopSelf()

        sendBroadcast(Intent("VPN_CONNECTION_STATUS").apply {
            putExtra("connected", false)
        })
        Log.d(TAG, "VPN disconnected")
    }

    private fun startPacketHandling() {
        packetHandlerJob = CoroutineScope(Dispatchers.IO).launch {
            handlePackets()
        }
    }

    private suspend fun handlePackets() {
        val vpnInput = vpnInterface?.fileDescriptor?.let { FileInputStream(it) }
        val vpnOutput = vpnInterface?.fileDescriptor?.let { FileOutputStream(it) }

        if (vpnInput == null || vpnOutput == null) {
            Log.e(TAG, "Failed to get VPN file descriptors")
            disconnect()
            return
        }

        val buffer = ByteArray(MTU)
        
        while (isRunning.get()) {
            try {
                val length = vpnInput.read(buffer)
                if (length > 0) {
                    processIpPacket(buffer, length, vpnOutput)
                }
            } catch (e: IOException) {
                if (isRunning.get()) {
                    Log.e(TAG, "Error reading from VPN interface", e)
                }
                break
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error in packet handling", e)
            }
        }
    }

    private fun processIpPacket(packet: ByteArray, length: Int, vpnOutput: FileOutputStream) {
        if (length < 20) return

        try {
            val ipHeader = PacketProcessor.parseIPHeader(packet, length)
            if (ipHeader == null) {
                Log.w(TAG, "Failed to parse IP header")
                return
            }

            when (ipHeader.protocol) {
                6 -> handleTcpPacket(packet, length, ipHeader, vpnOutput)
                17 -> handleUdpPacket(packet, length, ipHeader, vpnOutput)
                1 -> handleIcmpPacket(packet, length, ipHeader, vpnOutput)
                else -> {
                    Log.d(TAG, "Unhandled protocol: ${ipHeader.protocol}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing IP packet", e)
        }
    }

    private fun handleTcpPacket(
        packet: ByteArray,
        length: Int,
        ipHeader: PacketProcessor.IPHeader,
        vpnOutput: FileOutputStream
    ) {
        val tcpHeader = PacketProcessor.parseTCPHeader(packet, ipHeader.headerLength, length)
        if (tcpHeader == null) {
            Log.w(TAG, "Failed to parse TCP header")
            return
        }

        val connectionKey = "${ipHeader.sourceAddress}:${tcpHeader.sourcePort}-${ipHeader.destinationAddress}:${tcpHeader.destinationPort}"
        
        if (tcpHeader.isSYN() && !tcpHeader.isACK()) {
            Log.d(TAG, "New TCP connection to ${ipHeader.destinationAddress}:${tcpHeader.destinationPort}")
            
            val connectionHandler = ConnectionHandler(
                vpnOutput,
                ipHeader,
                tcpHeader,
                proxyHost,
                proxyPort,
                proxyUsername,
                proxyPassword,
                this
            )
            
            activeConnections[connectionKey] = connectionHandler
            CoroutineScope(Dispatchers.IO).launch {
                connectionHandler.startTunnel()
            }
        } else if (tcpHeader.isFIN() || tcpHeader.isRST()) {
            activeConnections.remove(connectionKey)?.close()
        } else {
            activeConnections[connectionKey]?.handleIncomingData(packet, length)
        }
    }

    private fun handleUdpPacket(
        packet: ByteArray,
        length: Int,
        ipHeader: PacketProcessor.IPHeader,
        vpnOutput: FileOutputStream
    ) {
        Log.d(TAG, "UDP packet to ${ipHeader.destinationAddress}")
    }

    private fun handleIcmpPacket(
        packet: ByteArray,
        length: Int,
        ipHeader: PacketProcessor.IPHeader,
        vpnOutput: FileOutputStream
    ) {
        Log.d(TAG, "ICMP packet from ${ipHeader.sourceAddress}")
    }

    fun removeConnection(key: String) {
        activeConnections.remove(key)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VPN Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "HTTP Secure Tunnel VPN connection"
                setShowBadge(false)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HTTP Secure Tunnel")
            .setContentText("Routing traffic through proxy")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        disconnect()
        super.onDestroy()
    }

    fun isServiceRunning(): Boolean = isRunning.get()
}