# Fit3Proxy ↔ Push API 토큰 등록 명세

기준 서버: `https://api-push.devlion.org`  
앱: Fit3Proxy `com.madmaxbunny.fit3proxy` (v0.6.1-push-query+)

## 합의 계약 (PM 확정)

- **메서드:** `GET` only (**POST 금지**, **JSON body 금지**)
- **경로:** `/api/v1/push/tokens`
- **파라미터:** 쿼리만 — `userId`, `deviceToken`, `platform=ANDROID`
- **인증:** 헤더 `X-API-Key` (`local.properties` → `BuildConfig.PUSH_API_KEY`)
- **Base URL:** `BuildConfig.PUSH_API_BASE_URL` (`https://api-push.devlion.org`)

전체 URL 예:

```
GET {base}/api/v1/push/tokens?userId=...&deviceToken=...&platform=ANDROID
```

## 앱 현재 구현 (0.6.1-push-query)

| 항목 | 값 |
|---|---|
| Method | `GET` |
| URL | `https://api-push.devlion.org/api/v1/push/tokens?userId=…&deviceToken=…&platform=ANDROID` |
| Body | **없음** (OkHttp `Request.Builder().get()`) |
| Headers | `X-API-Key`, `Accept: application/json` |
| platform | 고정 `ANDROID` |
| userId | 대시보드 입력 (기본 `fit3-demo-user`) |
| deviceToken | FCM 토큰 |

구현: `PushApiClient` — OkHttp `HttpUrl.Builder`로 쿼리 파라미터를 붙인 뒤 `.get()` (body 없음).

### 이력 메모 (프로브)

초기 서버는 GET+JSON body에서 200을 반환하고, GET+쿼리만은 500/415를 낸 적이 있습니다.  
**앱 계약은 PM 확정대로 GET+쿼리만**입니다. 서버는 `@RequestParam` / 쿼리 바인딩에 맞춰야 합니다.

## 앱 UI 동작

1. FCM 토큰 수신
2. `PUSH_API_KEY`가 빌드에 포함돼 있고 `userId`가 있으면 자동 등록 시도
3. **토큰 등록** 버튼 → 동일 API 호출 (GET+쿼리)
4. 성공 시 `토큰 등록 성공 (HTTP 200)` / 실패 시 HTTP 코드·본문 일부 표시

## 재현/점검 체크리스트 (앱)

- [ ] 대시보드에 FCM 토큰이 보이는지 (비어 있으면 등록 불가)
- [ ] `userId` 저장 후 등록했는지
- [ ] 빌드에 `PUSH_API_KEY` 포함됐는지 (없으면 “PUSH_API_KEY 없음” 메시지)
- [ ] `platform`이 `ANDROID`인지 (앱 고정)
- [ ] POST / JSON body를 쓰지 않는지 (앱은 GET+쿼리만)

## curl 예시 (앱 계약 = GET + 쿼리)

```bash
curl -G 'https://api-push.devlion.org/api/v1/push/tokens' \
  -H 'X-API-Key: <KEY>' \
  -H 'Accept: application/json' \
  --data-urlencode 'userId=fit3-demo-user' \
  --data-urlencode 'deviceToken=<FCM_TOKEN>' \
  --data-urlencode 'platform=ANDROID'
```
