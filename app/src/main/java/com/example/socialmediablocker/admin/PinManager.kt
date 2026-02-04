package com.example.socialmediablocker.admin

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.mindrot.jbcrypt.BCrypt

/**
 * 관리자 PIN 관리 클래스
 * BCrypt를 사용한 안전한 PIN 저장 및 검증
 */
class PinManager(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    /**
     * PIN이 설정되어 있는지 확인
     */
    fun isPinSet(): Boolean {
        return prefs.contains(KEY_PIN_HASH)
    }
    
    /**
     * PIN 설정
     */
    fun setPin(pin: String): Boolean {
        if (pin.length < MIN_PIN_LENGTH) {
            Log.w(TAG, "PIN too short. Minimum length: $MIN_PIN_LENGTH")
            return false
        }
        
        try {
            val hash = BCrypt.hashpw(pin, BCrypt.gensalt())
            prefs.edit()
                .putString(KEY_PIN_HASH, hash)
                .putLong(KEY_PIN_SET_TIME, System.currentTimeMillis())
                .apply()
            
            Log.i(TAG, "PIN set successfully")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set PIN", e)
            return false
        }
    }
    
    /**
     * PIN 검증
     */
    fun verifyPin(pin: String): Boolean {
        val hash = prefs.getString(KEY_PIN_HASH, null)
        
        if (hash.isNullOrEmpty()) {
            Log.w(TAG, "No PIN set")
            return false
        }
        
        return try {
            val isValid = BCrypt.checkpw(pin, hash)
            if (isValid) {
                Log.i(TAG, "PIN verified successfully")
            } else {
                Log.w(TAG, "Invalid PIN attempt")
            }
            isValid
        } catch (e: Exception) {
            Log.e(TAG, "PIN verification failed", e)
            false
        }
    }
    
    /**
     * PIN 제거
     */
    fun clearPin() {
        prefs.edit()
            .remove(KEY_PIN_HASH)
            .remove(KEY_PIN_SET_TIME)
            .apply()
        
        Log.i(TAG, "PIN cleared")
    }
    
    /**
     * PIN 설정 시간 가져오기
     */
    fun getPinSetTime(): Long {
        return prefs.getLong(KEY_PIN_SET_TIME, 0L)
    }
    
    companion object {
        private const val TAG = "PinManager"
        private const val PREFS_NAME = "admin_pin"
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_PIN_SET_TIME = "pin_set_time"
        private const val MIN_PIN_LENGTH = 4
    }
}
