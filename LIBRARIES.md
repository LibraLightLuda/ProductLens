# 라이브러리·모델 선정 및 출처

조사일: 2026-11-16. GitHub와 공식 문서를 확인하고, 이미 환경에서 검증된 Android 빌드 계열에 맞추어 버전을 고정했습니다. 최신 버전이라는 주장은 하지 않습니다. 자동으로 최신 의존성을 받아 실행하지 않습니다.

| 구성 | 선택 버전 | 라이선스 / 출처 |
|---|---|---|
| Kotlin / Compose compiler | 2.2.21 | Apache-2.0 · https://github.com/JetBrains/kotlin |
| Jetpack Compose / Material3 | BOM 2025.12.00 | Apache-2.0 · https://developer.android.com/jetpack/compose |
| Activity / Lifecycle | 1.12.2 / 2.10.0 | Apache-2.0 · https://android.googlesource.com/platform/frameworks/support/ |
| CameraX | 1.5.2 | Apache-2.0 · https://developer.android.com/media/camera/camerax |
| ExifInterface | 1.4.2 | Apache-2.0 · 위 AndroidX 소스 |
| Coroutines | 1.10.2 | Apache-2.0 · https://github.com/Kotlin/kotlinx.coroutines |
| ZXing core | 3.5.3 | Apache-2.0 · https://github.com/zxing/zxing |
| Korean Text Recognition 번들 | 16.0.1 | **Google ML Kit 약관, 완전한 오픈소스가 아님** · https://developers.google.com/ml-kit/vision/text-recognition/v2/android · https://developers.google.com/ml-kit/terms |
| MediaPipe Tasks Vision | 0.10.26, ARM64 AAR | Apache-2.0 및 전이 의존성 고지 · https://github.com/google-ai-edge/mediapipe |
| OkHttp / Okio | 4.12.0 / 전이 버전 | Apache-2.0 · https://github.com/square/okhttp · https://github.com/square/okio |
| Coil | 2.7.0 | Apache-2.0 · https://github.com/coil-kt/coil |
| SQLite | Android 플랫폼 | Android 내장 DB, 별도 배포 라이브러리 없음 |
| JUnit / AndroidX Test / MockWebServer | 테스트 의존성 | EPL-1.0 / Apache-2.0. 앱 기능용으로 배포하지 않음 |

`evidence/runtime-dependencies.tsv`에 실제 해결된 전이 의존성 버전이 있고, 앱 내 라이선스 안내에는 캐시된 공식 Maven POM의 라이선스 선언과 배포 아카이브의 고지를 수집합니다. ML Kit는 POM에 기재된 항목만으로 오픈소스로 간주하지 않습니다.

## 모델 코드와 가중치

| 모델 | 용도·실제 배포 | 가중치 조건 및 확인 출처 |
|---|---|---|
| MobileNet V3 Small, float32 v1 | 이미지 특징, 약 4.1MB, APK에 포함 | Apache-2.0. TFLite 내부 메타데이터의 license 문자열 및 Google 공식 모델 페이지: https://www.kaggle.com/models/google/mobilenet-v3 |
| EfficientDet Lite0, float32 v1 | COCO 범용 객체 영역 검출, 약 13.8MB, APK에 포함 | Apache-2.0. TensorFlow 공식 모델 배포: https://www.kaggle.com/models/tensorflow/efficientdet · 공식 MediaPipe 모델 연결: https://ai.google.dev/edge/mediapipe/solutions/vision/object_detector |
| ML Kit Korean OCR | 한국어·영어 OCR, APK에 SDK 모델 포함 | Google SDK 약관. 무료 온디바이스 처리이나 모델·SDK 전체를 오픈소스라고 표시하지 않음 |

MobileNet은 ImageNet 기반 일반 이미지 특징 모델이므로 상품별 고유 모델명이나 전 세계 상품을 자동으로 알지 못합니다. EfficientDet는 80개 COCO 범주용이며 특정 포장 상품 전체를 검출할 수 없습니다. 자동 검출 실패 시 전체 이미지/수동 크롭으로 이어집니다.

모델 다운로드는 Google 공식 저장소이며, 배포 파일과 `models.json`에 SHA-256을 고정합니다. 원래 내려받은 latest 주소 대신 재현 가능한 버전 1 주소를 기록했습니다.

- https://storage.googleapis.com/mediapipe-models/image_embedder/mobilenet_v3_small/float32/1/mobilenet_v3_small.tflite
- https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/float32/1/efficientdet_lite0.tflite

## 대안 검토

