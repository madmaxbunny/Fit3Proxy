# Fit3Proxy ↔ Push API 토큰 등록 명세

기준 서버: `https://api-push.devlion.org`  
앱: Fit3Proxy `com.madmaxbunny.fit3proxy` (v0.6.0-fcm+)

## 합의 목표 (PM)

- **메서드:** `GET` only (**POST 금지**)
- **경로:** `/api/v1/push/tokens`
- **파라미터:** `userId`, `deviceToken`, `platform`
- **인증:** 헤더 `X-API-Key: <key>` (또는 JWT Bearer)

## 앱 현재 구현 (2026-09-18 검증)

| 항목 | 값 |
|---|---|
| Method | `GET` |
| URL | `https://api-push.devlion.org/api/v1/push/tokens` |
| Body | JSON `{"userId","deviceToken","platform":"ANDROID"}` |
| Headers | `X-API-Key`, `Content-Type: application/json`, `Accept: application/json` |
| platform | 고정 `ANDROID` |
| userId | 대시보드 입력 (기본 `fit3-demo-user`) |
| deviceToken | FCM 토큰 |

### 라이브 프로브 결과

1. **GET + 쿼리만** (`?userId=&deviceToken=&platform=ANDROID`)  
   → HTTP **500** (메시지상 415: `TokenRegisterRequest` bodyType에 `application/octet-stream` 미지원)  
   → 서버가 아직 **@RequestBody JSON** 을 기대함
2. **GET + JSON body** (위 표와 동일)  
   → HTTP **200** upsert 성공

따라서 **지금 앱 버튼이 “정상 서버 + 동일 스펙”으로 맞춰져 있으면 GET+JSON 이어야 200** 입니다.  
**GET+쿼리만** 쓰려면 서버가 `@RequestParam` / 쿼리 바인딩으로 바뀌어야 합니다.

## 앱 UI 동작

1. FCM 토큰 수신
2. `PUSH_API_KEY`가 빌드에 포함돼 있고 `userId`가 있으면 자동 등록 시도
3. **토큰 등록** 버튼 → 동일 API 호출
4. 성공 시 `토큰 등록 성공 (HTTP 200)` / 실패 시 HTTP 코드·본문 일부 표시

## 재현/점검 체크리스트 (앱)

- [ ] 대시보드에 FCM 토큰이 보이는지 (비어 있으면 등록 불가)
- [ ] `userId` 저장 후 등록했는지
- [ ] 빌드에 `PUSH_API_KEY` 포함됐는지 (없으면 “PUSH_API_KEY 없음” 메시지)
- [ ] `platform`이 `ANDROID`인지 (앱 고정)
- [ ] POST를 쓰지 않는지 (앱은 GET만 사용)

## 백엔드 요청

Swagger/구현을 아래 중 하나로 **통일**해 주세요.

**A (현재 앱·라이브 동작):** GET + JSON body `@RequestBody TokenRegisterRequest`  
**B (PM 목표):** GET + 쿼리 `@RequestParam userId, deviceToken, platform` — POST 없음

B로 가면 앱을 즉시 쿼리 방식으로 전환하겠습니다.

## curl 예시

### A — GET + JSON (현재 200)

```bash
curl -X GET 'https://api-push.devlion.org/api/v1/push/tokens' \
  -H 'X-API-Key: <KEY>' \
  -H 'Content-Type: application/json' \
  -d '{"userId":"fit3-demo-user","deviceToken":"<FCM_TOKEN>","platform":"ANDROID"}'
```

### B — GET + 쿼리 (목표, 현재 서버에서는 실패)

```bash
curl -G 'https://api-push.devlion.org/api/v1/push/tokens' \
  -H 'X-API-Key: <KEY>' \
  --data-urlencode 'userId=fit3-demo-user' \
  --data-urlencode 'deviceToken=<FCM_TOKEN>' \
  --data-urlencode 'platform=ANDROID'
```
