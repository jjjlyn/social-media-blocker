package com.example.socialmediablocker

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import com.example.socialmediablocker.data.repository.DomainRepository
import com.example.socialmediablocker.policy.DefaultBlocklist
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
        "com.facebook.katana", // Facebook

        // Community (Korean)
        "com.dcinside.app", // DCInside
        "net.instiz.www.instiz", // 인스티즈
        "com.mobile.app.today.humor", // 오늘의 유머(오유)
        "co.kr.bobaedream" // 보배드림
    )

    private lateinit var domainRepository: DomainRepository
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRepositoryReady = false

    // PIN 인증 후 재요구 방지 타이머
    private var lastPinVerificationTime: Long = 0
    private val PIN_COOLDOWN_MS = 60000L // 60초 (설정화면 등 민감한 동작용)

    // Accessibility 권한 활성화 직후 예외 처리 (앱 복귀용)
    private val ACCESSIBILITY_ENABLE_GRACE_MS = 15000L // 15초 (초기 활성화 직후만)

    private val kakaoTalkPackageName = "com.kakao.talk"
    private val browserPackageIdentifiers = listOf(
        "chrome", "browser", "firefox", "opera", "internet", "samsungbrowser", "sec.",
        "com.android.chrome", "com.google.android.apps.chrome", "com.microsoft.emmx",
        "com.sec.android.app.sbrowser", "org.mozilla.firefox", "com.opera.browser",
        "com.opera.mini.native", "com.duckduckgo.mobile.android", "com.brave.browser",
        "com.kiwibrowser.browser", "com.vivaldi.browser", "com.uc.browser"
    )
    private val possibleUrlPattern = Regex(
        """(?i)(?:https?://)?(?:www\.)?[a-z0-9][a-z0-9-]*(?:\.[a-z0-9][a-z0-9-]*)+\.[a-z]{2,}(?:/[^\s]*)?"""
    )
    private val browserReentryBlockMs = 2800L
    private var browserReentryBlockUntil = 0L
    private var browserReentryPackage: String = ""
    private var lastBlockUiActionAt = 0L
    private val BLOCK_UI_THROTTLE_MS = 1200L
    private val BLOCK_MSG_DURATION_MS = 900L
    private var blockedOverlayView: android.widget.TextView? = null
    private var blockedOverlayRemoveTask: Runnable? = null
    private var blockedOverlayShowTask: Runnable? = null
    private val blockedOverlayHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var blockedOverlaySequence = 0
    private var browserBlockRetryTask: Runnable? = null
    private val browserFullScanThrottle = HashMap<String, Long>()
    private val BROWSER_FULL_SCAN_THROTTLE_MS = 160L
    private val BROWSER_BLOCK_RETRY_COUNT = 4
    private val BROWSER_BLOCK_RETRY_INTERVAL_MS = 160L
    private val BROWSER_SCAN_MAX_DEPTH = 8

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
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
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
                triggerBrowserBlockExit(packageName)
                return
            }

            if (packageName != kakaoTalkPackageName &&
                isBrowserPackage(packageName) &&
                shouldKeepBrowserBlocked(packageName)) {
                if (isBrowserStillBlocking(packageName)) {
                    Log.w(TAG, "Reentry guard active, forcing browser block: $packageName")
                    triggerBrowserBlockExit(packageName)
                    return
                }
                clearBrowserReentryState()
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
            if (isBrowserPackage(packageName)) {
                val eventText = eventTextForUrlDetection(event)
                if (eventText.isNotBlank() && isBlockedByTextContent(eventText.lowercase())) {
                    Log.w(TAG, "Blocked Website by browser event text (aggregated): $eventText")
                    triggerBrowserBlockExit(packageName)
                    return
                }

                // 3-1. 주소 입력 텍스트 기반 즉시 탐지
                val sourceText = event.source?.let { node ->
                    ((node.text?.toString() ?: "") + " " + (node.contentDescription?.toString() ?: "")).trim()
                }.orEmpty()
                if (sourceText.isNotBlank() && isBlockedByTextContent(sourceText.lowercase())) {
                    Log.w(TAG, "Blocked Website by browser source text: $sourceText")
                    triggerBrowserBlockExit(packageName)
                    return
                }

                Log.d(TAG, "Browser package detected: $packageName, eventType=${event.eventType}, class=${event.className}")

                if (shouldRunBrowserFullScan(packageName, event.eventType)) {
                    // Strategy 1: Check rootInActiveWindow
                    val rootNode = rootInActiveWindow
                    if (rootNode != null) {
                        Log.d(TAG, "rootInActiveWindow available, class=${rootNode.className}")
                        if (isNodeBrowserBlocked(rootNode, packageName)) {
                            Log.w(TAG, "Blocked Website by browser check (rootInActiveWindow)")
                            triggerBrowserBlockExit(packageName)
                            return
                        }
                    }
                
                    // Strategy 3: Check ALL windows (for multi-window / Custom Tabs scenarios)
                    try {
                        val allWindows = windows
                        if (allWindows != null) {
                            Log.d(TAG, "Checking ${allWindows.size} windows")
                            for (window in allWindows) {
                                val windowRoot = window.root ?: continue
                                Log.d(TAG, "Window: type=${window.type}, pkg=${windowRoot.packageName}")
                                
                                if (isNodeBrowserBlocked(windowRoot, packageName)) {
                                    Log.w(TAG, "Blocked Website by browser check (multi-window) in window type=${window.type}")
                                    triggerBrowserBlockExit(packageName)
                                    windowRoot.recycle()
                                    return
                                }
                                windowRoot.recycle()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error checking windows", e)
                    }
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
            
            blockedOverlaySequence++
            val thisSequence = blockedOverlaySequence

            val existingOverlay = blockedOverlayView
            if (existingOverlay != null) {
                try {
                    windowManager.removeView(existingOverlay)
                    Log.i(TAG, "Removed previous overlay")
                } catch (e: Exception) {
                    Log.e(TAG, "Error removing previous overlay", e)
                }
                blockedOverlayView = null
            }

            blockedOverlayShowTask?.let { oldShowTask ->
                blockedOverlayHandler.removeCallbacks(oldShowTask)
            }
            blockedOverlayRemoveTask?.let { oldRemoveTask ->
                blockedOverlayHandler.removeCallbacks(oldRemoveTask)
            }

            val showTask = Runnable {
                if (thisSequence != blockedOverlaySequence) return@Runnable

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

                try {
                    windowManager.addView(textView, params)
                    blockedOverlayView = textView
                    Log.i(TAG, "Overlay added successfully")

                    val removeTask = Runnable {
                        if (thisSequence != blockedOverlaySequence) return@Runnable
                        val overlay = blockedOverlayView
                        if (overlay == null || overlay != textView) return@Runnable
                        try {
                            windowManager.removeView(overlay)
                            Log.i(TAG, "Overlay removed")
                        } catch (e: Exception) {
                            Log.e(TAG, "Error removing overlay", e)
                        } finally {
                            if (blockedOverlayView == overlay) {
                                blockedOverlayView = null
                            }
                            if (thisSequence == blockedOverlaySequence) {
                                blockedOverlayRemoveTask = null
                                blockedOverlayShowTask = null
                            }
                        }
                    }
                    blockedOverlayRemoveTask = removeTask
                    blockedOverlayHandler.postDelayed(removeTask, BLOCK_MSG_DURATION_MS)
                } catch (e: Exception) {
                    Log.e(TAG, "Error adding overlay", e)
                } finally {
                    if (blockedOverlayView == null) {
                        blockedOverlayShowTask = null
                    }
                }
            }

            blockedOverlayShowTask = showTask
            blockedOverlayHandler.post(showTask)
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

    private fun isBlockedByTextContent(text: String): Boolean {
        if (text.isBlank()) return false

        val normalizedText = normalizeForUrlMatching(text)
        if (!isRepositoryReady) {
            val fallbackPatterns = DefaultBlocklist.getAllBlockedDomains()
            for (match in possibleUrlPattern.findAll(normalizedText)) {
                val candidate = match.value.trim()
                    .trimEnd('.', ',', ';', ':', ')', ']', '}', '!', '?', '\"', '\'', '>')
                if (candidate.isBlank()) continue

                val domain = extractDomainFromUrl(candidate)
                if (domain.isEmpty() || isWhitelistedDomain(domain)) continue

                if (fallbackPatterns.any { pattern ->
                        if (pattern.startsWith("*.")) {
                            val base = pattern.removePrefix("*.")
                            domain == base || domain.endsWith(".$base")
                        } else {
                            domain == pattern
                        }
                    }) {
                    return true
                }
            }
            if (containsBlockedDomainByTokens(normalizedText)) {
                return true
            }
            return false
        }

        for (match in possibleUrlPattern.findAll(normalizedText)) {
            val candidate = match.value.trim()
                .trimEnd('.', ',', ';', ':', ')', ']', '}', '!', '?', '"', '\'', '>')
            if (candidate.isNotBlank() && isBlockedUrl(candidate)) {
                return true
            }
        }
        if (containsBlockedDomainByTokens(normalizedText)) {
            return true
        }
        return false
    }

    private fun containsBlockedDomainByTokens(text: String): Boolean {
        if (text.isBlank()) return false

        val tokens = text
            .replace(Regex("[\\u0000-\\u001F\\u007F-\\u009F]"), " ")
            .split(Regex("\\s+"))

        for (tokenRaw in tokens) {
            var token = tokenRaw.trim().lowercase()
            if (token.isBlank()) continue

            if (token.contains("://")) {
                token = token.substringAfter("://", token)
            }
            token = token.trim('(', ')', '[', ']', '{', '}', '<', '>', ',', ';', ':', '.', '!', '?', '"', '\'')

            val hasPotentialDomain = token.contains(".") && (token.contains("www.") || token.contains(".com") || token.contains(".co.kr") ||
                    token.contains(".net") || token.contains(".org") || token.contains(".kr") ||
                    token.contains(".tv") || token.contains(".io"))
            if (!hasPotentialDomain) continue

            val domain = extractDomainFromUrl(token)
            if (domain.isBlank() || isWhitelistedDomain(domain)) continue

            val isBlocked = if (isRepositoryReady) {
                domainRepository.isBlocked(domain)
            } else {
                DefaultBlocklist.getAllBlockedDomains().any { pattern ->
                    if (pattern.startsWith("*.")) {
                        val base = pattern.removePrefix("*.")
                        domain == base || domain.endsWith(".$base")
                    } else {
                        domain == pattern
                    }
                }
            }

            if (isBlocked) {
                return true
            }
        }
        return false
    }

    private fun normalizeForUrlMatching(text: String): String {
        return text
            .lowercase()
            .replace('\u00A0', ' ')
            .replace(Regex("[\\u200B\\u200C\\u200D\\uFEFF]"), "")
    }

    private fun triggerBrowserBlockExit(lockPackage: String = "") {
        val now = System.currentTimeMillis()
        if (now - lastBlockUiActionAt < BLOCK_UI_THROTTLE_MS) {
            return
        }
        lastBlockUiActionAt = now
        if (lockPackage.isNotBlank() &&
            isBrowserPackage(lockPackage) &&
            lockPackage != kakaoTalkPackageName) {
            saveBrowserReentryState(lockPackage, now + browserReentryBlockMs)
        }

        showBlockedOverlay()
        browserBlockRetryTask?.let { blockedOverlayHandler.removeCallbacks(it) }
        browserBlockRetryTask = null
        blockedOverlayHandler.post {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
        scheduleBrowserBackRetry(lockPackage, BROWSER_BLOCK_RETRY_COUNT)
    }

    private fun scheduleBrowserBackRetry(lockPackage: String, remainingAttempts: Int) {
        if (remainingAttempts <= 0) return

        val retryTask = Runnable {
            if (lockPackage.isBlank() || !isBrowserPackage(lockPackage)) return@Runnable
            if (!shouldKeepBrowserBlocked(lockPackage)) {
                clearBrowserReentryState()
                return@Runnable
            }

            if (!isBrowserStillBlocking(lockPackage)) {
                clearBrowserReentryState()
                return@Runnable
            }

            Log.w(TAG, "Retrying browser back action for blocked package: $lockPackage, remaining=$remainingAttempts")
            performGlobalAction(GLOBAL_ACTION_BACK)
            scheduleBrowserBackRetry(lockPackage, remainingAttempts - 1)
        }
        browserBlockRetryTask = retryTask
        blockedOverlayHandler.postDelayed(retryTask, BROWSER_BLOCK_RETRY_INTERVAL_MS.toLong())
    }

    private fun isBrowserStillBlocking(packageName: String): Boolean {
        return try {
            val rootNode = rootInActiveWindow
            var blocked = false
            if (rootNode != null) {
                blocked = isNodeBrowserBlocked(rootNode, packageName)
            }

            if (!blocked) {
                val windows = windows
                if (windows != null) {
                    for (window in windows) {
                        val windowRoot = window.root ?: continue
                        try {
                            if (isNodeBrowserBlocked(windowRoot, packageName)) {
                                blocked = true
                                break
                            }
                        } finally {
                            windowRoot.recycle()
                        }
                    }
                }
            }
            blocked
        } catch (e: Exception) {
            Log.e(TAG, "Error checking browser retry state", e)
            false
        }
    }

    private fun isNodeBrowserBlocked(node: AccessibilityNodeInfo, packageName: String): Boolean {
        if (packageName != kakaoTalkPackageName && containsBlockedUrlInBrowserNode(node, 0)) {
            return true
        }
        return checkForYouTubeInBrowser(node, packageName)
    }

    private fun isBrowserPackage(packageName: String): Boolean {
        val normalizedPackageName = packageName.lowercase()
        return browserPackageIdentifiers.any { normalizedPackageName.contains(it) } ||
            normalizedPackageName == kakaoTalkPackageName
    }

    private fun shouldRunBrowserFullScan(packageName: String, _eventType: Int): Boolean {
        if (_eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            _eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            _eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
            _eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
            return true
        }

        val now = System.currentTimeMillis()
        val lastScan = browserFullScanThrottle[packageName] ?: 0L
        if (now - lastScan < BROWSER_FULL_SCAN_THROTTLE_MS) {
            return false
        }

        browserFullScanThrottle[packageName] = now
        return true
    }

    private fun eventTextForUrlDetection(event: AccessibilityEvent): String {
        val eventText = event.text.joinToString(" ")
        val contentDescription = event.contentDescription?.toString()?.trim().orEmpty()
        return listOf(eventText, contentDescription).joinToString(" ").trim()
    }

    private fun shouldKeepBrowserBlocked(packageName: String): Boolean {
        val now = System.currentTimeMillis()
        if (browserReentryBlockUntil <= now) {
            clearBrowserReentryState()
            return false
        }
        
        if (browserReentryPackage.isBlank()) {
            return false
        }
        
        if (!isBrowserPackage(packageName)) {
            return false
        }

        return true
    }

    private fun saveBrowserReentryState(lockPackage: String, until: Long) {
        val prefs = getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_BROWSER_REENTRY_PACKAGE, lockPackage)
            .putLong(KEY_BROWSER_REENTRY_UNTIL, until)
            .apply()
    }

    private fun loadBrowserReentryState() {
        val prefs = getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
        browserReentryPackage = prefs.getString(KEY_BROWSER_REENTRY_PACKAGE, "") ?: ""
        browserReentryBlockUntil = prefs.getLong(KEY_BROWSER_REENTRY_UNTIL, 0L)

        if (browserReentryBlockUntil <= System.currentTimeMillis()) {
            clearBrowserReentryState()
        }
    }

    private fun clearBrowserReentryState() {
        browserReentryPackage = ""
        browserReentryBlockUntil = 0L
        val prefs = getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_BROWSER_REENTRY_PACKAGE)
            .remove(KEY_BROWSER_REENTRY_UNTIL)
            .apply()
    }

    private fun checkForYouTubeInBrowser(node: AccessibilityNodeInfo?, packageName: String = ""): Boolean {
        if (node == null) return false

        try {
            val normalizedPackageName = packageName.lowercase()
            val isKakaoTalkBrowser = normalizedPackageName == kakaoTalkPackageName

            // 1. Detect Browser Context (WebView present in hierarchy?)
            // KakaoTalk은 앱 내 웹 접근 시만 차단 대상에 포함.
            val isBrowserContext = hasWebView(node)
            if (isKakaoTalkBrowser && !isBrowserContext) {
                return false
            }

            // Only check URL bar, not entire page content to avoid false positives
            // Look for specific View IDs used by browsers for URL bars
            val urlBarIds = listOf(
                // Chrome
                "com.android.chrome:id/url_bar",
                "com.android.chrome:id/address_bar",
                "com.android.chrome:id/omnibox_url_bar",
                "com.android.chrome:id/omnibox_text",
                "com.android.chrome:id/location_bar_edit_text",
                "com.android.chrome:id/location_bar",
                "com.android.chrome:id/search_box_text",
                "com.android.chrome:id/search_box",
                "com.android.chrome:id/url_bar_text",
                "com.android.chrome:id/toolbar",
                "com.android.chrome:id/url_bar_title",
                "com.android.chrome:id/toolbar_url_bar",
                "com.android.chrome:id/url_bar_container",
                "com.google.android.apps.chrome:id/omnibox_url_bar",
                "com.google.android.apps.chrome:id/location_bar_edit_text",
                "com.google.android.apps.chrome:id/location_bar",
                "com.google.android.apps.chrome:id/location_bar_url_text",
                "com.google.android.apps.chrome:id/url_bar_text",
                "com.google.android.apps.chrome:id/url_bar",
                // Firefox
                "org.mozilla.firefox:id/url_bar_title",
                "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
                "org.mozilla.firefox:id/url",
                "org.mozilla.firefox:id/url_bar_text",
                "org.mozilla.firefox:id/url_bar_input",
                // Samsung Internet
                "com.sec.android.app.sbrowser:id/location_bar_edit_text",
                "com.sec.android.app.sbrowser:id/location_bar",
                "com.sec.android.app.sbrowser:id/url_bar",
                "com.sec.android.app.sbrowser:id/search_box",
                "com.sec.android.app.sbrowser:id/url_bar_text",
                // Opera
                "com.opera.browser:id/url_field",
                "com.opera.browser:id/url",
                // Edge
                "com.microsoft.emmx:id/url_bar",
                "com.microsoft.emmx:id/omnibox_text",
                "com.microsoft.emmx:id/url_bar_text",
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
                            val text = (urlNode.text?.toString() ?: urlNode.contentDescription?.toString()) ?: continue
                            if (isBlockedByTextContent(text)) {
                                Log.w(TAG, "Blocked URL detected in address bar: $text")
                                return true
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error checking URL node", e)
                        }
                    }
                }
            }

            // Fallback: check only address-bar-like nodes
            return checkNodesForUrl(node, packageName, isBrowserContext)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in checkForYouTubeInBrowser", e)
            return false
        }
    }
    
    /**
     * 주소창 후보 노드에서만 URL을 검사
     */
    private fun checkNodesForUrl(node: AccessibilityNodeInfo?, packageName: String = "", isBrowserContext: Boolean = false): Boolean {
        if (node == null) return false
        
        try {
            val className = node.className?.toString()
            val text = node.text?.toString()
            val contentDesc = node.contentDescription?.toString()
            val viewId = node.viewIdResourceName?.lowercase()
            val combinedText = ((text ?: "") + " " + (contentDesc ?: "")).lowercase()

            if (isAddressCandidateNode(className, viewId, packageName, isBrowserContext) &&
                hasDomainLikeText(combinedText) &&
                isBlockedByTextContent(combinedText)) {
                Log.w(TAG, "Blocked URL detected in browser UI node: package=$packageName, class=$className, text=$combinedText")
                return true
            }
            
            // 자식 노드 순회
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                
                try {
                    if (checkNodesForUrl(child, packageName, isBrowserContext)) {
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

    private fun hasDomainLikeText(text: String): Boolean {
        if (text.isBlank()) return false

        if (possibleUrlPattern.containsMatchIn(text)) {
            return true
        }

        return text
            .replace(Regex("[\\u0000-\\u001F\\u007F-\\u009F]"), " ")
            .split(Regex("\\s+"))
            .any { token ->
                val normalized = token.trim()
                    .trim('(', ')', '[', ']', '{', '}', '<', '>', ',', ';', ':', '.', '!', '?', '\"', '\'')
                    .lowercase()
                if (normalized.isBlank()) return@any false
                val withProtocol = normalized.removePrefix("https://").removePrefix("http://")
                withProtocol.contains(".") && (withProtocol.contains("www.") || withProtocol.contains(".com") ||
                        withProtocol.contains(".co.kr") || withProtocol.contains(".net") ||
                        withProtocol.contains(".org") || withProtocol.contains(".kr") ||
                        withProtocol.contains(".tv") || withProtocol.contains(".io"))
            }
    }

    private fun isAddressCandidateNode(
        className: String?,
        viewId: String?,
        packageName: String,
        isBrowserContext: Boolean
    ): Boolean {
        val normalizedClassName = className?.lowercase() ?: ""
        val normalizedViewId = viewId?.lowercase() ?: ""
        val normalizedPackage = packageName.lowercase()
        val isKakaoTalkBrowser = normalizedPackage == kakaoTalkPackageName

        if (normalizedViewId.contains("url_bar") ||
            normalizedViewId.contains("address") ||
            normalizedViewId.contains("omnibox") ||
            normalizedViewId.contains("location_bar") ||
            normalizedViewId.contains("locationbar") ||
            normalizedViewId.contains("search_box") ||
            normalizedViewId.contains("searchbox") ||
            normalizedViewId.contains("search") ||
            normalizedViewId.contains("toolbar")) {
            return true
        }

        if (isKakaoTalkBrowser) {
            // KakaoTalk은 URL바 텍스트가 TextView로 잡히는 케이스가 있어, 웹뷰 문맥에서만 제한적으로 허용.
            return isBrowserContext && normalizedClassName.contains("edittext")
        }

        return false
    }

    private fun hasWebView(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        try {
            val className = node.className?.toString()
            if (className != null && (
                className.contains("WebView", ignoreCase = true) || 
                className.contains("webkit", ignoreCase = true) ||
                className.contains("Browser", ignoreCase = true) ||
                className.contains("WebBrowser", ignoreCase = true)
            )) {
                return true
            }
            
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    val found = hasWebView(child)
                    child.recycle()
                    if (found) return true
                }
            }
        } catch (e: Exception) {
             // Ignore
        }
        return false
    }

    private fun containsBlockedUrlInBrowserNode(node: AccessibilityNodeInfo?, depth: Int): Boolean {
        if (node == null) return false
        if (depth > BROWSER_SCAN_MAX_DEPTH) return false

        try {
            val combinedText = ((node.text?.toString() ?: "") + " " + (node.contentDescription?.toString() ?: "")).lowercase()
            if (combinedText.isNotBlank() &&
                possibleUrlPattern.containsMatchIn(combinedText) &&
                isBlockedByTextContent(combinedText)
            ) {
                return true
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try {
                    if (containsBlockedUrlInBrowserNode(child, depth + 1)) {
                        return true
                    }
                } finally {
                    child.recycle()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking blocked URL in browser node", e)
        }
        return false
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
        
        // 1. Extract domain first (for whitelist + domain-level checks)
        val domain = extractDomainFromUrl(normalizedUrl)
        if (domain.isEmpty()) return false
        
        // Whitelisted domains should never be blocked
        if (isWhitelistedDomain(domain)) {
            Log.d(TAG, "URL allowed by whitelist: $normalizedUrl")
            return false
        }
        
        // 1. Check URL patterns first (specific pages)
        if (isBlockedByPattern(normalizedUrl)) {
            Log.w(TAG, "Blocked by URL pattern: $normalizedUrl")
            return true
        }
        
        // 2. Check domain blocking (entire domains)
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

    private fun isWhitelistedDomain(domain: String): Boolean {
        return DefaultBlocklist.WHITELIST.any { pattern ->
            isWhitelistedByPattern(domain, pattern)
        }
    }

    private fun isWhitelistedByPattern(domain: String, pattern: String): Boolean {
        val normalizedDomain = domain.lowercase().trim()
        val normalizedPattern = pattern.lowercase().trim()

        if (normalizedPattern.isBlank()) return false
        if (normalizedPattern.startsWith("*.")) {
            val base = normalizedPattern.removePrefix("*.")
            return normalizedDomain == base || normalizedDomain.endsWith(".$base")
        }

        return normalizedDomain == normalizedPattern
    }
    
    /**
     * Extract domain from URL
     * e.g., "https://www.youtube.com/watch?v=..." -> "youtube.com"
     */
    private fun extractDomainFromUrl(url: String): String {
        try {
            val normalizedUrl = url
                .trim()
                .trimEnd('.', ',', ';', ':', ')', ']', '}', '!', '?', '"', '\'', '>')
                .let { if (it.contains("://")) it else "https://$it" }

            val host = Uri.parse(normalizedUrl).host ?: return ""
            return host.lowercase().trim().let {
                it.removePrefix("www.")
                    .removePrefix("m.")
            }
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
        loadBrowserReentryState()

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
        private const val KEY_BROWSER_REENTRY_PACKAGE = "browser_reentry_package"
        private const val KEY_BROWSER_REENTRY_UNTIL = "browser_reentry_until"
    }
}
