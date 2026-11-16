# 검증 보고서

검증일: 2026-11-16, Windows / JDK 17 / Android SDK 36. 앱 버전 1.0.0. 현재 **빌드 및 데스크톱 검증 결과**이며 실제 Android 설치·카메라·한국어 OCR 사용 검증 완료를 의미하지 않습니다.

## 수행한 검증

| 항목 | 입력과 기대 결과 | 결과·증거 |
|---|---|---|
| Android 앱 컴파일·디버그 APK | Kotlin/Compose/CameraX/ML Kit/MediaPipe 의존성 해결 및 APK 생성 | 성공. `evidence/final-build.log`, 최종 묶음 검사 결과 `evidence/package-verification.json` |
| Android 실행 테스트 APK 컴파일 | DeviceTest, UiTest, WorkflowTest, LiveApiTest 포함 | 성공. 실행 여부와 구분 |
| JVM 단위 테스트 | 체크섬, UPC-E 확장, 한영 OCR 검색 단서, 코사인 경계, 근거 통합, 후보 부재, 실제 ZXing 코드 디코딩 | **7개 통과**. `evidence/unit-tests.xml` |
| 실제 ZXing EAN-13/EAN-8/UPC-A/UPC-E/QR | `3017620422003`, `96385074`, `036000291452`, `04252614`, QR URL을 표준 여백을 가진 심볼로 생성 → 실제 공용 디코더에서 원문 복원 | 통과. 카메라 촬영이 아닌 JVM 생성 심볼 테스트 |
| GTIN 신뢰 경계 | 틀린 체크섬, 길이, 영문 혼합, 전각 숫자, 공백 → 거부. QR에 들어 있는 숫자는 상품 DB 자동 조회 제외 | 통과 |
| 검색 단서 추출 | SAMSUNG / Galaxy Buds SM-R510 / 500mAh와 고객센터·원재료 문구 → 브랜드·모델·규격 제안 및 노이즈 제외 | 통과. 실제 OCR 추론 결과가 아닌 텍스트 후처리 테스트 |
| 실제 MobileNet 가중치 추론 | Google 테스트 burger/cat 사진, burger를 75% 크기로 변경 → 등록 원본 사진이 다른 사진보다 높은 유사도 | **데스크톱 통과**. 1024차원, 동일 사진 0.999636, 다른 사진 0.103398. `evidence/desktop-model-smoke.json` |
| 실제 EfficientDet 가중치 추론 | 동일 Google burger 사진 → 유한한 원시 출력. 실제 MediaPipe Python 객체 검출에서 영역 반환 | **데스크톱 통과**. sandwich 영역 3개 반환, 점수 약 0.693/0.486/0.433. `evidence/desktop-mediapipe.log` |
| 모델 배포 일치 | 소스 모델 SHA-256과 APK 내부 모델이 같은지 비교 | 최종 패키징 스크립트에서 검사 |
| Android Lint | 오류 없이 분석 종료 | 오류 0. 버전 갱신 권고·KTX 권고 등 경고는 남아 있음. `evidence/lint-results.xml` |

데스크톱 모델 테스트 환경은 Python 3.13, ai-edge-litert 2.3.0, MediaPipe Python 1.2.3입니다. Android 의존성 MediaPipe 0.10.26의 기기 실행을 대체하지 않습니다. Python 환경은 `.venv-test`에 격리했으며 앱 의존성이나 소스 ZIP에는 포함하지 않습니다. 입력 사진은 일반 APK에 들어가지 않습니다.

## Android 실행 환경과 수행하지 못한 검증

`adb devices -l`에서 연결 기기가 없었습니다. 공식 SDK 도구와 API 36 Google APIs x86_64 시스템 이미지를 설치하고 `ProductLens_API36` AVD를 만들었습니다. AVD 생성 도구가 기록한 SDK 상대 경로는 수정했지만 에뮬레이터 실행은 다음 오류로 종료했습니다.

```
x86_64 emulation currently requires hardware acceleration!
Android Emulator hypervisor driver is not installed on this machine
```

시스템 가상화 드라이버 설치·Windows 보안 설정 변경·재부팅은 수행하지 않았습니다. 이후 소프트웨어 에뮬레이터 실행과 후속 검증을 묶은 명령은 자동 승인 검토가 `blocked by policy`로 거부했습니다. 그 명령은 실행되지 않았으며 자세한 거부 사유는 제공되지 않았습니다. 거부된 에뮬레이터 작업을 우회하지 않았습니다. 테스트용 에뮬레이터 프로세스가 남아 있지 않은지도 확인했습니다.

이후 패키지 내부 검사에서 MediaPipe 0.10.26 AAR에 ARM64 라이브러리만 있음을 확인했습니다. 다른 라이브러리가 x86_64를 포함하더라도 이미지 추론을 지원한다고 표시하면 잘못이므로 최종 APK를 ARM64 전용으로 제한했습니다. 따라서 이 x86_64 AVD는 최종 APK의 실행 대상도 아닙니다. Android 테스트는 실제 ARM64 스마트폰 또는 적합한 ARM64 테스트 환경에서 실행해야 합니다.

