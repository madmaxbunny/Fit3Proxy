# Fit3Proxy 버전 관리

## 규칙

- **앱 versionName:** `MAJOR.MINOR.PATCH[-suffix]` (예: `0.6.2-fcm-fit3`)
- **앱 versionCode:** 정수, 매 스토어/사이드로드 배포마다 +1 (현재 `14`)
- **Git 태그:** `vMAJOR.MINOR.PATCH` (예: `v0.6.2`) — 소스의 `versionName`과 맞출 것
- **배포물:** GitHub Release에 debug/release APK **및** `version.json` 첨부. 최신 설치 링크는 `/releases/latest`

## version.json (인앱 업데이트용)

각 릴리스에 아래 JSON을 **자산으로 첨부**하세요. 앱이 GitHub API로 latest를 조회한 뒤 이 파일의 `versionCode`를 `BuildConfig.VERSION_CODE`와 비교합니다.

```json
{
  "versionCode": 14,
  "versionName": "0.6.2-fcm-fit3",
  "apk": "Fit3Proxy-0.6.2-fcm-fit3-debug.apk"
}
```

| 필드 | 설명 |
|---|---|
| `versionCode` | 정수 (필수). 앱보다 크면 업데이트 제안 |
| `versionName` | 표시용 문자열 |
| `apk` | 같은 릴리스에 첨부된 APK 파일명 (`apkAssetName` 별칭도 허용) |

`gh release create` / 웹 UI에서 **APK + version.json** 둘 다 올립니다.  
`version.json`이 없으면 릴리스 본문의 `versionCode: N` 패턴을 폴백으로 파싱하지만, **반드시 version.json을 첨부하는 것을 권장**합니다.

### 현실 제약

비시스템 안드로이드 앱은 PackageInstaller / ACTION_VIEW로도 **사용자 확인 없이 무음 설치할 수 없습니다.**  
인앱 플로우는 자동 확인 → 자동 다운로드 → **설치 UI 한 번 탭**까지입니다.

## 시크릿 / 로컬 설정

| 파일 | 커밋? | 용도 |
|---|---|---|
| `local.properties` | **아니오** (`.gitignore`) | `sdk.dir`, **`PUSH_API_KEY`** (Push API `X-API-Key`) |
| `app/google-services.json` | **예** (Firebase Android 관례) | FCM 클라이언트 설정 (`com.madmaxbunny.fit3proxy`) |

빌드 시 `app/build.gradle.kts`가 `local.properties`의 `PUSH_API_KEY`를 `BuildConfig.PUSH_API_KEY`로 주입합니다.  
키가 없으면 앱은 FCM 토큰만 로컬에 확보·대시보드에 표시하고, Push API 등록은 UI에 명확한 안내를 띄웁니다. **실제 API 키를 README·릴리스 노트·소스에 넣지 마세요.**

```properties
# local.properties (커밋 금지)
sdk.dir=/path/to/Android/Sdk
PUSH_API_KEY=your-key-here
```

## 릴리스 체크리스트

1. `app/build.gradle.kts`에서 `versionName` / `versionCode` 갱신
2. 루트 `VERSION` 파일에 동일 `versionName` 기록
3. 루트 `version.json`을 새 버전으로 갱신 (릴리스 자산과 동일 내용)
4. README 상단 “현재 버전” 및 다운로드 링크 갱신
5. 커밋 후 태그 푸시:

```bash
git tag -a v0.6.2 -m "v0.6.2"
git push origin v0.6.2
```

6. GitHub → Releases에서 태그로 릴리스 생성하고 **APK + version.json** 업로드  
   또는 `v*` 태그 push 시 Actions가 APK를 빌드해 릴리스에 첨부합니다 (워크플로: `.github/workflows/release.yml`).  
   Actions만 쓰는 경우에도 **version.json을 수동/스크립트로 함께 첨부**하세요.

## 현재

| 항목 | 값 |
|---|---|
| versionName | 0.6.2-fcm-fit3 |
| versionCode | 14 |
| 태그 | v0.6.2 |
| 릴리스 | https://github.com/madmaxbunny/Fit3Proxy/releases/tag/v0.6.2 |
| API | https://api.github.com/repos/madmaxbunny/Fit3Proxy/releases/latest |

## GitHub Actions 자동화

태그 `v*` push 시 APK를 빌드해 Release에 첨부하는 워크플로는 `docs/examples/release.yml`에 초안이 있습니다.  
현재 `gh` 토큰에 `workflow` 스코프가 없어 `.github/workflows/`로는 아직 올릴 수 없습니다. 스코프 부여 후 해당 파일을 `.github/workflows/release.yml`로 옮기면 됩니다.
