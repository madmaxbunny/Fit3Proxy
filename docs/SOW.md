# 갤럭시 핏3 우회 연동(Workaround) 안드로이드 브릿지 앱 작업 명세서 (SOW)

## 1. 프로젝트 개요 (Overview)
* **프로젝트명**: Galaxy Fit3 Bridge (가칭: Fit3-Proxy Controller)
* **목적**: 전용 앱 설치 및 사이드로딩이 불가능한 RTOS 기반 갤럭시 핏3(Galaxy Fit3)의 특성을 고려하여, 안드로이드 표준 프레임워크(`MediaSessionCompat`, `NotificationCompat`, `RemoteInput`)를 활용해 손목 위에서 양방향 제어(출력: 모니터링 텍스트/진동, 입력: 미디어 버튼/빠른 답장 액션)를 지원하는 브릿지 애플리케이션 구축.
* **타깃 환경**: Android 14+ (API Level 34 이상 권장), Kotlin 기반

---

## 2. 전체 시스템 아키텍처 (System Architecture)

```
[갤럭시 핏3 (Galaxy Fit3)]
   ▲ (BLE 동기화 via Galaxy Wearable App)
   ▼
[안드로이드 스마트폰 (Galaxy Fit3 Bridge App)]
   ├── 1. Output Pipeline: Foreground Notification Service (지속 알림 / 상태 피드백)
   ├── 2. Input/Display Pipeline: MediaSessionCompat (가상 음악 재생기)
   │     ├── Metadata (Title/Artist) -> 메뉴/상태값 텍스트 표시
   │     └── PlaybackState/Callback  -> Play/Pause/Next/Prev 입력 수신
   ├── 3. Quick Action Pipeline: Notification Actions & RemoteInput (선택지/답장 버튼)
   └── 4. Integration Layer: REST API / MQTT / Webhook (외부 서버, IoT 제어 연동)
```

---

## 3. 핵심 기능별 세부 구현 명세 (Functional Specifications)

### 3.1. 가상 미디어 세션 (MediaSession) 기반 입력/출력 인터페이스
* **역할**: 핏3의 기본 음악 컨트롤러 화면을 커스텀 터치 인터페이스 및 단축 디스플레이로 활용
* **주요 컴포넌트**: `MediaSessionCompat`, `MediaSessionCompat.Callback`
* **세부 구현 요건**:
  1. **가상 재생 세션 유지**:
     * `PlaybackStateCompat.Builder`를 통해 `STATE_PAUSED` 또는 `STATE_PLAYING` 상태를 유지하여 핏3 음악 위젯에 지속 활성화 상태 전달.
     * 지원 액션 플래그 설정: `ACTION_PLAY`, `ACTION_PAUSE`, `ACTION_SKIP_TO_NEXT`, `ACTION_SKIP_TO_PREVIOUS`, `ACTION_FAST_FORWARD`, `ACTION_REWIND`.
  2. **디스플레이 텍스트 매핑 (Output)**:
     * `MediaMetadataCompat`의 메타데이터를 UI 전광판으로 사용:
       * `METADATA_KEY_TITLE`: 현재 선택된 디바이스/작업명 (예: `[조명 제어] 거실 전등`)
       * `METADATA_KEY_ARTIST`: 현재 상태값 (예: `상태: ON | 밝기: 75%`)
       * `METADATA_KEY_ALBUM`: 모드/서브메뉴 정보 (예: `Menu [1/4] Next로 이동`)
  3. **키 이벤트 핸들러 매핑 (Input)**:
     * `onPlay()` / `onPause()`: 현재 타깃 항목의 토글(Toggle Execute) 트리거.
     * `onSkipToNext()` / `onSkipToPrevious()`: 제어 타깃 메뉴 인덱스 변경(Carousel 이동).
     * `onFastForward()` / `onRewind()`: 수치 증감(예: 조명 밝기 +10% / -10%) 처리.

