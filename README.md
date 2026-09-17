# Fit3 Proxy — Galaxy Fit3 Bridge (Phase 1)

갤럭시 핏3(Galaxy Fit3)의 기본 **음악 컨트롤러**를 가상 `MediaSessionCompat`으로 가로채어, 손목에서 조명/전원 등 데모 슬롯을 제어하는 안드로이드 브릿지 앱입니다.

본 저장소는 SOW **Phase 1: MediaSession 프로토타입** 구현입니다. (인메모리 데모 슬롯만 사용, 네트워크 없음)

## 무엇을 하나요?

| 핏3 음악 버튼 | 앱 동작 |
|---|---|
| Play / Pause | 현재 슬롯 ON/OFF 토글 |
| Next / Previous | 슬롯 캐러셀 이동 (4개 데모) |
| Fast Forward / Rewind | 밝기 ±10% (밝기 지원 슬롯만) |

메타데이터 전광판:

- **Title** → `[조명 제어] 거실 전등`
- **Artist** → `상태: ON | 밝기: 75%`
- **Album** → `Menu [1/4] Next로 이동`

## 패키지 구조

```
com.madmaxbunny.fit3proxy
├── model/      ControlSlot, SlotRepository (인메모리)
├── session/    Fit3MediaSessionManager (MediaSession + soft AudioFocus)
├── service/    Fit3ProxyForegroundService (mediaPlayback FGS)
└── ui/         MainActivity (Material 대시보드)
```

## 요구 사항

- Android Studio Hedgehog+ (또는 AGP 8.5 호환)
- JDK 17
- minSdk 26 / targetSdk 34 / compileSdk 34
- 실제 검증: Galaxy Fit3 + Galaxy Wearable가 연결된 삼성 폰 권장

## 빌드 / 실행

1. Android Studio에서 `/Fit3Proxy` 폴더를 Open
2. SDK 34 설치 후 Sync Gradle
3. Run ▶ `app` (실기기 권장)

CLI:

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Galaxy Fit3에서 검증하는 방법

1. 폰에 앱 설치 후 **MediaSession 가동** 스위치 ON  
   → 포그라운드 알림이 뜨고 세션이 `STATE_PLAYING`으로 유지됩니다.
2. **Galaxy Wearable** 앱에서  
   - 알림 권한: Fit3 Proxy 허용  
   - **음악 제어 / 미디어 컨트롤러**에 이 앱(또는 현재 재생 앱)이 노출되는지 확인  
3. 핏3에서 음악(미디어) 화면을 엽니다.  
   Title/Artist가 대시보드 미리보기와 같으면 성공입니다.
4. Play·Next·Prev·FF·REW를 눌러 폰 앱의 **이벤트 로그**와 Logcat 태그 `Fit3MediaSession`에 콜백이 찍히는지 확인합니다.
5. 스위치를 OFF 하면 세션·AudioFocus·FGS가 해제되어 다른 음악 앱과의 충돌을 피합니다 (soft AudioFocus).

> 다른 음악 앱(Spotify 등)이 포커스를 가져가면 핏3 위젯이 그쪽으로 넘어갈 수 있습니다. 제어 모드일 때만 스위치를 켜세요.

## Soft AudioFocus

세션 스위치가 켜져 있을 때만 `AUDIOFOCUS_GAIN`을 요청하고, 끄면 즉시 `abandon` 합니다. 실제 오디오는 재생하지 않습니다.

## Phase 범위

- ✅ Phase 1: MediaSession + 메타데이터 + FGS + 대시보드 로그
- ⏳ Phase 2: Notification Actions / RemoteInput
- ⏳ Phase 3: Webhook / MQTT 슬롯 매핑
- ⏳ Phase 4: Doze·재연결 안정화

상세 명세: [docs/SOW.md](docs/SOW.md)

---

## English — Build & Verify (short)

**Build:** Open in Android Studio (JDK 17, SDK 34) → Sync → Run, or `./gradlew assembleDebug`.

**applicationId:** `com.madmaxbunny.fit3proxy`

**Fit3 verify:** Enable the session switch → allow notification + **music control** for this app in **Galaxy Wearable** → open the Fit3 media/music controls → confirm Title/Artist match the phone preview → press Play/Next/Prev/FF/REW and watch the in-app event log / Logcat (`Fit3MediaSession`). Turn the switch off when done so other players can take AudioFocus.
