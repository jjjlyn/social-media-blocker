package com.example.socialmediablocker.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.socialmediablocker.MainActivity
import com.example.socialmediablocker.R
import com.example.socialmediablocker.data.repository.DomainRepository
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * VPN 기반 도메인 차단 서비스
 * 모든 네트워크 트래픽을 가로채서 차단 도메인 필터링
 */
class BlockerVpnService : VpnService() {
    
    private var vpnInterface: ParcelFileDescriptor? = null
    private var isRunning = false
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private lateinit var domainRepository: DomainRepository
    private lateinit var packetHandler: PacketHandler
    
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "VPN Service created")
        
        try {
            domainRepository = DomainRepository(applicationContext)
            packetHandler = PacketHandler(domainRepository)
            
            // Repository 초기화를 백그라운드에서 실행
            serviceScope.launch {
                try {
                    domainRepository.initialize()
                    val count = domainRepository.getCount()
                    Log.i(TAG, "Domain repository initialized with $count domains")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initialize repository", e)
                    // Continue without repository - will allow all traffic
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate", e)
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "VPN Service starting...")
        
        if (isRunning) {
            Log.w(TAG, "VPN already running")
            return START_STICKY
        }
        
        try {
            // Foreground service notification with specialUse type
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    createNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
            }
            
            // VPN 설정 및 시작
            setupVpn()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN service", e)
            stopSelf()
            return START_NOT_STICKY
        }
        
        return START_STICKY
    }
    
    private fun setupVpn() {
        try {
            Log.i(TAG, "Setting up VPN interface...")
            
            val builder = Builder()
                .setSession("SocialMediaBlocker VPN")
                .addAddress("10.0.0.2", 24) // VPN 가상 IP
                .addRoute("0.0.0.0", 0) // 모든 트래픽 라우팅
                .addDnsServer("8.8.8.8") // Google DNS
                .addDnsServer("8.8.4.4")
                .setBlocking(false) // Non-blocking mode
            
            vpnInterface = builder.establish()
            
            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface")
                stopSelf()
                return
            }
            
            isRunning = true
            Log.i(TAG, "VPN established successfully")
            
            // 패킷 처리 시작
            startPacketProcessing()
            
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception - VPN permission not granted", e)
            stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup VPN", e)
            stopSelf()
        }
    }
    
    private fun startPacketProcessing() {
        val vpnFd = vpnInterface ?: return
        
        serviceScope.launch {
            try {
                val inputStream = FileInputStream(vpnFd.fileDescriptor)
                val outputStream = FileOutputStream(vpnFd.fileDescriptor)
                
                val buffer = ByteBuffer.allocate(32767) // Max IP packet size
                
                Log.i(TAG, "Packet processing started")
                
                while (isRunning && !Thread.currentThread().isInterrupted) {
                    try {
                        buffer.clear()
                        val length = inputStream.read(buffer.array())
                        
                        if (length > 0) {
                            buffer.limit(length)
                            
                            // 패킷 처리
                            val result = packetHandler.processPacket(buffer)
                            
                            // 결과 전송 (차단되지 않은 경우)
                            if (result != null) {
                                outputStream.write(result.array(), 0, result.limit())
                            }
                        }
                    } catch (e: Exception) {
                        if (isRunning) {
                            Log.e(TAG, "Error processing packet", e)
                        }
                        // Continue processing other packets
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(TAG, "Error in packet processing loop", e)
                }
            } finally {
                Log.i(TAG, "Packet processing stopped")
            }
        }
    }
    
    override fun onDestroy() {
        Log.i(TAG, "VPN Service destroying...")
        
        isRunning = false
        
        vpnInterface?.close()
        vpnInterface = null
        
        serviceScope.cancel()
        
        super.onDestroy()
        Log.i(TAG, "VPN Service destroyed")
    }
    
    override fun onRevoke() {
        Log.w(TAG, "VPN revoked by user")
        isRunning = false
        stopSelf()
    }
    
    private fun createNotification(): Notification {
        createNotificationChannel()
        
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("YouTube Blocker Active")
            .setContentText("Blocking YouTube and community sites")
            .setSmallIcon(android.R.drawable.ic_secure)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VPN Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "YouTube Blocker VPN Service"
            }
            
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
    
    companion object {
        private const val TAG = "BlockerVpnService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "vpn_service_channel"
    }
}
