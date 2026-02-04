package com.example.socialmediablocker.data.repository

import android.content.Context
import android.util.Log
import com.example.socialmediablocker.data.AppDatabase
import com.example.socialmediablocker.data.entity.BlockedDomain
import com.example.socialmediablocker.policy.DefaultBlocklist
import com.example.socialmediablocker.policy.DomainMatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * 도메인 관리 Repository
 * 데이터베이스와 메모리 캐시(DomainMatcher) 동기화
 */
class DomainRepository(context: Context) {
    
    private val domainDao = AppDatabase.getDatabase(context).domainDao()
    private val matcher = DomainMatcher()
    private var isInitialized = false
    
    /**
     * 초기화 - DB에서 도메인 로드 또는 기본 도메인 설정
     */
    suspend fun initialize() {
        if (isInitialized) return
        
        val count = domainDao.getCount()
        
        if (count == 0) {
            // 처음 실행 - 기본 도메인 리스트 삽입
            Log.i(TAG, "First run - loading default blocklist")
            loadDefaultDomains()
        } else {
            // 기존 데이터 로드
            Log.i(TAG, "Loading $count domains from database")
            val domains = domainDao.getAllDomains().first()
            matcher.clear()
            domains.forEach { matcher.addDomain(it.domain) }
        }
        
        isInitialized = true
        Log.i(TAG, "DomainRepository initialized with ${matcher.getCount()} domains")
    }
    
    /**
     * 기본 도메인 리스트 로드
     */
    private suspend fun loadDefaultDomains() {
        val youtubeDomains = DefaultBlocklist.YOUTUBE_DOMAINS.map { domain ->
            BlockedDomain(
                domain = domain,
                category = "youtube",
                isWildcard = domain.startsWith("*."),
                addedAt = System.currentTimeMillis()
            )
        }
        
        val communityDomains = DefaultBlocklist.KOREAN_COMMUNITIES.map { domain ->
            BlockedDomain(
                domain = domain,
                category = "community",
                isWildcard = domain.startsWith("*."),
                addedAt = System.currentTimeMillis()
            )
        }
        
        val allDomains = youtubeDomains + communityDomains
        domainDao.insertDomains(allDomains)
        
        // 메모리 캐시에도 로드
        matcher.clear()
        allDomains.forEach { matcher.addDomain(it.domain) }
        
        Log.i(TAG, "Loaded ${allDomains.size} default domains")
    }
    
    /**
     * 도메인이 차단되었는지 확인 (메모리 캐시 사용 - 빠름)
     */
    fun isBlocked(domain: String?): Boolean {
        return matcher.isBlocked(domain)
    }
    
    /**
     * 모든 도메인 가져오기 (Flow)
     */
    fun getAllDomains(): Flow<List<BlockedDomain>> {
        return domainDao.getAllDomains()
    }
    
    /**
     * 카테고리별 도메인 가져오기
     */
    fun getDomainsByCategory(category: String): Flow<List<BlockedDomain>> {
        return domainDao.getDomainsByCategory(category)
    }
    
    /**
     * 도메인 추가
     */
    suspend fun addDomain(domain: String, category: String = "custom") {
        val blockedDomain = BlockedDomain(
            domain = domain,
            category = category,
            isWildcard = domain.startsWith("*."),
            addedAt = System.currentTimeMillis()
        )
        
        domainDao.insertDomain(blockedDomain)
        matcher.addDomain(domain)
        
        Log.i(TAG, "Added domain: $domain (category: $category)")
    }
    
    /**
     * 도메인 삭제
     */
    suspend fun removeDomain(domain: String) {
        domainDao.deleteDomainByName(domain)
        matcher.removeDomain(domain)
        
        Log.i(TAG, "Removed domain: $domain")
    }
    
    /**
     * 카테고리 전체 삭제
     */
    suspend fun removeCategory(category: String) {
        // 먼저 해당 카테고리 도메인 가져오기
        val domains = domainDao.getDomainsByCategory(category).first()
        
        // DB에서 삭제
        domainDao.deleteByCategory(category)
        
        // 메모리 캐시에서도 삭제
        domains.forEach { matcher.removeDomain(it.domain) }
        
        Log.i(TAG, "Removed category: $category (${domains.size} domains)")
    }
    
    /**
     * 모든 도메인 삭제
     */
    suspend fun clearAll() {
        domainDao.deleteAll()
        matcher.clear()
        
        Log.i(TAG, "Cleared all domains")
    }
    
    /**
     * 도메인 수 가져오기
     */
    suspend fun getCount(): Int {
        return domainDao.getCount()
    }
    
    companion object {
        private const val TAG = "DomainRepository"
    }
}
