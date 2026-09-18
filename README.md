# Fit3 Proxy — Galaxy Fit3 Bridge (Phase 2)

갤럭시 핏3(Galaxy Fit3)의 기본 **음악 컨트롤러**와 **알림 액션**을 활용해, 손목에서 조명/전원 등 데모 슬롯을 제어하고 긴급 햅틱·빠른 액션을 검증하는 안드로이드 브릿지 앱입니다.

본 저장소는 SOW **Phase 2: Notification Action 구현**까지 포함합니다. (인메모리 데모 슬롯; MQTT 없음. **0.5.0+** 인앱 업데이트·**0.6.0+** FCM/Push API는 HTTPS 접속)

**현재 버전:** `0.6.0-fcm` (versionCode 12) — **Firebase Cloud Messaging** 토큰 + Push API(`api-push.devlion.org`) 등록 + 기존 인앱 업데이트·매트릭스/슬롯 토글/볼륨 분리 유지.

## 폰에 설치하기 (APK)

최신 설치 파일은 **GitHub Releases**에서 받습니다.

- **최신 릴리스:** https://github.com/madmaxbunny/Fit3Proxy/releases/latest
- **현재 버전 다운로드:** [Fit3Proxy-0.6.0-fcm-debug.apk](https://github.com/madmaxbunny/Fit3Proxy/releases/download/v0.6.0/Fit3Proxy-0.6.0-fcm-debug.apk) (`v0.6.0` / versionName `0.6.0-fcm` / versionCode `12`)
- **version.json:** [version.json](https://github.com/madmaxbunny/Fit3Proxy/releases/download/v0.6.0/version.json) (인앱 업데이터가 `versionCode` 비교에 사용)

설치: APK를 폰으로 보낸 뒤 사이드로드 → Galaxy Wearable에서 Fit3 Proxy **알림·진동** 허용 → 앱에서 MediaSession 가동 ON.

버전 규칙·릴리스 절차는 [docs/VERSIONING.md](docs/VERSIONING.md)를 참고하세요.


## 무엇을 하나요?

### Phase 1 — MediaSession

| 핏3 음악 버튼 | 앱 동작 |
|---|---|
| Play / Pause | 현재 슬롯 ON/OFF 토글 (**슬롯별 기억**; Fit3 PLAYING/PAUSED = 그 슬롯 `isOn`) |
| Next / Previous | **Axis A (행)** — 슬롯 이동 (4개 데모, wrap). 슬롯별 레벨·**토글(ON/OFF)** 유지; Fit3 재생 아이콘은 새 슬롯 상태로 갱신 |
| Fast Forward / Rewind | 밝기 ±10% (밝기 지원 슬롯만; 매트릭스 레벨과 독립) |
| Volume Up / Down (**Fit3**) | **Axis B (열)** — 현재 슬롯의 레벨/`remVol` ±10 (0–100 clamp, 슬롯별 독립 기억) |
| Volume Up / Down (**폰 HW**, 앱 포그라운드) | **로컬** `STREAM_MUSIC` + 시스템 볼륨 바 (`FLAG_SHOW_UI`). remVol 변경 없음 |

**2D 매트릭스 (0.4.0+):** 셀 `(slotIndex, levelIndex)`가 활성 제어점. 각 행(슬롯)이 자체 열(레벨)을 기억합니다.

**슬롯별 토글 (0.4.1+):** 각 행이 `isOn`을 독립 기억합니다. Play/Pause는 현재 행만 토글하고 `PlaybackState`를 즉시 `STATE_PLAYING`/`STATE_PAUSED`로 맞춥니다. Next/Prev는 다른 행의 토글을 리셋하지 않으며, 새로 선택된 행의 저장 `isOn`으로 Fit3 재생 아이콘을 동기화합니다.

메타데이터 전광판:

- **Title** → `[조명 제어] 거실 전등` (현재 행/슬롯)
- **Artist** → `상태: ON | 밝기: 75% | 레벨: 50% | Menu [1/4 × L50]`
- **Album** → `Matrix [1/4 × 50%]`

### Phase 2 — Notification Actions & Alert

| 기능 | 설명 |
|---|---|
| 상태 채널 (무음) | FGS 상시 알림, `setOnlyAlertOnce(true)` — 메타데이터 갱신 시 핏3 진동 없음 |
| 긴급 채널 (HIGH) | 별도 진동 패턴으로 핏3 햅틱 트리거 |
| 액션 버튼 | `[재부팅]` / `[승인]` / `[스누즈]` → BroadcastReceiver → 이벤트 로그 |
| RemoteInput | `[답장]` 빠른 답장 스텁 (네트워크 없음) |
| UI 테스트 | 대시보드 **긴급 알림 테스트** 버튼 |


### 인앱 업데이트 (0.5.0+) — GitHub Releases

앱 시작 시(및 **업데이트 확인** 버튼) GitHub Releases API로 최신 릴리스를 확인합니다.

| 단계 | 동작 |
|---|---|
| 1. 확인 | `https://api.github.com/repos/madmaxbunny/Fit3Proxy/releases/latest` |
| 2. 비교 | 릴리스에 첨부된 `version.json`의 `versionCode` vs `BuildConfig.VERSION_CODE` |
| 3. 다운로드 | 새 버전이면 APK를 앱 캐시에 자동 다운로드 (진행률 표시) |
| 4. 설치 | **설치** 버튼 → PackageInstaller(우선) 또는 FileProvider + ACTION_VIEW |

**현실 제약 (Android):** 일반(비시스템) 앱은 사용자 확인 없이 APK를 **무음 설치할 수 없습니다**.  
PackageInstaller / 시스템 설치 UI에서 **한 번 탭(확인)** 이 필요합니다. “완전 자동 설치”는 디바이스 오너/시스템 권한 없이는 불가능합니다.

릴리스 시 **APK와 `version.json`을 함께 첨부**해야 합니다. 예:

```json
{ "versionCode": 12, "versionName": "0.6.0-fcm", "apk": "Fit3Proxy-0.6.0-fcm-debug.apk" }
```

권한: `INTERNET`, `REQUEST_INSTALL_PACKAGES` + `FileProvider`.  
실패 시(오프라인/404/동일 버전) 조용히 넘어가거나 토스트 한 번만 표시합니다.


### FCM / Push API 토큰 등록 (0.6.0+)

앱이 Firebase에서 FCM 기기 토큰을 받아 [Push API](https://api-push.devlion.org)에 등록합니다.

| 항목 | 내용 |
|---|---|
| 등록 | `GET /api/v1/push/tokens?userId=…&deviceToken=…&platform=ANDROID` |
| 인증 | 헤더 `X-API-Key` (`local.properties`의 `PUSH_API_KEY` → `BuildConfig`) |
| userId | 대시보드 EditText + SharedPreferences (기본 `fit3-demo-user`) |
| UI | 잘린 FCM 토큰 · 등록 상태 · **토큰 등록** 버튼 · 런치/세션 ON 시 자동 시도 |

**빌드 시크릿:** `local.properties`에 `PUSH_API_KEY=…` 를 넣으세요 (**커밋 금지** — `.gitignore`에 포함).  
`app/google-services.json`은 Firebase 클라이언트 설정으로 **커밋합니다**.  
키가 없어도 빌드·FCM 토큰 로컬 확보는 가능하며, 대시보드에 키 필요 안내가 표시됩니다. 서버 `FCM_ENABLED=false`(sim)여도 앱은 토큰을 등록합니다.

```properties
# local.properties (절대 커밋하지 마세요)
sdk.dir=/path/to/Android/Sdk
PUSH_API_KEY=your-key-here
```

## 패키지 구조

```
com.madmaxbunny.fit3proxy
├── model/         ControlSlot, SlotRepository, ControlMatrix (2D 인메모리)
├── session/       Fit3MediaSessionManager (MediaSession + soft AudioFocus + remVol)
├── service/       Fit3ProxyForegroundService (mediaPlayback FGS, silent status)
├── notification/  AlertNotifier, NotificationActionReceiver
├── push/          FCM MessagingService + Push API 토큰 등록
├── update/        GitHub Releases 인앱 업데이트 (check → download → install UI)
├── log/           EventLogStore (UI 이벤트 버스)
└── ui/            MainActivity (Material 대시보드 + 매트릭스 + 업데이트 + FCM 카드)
```

## 요구 사항

- Android Studio Hedgehog+ (또는 AGP 8.5 호환)
- JDK 17
- minSdk 26 / targetSdk 34 / compileSdk 34
- 실제 검증: Galaxy Fit3 + Galaxy Wearable가 연결된 삼성 폰 권장

## 빌드 / 실행

1. Android Studio에서 `/Fit3Proxy` 폴더를 Open
2. `local.properties`에 `sdk.dir` 과 (선택) `PUSH_API_KEY` 설정 — **이 파일은 커밋하지 마세요**
3. `app/google-services.json` 확인 (Firebase; 저장소에 포함)
4. SDK 34 설치 후 Sync Gradle
5. Run ▶ `app` (실기기 권장 — FCM 토큰은 에뮬보다 실기기가 안정적)

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
   **0.3.1+ / 0.3.3+:** 세션 ON 상태에서 **Fit3** **볼륨 ↑/↓**를 눌러 보세요.
   오른쪽에 시스템 원격 볼륨 바가 뜰 수 있습니다(예상 동작). **성공 기준은**
   이벤트 로그의 `volumeUp`/`volumeDown`과 Artist/`remVol`이 ±10씩 변하는 것입니다.
   바만 뜨고 remVol이 50에 고정이면 Fit3가 조정을 세션으로 안 보내는 것입니다.
   **0.3.3+:** 앱이 열린 상태에서 **폰 하드웨어 볼륨 키** → 일반 시스템 미디어 볼륨 바
   (`STREAM_MUSIC`). remVol·`volumeUp` 로그는 변하지 않고, 대신
   `phone VOLUME key → STREAM_MUSIC` 로그가 찍혀야 합니다.
   세션 OFF 후 폰 미디어 볼륨(다른 앱)이 정상인지 확인하세요.
   **0.4.0+:** Next/Prev와 Fit3 볼륨이 **독립 축**인지 확인합니다.
   - Next → 로그 `axis A (slot) next`, Album `Matrix [N/4 × L%]`, 대시보드 행 변경
   - Fit3 볼륨 ↑ → 로그 `axis B (level) volumeUp`, 같은 행의 remVol/레벨만 ±10
   - 다른 슬롯으로 이동 후 볼륨 조절 → 이전 슬롯 레벨은 그대로(슬롯별 기억)
   - 다시 이전 슬롯으로 Next/Prev → remVol이 그 슬롯의 기억된 레벨로 복귀
   **0.4.1+:** 슬롯별 Play/Pause 토글이 기억되는지 확인합니다.
   - 슬롯 A에서 Pause(OFF) → Next로 슬롯 B → Play(ON) → Prev로 A 복귀 → Fit3가 **일시정지(PAUSED)** 이고 대시보드 토글 **OFF**
   - 다시 Next로 B → Fit3가 **재생(PLAYING)** / 대시보드 **ON** (B의 저장값)
   - 볼륨 ↑/↓는 토글을 지우지 않음; 매트릭스 그리드에 각 행 `[ON]`/`[OFF]` 표시
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


## Fit3 볼륨 ↑/↓ 리맵 + 폰 로컬 볼륨 분리 (0.3.3+)

세션이 **ON**일 때 `MediaSessionCompat.setPlaybackToRemote(VolumeProviderCompat)`로
볼륨을 **ABSOLUTE** 원격 제어로 넘깁니다 (0.3.0의 RELATIVE는 삼성/Fit3에서
시스템 원격 볼륨 바만 뜨고 `onAdjustVolume`이 안 오는 경우가 있어 교체).

### Fit3 / Wearable → remVol

Fit3(또는 Wearable)가 원격 프로바이더로 볼륨을 보내면:

- `onAdjustVolume` → `volumeStep` **±10** (0–100 clamp) + 즉시 `setCurrentVolume`
- 또는 `onSetVolumeTo` (절대 경로 / 일부 OEM) → 동일하게 remVol 동기화
- 이벤트 로그 / Logcat (`Fit3MediaSession`): `volumeUp → custom action | volumeStep=N`
- Artist 메타데이터 `remVol: N` + 대시보드 **Fit3 remVol** 미리보기 갱신
- **시스템 `STREAM_MUSIC`은 건드리지 않음** (원격 프로바이더만 사용)

### 폰 하드웨어 볼륨 키 → STREAM_MUSIC (0.3.3+)

앱이 **포그라운드**(대시보드 표시)일 때 `MainActivity.dispatchKeyEvent`가
`KEYCODE_VOLUME_UP` / `DOWN` / `MUTE`를 가로채서:

- `AudioManager.adjustStreamVolume(STREAM_MUSIC, ADJUST_*, FLAG_SHOW_UI)` — 일반 폰 미디어 볼륨 UX
- 이벤트를 **consume** (`return true`) → MediaSession `VolumeProvider`로 전달되지 않음
- **remVol / volumeStep은 변경하지 않음**

세션 **OFF** 시 `setPlaybackToLocal(STREAM_MUSIC)`으로 복구해, 폰 미디어 볼륨이
영구적으로 깨지지 않게 합니다.

> **제한 (백그라운드):** 앱이 백그라운드이고 폰 볼륨 키가 Activity가 아니라
> MediaSession으로만 전달되면, 여전히 원격 remVol 경로를 탈 수 있습니다.
> **포그라운드**에서는 폰 버튼 = 시스템 볼륨이 보장됩니다.

### 오른쪽 시스템 원격 볼륨 바 (모니터 아이콘)

세션 ON 후 **Fit3** 볼륨을 조작하면 화면 오른쪽에 Android **원격 볼륨 패널**(모니터/캐스트
아이콘)이 뜨는 것은 `VolumeProviderCompat` 사용 시 **정상**입니다. 이 바는 시스템이
그리는 UI이고, 앱이 숨길 수 없습니다.

**의미 있는 피드백은 앱 쪽입니다:**

1. 대시보드 / Artist의 **`remVol: N`** 이 ±10씩 변하는지 (Fit3 볼륨)
2. 이벤트 로그에 **`volumeUp` / `volumeDown`** 이 찍히는지
3. 앱 포그라운드에서 **폰 볼륨 키** → 일반 시스템 볼륨 바 + 로그 `phone VOLUME key → STREAM_MUSIC` (remVol 고정)

바만 뜨고 remVol·로그가 그대로면 Fit3/펌웨어가 원격 UI만 띄우고 세션으로
볼륨 조정을 전달하지 않는 경우입니다 (앱이 콜백을 못 받음).

> **중요:** Fit3가 실제로 볼륨 키/제스처를 미디어 세션으로 보내는지 기기·워치페이스·
> Wearable 설정에 따라 다릅니다. 세션 ON에서 Fit3 볼륨을 조작해도 로그에
> `volumeUp`/`volumeDown`이 없으면 밴드가 해당 키를 세션으로 전달하지 않는 것입니다.

## 2D 매트릭스 제어 (0.4.0+)

Fit3 **Next/Prev**와 **볼륨 ↑/↓**를 독립 축으로 합성합니다.

| 축 | Fit3 입력 | 상태 | 동작 |
|---|---|---|---|
| **A (행)** | Next / Previous | `slotIndex` | 슬롯(메뉴) 이동, wrap. 열(레벨)·토글은 바꾸지 않음; Fit3 PLAYING/PAUSED는 새 행 `isOn` |
| **B (열)** | Volume ↑ / ↓ | `levelBySlot[slotIndex]` | 현재 슬롯 레벨 ±10 (0–100 clamp). 행·토글은 바꾸지 않음 |

- 그리드: **4 슬롯 × 11 레벨** (0…100, step 10)
- 셀 `(slotIndex, levelIndex)` = 활성 제어점
- Play/Pause = 현재 슬롯 ON/OFF (**슬롯별 `isOn` 기억**, 0.4.1+)
- Next/Prev 후 `PlaybackState`를 새 슬롯의 저장 `isOn`으로 동기화 (Fit3 재생 아이콘 일치)
- VolumeProvider ABSOLUTE → remVol 경로는 유지하되, remVol은 **현재 셀의 레벨**
- 슬롯 전환 시 VolumeProvider `setCurrentVolume`을 그 행의 기억된 레벨로 동기화
- 폰 HW 볼륨 키 분리(0.3.3)는 그대로

대시보드: **현재 슬롯 토글 ON/OFF** + **행 N/4 × 열 L%** + 슬롯별 `[ON]`/`[OFF]`·레벨 그리드(`>` = 현재 행).

이벤트 로그: `axis A (slot) next/prev` vs `axis B (level) volumeUp/volumeDown`.

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

**Fit3 verify (Phase 1):** Enable the session switch → allow notification + **music control** for this app in **Galaxy Wearable** → open Fit3 media controls → confirm Title/Artist → press Play/Next/Prev/FF/REW and watch the event log / Logcat (`Fit3MediaSession`). Media buttons should feel **snappy** (no multi-second Fit3 UI freeze); FGS status notification updates are debounced so Wearable is not flooded. **0.3.1+ / 0.3.3+:** with session ON, press **Fit3** volume ↑/↓ — the right-side system remote-volume bar may appear (expected). Success = `volumeUp`/`volumeDown` in the event log and `remVol` moving ±10; bar-only with stuck remVol means Fit3 did not forward adjust. With the app in the **foreground**, **phone** volume keys adjust local `STREAM_MUSIC` (system volume UI) and must **not** change remVol. Background phone keys may still hit remote remVol (documented limitation). Session OFF restores phone media volume via `setPlaybackToLocal`. **0.4.0+:** Next/Prev vs Fit3 volume are independent axes — changing volume must not change slot, and Next must not change that slot’s remembered level; switching back restores remVol for that row. **0.4.1+:** Pause slot A, Next to B, Play B, Prev to A → Fit3 shows PAUSED/OFF for A; Next to B → PLAYING/ON. Volume must not clear toggles.

**Fit3 verify (Phase 2):** In Galaxy Wearable, allow **Fit3 Proxy** notifications. Expand the ongoing status notification — tap `[재부팅]` / `[승인]` / `[스누즈]` and confirm the in-app event log. Tap **긴급 알림 테스트** on the phone dashboard — Fit3 should **vibrate/haptic** (not phone-shade-only); use alert actions / RemoteInput reply and confirm logs (`Fit3NotifAction`). Routine status updates must **not** keep buzzing the band.

**0.2.1-phase2-fix:** Emergency alert uses explicit vibrate + WearableExtender + channel `fit3_alert_emergency_v2`; media hot-path avoids per-press FGS `notify()` storms.

**0.2.2-phase2-fix:** Living-room / bedroom light brightness now updates on Fit3 FF/REW (and seek-mapped FF/REW): live Artist metadata + PlaybackState nudge; brightness logged and clamped 0–100.

**0.3.0-volume-remap:** Initial remote volume remap via `VolumeProviderCompat` (RELATIVE). On some Samsung/Fit3 setups the system remote-volume bar appeared without `onAdjustVolume` / remVol updates.

**0.3.1-volume-remap:** Switch to `VOLUME_CONTROL_ABSOLUTE`, always `setCurrentVolume` after each adjust/set (±10 step, 0–100), main-thread metadata/UI, clearer logs. The right-side remote volume panel is Android’s expected UI for `VolumeProviderCompat` — trust `remVol` + event log, not the bar alone. Session OFF still calls `setPlaybackToLocal(STREAM_MUSIC)`.

**0.3.2-ui:** Dashboard shows install version (`versionName` / `versionCode`).

**0.3.3-volume-split:** Keep remote `VolumeProviderCompat` for Fit3 remVol; `MainActivity` intercepts phone HW volume keys (foreground) → local `STREAM_MUSIC` + `FLAG_SHOW_UI`, consume so remVol is unchanged. Document background limitation.

**0.4.0-matrix:** 2D control matrix — Next/Prev = Axis A (slotIndex), Fit3 volume = Axis B (per-slot level 0–100 step 10). Metadata Album `Matrix [row/4 × L%]`; dashboard row×col + grid; event log tags `axis A` / `axis B`. remVol path + phone volume-key split unchanged.

**0.4.1-matrix:** Per-slot Play/Pause persistence — each row keeps `isOn`; toggle updates that slot + `STATE_PLAYING`/`STATE_PAUSED` immediately; Next/Prev restores PlaybackState from the newly selected slot’s saved `isOn` so Fit3 play/pause matches that row. Dashboard shows current-slot toggle ON/OFF. Volume/level still independent of toggle.

**0.5.0-updater:** GitHub Releases 인앱 업데이트 — 런치 시 자동 확인, `version.json`로 versionCode 비교, APK 캐시 다운로드, PackageInstaller/FileProvider 설치 UI(사용자 확인 1회). 대시보드에 업데이트 카드·현재/최신 비교. 기존 매트릭스·슬롯 토글·볼륨 분리 유지.

**0.6.0-fcm:** Firebase Cloud Messaging + Push API 토큰 등록. `FirebaseMessagingService`로 토큰 갱신 시 재등록. 대시보드에 userId(SharedPreferences)·잘린 FCM 토큰·등록 상태·**토큰 등록** 버튼. `PUSH_API_KEY`는 `local.properties` → BuildConfig만 (커밋 금지). `google-services.json` 커밋. 기존 매트릭스·업데이트·볼륨 분리 유지.
