package com.example.socialmediablocker.data.dao

import androidx.room.*
import com.example.socialmediablocker.data.entity.BlockedDomain
import kotlinx.coroutines.flow.Flow

@Dao
interface DomainDao {
    
    @Query("SELECT * FROM blocked_domains ORDER BY addedAt DESC")
    fun getAllDomains(): Flow<List<BlockedDomain>>
    
    @Query("SELECT * FROM blocked_domains WHERE category = :category")
    fun getDomainsByCategory(category: String): Flow<List<BlockedDomain>>
    
    @Query("SELECT * FROM blocked_domains WHERE domain = :domain LIMIT 1")
    suspend fun getDomain(domain: String): BlockedDomain?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDomain(domain: BlockedDomain)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDomains(domains: List<BlockedDomain>)
    
    @Delete
    suspend fun deleteDomain(domain: BlockedDomain)
    
    @Query("DELETE FROM blocked_domains WHERE domain = :domain")
    suspend fun deleteDomainByName(domain: String)
    
    @Query("DELETE FROM blocked_domains WHERE category = :category")
    suspend fun deleteByCategory(category: String)
    
    @Query("DELETE FROM blocked_domains")
    suspend fun deleteAll()
    
    @Query("SELECT COUNT(*) FROM blocked_domains")
    suspend fun getCount(): Int
}
