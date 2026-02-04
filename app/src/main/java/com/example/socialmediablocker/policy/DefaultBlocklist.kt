package com.example.socialmediablocker.policy

/**
 * 기본 차단 도메인 리스트
 */
object DefaultBlocklist {
    
    /**
     * YouTube 관련 도메인
     */
    val YOUTUBE_DOMAINS = listOf(
        "youtube.com",
        "www.youtube.com",
        "m.youtube.com",
        "youtu.be",
        "youtube-nocookie.com",
        "youtubei.googleapis.com",
        "*.youtube.com",
        "*.googlevideo.com", // YouTube 비디오 서버
        "youtubeeducation.com",
        "*.youtubeeducation.com",
        "youtubekids.com",
        "*.youtubekids.com"
    )
    
    /**
     * 한국 커뮤니티 사이트
     */
    val KOREAN_COMMUNITIES = listOf(
        // Blind
        "blind.com",
        "www.blind.com",
        "teamblind.com",
        "*.teamblind.com",
        
        // DCInside
        "dcinside.com",
        "www.dcinside.com",
        "m.dcinside.com",
        "gall.dcinside.com",
        "*.dcinside.com",
        
        // Clien
        "clien.net",
        "www.clien.net",
        "m.clien.net",
        
        // MLBPARK
        "mlbpark.donga.com",
        
        // theqoo
        "theqoo.net",
        "www.theqoo.net",
        "m.theqoo.net",
        "*.theqoo.net",
        
        // Instiz
        "instiz.net",
        "www.instiz.net",
        "*.instiz.net",
        
        // FMKOREA
        "fmkorea.com",
        "*.fmkorea.com",
        
        // Ruliweb
        "ruliweb.com",
        "*.ruliweb.com",
        
        // SLRClub
        "slrclub.com",
        "*.slrclub.com",
        
        // 아프리카TV
        "afreecatv.com",
        "www.afreecatv.com",
        "m.afreecatv.com",
        "*.afreecatv.com",
        "play.afreecatv.com",
        
        // 트위치
        "twitch.tv",
        "www.twitch.tv",
        "m.twitch.tv",
        "*.twitch.tv",
        
        // 인스타그램 (웹)
        "instagram.com",
        "www.instagram.com",
        "*.instagram.com",
        
        // 페이스북 (웹)
        "facebook.com",
        "www.facebook.com",
        "m.facebook.com",
        "*.facebook.com",
        
        // TikTok (웹)
        "tiktok.com",
        "www.tiktok.com",
        "m.tiktok.com",
        "*.tiktok.com"
    )
    
    /**
     * 화이트리스트 - 절대 차단하면 안 되는 도메인
     */
    val WHITELIST = listOf(
        "google.com",
        "*.google.com",
        "android.com",
        "*.android.com",
        "gstatic.com",
        "*.gstatic.com",
        "googleapis.com",
        "*.googleapis.com"
    )
    
    /**
     * URL 패턴 차단 목록
     * 특정 URL 패턴만 차단 (메인 페이지는 허용)
     */
    val URL_PATTERNS = listOf(
        // 네이버 뉴스 기사 상세
        "news.naver.com/article/",
        "n.news.naver.com/article/",
        "news.naver.com/main/read",
        
        // 네이버 카페 상세
        "cafe.naver.com/",
        
        // 다음 뉴스 기사 상세
        "v.daum.net/v/",
        "news.daum.net/v/",
        "media.daum.net/v/",
        
        // 다음 카페 상세  
        "cafe.daum.net/",
        
        // 쿠팡 상품 상세
        "coupang.com/vp/products/",
        "www.coupang.com/vp/products/",
        
        // 11번가 상품 상세
        "11st.co.kr/products/",
        "www.11st.co.kr/products/",
        "m.11st.co.kr/products/",
        
        // G마켓 상품 상세
        "item.gmarket.co.kr/",
        "browse.gmarket.co.kr/",
        
        // 옥션 상품 상세
        "itempage3.auction.co.kr/",
        "browse.auction.co.kr/"
    )
    
    /**
     * 모든 차단 도메인 반환
     */
    fun getAllBlockedDomains(): List<String> {
        return YOUTUBE_DOMAINS + KOREAN_COMMUNITIES
    }
}
