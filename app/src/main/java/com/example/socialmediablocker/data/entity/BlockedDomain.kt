package com.example.socialmediablocker.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 차단 도메인 Entity
 * @param domain 차단할 도메인 (예: "youtube.com", "*.dcinside.com")
 * @param category 카테고리 ("youtube", "community", "custom")
 * @param isWildcard 와일드카드 여부
 * @param addedAt 추가 시간 (timestamp)
 */
@Entity(tableName = "blocked_domains")
data class BlockedDomain(
    @PrimaryKey val domain: String,
    val category: String,
    val isWildcard: Boolean,
    val addedAt: Long
)
