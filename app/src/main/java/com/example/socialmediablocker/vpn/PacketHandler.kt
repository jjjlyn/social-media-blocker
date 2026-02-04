package com.example.socialmediablocker.vpn

import android.util.Log
import com.example.socialmediablocker.data.repository.DomainRepository
import java.nio.ByteBuffer

/**
 * IP 패킷 처리 핸들러
 * DNS, HTTP, HTTPS 프로토콜별로 필터링
 */
class PacketHandler(private val domainRepository: DomainRepository) {
    
    private val dnsFilter = DnsFilter(domainRepository)
    
    /**
     * 패킷 처리
     * @return 전달할 패킷 (null이면 차단)
     */
    fun processPacket(packet: ByteBuffer): ByteBuffer? {
        return try {
            // Fail-safe: 에러 시 패킷을 그대로 통과 (네트워크 단절 방지)
            processPacketInternal(packet)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing packet, passing through", e)
            packet
        }
    }
    
    private fun processPacketInternal(packet: ByteBuffer): ByteBuffer? {
        packet.position(0)
        
        if (packet.remaining() < 20) {
            return packet // Too small to be valid IP packet
        }
        
        // IP 헤더 파싱
        val versionAndIHL = packet.get().toInt() and 0xFF
        val version = versionAndIHL shr 4
        
        if (version != 4) {
            return packet // IPv6 not supported yet
        }
        
        val ihl = (versionAndIHL and 0x0F) * 4
        
        // Skip to protocol field
        packet.position(9)
        val protocol = packet.get().toInt() and 0xFF
        
        // Skip to source/dest IPs (not needed for now)
        packet.position(ihl)
        
        when (protocol) {
            PROTOCOL_UDP -> {
                return handleUdpPacket(packet, ihl)
            }
            PROTOCOL_TCP -> {
                return handleTcpPacket(packet, ihl)
            }
            else -> {
                return packet // Pass through other protocols
            }
        }
    }
    
    private fun handleUdpPacket(packet: ByteBuffer, ipHeaderLength: Int): ByteBuffer? {
        if (packet.remaining() < 8) {
            return packet
        }
        
        packet.position(ipHeaderLength)
        
        val sourcePort = packet.short.toInt() and 0xFFFF
        val destPort = packet.short.toInt() and 0xFFFF
        
        // DNS 쿼리 (포트 53)
        if (destPort == 53 || sourcePort == 53) {
            packet.position(0)
            return dnsFilter.filterDnsPacket(packet)
        }
        
        return packet // Pass through
    }
    
    private fun handleTcpPacket(packet: ByteBuffer, ipHeaderLength: Int): ByteBuffer? {
        if (packet.remaining() < 20) {
            return packet
        }
        
        packet.position(ipHeaderLength)
        
        val sourcePort = packet.short.toInt() and 0xFFFF
        val destPort = packet.short.toInt() and 0xFFFF
        
        // HTTP (포트 80)
        if (destPort == 80 || sourcePort == 80) {
            // HTTP 필터링 (간단한 구현)
            packet.position(0)
            return checkHttpHost(packet)
        }
        
        // HTTPS (포트 443)
        if (destPort == 443 || sourcePort == 443) {
            // TLS SNI 필터링
            packet.position(0)
            return checkTlsSni(packet)
        }
        
        return packet // Pass through
    }
    
    private fun checkHttpHost(packet: ByteBuffer): ByteBuffer? {
        // HTTP 헤더에서 Host 추출은 복잡하므로 간단히만 구현
        // 실제로는 TCP 스트림을 재조합해야 함
        
        // 여기서는 DNS 차단에 의존
        return packet
    }
    
    private fun checkTlsSni(packet: ByteBuffer): ByteBuffer? {
        // TLS ClientHello에서 SNI 추출
        // 복잡한 파싱이 필요하므로 기본 구현만 제공
        
        // 실제 구현에서는 TLS 핸드셰이크를 파싱하여 SNI 추출
        // 여기서는 DNS 차단에 주로 의존
        
        return packet
    }
    
    companion object {
        private const val TAG = "PacketHandler"
        private const val PROTOCOL_TCP = 6
        private const val PROTOCOL_UDP = 17
    }
}
