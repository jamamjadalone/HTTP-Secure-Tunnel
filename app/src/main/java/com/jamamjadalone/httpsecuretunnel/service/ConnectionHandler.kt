package com.jamamjadalone.httpsecuretunnel.service

import android.util.Log
import kotlinx.coroutines.*
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class ConnectionHandler(
    private val vpnOutput: FileOutputStream,
    private val ipHeader: PacketProcessor.IPHeader,
    private val tcpHeader: PacketProcessor.TCPHeader,
    private val proxyHost: String,
    private val proxyPort: Int,
    private val proxyUsername: String,
    private val proxyPassword: String,
    private val vpnService: ProxyVpnService
) {
    private val TAG = "ConnectionHandler"
    private val isRunning = AtomicBoolean(false)
    private var proxySocket: Socket? = null
    private var connectionKey = "${ipHeader.sourceAddress}:${tcpHeader.sourcePort}-${ipHeader.destinationAddress}:${tcpHeader.destinationPort}"
    
    suspend fun startTunnel() {
        if (isRunning.get()) return
        isRunning.set(true)
        
        Log.d(TAG, "Starting tunnel for $connectionKey")
        
        try {
            proxySocket = Socket().apply {
                connect(InetSocketAddress(proxyHost, proxyPort), 10000)
            }
            
            vpnService.protect(proxySocket!!)
            
            if (performHttpConnect()) {
                Log.d(TAG, "HTTP CONNECT successful for ${ipHeader.destinationAddress}:${tcpHeader.destinationPort}")
                startDataForwarding()
            } else {
                Log.e(TAG, "HTTP CONNECT failed")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish proxy connection: ${e.message}", e)
        } finally {
            close()
        }
    }
    
    private suspend fun performHttpConnect(): Boolean {
        val socket = proxySocket ?: return false
        val outputStream = socket.getOutputStream()
        val inputStream = socket.getInputStream()
        
        try {
            val targetHost = ipHeader.destinationAddress
            val targetPort = tcpHeader.destinationPort
            
            val connectRequest = buildString {
                append("CONNECT $targetHost:$targetPort HTTP/1.1\r\n")
                append("Host: $targetHost:$targetPort\r\n")
                if (proxyUsername.isNotEmpty() && proxyPassword.isNotEmpty()) {
                    val credentials = "$proxyUsername:$proxyPassword"
                    val encoded = android.util.Base64.encodeToString(
                        credentials.toByteArray(), 
                        android.util.Base64.NO_WRAP
                    )
                    append("Proxy-Authorization: Basic $encoded\r\n")
                }
                append("Connection: keep-alive\r\n")
                append("\r\n")
            }
            
            outputStream.write(connectRequest.toByteArray())
            outputStream.flush()
            
            val responseBuffer = ByteArray(4096)
            val bytesRead = withTimeout(10000L) {
                inputStream.read(responseBuffer)
            }
            
            if (bytesRead > 0) {
                val response = String(responseBuffer, 0, bytesRead)
                Log.d(TAG, "Proxy response: ${response.take(200)}...")
                
                return response.startsWith("HTTP/1.1 200") || response.contains("200 Connection established")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during HTTP CONNECT: ${e.message}", e)
        }
        
        return false
    }
    
    private suspend fun startDataForwarding() {
        val socket = proxySocket ?: return
        val proxyInput = socket.getInputStream()
        val proxyOutput = socket.getOutputStream()
        
        val clientToProxy = CoroutineScope(Dispatchers.IO).launch {
            forwardClientToProxy(proxyOutput)
        }
        
        val proxyToClient = CoroutineScope(Dispatchers.IO).launch {
            forwardProxyToClient(proxyInput)
        }
        
        try {
            withTimeout(300000L) {
                listOf(clientToProxy, proxyToClient).joinAll()
            }
        } catch (e: TimeoutCancellationException) {
            Log.d(TAG, "Connection timeout for $connectionKey")
        } catch (e: Exception) {
            Log.d(TAG, "Connection error: ${e.message}")
        } finally {
            clientToProxy.cancel()
            proxyToClient.cancel()
            close()
        }
    }
    
    private suspend fun forwardClientToProxy(proxyOutput: OutputStream) {
        Log.d(TAG, "Starting client->proxy forwarding")
        // Data forwarding implementation would go here
    }
    
    private suspend fun forwardProxyToClient(proxyInput: InputStream) {
        Log.d(TAG, "Starting proxy->client forwarding")
        
        val buffer = ByteArray(4096)
        try {
            while (isRunning.get()) {
                val bytesRead = withTimeout(30000L) {
                    proxyInput.read(buffer)
                }
                
                if (bytesRead == -1) {
                    Log.d(TAG, "Proxy closed connection")
                    break
                }
                
                if (bytesRead > 0) {
                    Log.d(TAG, "Forwarding $bytesRead bytes to client")
                }
            }
        } catch (e: Exception) {
            if (isRunning.get()) {
                Log.e(TAG, "Error in proxy->client forwarding", e)
            }
        }
    }
    
    fun handleIncomingData(packet: ByteArray, length: Int) {
        Log.d(TAG, "Received $length bytes from client")
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val payload = extractTCPPayload(packet, length)
                if (payload.isNotEmpty()) {
                    proxySocket?.getOutputStream()?.write(payload)
                    proxySocket?.getOutputStream()?.flush()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error forwarding client data to proxy", e)
                close()
            }
        }
    }
    
    private fun extractTCPPayload(packet: ByteArray, length: Int): ByteArray {
        val ipHeaderLength = (packet[0].toInt() and 0x0F) * 4
        val tcpHeaderLength = ((packet[ipHeaderLength + 12].toInt() and 0xF0) shr 4) * 4
        val payloadStart = ipHeaderLength + tcpHeaderLength
        
        if (payloadStart >= length) {
            return ByteArray(0)
        }
        
        return packet.copyOfRange(payloadStart, length)
    }
    
    fun close() {
        if (!isRunning.getAndSet(false)) return
        
        Log.d(TAG, "Closing connection $connectionKey")
        
        try {
            proxySocket?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing proxy socket", e)
        }
        
        proxySocket = null
        vpnService.removeConnection(connectionKey)
    }
}