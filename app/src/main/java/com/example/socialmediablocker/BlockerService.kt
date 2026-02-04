package com.example.socialmediablocker

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import com.example.socialmediablocker.data.repository.DomainRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class BlockerService : AccessibilityService() {

    private val blockedPackages = setOf(
        "com.google.android.youtube", // YouTube App
        "com.google.android.youtube.tv", // YouTube TV
        "com.google.android.youtube.go", // YouTube Go
        "com.google.android.apps.youtube.kids", // YouTube Kids
        "com.google.android.apps.youtube.music", // YouTube Music (actual)
        "com.google.android.youtube.music", // YouTube Music (legacy)
        "com.tiktok.android", // TikTok
        "com.tiktok.android.lite",
        "com.instagram.android", // Instagram
        "com.facebook.katana" // Facebook
    )

    private lateinit var domainRepository: DomainRepository
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRepositoryReady = false

    // PIN 인증 후 재요구 방지 타이머
    private var lastPinVerificationTime: Long = 0
    private val PIN_COOLDOWN_MS = 60000L // 60초 (설정화면 등 민감한 동작용)

    // Accessibility 권한 활성화 직후 예외 처리 (앱 복귀용)
    private val ACCESSIBILITY_ENABLE_GRACE_MS = 15000L // 15초 (초기 활성화 직후만)

    // Keywords to search for in browser URL bar or content descriptions
    private val blockedKeywords = listOf("youtube.com", "youtu.be", "m.youtube.com")

    // PIN 프롬프트 중복 방지
    private var lastPinPromptAt: Long = 0
    private val PIN_PROMPT_DEBOUNCE_MS = 1500L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                handleEvent(event)
            }
        }
    }

    private fun handleEvent(event: AccessibilityEvent) {
        if (event.packageName == null) return
        
        val packageName = event.packageName.toString()

        try {
            // 우리 앱 화면에서는 추가 처리하지 않음 (PIN 화면 중복 방지)
            if (packageName == applicationContext.packageName) {
                return
            }

            // 1. 차단된 앱 (유튜브 등) 확인
            // 단순 포함 여부 확인 (더 광범위하게 차단)
            if (blockedPackages.contains(packageName)) {
                Log.w(TAG, "Blocked app detected: $packageName")
                showBlockedOverlay()
                performGlobalAction(GLOBAL_ACTION_HOME)
                
                // 2중 차단: 뒤로가기도 시도
                performGlobalAction(GLOBAL_ACTION_BACK)
                return
            }

            // Launcher(앱 목록)에서는 보호 로직을 적용하지 않음 (탐색 방해 방지)
            if (isLauncherPackage(packageName)) {
                return
            }
            
            // 2. 보호 설정 접근 방지 (설정 앱, 접근성, 패키지 삭제 등)
            val className = event.className?.toString() ?: ""
            val isPotentialSystemUi =
                isSystemUiPackage(packageName) ||
                className.contains("Settings", ignoreCase = true) ||
                className.contains("Uninstall", ignoreCase = true) ||
                className.contains("PackageInstaller", ignoreCase = true) ||
                className.contains("AppInfo", ignoreCase = true) ||
                className.contains("AppDetails", ignoreCase = true)

            if (isPotentialSystemUi) {
                val rootNode = rootInActiveWindow
                val onAccessibilityPage = shouldRequirePinForAccessibility(rootNode, event, packageName)
                val onSelfAppDetails = isSelfAppDetails(rootNode, event)
                val onDeviceAdminPage = isDeviceAdminPage(rootNode, event)
                val onUninstallFlow = isUninstallFlow(rootNode, event)
                val verificationNeeded = onAccessibilityPage || onSelfAppDetails || onDeviceAdminPage || onUninstallFlow

                if (verificationNeeded) {
                    // Accessibility 권한 최초 활성화 직후에는 차단하지 않음
                    if (onAccessibilityPage && shouldBypassAccessibilityEnable()) {
                        Log.i(TAG, "Bypassing protection for initial accessibility enable flow")
                        return
                    }

                    val currentTime = System.currentTimeMillis()
                    val lastVerified = getLastPinVerificationTime()
                    val timeDiff = currentTime - lastVerified

                    // PIN 인증 유효기간 체크
                    if (timeDiff > PIN_COOLDOWN_MS) {
                        Log.w(TAG, "Sensitive settings detected - requiring PIN")
                        // PIN 요구 (설정 화면 위에 표시)
                        showPinVerification(true)
                    } else {
                        Log.i(TAG, "Sensitive settings allowed (PIN valid session)")
                    }
                    return
                }
            }
            
            // 3. 브라우저 차단 확인
             val browserPackages = listOf(
                "chrome", "browser", "firefox", "opera", "kakao.talk", "internet"
            )
            
            if (browserPackages.any { packageName.contains(it) }) {
                val rootNode = rootInActiveWindow
                if (checkForYouTubeInBrowser(rootNode)) {
                    Log.w(TAG, "Blocked Website Detected into Browser")
                    showBlockedOverlay()
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in handleEvent", e)
        }
    }
    
    /**
     * Accessibility 설정 페이지인지 확인
     * Activity ClassName을 기반으로 감지
     */
    private fun isAccessibilitySettingsPage(node: AccessibilityNodeInfo?, event: AccessibilityEvent?): Boolean {
        // 1. Event의 ClassName 확인 (가장 정확함)
        if (event != null && event.className != null) {
            val className = event.className.toString()
            Log.d(TAG, "Window ClassName: $className")
            
            if (className.contains("AccessibilitySettingsActivity") || 
                className.contains("AccessibilitySettings") ||
                className.contains("SubSettings")) {
                
                // SubSettings인 경우 제목 확인 필요 (Accessibility가 아닌 다른 설정일 수 있음)
                if (node != null && className.contains("SubSettings")) {
                     return checkTitleForAccessibility(node)
                }
                return true
            }
        }
        
        // 2. Node 정보로 확인 (fallback)
        return checkTitleForAccessibility(node)
    }

    private fun checkTitleForAccessibility(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        try {
             val keywords = listOf(
                "접근성", "Accessibility",
                "유용한 기능", "installed services", "설치된 서비스",
                "접근성 서비스", "Accessibility Service"
            )
            
            // 페이지 제목 또는 리스트 아이템 텍스트 확인
            val nodesWithText = mutableListOf<AccessibilityNodeInfo>()
            for (keyword in keywords) {
                nodesWithText.addAll(node.findAccessibilityNodeInfosByText(keyword))
            }
            
            if (nodesWithText.isNotEmpty()) {
                return true
            }
            
            return false
        } catch (e: Exception) {
            return false
        }
    }

    private fun shouldRequirePinForAccessibility(
        node: AccessibilityNodeInfo?,
        event: AccessibilityEvent?,
        packageName: String
    ): Boolean {
        if (node == null) return false
        if (!isSettingsPackage(packageName)) return false

        val className = event?.className?.toString() ?: ""
        val detailClassHints = listOf(
            "AccessibilitySettings",
            "AccessibilityService",
            "ToggleAccessibilityService",
            "InstalledServiceDetails",
            "AccessibilityServiceDetails"
        )
        val isAccessibilityClass = detailClassHints.any { className.contains(it, ignoreCase = true) }
        val isAccessibilityPage = isAccessibilityClass || checkTitleForAccessibility(node)

        if (!isAccessibilityPage) return false

        // 우리 앱 이름 또는 패키지명이 화면에 있는 경우만 PIN 요구
        if (!containsAppName(node, event)) return false

        // 상세 화면이면 즉시 PIN 요구
        if (isAccessibilityClass) return true

        // 토글 클릭 시 PIN 요구
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            if (isToggleNode(event.source)) {
                return true
            }
        }

        // "사용함/사용안함" 토글 관련 텍스트가 있을 때만 추가로 요구
        val toggleKeywords = listOf(
            "사용함", "사용 안함", "사용 중", "사용 안 됨",
            "켜짐", "꺼짐", "허용됨", "비활성화", "disable", "enabled"
        )
        return containsAnyText(node, toggleKeywords)
    }

    /**
     * 기기 관리자(Device Admin) 설정 페이지 감지
     */
    private fun isDeviceAdminPage(node: AccessibilityNodeInfo?, event: AccessibilityEvent?): Boolean {
        if (node == null) return false
        try {
            val keywords = listOf(
                "기기 관리자", "Device admin",
                "Phone administrators", "Device admin apps",
                "보안", "Security", "기타 보안 설정"
            )
            
            for (keyword in keywords) {
                if (!node.findAccessibilityNodeInfosByText(keyword).isNullOrEmpty()) {
                    return true
                }
            }
            return false
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * 본인 앱(소셜미디어블로커)의 설정/상세 페이지인지 확인
     */
    private fun isSelfAppDetails(node: AccessibilityNodeInfo?, event: AccessibilityEvent?): Boolean {
        if (node == null) return false
        
        try {
            val pkgName = event?.packageName?.toString() ?: ""
            if (!isSystemUiPackage(pkgName)) {
                return false
            }

            // 앱 이름 또는 패키지명이 화면에 보이는지 확인
            // 삼성의 경우 "애플리케이션 정보" 화면의 헤더 Title이 앱 이름임
            val appName = getString(R.string.app_name)
            val packageName = applicationContext.packageName
            val appNameNodes = node.findAccessibilityNodeInfosByText(appName)
            val englishNameNodes = node.findAccessibilityNodeInfosByText("SocialMediaBlocker")
            val packageNameNodes = node.findAccessibilityNodeInfosByText(packageName)
            val eventTexts = event?.text?.joinToString(" ") ?: ""
            val isSelfNameVisible = !appNameNodes.isNullOrEmpty() ||
                !englishNameNodes.isNullOrEmpty() ||
                !packageNameNodes.isNullOrEmpty() ||
                eventTexts.contains(appName) ||
                eventTexts.contains(packageName)
            
            if (isSelfNameVisible) {
                val className = event?.className?.toString() ?: ""
                val appInfoClassHints = listOf(
                    "InstalledAppDetails",
                    "AppInfo",
                    "AppDetails",
                    "ManageApplications",
                    "ApplicationInfo",
                    "AppDetailsActivity"
                )

                if (appInfoClassHints.any { className.contains(it, ignoreCase = true) }) {
                    Log.i(TAG, "Detected Self App Details Page via class: $className")
                    return true
                }

                // 앱 이름이 화면에 있으면, "설정" 관련 화면일 확률이 매우 높음
                // 추가 확인: '삭제', '강제 중지', '열기', '권한' 등 설정 버튼이 있는지 확인
                val settingKeywords = listOf(
                    "삭제", "제거", "uninstall", 
                    "강제 중지", "force stop", 
                    "열기", "open",
                    "저장공간", "storage",
                    "애플리케이션 정보", "app info", "앱 정보", "application info",
                    "권한", "permissions", "알림", "notifications", "배터리", "battery"
                )
                
                for (keyword in settingKeywords) {
                    if (!node.findAccessibilityNodeInfosByText(keyword).isNullOrEmpty()) {
                        Log.i(TAG, "Detected Self App Details Page via keyword: $keyword")
                        return true
                    }
                }
                
                // 만약 키워드를 못 찾았더라도, 시스템 UI이면 의심
                if (isSystemUiPackage(pkgName)) {
                    return true
                }
            }
            
            return false
        } catch (e: Exception) {
            return false
        }
    }

    private fun isUninstallFlow(node: AccessibilityNodeInfo?, event: AccessibilityEvent?): Boolean {
        if (node == null) return false
        try {
            val pkgName = event?.packageName?.toString() ?: ""
            if (!isSystemUiPackage(pkgName)) {
                return false
            }

            val className = event?.className?.toString() ?: ""
            val uninstallClassHints = listOf(
                "Uninstall",
                "DeletePackage",
                "PackageInstaller",
                "AppNotResponding",
                "DeviceAdminAdd"
            )
            val classMatch = uninstallClassHints.any { className.contains(it, ignoreCase = true) }

            if (!classMatch && !containsAnyText(node, listOf("삭제", "제거", "uninstall", "remove", "권한 해제"))) {
                return false
            }

            return containsAppName(node, event)
        } catch (e: Exception) {
            return false
        }
    }
    
    /**
     * PIN 확인 Activity 띄우기
     */
    private fun showPinVerification(isAppLockMode: Boolean = false) {
        try {
            if (PinVerifyActivity.isShowing) {
                return
            }

            val now = System.currentTimeMillis()
            if (now - lastPinPromptAt < PIN_PROMPT_DEBOUNCE_MS) {
                return
            }
            lastPinPromptAt = now

            val intent = Intent(this, PinVerifyActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (isAppLockMode) {
                intent.putExtra("mode", "app_lock")
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error showing PIN verification", e)
        }
    }

    private fun showBlockedOverlay() {
        try {
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager ?: return
            
            val params = android.view.WindowManager.LayoutParams(
                android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
                android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or 
                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                android.graphics.PixelFormat.TRANSLUCENT
            )
            
            params.gravity = android.view.Gravity.CENTER
            params.y = 0
            
            val textView = android.widget.TextView(this).apply {
                text = getString(R.string.blocked_msg)
                textSize = 16f
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(android.graphics.Color.DKGRAY)
                setPadding(40, 20, 40, 20)
                
                // Rounded corners
                val shape = android.graphics.drawable.GradientDrawable()
                shape.shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                shape.cornerRadius = 30f
                shape.setColor(android.graphics.Color.parseColor("#CC000000"))
                background = shape
            }
            
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    windowManager.addView(textView, params)
                    Log.i(TAG, "Overlay added successfully")
                    
                    // Remove after 2 seconds
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        try {
                            windowManager.removeView(textView)
                            Log.i(TAG, "Overlay removed")
                        } catch (e: Exception) {
                            Log.e(TAG, "Error removing overlay", e)
                        }
                    }, 2000)
                } catch (e: Exception) {
                    Log.e(TAG, "Error adding overlay", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating overlay", e)
        }
    }

    private fun shouldBypassAccessibilityEnable(): Boolean {
        val prefs = getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
        val graceUntil = prefs.getLong(KEY_ACCESSIBILITY_ENABLE_GRACE_UNTIL, 0L)
        if (graceUntil <= 0L) return false

        val now = System.currentTimeMillis()
        val withinWindow = now <= graceUntil
        if (!withinWindow) {
            prefs.edit().remove(KEY_ACCESSIBILITY_ENABLE_GRACE_UNTIL).apply()
        }
        return withinWindow
    }

    private fun containsAppName(node: AccessibilityNodeInfo?, event: AccessibilityEvent?): Boolean {
        if (node == null) return false
        val appName = getString(R.string.app_name)
        val packageName = applicationContext.packageName
        val appNameNodes = node.findAccessibilityNodeInfosByText(appName)
        val englishNameNodes = node.findAccessibilityNodeInfosByText("SocialMediaBlocker")
        val packageNameNodes = node.findAccessibilityNodeInfosByText(packageName)
        val eventTexts = event?.text?.joinToString(" ") ?: ""
        return !appNameNodes.isNullOrEmpty() ||
            !englishNameNodes.isNullOrEmpty() ||
            !packageNameNodes.isNullOrEmpty() ||
            eventTexts.contains(appName) ||
            eventTexts.contains(packageName)
    }

    private fun containsAnyText(node: AccessibilityNodeInfo?, keywords: List<String>): Boolean {
        if (node == null) return false
        for (keyword in keywords) {
            if (!node.findAccessibilityNodeInfosByText(keyword).isNullOrEmpty()) {
                return true
            }
        }
        return false
    }

    private fun isToggleNode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val className = node.className?.toString() ?: ""
        if (className.contains("Switch", ignoreCase = true) ||
            className.contains("Toggle", ignoreCase = true) ||
            className.contains("CheckBox", ignoreCase = true) ||
            className.contains("CompoundButton", ignoreCase = true)
        ) {
            return true
        }
        val parent = node.parent
        val parentClass = parent?.className?.toString() ?: ""
        return parentClass.contains("Switch", ignoreCase = true) ||
            parentClass.contains("Toggle", ignoreCase = true) ||
            parentClass.contains("CheckBox", ignoreCase = true) ||
            parentClass.contains("CompoundButton", ignoreCase = true)
    }

    private fun isSystemUiPackage(packageName: String): Boolean {
        val pkg = packageName.lowercase()
        return isSettingsPackage(pkg) ||
            isPackageInstallerPackage(pkg) ||
            pkg.contains("securitycenter") ||
            pkg.contains("systemmanager") ||
            pkg.contains("safecenter") ||
            pkg.contains("permissionmanager") ||
            pkg.contains("appmanager") ||
            pkg == "com.samsung.android.app.appsedge"
    }

    private fun isSettingsPackage(packageName: String): Boolean {
        val pkg = packageName.lowercase()
        return pkg.contains("settings") || pkg.contains("permissioncontroller")
    }

    private fun isPackageInstallerPackage(packageName: String): Boolean {
        val pkg = packageName.lowercase()
        return pkg.contains("packageinstaller") || pkg.contains("appinstaller")
    }

    private fun isLauncherPackage(packageName: String): Boolean {
        val pkg = packageName.lowercase()
        return pkg.contains("launcher") || pkg.contains("quickstep") || pkg.endsWith(".home")
    }


    private fun checkForYouTubeInBrowser(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        
        try {
            // Only check URL bar, not entire page content to avoid false positives
            // Look for specific View IDs used by browsers for URL bars
            val urlBarIds = listOf(
                // Chrome
                "com.android.chrome:id/url_bar",
                "com.android.chrome:id/omnibox_url_bar",
                // Firefox
                "org.mozilla.firefox:id/url_bar_title",
                "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
                // Samsung Internet
                "com.sec.android.app.sbrowser:id/location_bar_edit_text",
                "com.sec.android.app.sbrowser:id/location_bar",
                // Opera
                "com.opera.browser:id/url_field",
                // Edge
                "com.microsoft.emmx:id/url_bar",
                // AOSP Browser
                "com.android.browser:id/url",
                "com.android.browser:id/url_bar"
            )
            
            // First, try to find URL bar by ViewId
            for (urlBarId in urlBarIds) {
                val urlNodes = node.findAccessibilityNodeInfosByViewId(urlBarId)
                if (!urlNodes.isNullOrEmpty()) {
                    for (urlNode in urlNodes) {
                        try {
                            val text = urlNode.text?.toString() ?: continue
                            if (blockedKeywords.any { text.contains(it, ignoreCase = true) } || isBlockedUrl(text)) {
                                Log.w(TAG, "Blocked URL detected in address bar: $text")
                                return true
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error checking URL node", e)
                        }
                    }
                }
            }
            
            // Fallback: check all relevant nodes for URLs
            // Expanded to include TextViews for apps like KakaoTalk
            return checkNodesForUrl(node)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in checkForYouTubeInBrowser", e)
            return false
        }
    }
    
    /**
     * 재귀적으로 URL을 포함한 노드 찾기
     * EditText, TextView, Button 등 텍스트가 있는 모든 노드 검사
     */
    /**
     * 웹뷰 및 브라우저 콘텐츠 검사
     * 1. WebView 클래스가 있는지 확인
     * 2. WebView 내부 또는 주소창에서 차단 키워드/URL 감지
     */
    private fun checkNodesForUrl(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        
        try {
            // EditText 취약점: 입력 가능한 필드(EditText 등)는 검사하지 않음 (타이핑 방해 방지)
            if (node.isEditable) return false

            val className = node.className?.toString()
            val text = node.text?.toString()
            val contentDesc = node.contentDescription?.toString()
            val viewId = node.viewIdResourceName?.lowercase()
            
            // 1. WebView 감지 (카카오톡 #검색 등은 WebView 사용)
            if (className == "android.webkit.WebView") {
                // WebView 내부 콘텐츠 검사 시작
                if (checkWebViewContent(node)) {
                    return true
                }
            }

            // 1-1. URL 바로 추정되는 ViewId 검사
            if (viewId != null && (
                viewId.contains("url_bar") ||
                viewId.contains("address") ||
                viewId.contains("omnibox") ||
                viewId.contains("location_bar")
            )) {
                val checkText = (text ?: "") + " " + (contentDesc ?: "")
                if (checkText.isNotBlank() && (blockedKeywords.any { checkText.contains(it, ignoreCase = true) } || isBlockedUrl(checkText))) {
                    Log.w(TAG, "Blocked URL detected in viewId($viewId): $checkText")
                    return true
                }
            }
            
            // 2. 텍스트/콘텐츠 검사
            // 주의: 일반 TextView나 Button을 검사하면 크롬 시작 페이지의 '북마크' 아이콘 등을 URL로 오인하여 차단함
            // 따라서 EditText(주소창 후보)가 아니면 일반 텍스트 문맥 검사는 건너뜀
            if (className == "android.widget.EditText" || className?.contains("EditText") == true) {
                 if (text != null || contentDesc != null) {
                    val checkText = (text ?: "") + " " + (contentDesc ?: "")
                    
                    // URL 형식 확인 (http, www 등)
                    if (checkText.length > 3 && checkText.contains(".")) {
                         if (isBlockedUrl(checkText)) {
                            Log.w(TAG, "Blocked URL detected in $className: $checkText")
                            return true
                        }
                    }
                }
            }
            
            // 자식 노드 순회
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                
                try {
                    if (checkNodesForUrl(child)) {
                        child.recycle()
                        return true
                    }
                    child.recycle()
                } catch (e: Exception) {
                    Log.e(TAG, "Error checking child node", e)
                }
            }
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error in checkNodesForUrl", e)
            return false
        }
    }

    /**
     * WebView 내부 콘텐츠 재귀 검색
     * WebView 내부에서는 URL 형식이 아니더라도 'YouTube' 같은 키워드가 보이면 차단 (더 강력함)
     */
    private fun checkWebViewContent(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        
        try {
            // **중요**: 입력 가능한 필드(EditText 등)는 검사하지 않음 (타이핑 방해 방지)
            if (node.isEditable) {
                return false
            }

            val text = node.text?.toString()
            val contentDesc = node.contentDescription?.toString()
            
            // 검사할 텍스트 통합
            val contentToCheck = ((text ?: "") + " " + (contentDesc ?: "")).lowercase()
            
            // 차단 키워드 확인
            // 1. 단순 "keyboard"나 "suggestion" 등은 제외
            // 2. 텍스트가 너무 짧으면(예: "YouTube" 버튼 하나) 오탐 가능성 있으므로, 
            //    확실한 콘텐츠(제목, URL 등)일 때만 차단
            
            if (contentToCheck.isNotEmpty()) {
                // 입력창 관련 텍스트 제외
                if (contentToCheck.contains("search") || contentToCheck.contains("검색") || contentToCheck.contains("입력")) {
                    return false
                } 
                
                // Block 'youtube' only if it has video metadata, is long content, OR matches specific URL patterns
                
                if (contentToCheck.contains("youtube") || contentToCheck.contains("youtu.be")) {
                    
                    // A. 구체적인 URL 패턴 확인 (단순 'youtube.com' 텍스트는 북마크일 수 있어 제외)
                    // 실제로 접속했을 때 주소창이나 페이지 내용에 포함되는 패턴들
                    if (contentToCheck.contains("m.youtube.com") || 
                        contentToCheck.contains("www.youtube.com") ||
                        contentToCheck.contains("/watch") ||
                        contentToCheck.contains("/shorts") ||
                        contentToCheck.contains("youtu.be")) {
                        Log.w(TAG, "Blocked Specific URL Pattern inside WebView: $contentToCheck")
                        return true
                    }
                    
                    // B. 비디오 관련 메타데이터 포함 (검색 제안이 아닌 실제 콘텐츠일 확률 높음)
                    val videoKeywords = listOf(
                        "조회수", "views", 
                        "구독", "subscri", 
                        "좋아요", "like", 
                        "싫어요", "dislike", 
                        "공유", "share",
                        "저장", "save",
                        "댓글", "comment",
                        "스트리밍", "stream",
                        "실시간", "live",
                        "게시", "posted",
                        "전", "ago", // 1시간 전, 1 hour ago
                        "watching", "시청",
                        // 유튜브 홈 화면 UI 요소 (하단 탭, 상단 칩 등)
                        "홈", "home",
                        "보관함", "library",
                        "맞춤 동영상", "recommended",
                        "탐색", "explore",
                        "커뮤니티", "community"
                    )
                    
                    // "Shorts"는 탭 이름이기도 하고 콘텐츠이기도 함 -> URL 패턴에 이미 포함됨 (contentToCheck.contains("shorts"))
                    
                    if (videoKeywords.any { contentToCheck.contains(it) }) {
                         Log.w(TAG, "Blocked Video/Home Content detected inside WebView: $contentToCheck")
                         return true
                    }
                    
                    // C. 아주 긴 텍스트는 여전히 차단 (단, 단순 suggestion인 15~20자 내외는 허용)
                    // 예: "youtube premium" (15) -> 허용
                    // 예: "Amazing Video Title - YouTube" (> 25) -> 차단
                    if (contentToCheck.length > 30) {
                         Log.w(TAG, "Blocked Long Content detected inside WebView: $contentToCheck")
                         return true
                    }
                }
            }
            
            // 자식 노드 순회
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                if (checkWebViewContent(child)) {
                    child.recycle()
                    return true
                }
                child.recycle()
            }
            
            return false
        } catch (e: Exception) {
             Log.e(TAG, "Error checking WebView content", e)
             return false
        }
    }
    
    /**
     * Check if URL is actually navigating to a blocked domain
     * (not just a search query or keyword mention)
     */
    private fun isBlockedUrl(url: String): Boolean {
        if (!isRepositoryReady) {
            Log.w(TAG, "Repository not ready yet")
            return false
        }
        
        val normalizedUrl = url.trim().lowercase()
        
        // Skip if it's clearly a search query
        if (normalizedUrl.contains("/search") ||
            normalizedUrl.contains("google.com/search") ||
            normalizedUrl.contains("bing.com/search") ||
            normalizedUrl.contains("naver.com/search") ||
            normalizedUrl.contains("daum.net/search")) {
            return false
        }
        
        // 1. Check URL patterns first (specific pages)
        if (isBlockedByPattern(normalizedUrl)) {
            Log.w(TAG, "Blocked by URL pattern: $normalizedUrl")
            return true
        }
        
        // 2. Check domain blocking (entire domains)
        val domain = extractDomainFromUrl(normalizedUrl)
        if (domain.isEmpty()) return false
        
        // Check against DomainRepository
        val isBlocked = domainRepository.isBlocked(domain)
        if (isBlocked) {
            Log.w(TAG, "Blocked domain: $domain")
        }
        return isBlocked
    }
    
    /**
     * Check if URL matches any blocked URL pattern
     */
    private fun isBlockedByPattern(url: String): Boolean {
        try {
            val patterns = com.example.socialmediablocker.policy.DefaultBlocklist.URL_PATTERNS
            
            for (pattern in patterns) {
                // Check if URL contains the pattern
                if (url.contains(pattern.lowercase())) {
                    return true
                }
            }
            
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking URL patterns", e)
            return false
        }
    }
    
    /**
     * Extract domain from URL
     * e.g., "https://www.youtube.com/watch?v=..." -> "youtube.com"
     */
    private fun extractDomainFromUrl(url: String): String {
        try {
            var domain = url
            
            // Remove protocol
            domain = domain.removePrefix("http://")
            domain = domain.removePrefix("https://")
            
            // Remove www.
            domain = domain.removePrefix("www.")
            domain = domain.removePrefix("m.")
            
            // Remove path and query
            val slashIndex = domain.indexOf('/')
            if (slashIndex > 0) {
                domain = domain.substring(0, slashIndex)
            }
            
            val questionIndex = domain.indexOf('?')
            if (questionIndex > 0) {
                domain = domain.substring(0, questionIndex)
            }
            
            return domain
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting domain from URL", e)
            return ""
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service Interrupted")
    }

    private fun getLastPinVerificationTime(): Long {
        val prefs = getSharedPreferences("pin_prefs", Context.MODE_PRIVATE)
        return prefs.getLong("last_verified_time", 0)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Accessibility Service Connected Successfully")

        // 접근성 권한 활성화 직후에는 앱으로 복귀
        handlePendingAccessibilityEnable()
        
        // Initialize DomainRepository
        try {
            domainRepository = DomainRepository(applicationContext)
            serviceScope.launch {
                try {
                    domainRepository.initialize()
                    isRepositoryReady = true
                    val count = domainRepository.getCount()
                    Log.i(TAG, "Domain repository initialized with $count blocked domains")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initialize domain repository", e)
                    isRepositoryReady = false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating domain repository", e)
        }
    }
    
    override fun onUnbind(intent: Intent?): Boolean {
        Log.w(TAG, "Service Unbound")
        return super.onUnbind(intent)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    private fun handlePendingAccessibilityEnable() {
        val prefs = getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
        val requestedAt = prefs.getLong(KEY_PENDING_ACCESSIBILITY_ENABLE_TIME, 0L)
        if (requestedAt <= 0L) return

        val now = System.currentTimeMillis()
        if (now - requestedAt <= ACCESSIBILITY_ENABLE_GRACE_MS) {
            prefs.edit()
                .remove(KEY_PENDING_ACCESSIBILITY_ENABLE_TIME)
                .putLong(KEY_ACCESSIBILITY_ENABLE_GRACE_UNTIL, now + ACCESSIBILITY_ENABLE_GRACE_MS)
                .apply()
            try {
                val intent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Error returning to MainActivity after accessibility enable", e)
            }
        } else {
            prefs.edit()
                .remove(KEY_PENDING_ACCESSIBILITY_ENABLE_TIME)
                .remove(KEY_ACCESSIBILITY_ENABLE_GRACE_UNTIL)
                .apply()
        }
    }
    
    companion object {
        private const val TAG = "BlockerService"
        private const val APP_PREFS = "app_prefs"
        private const val KEY_PENDING_ACCESSIBILITY_ENABLE_TIME = "pending_accessibility_enable_time"
        private const val KEY_ACCESSIBILITY_ENABLE_GRACE_UNTIL = "accessibility_enable_grace_until"
    }
}
