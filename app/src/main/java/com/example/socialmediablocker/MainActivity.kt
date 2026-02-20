package com.example.socialmediablocker

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.socialmediablocker.data.repository.DomainRepository
import com.example.socialmediablocker.policy.PolicyEnforcer
import com.example.socialmediablocker.admin.PinManager
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var btnEnableAccessibility: Button
    private lateinit var btnEnableAdmin: Button
    
    private lateinit var domainRepository: DomainRepository
    private lateinit var policyEnforcer: PolicyEnforcer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val prefs = getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
        val skipIntro = prefs.getBoolean("skip_intro", false)
        val fromIntro = intent.getBooleanExtra("from_intro", false)
        
        // If not skipped and not just coming from intro, redirect to Intro
        if (!skipIntro && !fromIntro) {
            val introIntent = android.content.Intent(this, IntroActivity::class.java)
            startActivity(introIntent)
            finish()
            return
        }

        try {
            setContentView(R.layout.activity_main)

            tvStatus = findViewById(R.id.tvStatus)
            btnEnableAccessibility = findViewById(R.id.btnEnableAccessibility)
            btnEnableAdmin = findViewById(R.id.btnEnableAdmin)
            
            // Initialize repository and enforcer
            domainRepository = DomainRepository(applicationContext)
            policyEnforcer = PolicyEnforcer(applicationContext)
            
            // Initialize domain repository
            lifecycleScope.launch {
                try {
                    domainRepository.initialize()
                    val count = domainRepository.getCount()
                    android.util.Log.i("MainActivity", "Loaded $count blocked domains")
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Failed to initialize repository", e)
                }
            }

            btnEnableAccessibility.setOnClickListener {
                try {
                    val pinManager = PinManager(this)
                    if (!pinManager.isPinSet()) {
                        showPinSetupDialog()
                        return@setOnClickListener
                    }

                    // Mark that we're initiating accessibility enable flow
                    val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                    prefs.edit()
                        .putLong("pending_accessibility_enable_time", System.currentTimeMillis())
                        .apply()

                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    startActivity(intent)
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Error starting accessibility settings", e)
                }
            }

            btnEnableAdmin.setOnClickListener {
                try {
                    val pinManager = PinManager(this)
                    if (!pinManager.isPinSet()) {
                        showPinSetupDialog()
                        return@setOnClickListener
                    }

                    val componentName = ComponentName(this, MyDeviceAdminReceiver::class.java)
                    val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
                    intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, getString(R.string.device_admin_description))
                    startActivity(intent)
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Error starting admin settings", e)
                }
            }
            
            // PIN 설정 버튼
            val btnSetupPin: Button = findViewById(R.id.btnSetupPin)
            btnSetupPin.setOnClickListener {
                showPinSetupDialog()
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error in onCreate", e)
            finish()
        }
    }
    
    /**
     * PIN 설정 다이얼로그 표시
     */
    private fun showPinSetupDialog() {
        val pinManager = PinManager(this)
        
        val builder = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
        builder.setTitle(getString(R.string.dialog_pin_title))

        val dialogView = layoutInflater.inflate(R.layout.dialog_pin_input, null)
        val pinInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etPinDialog)
        builder.setView(dialogView)

        if (pinManager.isPinSet()) {
            builder.setMessage(getString(R.string.dialog_pin_verify_msg))
            builder.setPositiveButton(getString(R.string.dialog_confirm)) { _, _ ->
                val oldPin = pinInput.text?.toString().orEmpty()
                if (!pinManager.isValidPin(oldPin)) {
                    android.widget.Toast.makeText(this, getString(R.string.toast_pin_invalid_format), android.widget.Toast.LENGTH_SHORT).show()
                } else if (pinManager.verifyPin(oldPin)) {
                    showNewPinDialog()
                } else {
                    android.widget.Toast.makeText(this, getString(R.string.toast_verify_fail), android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            builder.setMessage(getString(R.string.dialog_new_pin_msg))
            builder.setPositiveButton(getString(R.string.dialog_establish)) { _, _ ->
                val newPin = pinInput.text?.toString().orEmpty()
                if (!pinManager.isValidPin(newPin)) {
                    android.widget.Toast.makeText(this, getString(R.string.toast_pin_invalid_format), android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    if (pinManager.setPin(newPin)) {
                        android.widget.Toast.makeText(this, getString(R.string.toast_pin_set), android.widget.Toast.LENGTH_LONG).show()
                        updateStatus()
                    } else {
                        android.widget.Toast.makeText(this, getString(R.string.toast_pin_set_failed), android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        
        builder.setNegativeButton(getString(R.string.dialog_cancel), null)
        builder.show()
    }
    
    /**
     * 새 PIN 설정 다이얼로그
     */
    private fun showNewPinDialog() {
        val pinManager = PinManager(this)
        
        val builder = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
        builder.setTitle(getString(R.string.dialog_pin_title))
        builder.setMessage(getString(R.string.dialog_new_pin_msg))

        val dialogView = layoutInflater.inflate(R.layout.dialog_pin_input, null)
        val newPinInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etPinDialog)
        builder.setView(dialogView)
        
        builder.setPositiveButton(getString(R.string.dialog_establish)) { _, _ ->
            val newPin = newPinInput.text?.toString().orEmpty()
            if (!pinManager.isValidPin(newPin)) {
                android.widget.Toast.makeText(this, getString(R.string.toast_pin_invalid_format), android.widget.Toast.LENGTH_SHORT).show()
            } else {
                if (pinManager.setPin(newPin)) {
                    android.widget.Toast.makeText(this, getString(R.string.toast_pin_updated), android.widget.Toast.LENGTH_SHORT).show()
                    updateStatus()
                } else {
                    android.widget.Toast.makeText(this, getString(R.string.toast_pin_set_failed), android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
        
        builder.setNegativeButton(getString(R.string.dialog_cancel), null)
        builder.show()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }
    
    
    private fun updateAdminButtonState(isActive: Boolean) {
        if (isActive) {
            btnEnableAdmin.isEnabled = false
            btnEnableAdmin.text = getString(R.string.btn_active)
            btnEnableAdmin.alpha = 0.5f
        } else {
            btnEnableAdmin.isEnabled = true
            btnEnableAdmin.text = getString(R.string.btn_elevate)
            btnEnableAdmin.alpha = 1.0f
        }
    }
    

    private fun updateStatus() {
        try {
            val isServiceEnabled = isAccessibilityServiceEnabled(this, BlockerService::class.java)
            val devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            val componentName = ComponentName(this, MyDeviceAdminReceiver::class.java)
            val isAdminActive = devicePolicyManager?.isAdminActive(componentName) ?: false
            val isDeviceOwner = policyEnforcer.isDeviceOwner()
            val pinManager = PinManager(this)

            if (!pinManager.isPinSet()) {
                // Require PIN before allowing security controls
                findViewById<TextView>(R.id.tvAccessDetail).text = getString(R.string.detail_accessibility_pin_required)
                findViewById<TextView>(R.id.tvAdminDetail).text = getString(R.string.detail_admin_pin_required)

                btnEnableAccessibility.text = getString(R.string.btn_pin_required)
                btnEnableAccessibility.isEnabled = false
                btnEnableAccessibility.alpha = 0.5f

                btnEnableAdmin.text = getString(R.string.btn_pin_required)
                btnEnableAdmin.isEnabled = false
                btnEnableAdmin.alpha = 0.5f

                // PIN 버튼 강조
                val btnSetupPin: Button = findViewById(R.id.btnSetupPin)
                btnSetupPin.text = getString(R.string.btn_configure)

                // Skip the rest of status updates until PIN is set
                return
            }

            // Update main status card
            if (isServiceEnabled) {
                tvStatus.text = "당신의 시간은 안전합니다"
                tvStatus.setTextColor(android.graphics.Color.parseColor("#1A1C1E"))
                findViewById<android.view.View>(R.id.cardSummary).setBackgroundColor(android.graphics.Color.parseColor("#E8F5E9")) // Soft Green
            } else {
                tvStatus.text = "현재 보호가 중지됨"
                tvStatus.setTextColor(android.graphics.Color.parseColor("#BA1A1A")) // Error Red
                findViewById<android.view.View>(R.id.cardSummary).setBackgroundColor(android.graphics.Color.parseColor("#FFEBEE")) // Soft Red
            }
            
            // Update accessibility button
            if (isServiceEnabled) {
                btnEnableAccessibility.text = getString(R.string.btn_active)
                btnEnableAccessibility.isEnabled = false
                btnEnableAccessibility.alpha = 0.5f
            } else {
                btnEnableAccessibility.text = getString(R.string.btn_grant)
                btnEnableAccessibility.isEnabled = true
                btnEnableAccessibility.alpha = 1.0f
            }

            // Update Admin status
            if (isDeviceOwner) {
                findViewById<TextView>(R.id.tvAdminDetail).text = "기기 소유자 보호 활성화"
                updateAdminButtonState(true)
            } else if (isAdminActive) {
                findViewById<TextView>(R.id.tvAdminDetail).text = "기본 보안 보호 활성화"
                updateAdminButtonState(true)
            } else {
                findViewById<TextView>(R.id.tvAdminDetail).text = getString(R.string.detail_admin)
                updateAdminButtonState(false)
            }
            
            // Update PIN button text
            val btnSetupPin: Button = findViewById(R.id.btnSetupPin)
            if (pinManager.isPinSet()) {
                btnSetupPin.text = getString(R.string.btn_modify)
            } else {
                btnSetupPin.text = getString(R.string.btn_configure)
            }

            // Domain count in summary
            lifecycleScope.launch {
                val count = domainRepository.getCount()
                findViewById<TextView>(R.id.tvDomainCount).text = "${count}${getString(R.string.label_registered_filters)}"
            }
            
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error updating status", e)
        }
    }

    private fun isAccessibilityServiceEnabled(context: Context, service: Class<*>): Boolean {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        
        if (enabledServices.isNullOrEmpty()) {
            return false
        }
        
        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)
        val myComponentName = ComponentName(context, service)
        val myServiceString = myComponentName.flattenToString()
        
        while (colonSplitter.hasNext()) {
            val componentName = colonSplitter.next()
            if (componentName.equals(myServiceString, ignoreCase = true)) {
                return true
            }
        }
        return false
    }
    
}
