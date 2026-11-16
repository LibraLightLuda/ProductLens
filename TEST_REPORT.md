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
