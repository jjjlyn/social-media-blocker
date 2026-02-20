package com.example.socialmediablocker

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.socialmediablocker.admin.PinManager

/**
 * PIN 확인 Activity
 * 설정 앱 접근 시 PIN 입력을 요구
 */
class PinVerifyActivity : AppCompatActivity() {

    private lateinit var pinManager: PinManager
    private lateinit var etPin: EditText
    private lateinit var btnVerify: Button
    private lateinit var btnCancel: Button
    private var attemptCount = 0
    private val maxAttempts = 3

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pin_verify)

        isShowing = true
        pinManager = PinManager(this)

        // PIN이 설정되지 않았으면 통과
        if (!pinManager.isPinSet()) {
            finish()
            return
        }

        etPin = findViewById(R.id.etPin)
        btnVerify = findViewById(R.id.btnVerify)
//        btnCancel = findViewById(R.id.btnCancel)

        // 뒤로가기 방지
        setFinishOnTouchOutside(false)

        btnVerify.setOnClickListener {
            verifyPin()
        }

//        btnCancel.setOnClickListener {
//            finish()
//        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isShowing = false
    }

    private fun verifyPin() {
        val inputPin = etPin.text.toString()

        if (!pinManager.isValidPin(inputPin)) {
            Toast.makeText(this, getString(R.string.toast_pin_invalid_format), Toast.LENGTH_SHORT).show()
            etPin.text.clear()
            return
        }

        if (pinManager.verifyPin(inputPin)) {
            // PIN 일치
            Toast.makeText(this, getString(R.string.toast_pin_verified), Toast.LENGTH_SHORT).show()
            
            // SharedPreferences에 인증 시간 저장 (Service와 공유)
            val prefs = getSharedPreferences("pin_prefs", Context.MODE_PRIVATE)
            prefs.edit().putLong("last_verified_time", System.currentTimeMillis()).apply()
            
            // 앱 실행 시 잠금 해제인 경우
            finish() 
        } else {
            // PIN 불일치
            attemptCount++
            etPin.text.clear()

            if (attemptCount >= maxAttempts) {
                Toast.makeText(this, getString(R.string.toast_attempts_exceeded), Toast.LENGTH_SHORT).show()
                // 홈으로 강제 이동
                val homeIntent = Intent(Intent.ACTION_MAIN)
                homeIntent.addCategory(Intent.CATEGORY_HOME)
                homeIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(homeIntent)
                finish()
            } else {
                val remaining = maxAttempts - attemptCount
                Toast.makeText(this, getString(R.string.toast_pin_invalid, remaining), Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onBackPressed() {
        // 앱 잠금 모드일 때 뒤로가기 누르면 앱 종료 (홈으로)
        if (intent.getStringExtra("mode") == "app_lock") {
             val homeIntent = Intent(Intent.ACTION_MAIN)
             homeIntent.addCategory(Intent.CATEGORY_HOME)
             homeIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
             startActivity(homeIntent)
             finish()
        } else {
            // 설정 접근 차단일 때는 그냥 토스트
            Toast.makeText(this, getString(R.string.toast_pin_mandatory), Toast.LENGTH_SHORT).show()
        }
    }

    private fun performGlobalAction(action: Int) {
        // Accessibility Service를 통해 홈으로 이동
        val intent = android.content.Intent(this, BlockerService::class.java)
        intent.putExtra("action", "go_home")
        startService(intent)
    }

    companion object {
        @Volatile
        var isShowing: Boolean = false
    }
}
