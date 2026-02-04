# SocialMediaBlocker

SocialMediaBlocker는 본인의 필요로 만든 앱입니다. 나이가 30이 넘어서도 쇼츠와 미디어를 끊지 못하는 스스로의 모습에 경각심을 느껴 제작하게 되었습니다. 앱의 접근성 옵션에서 본 서비스를 활성화하고, 가족이나 친구에게 PIN 번호를 설정을 부탁하십시오. 타인의 손을 빌린다면 '유튜브 보고 싶으니 다시 풀어달라'는 부끄러운 부탁을 하기 어려워질 것입니다.

디지털 디톡스를 위한 강력한 도구가 되기를 바랍니다.

## 주요 기능

본 애플리케이션은 접근성 옵션을 활용하여 사용자의 앱 이용 습관을 교정합니다.

1. **앱 접근 제어**
   - 접근성 서비스(Accessibility Service)를 활용하여 YouTube 등 대상 앱이 실행되는 것을 감지하고 차단합니다.

2. **브라우저 내 차단**
   - 브라우저를 통해 특정 커뮤니티 사이트에 접속할 경우 이를 감지하여 접근을 제한합니다.

## 설치 및 설정 방법

### 1. 사전 준비
- 안드로이드 기기 (삼성 갤럭시 시리즈 권장)
- ADB가 설치된 PC 및 USB 케이블

### 2. 빌드 및 설치
```bash
# 저장소 클론
git clone https://github.com/jjjlyn/social-media-blocker.git
cd social-media-blocker

# APK 빌드
./gradlew assembleDebug

# 기기에 앱 설치
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 3. 필수 설정
1. 설치된 앱을 실행합니다.
2. 부모님이나 친구에게 PIN 번호 설정을 부탁합니다.
2. 'Enable Accessibility Service'를 클릭하여 설정에서 이 앱의  접근성 권한을 허용합니다(가장 중요한 단계).

## 제거 방법

본 앱은 다음과 같은 방법으로 간편하게 제거할 수 있습니다.

- **기기 설정에서 제거**: 휴대전화 설정 > 앱 > SocialMediaBlocker > 제거
- **ADB를 이용한 제거**:
  ```bash
  adb uninstall com.example.socialmediablocker
  ```

## 법적 고지
본 애플리케이션은 사용자의 자발적인 동의 하에 생산성 향상을 목적으로 사용되어야 합니다. 타인의 기기에 무단 설치하거나 불법적인 목적으로 사용하는 행위로 발생하는 모든 책임은 사용자에게 있습니다.