- **ZXing/JourneyApps:** ZXing core는 유지보수 모드입니다. 독립 Barcode Scanner 앱의 Android 14 비호환 안내를 core 디코더의 비호환으로 오해하지 않습니다. JourneyApps 4.3.0은 Android 카메라·스캔 UI를 포함하지만 이 앱에는 CameraX와 사진 통합 흐름이 있어 core만 재사용합니다. 주요 EAN/UPC, QR, Data Matrix, Code128 등은 core에서 처리합니다. https://github.com/journeyapps/zxing-android-embedded
- **PaddleOCR:** Apache-2.0 코드. PP-OCRv5의 `korean_PP-OCRv5_mobile_rec`와 이후 공식 Android ONNX Runtime 예제가 존재합니다. 한국어 지원이 없거나 Android에서 불가능하다고 판단한 것이 아닙니다. 텍스트 검출·한국어 인식·사전·가변폭 전처리·CTC 후처리·ONNX 연산자 조합을 함께 검증하는 별도 통합이 필요하므로, 이번 MVP의 설치 직후 오프라인 안정성과 검증 범위에 맞춰 번들 ML Kit를 무료 대체 수단으로 택했습니다. 완전한 공개 OCR만으로 구성해야 한다면 교체 대상입니다. https://www.paddleocr.ai/latest/en/version3.x/pipeline_usage/OCR.html · https://www.paddleocr.ai/latest/en/version3.x/inference_deployment/cross_platform/android_deployment.html · https://huggingface.co/PaddlePaddle/korean_PP-OCRv5_mobile_rec
- **MobileCLIP:** 공식 MobileCLIP/MobileCLIP2는 이미지·텍스트 비교에 유용하나, Android 배포용 변환·토크나이저·더 큰 모델 비용이 추가됩니다. 코드와 `LICENSE_MODELS`가 분리되어 있으며 Apple Machine Learning Research Model License를 Apache/MIT로 간주하지 않았습니다. 작은 검증 가능한 MediaPipe 특징 모델을 선택했습니다. https://github.com/apple-aiml-research/ml-mobileclip · https://github.com/apple-aiml-research/ml-mobileclip/blob/main/LICENSE_MODELS
- **추론 엔진:** ONNX Runtime은 PaddleOCR 변환에 적합하지만 이 MVP에서 별도의 엔진을 추가하지 않았습니다. MediaPipe Tasks는 TFLite 기반 전처리·모델 추론·후처리를 묶고 공개 Android 예제가 있어 선택했습니다. https://ai.google.dev/edge/mediapipe/solutions/vision/image_embedder

## 공개 상품 데이터

Open Food Facts 계열 API v3, `product_type=all`을 사용합니다. `OpenFactsSource`는 다른 데이터 소스와 교체 가능한 인터페이스 뒤에 있습니다. 국내 상품 조회율과 사용자 입력 데이터의 정확도는 보장하지 않습니다. 서버 구현이나 smooth-app 코드를 복사하지 않았습니다.

DB: ODbL 1.0, 개별 내용: DbCL, 상품 사진: CC BY-SA 3.0. 결과마다 출처·라이선스를 표시하고 원본 상품 페이지로 연결합니다. 파생 DB를 외부 배포한다면 ODbL 공유 조건을 별도로 준수해야 합니다. 사용자가 직접 등록한 사진은 로컬에만 보관하며 앱에 대한 업로드 권리를 수집하지 않습니다.

- API v3 및 상품 유형: https://openfoodfacts.github.io/documentation/docs/Product-Opener/v3/products/get-api-v3-product-code/
- User-Agent·호출 제한: https://openfoodfacts.github.io/openfoodfacts-server/api/
- 라이선스: https://openfoodfacts.github.io/documentation/docs/Product-Opener/api/tutorials/license-be-on-the-legal-side/

## 유지보수·보안 검토 범위

공식 릴리스와 이슈/보안 페이지를 확인했습니다. ZXing의 유지보수 모드, JourneyApps의 구형 카메라 흐름, MediaPipe 0.10.26의 Android 16KB 페이지 지원 안내를 고려했습니다. MediaPipe 공식 보안 페이지의 공개 권고 부재는 무취약성을 뜻하지 않습니다. Maven 전체 CVE 정밀 감사나 모든 OEM/ABI 검증을 수행했다고 주장하지 않습니다. Play 배포 전 최신 정책, SDK 보안 공지, 네이티브 라이브러리 16KB 정렬과 실제 ARM 기기 검증이 필요합니다.

테스트 전용 burger.jpg/cat.jpg는 Google MediaPipe 공식 테스트 자산 저장소에서 내려받아 Android 테스트 APK에만 포함합니다. 일반 앱에는 샘플 상품이나 테스트 후보가 들어가지 않습니다. https://github.com/google-ai-edge/mediapipe/tree/master/mediapipe/tasks/testdata/vision

## 1.1.0 업데이트 기능의 추가 구성

기존 OkHttp, Coroutines, AndroidX FileProvider/PackageInfoCompat, Android PackageManager를 사용합니다. 추가 런타임 의존성은 없습니다. JVM에서 Android JSON 파서를 검증하기 위해 테스트 전용 org.json:json:20240303을 사용하며 APK에는 이 JAR를 넣지 않습니다. JSON-java 출처: https://github.com/stleary/JSON-java

GitHub 공식 정식 릴리스 API: https://docs.github.com/en/rest/releases/releases#get-the-latest-release
자산 digest/size: https://docs.github.com/en/rest/releases/assets
Android 출처별 설치 권한: https://developer.android.com/reference/android/content/pm/PackageManager#canRequestPackageInstalls()
