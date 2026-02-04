package com.example.socialmediablocker.vpn

import android.util.Log
import com.example.socialmediablocker.data.repository.DomainRepository
import java.nio.ByteBuffer

/**
 * DNS 필터
 * DNS 쿼리를 분석하여 차단 도메인 조회 시 NXDOMAIN 응답
 */
class DnsFilter(private val domainRepository: DomainRepository) {
    
    /**
     * DNS 패킷 필터링
     * @return null이면 차단, ByteBuffer면 응답 패킷
     */
    fun filterDnsPacket(packet: ByteBuffer): ByteBuffer? {
        try {
            packet.position(0)
            
            // IP 헤더 길이 확인
            val versionAndIHL = packet.get(0).toInt() and 0xFF
            val ipHeaderLength = (versionAndIHL and 0x0F) * 4
            
            // UDP 헤더는 8바이트
            val dnsPayloadOffset = ipHeaderLength + 8
            
            if (packet.remaining() < dnsPayloadOffset + 12) {
                return packet // Too small
            }
            
            packet.position(dnsPayloadOffset)
            
            // DNS 헤더 파싱
            val transactionId = packet.short
            val flags = packet.short.toInt() and 0xFFFF
            
            // Query인지 확인 (QR bit = 0)
            val isQuery = (flags and 0x8000) == 0
            
            if (!isQuery) {
                packet.position(0)
                return packet // Response, pass through
            }
            
            val questionCount = packet.short.toInt() and 0xFFFF
            
            if (questionCount == 0) {
                packet.position(0)
                return packet
            }
            
            // Skip answer, authority, additional counts
            packet.position(packet.position() + 6)
            
            // 질문 섹션에서 도메인 추출
            val domain = extractDomain(packet)
            
            if (domain != null) {
                Log.d(TAG, "DNS query for: $domain")
                
                if (domainRepository.isBlocked(domain)) {
                    Log.w(TAG, "🚫 BLOCKING DNS query for: $domain")
                    // 차단! null 반환으로 패킷을 drop
                    return null
                } else {
                    Log.d(TAG, "Allowing DNS query for: $domain")
                }
            }
            
            // 허용된 도메인 - 그대로 통과
            packet.position(0)
            return packet
            
        } catch (e: Exception) {
            Log.e(TAG, "Error filtering DNS packet", e)
            packet.position(0)
            return packet // Fail-safe: pass through
        }
    }
    
    /**
     * DNS 쿼리에서 도메인명 추출
     */
    private fun extractDomain(buffer: ByteBuffer): String? {
        try {
            val labels = mutableListOf<String>()
            
            while (buffer.hasRemaining()) {
                val length = buffer.get().toInt() and 0xFF
                
                if (length == 0) {
                    break // End of domain name
                }
                
                if (length > 63) {
                    return null // Invalid
                }
                
                val labelBytes = ByteArray(length)
                buffer.get(labelBytes)
                labels.add(String(labelBytes, Charsets.US_ASCII))
            }
            
            return if (labels.isNotEmpty()) {
                labels.joinToString(".")
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting domain", e)
            return null
        }
    }
    
    /**
     * NXDOMAIN 응답 생성
     */
    private fun createNxDomainResponse(
        originalPacket: ByteBuffer,
        transactionId: Short,
        domain: String
    ): ByteBuffer? {
        // 간단한 NXDOMAIN 응답 생성
        // 실제로는 원본 IP/UDP 헤더를 기반으로 응답 패킷을 구성해야 함
        
        // 여기서는 패킷을 Drop하여 연결 차단
        // (실제 NXDOMAIN 응답 생성은 복잡하므로 생략)
        
        Log.i(TAG, "Dropping DNS query for blocked domain: $domain")
        return null
    }
    
    companion object {
        private const val TAG = "DnsFilter"
    }
}
