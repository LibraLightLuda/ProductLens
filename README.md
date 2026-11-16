# 제품렌즈 · ProductLens

로그인·API 키·자체 서버 없이 사용하는 Android 제품 인식 MVP. 사진과 바코드는 기기에서 처리하며, 공개 상품 조회에만 인터넷을 사용합니다. 기존 AudioFlow 및 FullAuto100과 독립된 프로젝트입니다.

## 설치와 사용

1. [GitHub 최신 릴리스](https://github.com/LibraLightLuda/ProductLens/releases/latest)의 `ProductLens-1.1.1-debug.apk` 또는 로컬 `dist`의 같은 APK를 스마트폰으로 복사하여 설치합니다. 개인 설치용 디버그 서명입니다. 1.1.1은 사진 분석 중 원본 비트맵이 해제되는 오류를 수정한 버전입니다.
2. Android 8.0 이상, **ARM64 스마트폰** 대상입니다. 선택한 MediaPipe 배포본에 ARM64 네이티브 라이브러리만 들어 있어 설치 ABI를 ARM64로 제한했습니다. x86 에뮬레이터·32비트 전용 기기는 이 APK의 대상이 아닙니다. 바코드/OCR/이미지 모델이 APK에 포함되어 첫 실행에도 모델 다운로드가 필요하지 않습니다.
3. **카메라로 제품 인식** 또는 **바코드 스캔**을 누르고 카메라 권한을 허용합니다. 바코드는 자동 인식하며, 일반 제품은 **사진으로 인식**을 누릅니다.
4. 갤러리는 시스템 Photo Picker로 선택합니다. 제품 영역을 드래그하거나 자동 검출 영역을 선택하고 **이 영역으로 제품 찾기**를 누릅니다.
5. 결과에서 원본 코드, 공개 상품, OCR 검색 제안, 등록 사진의 코사인 유사도를 확인합니다. 검색어와 OCR을 수정할 수 있습니다.
6. **내 상품 등록**으로 제품명·브랜드·모델을 입력합니다. 다른 각도의 사진을 같은 이름으로 여러 번 등록하면 개인 카탈로그가 됩니다. 이후 사진을 인식하면 등록 사진들과 실제 임베딩 유사도를 비교합니다.

정확한 제품을 확인하지 못하면 그대로 안내합니다. 전 세계 상품에 대한 역이미지 검색 DB나 가격 데이터는 포함되어 있지 않습니다. 네이버 쇼핑·Google 검색 버튼은 사용자가 확인한 검색어를 외부 브라우저로 전달합니다.

## 앱 업데이트 (1.1.0부터)

앱 시작 시 GitHub 최신 정식 릴리스를 확인하고 설치 버전보다 높은 버전이면 **해당 버전당 한 번만** 알립니다. ‘나중에’를 눌러도 같은 버전은 다음 실행 때 다시 알리지 않습니다. 그다음 새 버전은 다시 한 번 알립니다. 설정에서는 언제든 직접 확인할 수 있습니다.

**설정 → 최신 버전 확인 → 버전 다운로드 → 설치 계속** 순서입니다. 첫 설치 요청에는 Android의 ‘이 출처 허용’이 필요합니다. 다운로드는 사용자가 누를 때만 실행하고 Android 설치 화면에서 최종 확인합니다. 자동 확인은 설정에서 끌 수 있습니다. 네트워크 실패는 인식을 막지 않습니다.

APK의 크기·SHA-256·패키지명·높은 versionCode·현재 앱과 동일한 서명을 검사합니다. 정식 v숫자.숫자.숫자 태그와 정확한 APK 이름만 허용합니다. 1.0.0에는 이 기능이 없어 1.1.0은 한 번 직접 설치해야 합니다. 앱 삭제 없이 업데이트하면 로컬 데이터를 유지합니다.

이 기능은 GitHub 직접 배포용입니다. Google Play 배포 시 직접 APK 설치 권한과 업데이트 방식을 바꿔야 합니다. 배포 방법은 [RELEASING.md](RELEASING.md)를 확인하세요.

## 개발과 빌드

JDK 17, Android SDK Platform 36, Build Tools 36, 인터넷 연결이 필요합니다. Android Studio에서 이 폴더를 열거나 Windows PowerShell에서:

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
# 연결한 기기/에뮬레이터에서 Android 실행 테스트
.\gradlew.bat :app:connectedDebugAndroidTest
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

`local.properties`는 로컬 전용입니다. 직접 작성한다면 `sdk.dir=C\:/Users/.../Android/Sdk`처럼 드라이브 구분자를 이스케이프합니다. Gradle Wrapper와 두 TFLite 모델이 소스에 포함되어 있습니다. 의존성 버전은 고정되어 있으며 유료 서비스나 인증키 설정은 없습니다.

## 구성과 문서

- `app/src/main/java/app/productlens/`: Compose 화면, CameraX, 통합 인식, SQLite, 공개 DB 어댑터
- `app/src/main/assets/`: 실제 모델·라이선스 안내
- `app/src/test/`, `app/src/androidTest/`: 체크섬·근거 통합·Android 모델·저장·UI 검증
- `ARCHITECTURE.md`: 처리 순서와 데이터 경계
- `LIBRARIES.md`: 선정 조사·실제 버전·모델 라이선스와 출처
- `TEST_REPORT.md`: 실제 실행 결과와 미검증 항목
- `LIMITATIONS.md`: 정확도·범위·배포 전 남은 사항

설정에서 온라인 조회, OCR, 영역 검출, 사진 비교, 기록 자동 저장을 각각 끌 수 있습니다. 사진은 외부로 업로드하지 않습니다. Open Facts 조회 시 바코드·IP·User-Agent가 전송되고, ML Kit의 진단·사용 통계에는 Google 약관이 적용됩니다. 앱의 OS 백업·기기 이전을 비활성화했습니다.