다음 항목은 **코드와 컴파일된 Android 테스트는 있지만 실행 검증하지 못했습니다**.

- APK의 실제 기기 설치·정상 실행과 화면 시각 품질
- 실제 EAN/UPC 라벨을 비추는 카메라 인식, 초점·플래시, 카메라 권한 허용/거부/설정 복귀
- 번들 한국어·영어 OCR 추론 및 기기 오프라인 첫 실행
- Photo Picker 사용자 선택, EXIF 회전·갤러리·수동 크롭 전체 사용자 흐름
- Android MediaPipe 임베딩/객체 검출, 등록 상품 후보 비교와 텍스트 수정 전체 흐름
- Android SQLite 기록 저장·재시작·개별/전체 삭제와 참조 사진 보존
- OFF 실제 서버 조회 성공/미등록/오프라인/429 및 HTTPS 리다이렉트
- 앱 백그라운드 복귀·회전·프로세스 종료 후 재실행, 장시간 발열·배터리·메모리와 실제 ARM 기기

공식 API v3 엔드포인트의 브라우징 도구 접근도 실패해 라이브 API 성공을 주장하지 않습니다. 정상/404/500/429·캐시·User-Agent용 MockWebServer 테스트도 Android 테스트이므로 아직 실행되지 않았습니다. 공개 DB가 조회된 것처럼 꾸민 결과나 상품 데이터는 넣지 않았습니다.

