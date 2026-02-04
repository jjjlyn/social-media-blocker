package com.example.socialmediablocker.policy

import android.util.Log

/**
 * 도메인 매칭 엔진
 * Wildcard 및 정확한 매칭 지원
 */
class DomainMatcher {
    
    private val exactMatches = mutableSetOf<String>()
    private val wildcardPatterns = mutableListOf<WildcardPattern>()
    
    private data class WildcardPattern(
        val originalPattern: String,
        val regex: Regex
    )
    
    /**
     * 도메인 추가
     */
    fun addDomain(domain: String) {
        val normalizedDomain = domain.lowercase().trim()
        
        if (normalizedDomain.startsWith("*.")) {
            // Wildcard 패턴
            val baseDomain = normalizedDomain.substring(2)
            val pattern = "(.*\\.)?${Regex.escape(baseDomain)}"
            val regex = Regex(pattern)
            wildcardPatterns.add(WildcardPattern(normalizedDomain, regex))
            Log.d(TAG, "Added wildcard pattern: $normalizedDomain -> $pattern")
        } else {
            // 정확한 매칭
            exactMatches.add(normalizedDomain)
            Log.d(TAG, "Added exact match: $normalizedDomain")
        }
    }
    
    /**
     * 여러 도메인 추가
     */
    fun addDomains(domains: Collection<String>) {
        domains.forEach { addDomain(it) }
    }
    
    /**
     * 도메인 제거
     */
    fun removeDomain(domain: String) {
        val normalizedDomain = domain.lowercase().trim()
        
        if (normalizedDomain.startsWith("*.")) {
            wildcardPatterns.removeIf { it.originalPattern == normalizedDomain }
        } else {
            exactMatches.remove(normalizedDomain)
        }
    }
    
    /**
     * 모든 도메인 제거
     */
    fun clear() {
        exactMatches.clear()
        wildcardPatterns.clear()
    }
    
    /**
     * 도메인이 차단되었는지 확인
     */
    fun isBlocked(domain: String?): Boolean {
        if (domain.isNullOrBlank()) return false
        
        val normalizedDomain = domain.lowercase().trim()
        
        // 1. 정확한 매칭 확인 (빠름)
        if (exactMatches.contains(normalizedDomain)) {
            Log.d(TAG, "Blocked (exact match): $normalizedDomain")
            return true
        }
        
        // 2. Wildcard 매칭 확인
        for (pattern in wildcardPatterns) {
            if (pattern.regex.matches(normalizedDomain)) {
                Log.d(TAG, "Blocked (wildcard ${pattern.originalPattern}): $normalizedDomain")
                return true
            }
        }
        
        return false
    }
    
    /**
     * 현재 로드된 도메인 수 반환
     */
    fun getCount(): Int {
        return exactMatches.size + wildcardPatterns.size
    }
    
    companion object {
        private const val TAG = "DomainMatcher"
    }
}
