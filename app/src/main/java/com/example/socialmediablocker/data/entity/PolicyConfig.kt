package com.example.socialmediablocker.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 정책 설정 Entity (키-값 저장소)
 * @param key 설정 키
 * @param value 설정 값
 */
@Entity(tableName = "policy_config")
data class PolicyConfig(
    @PrimaryKey val key: String,
    val value: String
)
