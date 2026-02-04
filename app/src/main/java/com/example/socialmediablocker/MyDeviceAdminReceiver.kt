package com.example.socialmediablocker

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.example.socialmediablocker.policy.PolicyEnforcer

class MyDeviceAdminReceiver : DeviceAdminReceiver() {
    
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val isDeviceOwner = dpm.isDeviceOwnerApp(context.packageName)
        
        if (isDeviceOwner) {
            Log.i(TAG, "Device Owner 활성화됨 - 정책 적용 중")
            Toast.makeText(context, "기기 소유자 모드 활성화 - 보호 정책이 강화되었습니다.", Toast.LENGTH_LONG).show()
            
            // 초기 정책 적용
            val enforcer = PolicyEnforcer(context)
            enforcer.applyInitialPolicies()
        } else {
            Log.i(TAG, "Device Admin 활성화됨 (일반)")
            Toast.makeText(context, "기기 관리자 권한이 부여되었습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.w(TAG, "Device Admin Disabled!")
        Toast.makeText(context, "기기 관리자 권한이 해제되었습니다. 보호 기능이 작동하지 않습니다.", Toast.LENGTH_LONG).show()
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        // Device Owner 모드에서는 이 메서드가 호출되지 않음
        Log.w(TAG, "Disable requested")
        return "이 권한을 해제하면 모든 소셜 미디어 차단 및 보호 기능이 무력화됩니다. 정말 해제하시겠습니까?"
    }
    
    companion object {
        private const val TAG = "MyDeviceAdminReceiver"
    }
}