## 재현 명령

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:lintDebug
# Android 기기를 연결한 뒤 네트워크 없는 실행도 검증할 테스트 (라이브 API만 제외)
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.notClass=app.productlens.LiveApiTest'
# 네트워크 연결 상태에서 공식 서버 테스트만 별도 실행
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=app.productlens.LiveApiTest'
# 데스크톱 모델 검증 (선택; Android 실행 근거가 아님)
python -m venv .venv-test
.\.venv-test\Scripts\python.exe -m pip install ai-edge-litert==2.3.0 Pillow==13.0.0 numpy==2.5.5
.\.venv-test\Scripts\python.exe tools\model_smoke.py
```

실제 Android 실행이 가능해지면 라이브 서버 테스트와 오프라인 테스트를 분리하고, 카메라의 인식 속도·권한·반복 인식·회전·백그라운드 복귀를 확인해야 합니다. 디버그 APK 전달은 Play 게시나 실기기 품질 승인을 뜻하지 않습니다.

## 1.1.0 업데이트 기능 검증

v1.0.0 저장소와 정식 릴리스를 먼저 공개한 뒤 실제 GitHub 릴리스 응답을 테스트 입력으로 저장해 기능을 구현했습니다. 1.1.0의 검증은 evidence/update-build.log, evidence/package-verification.json 및 TEST-app.productlens.ReleaseUpdateTest.xml을 기준으로 확인합니다.

추가 JVM 테스트: 실제 1.0.0 릴리스 파싱, 숫자 버전 비교, 같은 새 버전 알림 억제/다음 새 버전 재알림, draft/prerelease 제외, 다른 저장소·잘못된 이름·해시·크기·중복 APK 거부, HTTPS 리다이렉트 제한, 응답 크기 제한, 낮은 versionCode·다른 패키지/서명 거부, 파일 변조 검출입니다. 실제 GitHub 최신 릴리스와 APK 전체 다운로드 검증은 PRODUCTLENS_LIVE_UPDATE=1일 때 별도 실행하는 LiveReleaseTest로 분리했습니다.

연결된 ARM64 Android 기기가 없어 설치 출처 허용·설치 화면·실제 앱 교체·영속 알림 상태의 기기 재시작 검증은 수행하지 못했습니다. 이전 에뮬레이터 실행 거부를 우회하거나 시스템 드라이버를 변경하지 않았습니다.

## 1.1.0 공개 배포 후 검증 완료

공개 릴리스: https://github.com/LibraLightLuda/ProductLens/releases/tag/v1.1.0

- 공개 배포 전 일반 JVM 테스트 17개 통과, 실제 공개 APK 다운로드 테스트 1개는 별도 실행 대기 상태였습니다.
- 공개 배포 후 PRODUCTLENS_LIVE_UPDATE=1로 전체 JVM 테스트를 실행하여 **18개 통과, 실패 0, 건너뜀 0**을 확인했습니다.
- LiveReleaseTest는 앱의 ReleaseUpdate.latest/download 코드를 그대로 사용해 인증 토큰 없이 최신 정식 릴리스와 APK 전체 69,719,056바이트를 실제 다운로드했습니다. GitHub digest, 파일 크기와 SHA-256 일치를 확인했습니다.
- 최신 버전 1.1.0 판정과 이전 버전 기준 1회 알림/동일 버전 재알림 억제 규칙을 통과했습니다. Android UI의 실제 알림 재실행 검증을 뜻하지 않습니다.
- Android apksigner로 1.0.0/1.1.0 APK 서명을 각각 검증하고 동일한 서명 인증서임을 확인했습니다. versionCode 2, versionName 1.1.0, app.productlens, ARM64도 패키지에서 확인했습니다.
- Lint 오류 0, 경고 32개이며 APK·소스 ZIP·SHA256SUMS의 GitHub 자산 digest가 모두 로컬 파일과 같습니다.

PUBLICATION.json은 공개 이후 검증 기록입니다. 공개 APK와 소스 ZIP을 교체하지 않았습니다. GitHub main의 이 문서와 기록은 릴리스 공개 후 추가한 근거이며, 태그의 앱 코드는 변경하지 않았습니다. 실제 ARM64 Android 설치·설치 출처 권한·앱 교체·영속 알림 상태 검증은 여전히 미수행입니다.

## 1.1.1 이미지 분석 오류 재현 및 수정

사용자 실기기 오류: `cannot use a recycled source in createBitmap`.

원인: MediaPipe 0.10.26의 MPImage.close()가 BitmapImageBuilder에 전달한 비트맵을 recycle합니다. 객체 검출 뒤 원본을 크롭하거나, 임베딩 뒤 크롭을 저장하는 처리에서 이미 해제된 비트맵을 사용했습니다. SDK 구현과 호출 순서를 확인했습니다.

실행 검증:

- 수정 전 동일 SDK/비트맵 수명을 실행한 BitmapOwnershipTest: 4개 중 원본 보존 검사 3개 실패. 원본을 직접 전달하면 close가 recycle한다는 동작 재현 1개 통과. evidence/bitmap-regression-before.xml 및 bitmap-regression-before.log에 기록.
- 수정 후 같은 테스트: 4개 모두 통과. 원본 보존, 추론용 복사본 해제, 전체/부분 크롭의 후속 저장·재크롭, 추론 실패 시 finally 정리를 확인.
- 실행 환경: Robolectric 4.16.1, Android API 28, LEGACY 비트맵, 실제 MediaPipe 0.10.26 Java 이미지 클래스. 모델 추론·카메라를 가짜로 실행한 테스트가 아님. 해당 기능의 ARM 네이티브 실행은 하지 않음.
- 일반 JVM 테스트 21개 통과, 실패 0개. GitHub 최신 APK 다운로드 1개는 1.1.1 공개 후 별도 실행 대상으로 구분.
- 앱 및 Android 실행 테스트 APK 빌드 성공, Lint 오류 0개. Android DeviceTest에도 검출·임베딩 이후 원본 보존 검사를 추가하고 컴파일 확인.
- 기기: adb 연결된 ARM64 스마트폰 없음. 사용자의 스마트폰에서 수정 APK로 실제 촬영·한글 OCR·결과 화면까지 재확인한 것은 아님. 초기 에뮬레이터 실행 거부를 재시도하거나 우회하지 않음.

수정 범위는 Vision의 추론 입력 소유권, 회귀 테스트 및 버전/배포 문서입니다. 이미지 인식을 끄거나 오류를 숨기는 처리를 추가하지 않았습니다. SDK 원본: https://github.com/google-ai-edge/mediapipe/blob/v0.10.26/mediapipe/java/com/google/mediapipe/framework/image/BitmapImageContainer.java

### 1.1.1 공개 배포 후 확인

공개 릴리스: https://github.com/LibraLightLuda/ProductLens/releases/tag/v1.1.1

- 공개 후 앱과 같은 업데이트 코드로 최신 1.1.1 APK 전체 69,492,869바이트를 인증 없이 다운로드하고 SHA-256을 검증했습니다.
- 전체 JVM 테스트 **22개 통과, 실패 0, 건너뜀 0**. 비트맵 수명 회귀 테스트 4개 포함입니다.
- APK·소스 ZIP·SHA256SUMS의 GitHub digest가 모두 로컬 파일과 일치합니다.
- apksigner로 1.1.1 APK를 검증하고 기존 1.1.0과 동일한 인증서임을 확인했습니다. 패키지는 app.productlens, versionCode 3, versionName 1.1.1입니다.
- Lint 오류 0, 경고 33. Robolectric은 API 28로 실행했으며 SDK 36 테스트에는 Java 21이 필요하다는 안내가 있습니다. 이 테스트를 SDK 36/실기기 검증으로 표시하지 않습니다.
- 공개 이후 추가한 검증 기록은 PUBLICATION.json 및 이 문서입니다. 공개 APK·소스 ZIP과 태그의 앱 코드는 교체하지 않았습니다.

사용자의 스마트폰에서 수정본으로 재촬영·한국어 OCR·결과 표시를 확인한 것은 아닙니다. Android의 실제 네이티브 추론과 PC의 비트맵 수명 회귀 테스트를 구분합니다.
