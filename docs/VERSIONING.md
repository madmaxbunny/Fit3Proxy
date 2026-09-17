# Fit3Proxy 버전 관리

## 규칙

- **앱 versionName:** `MAJOR.MINOR.PATCH[-suffix]` (예: `0.2.1-phase2-fix`)
- **앱 versionCode:** 정수, 매 스토어/사이드로드 배포마다 +1 (현재 `10`)
- **Git 태그:** `vMAJOR.MINOR.PATCH` (예: `v0.2.1`) — 소스의 `versionName`과 맞출 것
- **배포물:** GitHub Release에 debug/release APK 첨부. 최신 설치 링크는 `/releases/latest`

## 릴리스 체크리스트

1. `app/build.gradle.kts`에서 `versionName` / `versionCode` 갱신
2. 루트 `VERSION` 파일에 동일 `versionName` 기록
3. README 상단 “현재 버전” 및 다운로드 링크 갱신
4. 커밋 후 태그 푸시:

```bash
git tag -a v0.2.1 -m "v0.2.1"
git push origin v0.2.1
```

5. GitHub → Releases에서 태그로 릴리스 생성하고 APK 업로드  
   또는 `v*` 태그 push 시 Actions가 APK를 빌드해 릴리스에 첨부합니다 (워크플로: `.github/workflows/release.yml`).

## 현재

| 항목 | 값 |
|---|---|
| versionName | 0.4.1-matrix |
| versionCode | 10 |
| 태그 | v0.4.1 |
| 릴리스 | https://github.com/madmaxbunny/Fit3Proxy/releases/tag/v0.4.1 |

## GitHub Actions 자동화

태그 `v*` push 시 APK를 빌드해 Release에 첨부하는 워크플로는 `docs/examples/release.yml`에 초안이 있습니다.  
현재 `gh` 토큰에 `workflow` 스코프가 없어 `.github/workflows/`로는 아직 올릴 수 없습니다. 스코프 부여 후 해당 파일을 `.github/workflows/release.yml`로 옮기면 됩니다.