### 3.2. 알림(Notification) 및 빠른 액션(Action) 파이프라인
* **역할**: 중요한 경고/상태 푸시 수신 및 분기 선택 액션 버튼 제공
* **주요 컴포넌트**: `NotificationManagerCompat`, `NotificationCompat.Builder`, `RemoteInput`
* **세부 구현 요건**:
  1. **Foreground Service 기반 상시 알림 채널**:
     * 백그라운드 킬 방지를 위한 포그라운드 서비스 상주.
     * Silent 업데이트 지원 (`setOnlyAlertOnce(true)`)을 통해 주기적 시스템/서버 헬스 모니터링 시 핏3에 지속적인 불필요 진동 방지.
  2. **긴급 알림(Alert) 진동 패턴**:
     * 특정 임계치 초과(예: 서버 다운, 긴급 호출) 시 새로운 알림 채널 또는 우선순위(`PRIORITY_MAX`)로 발행하여 손목 햅틱 피드백 트리거.
  3. **인터랙티브 액션 버튼 (Notification Actions)**:
     * 핏3 알림 상세 화면 하단에 표시될 1~3개의 직관적 액션 정의 (예: `[재부팅]`, `[승인]`, `[스누즈]`).
     * `PendingIntent`를 통해 `BroadcastReceiver`로 이벤트 수신 후 지정된 백엔드 로직 수행.

### 3.3. 백그라운드 영속성 및 전력 관리
* **포그라운드 서비스 타입**: Android 14 정책 준수 (`mediaPlayback` 또는 `dataSync` 타입 명시).
* **배터리 최적화 예외 처리**: 사용자가 앱 설정에서 "배터리 사용량 제한 없음(Unrestricted)"을 활성화하도록 가이드/인텐트 제공.

---

## 4. UI/UX 및 스마트폰 관리 화면 구성

1. **대시보드 탭**:
   * 가상 MediaSession 가동/중지 스위치
   * 현재 핏3 화면에 출력 중인 가상 타이틀/아티스트 미리보기
2. **슬롯/메뉴 매핑 설정 탭**:
   * 핏3 음악 컨트롤러의 `Next / Prev`로 전환할 메뉴 리스트 설정 (예: 1번: 거실 조명, 2번: PC 전원, 3번: 배포 파이프라인)
   * 각 슬롯별 Play/Pause 트리거 시 호출할 Webhook URL, MQTT 토픽, 또는 쉘 스크립트 등록 UI
3. **로그 및 테스트 탭**:
   * 수신된 MediaButton KeyEvent 및 Notification Action 클릭 로그 실시간 모니터링

---

## 5. 단계별 개발 마일스톤 (Milestones)

| 단계 | 작업 내용 | 상세 구현 항목 |
|---|---|---|
| **Phase 1: MediaSession 프로토타입** | 가상 오디오 세션 등록 및 핏3 연동 검증 | • `MediaSessionCompat` 생성 및 활성화<br>• 타이틀/아티스트 갱신 시 핏3 음악 위젯 텍스트 변경 확인<br>• Next/Prev/Play 버튼 클릭 시 안드로이드 Logcat 수신 테스트 |
| **Phase 2: Notification Action 구현** | 단방향 알림 및 빠른 응답 채널 구축 | • 포그라운드 서비스 알림 템플릿 작성<br>• 액션 버튼(`PendingIntent`) 클릭 이벤트 처리 리시버 연동<br>• 긴급 진동 패턴 적용 검증 |
| **Phase 3: 외부 연동 레이어 개발** | 스마트홈/서버 Webhook 연동 | • Retrofit/OkHttp 또는 Eclipse Paho MQTT 클라이언트 연동<br>• 슬롯별 액션 매핑 엔진 구현 (버튼 이벤트 -> HTTP 호출) |
| **Phase 4: 안정화 및 백그라운드 유지** | 전력 최적화 대응 및 에러 핸들링 | • Doze 모드 및 화면 꺼짐 상태 지속 테스트<br>• 블루투스 재연결 시 세션 복구 로직 보완 |

---

## 6. 주의 사항 및 제약 조건 (Limitations)
1. **Galaxy Wearable 앱 의존성**: 스마트폰의 `Galaxy Wearable` 앱에서 해당 브릿지 앱의 알림 권한 및 음악 제어 권한이 활성화되어 있어야 합니다.
2. **실제 미디어 재생 앱과의 충돌 방지**: Spotify, YouTube 등 타 음악 앱과 `MediaSession` 오디오 포커스 경합이 발생할 수 있으므로, 제어 모드 진입 시에만 세션을 잡거나 AudioFocus 요청 정책을 유연하게 설계해야 합니다.
