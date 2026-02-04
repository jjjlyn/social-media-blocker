package com.example.socialmediablocker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.socialmediablocker.data.dao.DomainDao
import com.example.socialmediablocker.data.dao.PolicyDao
import com.example.socialmediablocker.data.entity.BlockedDomain
import com.example.socialmediablocker.data.entity.PolicyConfig

@Database(
    entities = [BlockedDomain::class, PolicyConfig::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    
    abstract fun domainDao(): DomainDao
    abstract fun policyDao(): PolicyDao
    
    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null
        
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "youtube_blocker_db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
