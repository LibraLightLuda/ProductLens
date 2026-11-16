# GitHub 릴리스 배포

배포 저장소: https://github.com/LibraLightLuda/ProductLens

업데이트 기능은 공개 정식 릴리스의 `vMAJOR.MINOR.PATCH` 태그와 정확한 `ProductLens-MAJOR.MINOR.PATCH-debug.apk` 이름을 사용합니다. draft와 prerelease는 설치 대상으로 삼지 않습니다. 같은 태그의 APK를 교체하지 말고 새 버전을 발행하세요.

1. `app/build.gradle.kts`의 versionName을 높이고 versionCode도 반드시 증가시킵니다. 릴리스 변경사항 문서를 작성합니다.
2. 같은 서명키를 유지합니다. 현재 개인 디버그 배포는 빌드 PC의 `%USERPROFILE%\.android\debug.keystore`를 사용합니다. 이 파일을 GitHub·소스 ZIP·릴리스 자산에 넣지 않습니다. 키가 달라지면 기존 사용자의 업데이트가 차단됩니다.
3. Gradle 빌드·테스트·Lint를 실행한 뒤 `python tools/package.py`로 APK·소스 ZIP·SHA256SUMS를 만듭니다. 스크립트가 현재 버전과 모든 JVM 테스트 결과를 읽고 모델·ABI·ZIP 내용을 검사합니다.
4. 변경사항을 commit/push한 뒤 아래 예처럼 정식 릴리스를 발행합니다. GitHub가 계산한 자산 digest를 로컬 SHA-256과 비교합니다.

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:lintDebug
python tools/package.py
gh release create v1.1.0 dist/ProductLens-1.1.0-debug.apk dist/ProductLens-1.1.0-source.zip dist/SHA256SUMS.txt --repo LibraLightLuda/ProductLens --title '제품렌즈 1.1.0' --notes-file RELEASE_NOTES_1.1.0.md --target main
# 공개된 최신 릴리스에서 앱과 같은 다운로드 코드로 실제 바이트를 검증
$env:PRODUCTLENS_LIVE_UPDATE='1'
.\gradlew.bat :app:testDebugUnitTest --tests app.productlens.LiveReleaseTest
Remove-Item Env:PRODUCTLENS_LIVE_UPDATE
```

릴리스는 사용자가 배포를 지시했을 때 발행합니다. 일반 빌드나 commit만으로 외부 공개·릴리스 업로드가 실행되는 작업은 추가하지 않았습니다. 이 APK는 GitHub 직접 설치용이며 Play용 빌드에는 별도 서명·권한·업데이트 정책 검토가 필요합니다.
