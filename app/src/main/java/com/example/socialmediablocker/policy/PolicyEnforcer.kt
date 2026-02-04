package com.example.socialmediablocker.policy

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.example.socialmediablocker.MyDeviceAdminReceiver

/**
 * 정책 적용 엔진
 * DevicePolicyManager와 연동하여 앱 차단, 삭제 방지 등을 수행
 */
class PolicyEnforcer(private val context: Context) {
    
    private val dpm: DevicePolicyManager = 
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    
    private val adminComponent = ComponentName(context, MyDeviceAdminReceiver::class.java)
    
    /**
     * Device Owner 여부 확인
     */
    fun isDeviceOwner(): Boolean {
        return dpm.isDeviceOwnerApp(context.packageName)
    }
    
    /**
     * Device Admin 활성화 여부 확인
     */
    fun isAdminActive(): Boolean {
        return dpm.isAdminActive(adminComponent)
    }
    
    /**
     * YouTube 앱 차단 (Device Owner 필요)
     */
    fun blockYouTubeApp(): Boolean {
        if (!isDeviceOwner()) {
            Log.w(TAG, "Not device owner - cannot hide apps")
            return false
        }
        
        val youtubePackages = listOf(
            "com.google.android.youtube",
            "com.google.android.youtube.tv",
            "com.google.android.youtube.music"
        )
        
        var success = true
        for (pkg in youtubePackages) {
            try {
                val hidden = dpm.setApplicationHidden(adminComponent, pkg, true)
                if (hidden) {
                    Log.i(TAG, "Hidden app: $pkg")
                } else {
                    Log.w(TAG, "Failed to hide app (may not be installed): $pkg")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error hiding app $pkg", e)
                success = false
            }
        }
        
        return success
    }
    
    /**
     * 이 앱의 삭제 차단 (Device Owner 필요)
     */
    fun preventUninstall(): Boolean {
        if (!isDeviceOwner()) {
            Log.w(TAG, "Not device owner - cannot block uninstall")
            return false
        }
        
        return try {
            dpm.setUninstallBlocked(adminComponent, context.packageName, true)
            Log.i(TAG, "Uninstall blocked for this app")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to block uninstall", e)
            false
        }
    }
    
    /**
     * 특정 앱 설치 차단 (예: VPN 앱)
     */
    fun blockAppInstallation(packageName: String): Boolean {
        if (!isDeviceOwner()) {
            Log.w(TAG, "Not device owner - cannot block installation")
            return false
        }
        
        return try {
            dpm.setUninstallBlocked(adminComponent, packageName, true)
            Log.i(TAG, "Installation blocked for: $packageName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to block installation for $packageName", e)
            false
        }
    }
    
    /**
     * 사용자 제약 설정 (앱 설치 제한 등)
     */
    fun applyUserRestrictions(): Boolean {
        if (!isDeviceOwner()) {
            Log.w(TAG, "Not device owner - cannot apply user restrictions")
            return false
        }
        
        return try {
            // 알 수 없는 출처 앱 설치 차단
            dpm.addUserRestriction(adminComponent, "no_install_unknown_sources")
            
            // 안전 모드 진입 차단 (일부 기기)
            dpm.addUserRestriction(adminComponent, "no_safe_boot")
            
            Log.i(TAG, "User restrictions applied")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply user restrictions", e)
            false
        }
    }
    
    /**
     * 초기 정책 적용 (Device Owner 설정 후 최초 실행)
     */
    fun applyInitialPolicies(): Boolean {
        if (!isDeviceOwner()) {
            Log.w(TAG, "Not device owner - skipping initial policies")
            return false
        }
        
        Log.i(TAG, "Applying initial Device Owner policies...")
        
        var allSuccess = true
        
        // YouTube 앱 차단
        if (!blockYouTubeApp()) {
            allSuccess = false
        }
        
        // 이 앱의 삭제 차단
        if (!preventUninstall()) {
            allSuccess = false
        }
        
        // 사용자 제약 적용
        if (!applyUserRestrictions()) {
            allSuccess = false
        }
        
        Log.i(TAG, "Initial policies applied: success=$allSuccess")
        return allSuccess
    }
    
    /**
     * 모든 정책 해제 (관리자만)
     */
    fun removeAllPolicies(): Boolean {
        if (!isDeviceOwner()) {
            return false
        }
        
        try {
            // YouTube 앱 표시
            val youtubePackages = listOf(
                "com.google.android.youtube",
                "com.google.android.youtube.tv",
                "com.google.android.youtube.music"
            )
            
            for (pkg in youtubePackages) {
                try {
                    dpm.setApplicationHidden(adminComponent, pkg, false)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to unhide $pkg", e)
                }
            }
            
            // 이 앱 삭제 허용
            dpm.setUninstallBlocked(adminComponent, context.packageName, false)
            
            // 사용자 제약 제거
            dpm.clearUserRestriction(adminComponent, "no_install_unknown_sources")
            dpm.clearUserRestriction(adminComponent, "no_safe_boot")
            
            Log.i(TAG, "All policies removed")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove policies", e)
            return false
        }
    }
    
    /**
     * 화면 잠금 (긴급 시)
     */
    fun lockScreen(): Boolean {
        if (!isAdminActive()) {
            return false
        }
        
        return try {
            dpm.lockNow()
            Log.i(TAG, "Screen locked")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to lock screen", e)
            false
        }
    }
    
    companion object {
        private const val TAG = "PolicyEnforcer"
    }
}
