# Fit3Proxy 버전 관리

## 규칙

- **앱 versionName:** `MAJOR.MINOR.PATCH[-suffix]` (예: `0.5.0-updater`)
- **앱 versionCode:** 정수, 매 스토어/사이드로드 배포마다 +1 (현재 `11`)
- **Git 태그:** `vMAJOR.MINOR.PATCH` (예: `v0.5.0`) — 소스의 `versionName`과 맞출 것
- **배포물:** GitHub Release에 debug/release APK **및** `version.json` 첨부. 최신 설치 링크는 `/releases/latest`

## version.json (인앱 업데이트용)

각 릴리스에 아래 JSON을 **자산으로 첨부**하세요. 앱이 GitHub API로 latest를 조회한 뒤 이 파일의 `versionCode`를 `BuildConfig.VERSION_CODE`와 비교합니다.

```json
{
  "versionCode": 11,
  "versionName": "0.5.0-updater",
  "apk": "Fit3Proxy-0.5.0-updater-debug.apk"
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

## 릴리스 체크리스트

1. `app/build.gradle.kts`에서 `versionName` / `versionCode` 갱신
2. 루트 `VERSION` 파일에 동일 `versionName` 기록
3. 루트 `version.json`을 새 버전으로 갱신 (릴리스 자산과 동일 내용)
4. README 상단 “현재 버전” 및 다운로드 링크 갱신
5. 커밋 후 태그 푸시:

```bash
git tag -a v0.5.0 -m "v0.5.0"
git push origin v0.5.0
```

6. GitHub → Releases에서 태그로 릴리스 생성하고 **APK + version.json** 업로드  
   또는 `v*` 태그 push 시 Actions가 APK를 빌드해 릴리스에 첨부합니다 (워크플로: `.github/workflows/release.yml`).  
   Actions만 쓰는 경우에도 **version.json을 수동/스크립트로 함께 첨부**하세요.

## 현재

| 항목 | 값 |
|---|---|
| versionName | 0.5.0-updater |
| versionCode | 11 |
| 태그 | v0.5.0 |
| 릴리스 | https://github.com/madmaxbunny/Fit3Proxy/releases/tag/v0.5.0 |
| API | https://api.github.com/repos/madmaxbunny/Fit3Proxy/releases/latest |

## GitHub Actions 자동화

태그 `v*` push 시 APK를 빌드해 Release에 첨부하는 워크플로는 `docs/examples/release.yml`에 초안이 있습니다.  
현재 `gh` 토큰에 `workflow` 스코프가 없어 `.github/workflows/`로는 아직 올릴 수 없습니다. 스코프 부여 후 해당 파일을 `.github/workflows/release.yml`로 옮기면 됩니다.
