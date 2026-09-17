# Fit3 Proxy — Galaxy Fit3 Bridge (Phase 2)

갤럭시 핏3(Galaxy Fit3)의 기본 **음악 컨트롤러**와 **알림 액션**을 활용해, 손목에서 조명/전원 등 데모 슬롯을 제어하고 긴급 햅틱·빠른 액션을 검증하는 안드로이드 브릿지 앱입니다.

본 저장소는 SOW **Phase 2: Notification Action 구현**까지 포함합니다. (인메모리 데모 슬롯만 사용, 네트워크/MQTT 없음)

**현재 버전:** `0.2.2-phase2-fix` (versionCode 4) — 거실 전등 등 밝기 슬롯 FF/REW 즉시 반영 수정.

## 폰에 설치하기 (APK)

최신 설치 파일은 **GitHub Releases**에서 받습니다.

- **최신 릴리스:** https://github.com/madmaxbunny/Fit3Proxy/releases/latest
- **현재 버전 다운로드:** [Fit3Proxy-0.2.2-phase2-fix-debug.apk](https://github.com/madmaxbunny/Fit3Proxy/releases/download/v0.2.2/Fit3Proxy-0.2.2-phase2-fix-debug.apk) (`v0.2.2` / versionName `0.2.2-phase2-fix` / versionCode `4`)

설치: APK를 폰으로 보낸 뒤 사이드로드 → Galaxy Wearable에서 Fit3 Proxy **알림·진동** 허용 → 앱에서 MediaSession 가동 ON.

버전 규칙·릴리스 절차는 [docs/VERSIONING.md](docs/VERSIONING.md)를 참고하세요.


## 무엇을 하나요?

### Phase 1 — MediaSession

| 핏3 음악 버튼 | 앱 동작 |
|---|---|
| Play / Pause | 현재 슬롯 ON/OFF 토글 |
| Next / Previous | 슬롯 캐러셀 이동 (4개 데모) |
| Fast Forward / Rewind | 밝기 ±10% (밝기 지원 슬롯만) |

메타데이터 전광판:

- **Title** → `[조명 제어] 거실 전등`
- **Artist** → `상태: ON | 밝기: 75%`
- **Album** → `Menu [1/4] Next로 이동`

### Phase 2 — Notification Actions & Alert

| 기능 | 설명 |
|---|---|
| 상태 채널 (무음) | FGS 상시 알림, `setOnlyAlertOnce(true)` — 메타데이터 갱신 시 핏3 진동 없음 |
| 긴급 채널 (HIGH) | 별도 진동 패턴으로 핏3 햅틱 트리거 |
| 액션 버튼 | `[재부팅]` / `[승인]` / `[스누즈]` → BroadcastReceiver → 이벤트 로그 |
| RemoteInput | `[답장]` 빠른 답장 스텁 (네트워크 없음) |
| UI 테스트 | 대시보드 **긴급 알림 테스트** 버튼 |

## 패키지 구조

```
com.madmaxbunny.fit3proxy
├── model/         ControlSlot, SlotRepository (인메모리)
├── session/       Fit3MediaSessionManager (MediaSession + soft AudioFocus)
├── service/       Fit3ProxyForegroundService (mediaPlayback FGS, silent status)
├── notification/  AlertNotifier, NotificationActionReceiver
├── log/           EventLogStore (UI 이벤트 버스)
└── ui/            MainActivity (Material 대시보드)
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

### A. Phase 1 — MediaSession

1. 폰에 앱 설치 후 **MediaSession 가동** 스위치 ON  
   → 포그라운드(상태) 알림이 뜨고 세션이 `STATE_PLAYING`으로 유지됩니다.
2. **Galaxy Wearable** 앱에서  
   - 알림 권한: **Fit3 Proxy 허용**  
   - **음악 제어 / 미디어 컨트롤러**에 이 앱이 노출되는지 확인  
3. 핏3에서 음악(미디어) 화면을 엽니다.  
   Title/Artist가 대시보드 미리보기와 같으면 성공입니다.
4. Play·Next·Prev·FF·REW를 눌러 폰 앱의 **이벤트 로그**와 Logcat 태그 `Fit3MediaSession`에 콜백이 찍히는지 확인합니다.  
   **0.2.1+:** 버튼이 **즉시** 반응해야 합니다 (수 초 UI 프리즈 없어야 함). Fit3 음악 화면은 MediaSession 메타데이터만 갱신하고, FGS 상태 알림은 Wearable 재동기화 부하를 줄이기 위해 디바운스됩니다.
   **0.2.2+:** 거실 전등/침실 스탠드에서 FF(+10%) / REW(−10%) 시 Artist의 `밝기: N%`가 **즉시** 바뀌어야 합니다 (0–100 clamp). 이벤트 로그에 `brightness … → N%`가 찍히는지 확인하세요.
5. 스위치를 OFF 하면 세션·AudioFocus·FGS가 해제됩니다 (soft AudioFocus).

### B. Phase 2 — Notification Actions & 긴급 진동

1. **Galaxy Wearable** → 알림 설정에서 **Fit3 Proxy** 알림이 **허용**인지 확인합니다.  
   (차단되어 있으면 핏3에 액션/진동이 전달되지 않습니다.)
2. 세션 스위치를 ON 한 뒤, 폰 알림 셰이드에서 상태 알림을 펼칩니다.  
   `[재부팅]` / `[승인]` / `[스누즈]` 액션이 보여야 합니다.  
   핏3 알림 상세에서도 동일 액션이 보이는지 확인합니다.
3. 핏3(또는 폰)에서 액션을 탭합니다.  
   → 폰 앱 **이벤트 로그**에 `NotificationAction: [승인] …` 등이 기록되고, Logcat 태그 `Fit3NotifAction` / `Fit3EventLog`에도 남습니다.
4. 대시보드의 **긴급 알림 테스트 (Fit3 진동)** 버튼을 누릅니다.  
   → 고우선순위 알림이 발행되고, 핏3에서 **뚜렷한 햅틱(진동)** 이 와야 합니다.  
   (상태 채널 업데이트와 달리 긴급 채널만 진동합니다.)  
   **0.2.1+:** 알림에 명시적 `setVibrate` + `WearableExtender` + cancel-before-notify + (세션 ON 시) MediaSession pulse를 사용합니다.  
   폰 셰이드에만 뜨고 핏3가 무반응이면 Galaxy Wearable에서 Fit3 Proxy 알림이 **허용**인지, 밴드 진동이 켜져 있는지 다시 확인하세요.
5. 긴급 알림 상세에서 `[재부팅]`/`[승인]`/`[스누즈]`/`[답장]`을 눌러 로그를 확인합니다.  
   `[스누즈]` 또는 답장 시 긴급 알림이 해제됩니다.
6. 상태 알림만 갱신될 때(Next/Prev로 메타데이터 변경) **핏3가 반복 진동하지 않는지** 확인합니다 (`setOnlyAlertOnce` + 무음 채널).

> 다른 음악 앱(Spotify 등)이 포커스를 가져가면 핏3 위젯이 그쪽으로 넘어갈 수 있습니다. 제어 모드일 때만 스위치를 켜세요.

## Soft AudioFocus

세션 스위치가 켜져 있을 때만 `AUDIOFOCUS_GAIN`을 요청하고, 끄면 즉시 `abandon` 합니다. 실제 오디오는 재생하지 않습니다.

## Phase 범위

- ✅ Phase 1: MediaSession + 메타데이터 + FGS + 대시보드 로그
- ✅ Phase 2: 무음 상태 채널 / 긴급 알림 채널 / Notification Actions / RemoteInput stub / UI 테스트 버튼
- ⏳ Phase 3: Webhook / MQTT 슬롯 매핑
- ⏳ Phase 4: Doze·재연결 안정화

상세 명세: [docs/SOW.md](docs/SOW.md)

---

## English — Build & Verify (short)

**Build:** Open in Android Studio (JDK 17, SDK 34) → Sync → Run, or `./gradlew assembleDebug`.

**applicationId:** `com.madmaxbunny.fit3proxy`

**Fit3 verify (Phase 1):** Enable the session switch → allow notification + **music control** for this app in **Galaxy Wearable** → open Fit3 media controls → confirm Title/Artist → press Play/Next/Prev/FF/REW and watch the event log / Logcat (`Fit3MediaSession`). Media buttons should feel **snappy** (no multi-second Fit3 UI freeze); FGS status notification updates are debounced so Wearable is not flooded.

**Fit3 verify (Phase 2):** In Galaxy Wearable, allow **Fit3 Proxy** notifications. Expand the ongoing status notification — tap `[재부팅]` / `[승인]` / `[스누즈]` and confirm the in-app event log. Tap **긴급 알림 테스트** on the phone dashboard — Fit3 should **vibrate/haptic** (not phone-shade-only); use alert actions / RemoteInput reply and confirm logs (`Fit3NotifAction`). Routine status updates must **not** keep buzzing the band.

**0.2.1-phase2-fix:** Emergency alert uses explicit vibrate + WearableExtender + channel `fit3_alert_emergency_v2`; media hot-path avoids per-press FGS `notify()` storms.

**0.2.2-phase2-fix:** Living-room / bedroom light brightness now updates on Fit3 FF/REW (and seek-mapped FF/REW): live Artist metadata + PlaybackState nudge; brightness logged and clamped 0–100.
