package com.jamamjadalone.httpsecuretunnel.service

import java.nio.ByteBuffer
import java.nio.ByteOrder

class PacketProcessor {
    
    companion object {
        // IP Header offsets
        const val IP_VERSION_OFFSET = 0
        const val IP_PROTOCOL_OFFSET = 9
        const val IP_SRC_ADDR_OFFSET = 12
        const val IP_DEST_ADDR_OFFSET = 16
        const val IP_HEADER_LENGTH_OFFSET = 0
        const val IP_TOTAL_LENGTH_OFFSET = 2
        
        // TCP Header offsets
        const val TCP_SRC_PORT_OFFSET = 0
        const val TCP_DEST_PORT_OFFSET = 2
        const val TCP_SEQ_OFFSET = 4
        const val TCP_ACK_OFFSET = 8
        const val TCP_FLAGS_OFFSET = 13
        const val TCP_WINDOW_OFFSET = 14
        const val TCP_HEADER_LENGTH_OFFSET = 12
        
        // TCP Flags
        const val TCP_FIN = 0x01
        const val TCP_SYN = 0x02
        const val TCP_RST = 0x04
        const val TCP_PSH = 0x08
        const val TCP_ACK = 0x10
    }
    
    data class IPHeader(
        val version: Int,
        val headerLength: Int,
        val protocol: Int,
        val sourceAddress: String,
        val destinationAddress: String,
        val totalLength: Int
    )
    
    data class TCPHeader(
        val sourcePort: Int,
        val destinationPort: Int,
        val sequenceNumber: Long,
        val acknowledgmentNumber: Long,
        val flags: Int,
        val windowSize: Int,
        val headerLength: Int
    ) {
        fun isSYN(): Boolean = (flags and TCP_SYN) != 0
        fun isACK(): Boolean = (flags and TCP_ACK) != 0
        fun isFIN(): Boolean = (flags and TCP_FIN) != 0
        fun isRST(): Boolean = (flags and TCP_RST) != 0
    }
    
    fun parseIPHeader(packet: ByteArray, length: Int): IPHeader? {
        if (length < 20) return null
        
        val versionAndLength = packet[IP_VERSION_OFFSET].toInt() and 0xFF
        val version = (versionAndLength and 0xF0) shr 4
        val headerLength = (versionAndLength and 0x0F) * 4
        
        if (headerLength < 20 || headerLength > length) return null
        
        val protocol = packet[IP_PROTOCOL_OFFSET].toInt() and 0xFF
        val totalLength = ((packet[IP_TOTAL_LENGTH_OFFSET].toInt() and 0xFF) shl 8) or 
                         (packet[IP_TOTAL_LENGTH_OFFSET + 1].toInt() and 0xFF)
        
        val sourceAddr = String.format("%d.%d.%d.%d",
            packet[IP_SRC_ADDR_OFFSET].toInt() and 0xFF,
            packet[IP_SRC_ADDR_OFFSET + 1].toInt() and 0xFF,
            packet[IP_SRC_ADDR_OFFSET + 2].toInt() and 0xFF,
            packet[IP_SRC_ADDR_OFFSET + 3].toInt() and 0xFF
        )
        
        val destAddr = String.format("%d.%d.%d.%d",
            packet[IP_DEST_ADDR_OFFSET].toInt() and 0xFF,
            packet[IP_DEST_ADDR_OFFSET + 1].toInt() and 0xFF,
            packet[IP_DEST_ADDR_OFFSET + 2].toInt() and 0xFF,
            packet[IP_DEST_ADDR_OFFSET + 3].toInt() and 0xFF
        )
        
        return IPHeader(
            version = version,
            headerLength = headerLength,
            protocol = protocol,
            sourceAddress = sourceAddr,
            destinationAddress = destAddr,
            totalLength = totalLength
        )
    }
    
    fun parseTCPHeader(packet: ByteArray, ipHeaderLength: Int, totalLength: Int): TCPHeader? {
        val tcpStart = ipHeaderLength
        if (totalLength < tcpStart + 20) return null
        
        val sourcePort = ((packet[tcpStart + TCP_SRC_PORT_OFFSET].toInt() and 0xFF) shl 8) or
                        (packet[tcpStart + TCP_SRC_PORT_OFFSET + 1].toInt() and 0xFF)
        
        val destPort = ((packet[tcpStart + TCP_DEST_PORT_OFFSET].toInt() and 0xFF) shl 8) or
                      (packet[tcpStart + TCP_DEST_PORT_OFFSET + 1].toInt() and 0xFF)
        
        val seqNumber = ByteBuffer.wrap(packet, tcpStart + TCP_SEQ_OFFSET, 4)
            .order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
        
        val ackNumber = ByteBuffer.wrap(packet, tcpStart + TCP_ACK_OFFSET, 4)
            .order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
        
        val flags = packet[tcpStart + TCP_FLAGS_OFFSET].toInt() and 0xFF
        
        val window = ((packet[tcpStart + TCP_WINDOW_OFFSET].toInt() and 0xFF) shl 8) or
                    (packet[tcpStart + TCP_WINDOW_OFFSET + 1].toInt() and 0xFF)
        
        val dataOffset = (packet[tcpStart + TCP_HEADER_LENGTH_OFFSET].toInt() and 0xF0) shr 4
        val tcpHeaderLength = dataOffset * 4
        
        return TCPHeader(
            sourcePort = sourcePort,
            destinationPort = destPort,
            sequenceNumber = seqNumber,
            acknowledgmentNumber = ackNumber,
            flags = flags,
            windowSize = window,
            headerLength = tcpHeaderLength
        )
    }
    
    fun createTCPResetPacket(
        originalPacket: ByteArray, 
        originalLength: Int,
        ipHeader: IPHeader,
        tcpHeader: TCPHeader
    ): ByteArray {
        val packet = originalPacket.copyOf(originalLength)
        
        // Swap IP addresses
        System.arraycopy(packet, IP_SRC_ADDR_OFFSET, packet, IP_DEST_ADDR_OFFSET, 4)
        System.arraycopy(packet, IP_DEST_ADDR_OFFSET, packet, IP_SRC_ADDR_OFFSET, 4)
        
        // Swap TCP ports
        val tcpStart = ipHeader.headerLength
        System.arraycopy(packet, tcpStart + TCP_SRC_PORT_OFFSET, packet, tcpStart + TCP_DEST_PORT_OFFSET, 2)
        System.arraycopy(packet, tcpStart + TCP_DEST_PORT_OFFSET, packet, tcpStart + TCP_SRC_PORT_OFFSET, 2)
        
        // Set RST + ACK flags
        packet[tcpStart + TCP_FLAGS_OFFSET] = (TCP_RST or TCP_ACK).toByte()
        
        // Reset checksum (simplified)
        packet[10] = 0
        packet[11] = 0
        
        return packet
    }
}