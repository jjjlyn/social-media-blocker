package com.example.socialmediablocker.data.dao

import androidx.room.*
import com.example.socialmediablocker.data.entity.PolicyConfig

@Dao
interface PolicyDao {
    
    @Query("SELECT * FROM policy_config WHERE key = :key LIMIT 1")
    suspend fun getConfig(key: String): PolicyConfig?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setConfig(config: PolicyConfig)
    
    @Query("DELETE FROM policy_config WHERE key = :key")
    suspend fun deleteConfig(key: String)
    
    @Query("SELECT value FROM policy_config WHERE key = :key LIMIT 1")
    suspend fun getValue(key: String): String?
}
