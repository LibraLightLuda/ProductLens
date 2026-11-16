# 구조

단일 Android 모듈, Kotlin/Compose, AndroidViewModel, 코루틴을 사용합니다. 서버·계정·DI 프레임워크·유료 API는 없습니다. SQLiteOpenHelper는 이 규모에서 Room 코드 생성 없이 트랜잭션과 바인딩 인자를 제공하는 플랫폼 기능입니다.

## 인식 흐름

CameraX / 시스템 Photo Picker → 해상도 제한·회전 보정 → EfficientDet Lite0 영역 제안 → 사용자가 영역 선택 → 원본/선택 영역 ZXing → 원본 한국어·영어 OCR → 검색 단서 추출 → 선택 영역 MobileNet V3 임베딩 → 등록 카탈로그의 코사인 비교 → 유효 상품 바코드만 Open Facts 조회 → 근거를 함께 표시합니다.

실시간 바코드는 Y 평면만 약 700ms 간격으로 분석하고, 수평·수직·반전 코드를 시도합니다. 하나의 분석 실행기와 CameraX KEEP_ONLY_LATEST로 중복 프레임을 피합니다. 첫 성공 시 원자 플래그로 중복 인식을 막고 해당 프레임을 통합 분석합니다. 카메라는 화면의 LifecycleOwner에 바인딩되며 화면 종료 시 바인딩·플래시·분석 실행기를 해제합니다. 사진 촬영은 목표 1600×1200, 갤러리는 약 1800px 이하로 샘플링하며 EXIF 회전/반전을 보정합니다.

모델은 온디바이스 CPU 추론입니다. 실시간 OCR·객체 검출·임베딩을 매 프레임 실행하지 않습니다. 네이티브 모델 접근은 직렬화하고 ViewModel 종료 시 해제합니다. 모델별 실패는 근거 메시지로 남기며 가능한 다른 인식을 계속합니다. 사용자가 촬영한 글자를 읽는 규칙 기반 브랜드/모델 추출은 확정 데이터와 구분합니다.

## 근거 통합

`Fusion.rank`는 등록 바코드 일치, OCR 속 등록 브랜드·모델 일치, 실제 이미지 코사인 유사도로 후보를 정렬합니다. 내부 정렬 우선값은 통계적 정확도가 아니며 UI에 정확도로 노출하지 않습니다. 코사인은 [-1,1] 범위의 특징 유사도이고, 검출 점수는 범용 객체 검출 모델의 출력입니다. OCR 문자열에는 인식 점수나 가짜 확률을 붙이지 않습니다. 공개 DB 브랜드와 OCR 텍스트도 교차 확인합니다. 후보가 있어도 제품 확정으로 처리하지 않습니다.

## 파일 책임

| 파일 | 책임 |
|---|---|
| Core.kt | GTIN/UPC-E 검증, 검색 단서 추출, 코사인 및 근거 통합. JVM에서 검증 가능 |
| Vision.kt | 실제 ML Kit/MediaPipe 모델 수명, Bitmap·크롭·EXIF·ZXing |
| Data.kt | 공개 데이터 소스 인터페이스, Open Facts API, SQLite와 JSON 직렬화 |
| LensViewModel.kt | 사용자 작업 직렬화, 기능 설정, 실패 처리와 결과/기록/등록 흐름 |
| CameraScreen.kt | CameraX 미리보기·촬영·실시간 디코드·권한·플래시 |
| MainActivity.kt | 한국어 Compose 화면, 영역 선택, Photo Picker, 외부 검색 |

## 저장·통신

`history`: 결과·원본 코드·OCR·후보 근거 스냅샷, 시각, 사진 경로. 최근 200개 표시, 개별·전체 삭제 가능.
`catalog`: 사용자가 지정한 상품명·사진과 MobileNet 특징 벡터. 같은 제품의 여러 각도를 별도 샘플로 등록.
`cache`: Open Facts 성공 24시간, 미등록 1시간. 네트워크 오류는 미등록으로 캐시하지 않음.

사진은 앱 내부 전용 디렉터리에 JPEG로 저장합니다. 기록과 카탈로그가 같은 사진을 참조할 수 있어 한쪽 삭제가 다른 쪽 사진을 지우지 않습니다. 저장·등록하지 않은 결과용 사진은 다음 앱 시작 시 정리합니다. SQL에는 사용자 문자열을 바인딩합니다. DB 변경 시 데이터를 지우는 파괴적 마이그레이션은 사용하지 않습니다.

`ProductSource`는 바코드 → Lookup 계약입니다. 추가 DB는 같은 인터페이스를 구현하여 합법적 소스만 추가합니다. Open Facts v3 `product_type=all`은 food/beauty/petfood/product 유형을 통합하며 공식 도메인으로의 HTTPS 이동만 허용합니다. 키·로그인은 없습니다. 앱은 보수적으로 12요청/분과 초기 요청 5초 간격을 적용하며, 리다이렉트도 횟수에 포함합니다. HTTP 429의 Retry-After를 존중하고 자동 재시도/자동 완성 검색을 하지 않습니다. API 정책은 2026-11-16 조사 기준 상품 읽기 15회/분/IP, 검색 10회/분/IP이며 출시 전 재확인합니다.

사진 업로드 코드는 없습니다. 외부 브라우저 검색은 명시적 클릭에만 동작하며 코드 속 URL을 자동 실행하지 않습니다. Android INTERNET, CAMERA, ACCESS_NETWORK_STATE 외에 저장소·위치·연락처 권한을 요구하지 않습니다. 사진 선택은 Photo Picker, 미지원 기기에서는 AndroidX의 시스템 문서 선택 대체 경로를 사용합니다.

## GitHub 업데이트 (1.1.0)

ReleaseUpdate는 공개 GitHub releases/latest를 토큰 없이 읽고, draft/prerelease를 제외하며 숫자 SemVer와 정확한 APK 이름·주소·업로드 상태·크기·SHA-256을 검증합니다. 메타데이터는 최대 1MB, APK는 최대 100MB이며 시간 제한·리다이렉트 호스트 제한을 적용합니다. 단일 UpdateViewModel의 busy 상태로 요청을 직렬화합니다.

시작 확인은 ViewModel 생성 시 한 번이며 화면 회전에는 반복하지 않습니다. shouldNotifyUpdate가 설치 버전/최신 버전/영속 lastNotified 버전을 비교합니다. 실제 시작 알림을 그릴 때 버전을 기록하므로 같은 버전은 재실행에도 다시 알리지 않습니다. 수동 확인은 이 기록과 무관하게 가능합니다. 같은 알림도 앱 데이터 삭제 후에는 다시 나올 수 있습니다.

다운로드 파일은 cache/updates의 임시 파일에 스트리밍하고 해시·크기를 검증한 뒤 정식 파일명으로 옮깁니다. 설치 요청 전에 디스크 파일을 다시 해시 검사하며 Android PackageManager로 패키지명·정확한 versionName·상승한 versionCode·설치 앱과 같은 서명을 확인합니다. FileProvider는 이 폴더만 공유하며 외부 공개되지 않습니다. REQUEST_INSTALL_PACKAGES 권한과 출처 허용 뒤 사용자 확인이 필요한 Android 설치 화면을 엽니다. 무인 설치는 없습니다. 인식 기록·카탈로그 DB는 변경하지 않습니다.

## 1.1.1 사진의 소유권 수정

MediaPipe 0.10.26의 BitmapImageBuilder는 원본을 복사하지 않고 BitmapImageContainer에 넣습니다. MPImage.close()가 이 컨테이너를 닫으면 bitmap.recycle()이 호출됩니다. 기존 객체 검출은 이후 크롭/OCR에 필요한 원본을, 임베딩은 이후 저장에 필요한 크롭을 해제하는 결함이 있었습니다.

Vision.kt의 Bitmap.toMpImageCopy()가 추론 전용 ARGB_8888 복사본을 만들고 두 추론 경로가 함께 사용합니다. finally의 image.close()는 유지해 복사본을 해제하고 호출자가 가진 원본은 보존합니다. 전체 크롭이 원본과 같은 Bitmap을 반환하는 경우도 안전하게 처리합니다. 모델·카메라·DB·네트워크 동작은 바꾸지 않았습니다.

SDK 근거: https://github.com/google-ai-edge/mediapipe/blob/v0.10.26/mediapipe/java/com/google/mediapipe/framework/image/BitmapImageContainer.java

## 1.1.2 바코드 위치와 확정

CameraX UseCaseGroup/ViewPort로 Preview/ImageAnalysis/ImageCapture의 가시 영역을 일치시킵니다. CameraFrames는 cropRect, Y 평면 rowStride/pixelStride/버퍼 시작 위치, 회전 0/90/180/270도를 반영합니다. 정규화된 가이드 영역은 UI·분석·결과 사진에 같은 값으로 사용합니다. 상태 문구와 확대 컨트롤의 높이를 고정해 가이드가 준비된 뒤 미리보기 크기가 변하지 않도록 합니다.

시작/터치/초점 버튼에서 PreviewView.meteringPointFactory로 같은 위치의 AF+AE를 요청합니다. 터치하면 가이드를 그 위치로 옮기고 초점 결과를 표시합니다. 초점 진행 중 또는 확대 직후 프레임은 확정하지 않으며 과거 확인 상태를 초기화합니다. 분석 목표 해상도는 1920x1080, 촬영 목표는 1920x1440이고 기기 스트림 제약에 따라 CameraX가 대체 해상도를 고릅니다. 사진은 MAXIMIZE_QUALITY로 촬영합니다.

BarcodeScan은 ZXing 3.5.3의 실제 1D 행 디코더로 구별되는 최대 31개 행을 읽습니다. 같은 값·형식과 유사한 X 범위가 3개 이상 행에서 충분한 높이에 걸쳐 일치해야 후보가 됩니다. 후보가 여러 개면 초점을 맞춘 위치에 가까운 것을 우선합니다. 세로/역방향/반전과 별도 2D 디코더도 지원합니다. StableBarcode는 같은 위치의 3프레임과 최소 시간 간격을 확인합니다. 누락·변경·긴 간격·초점 변경에서는 누적 결과를 버립니다. 3회 표시는 확률이 아닌 확인 횟수입니다.

확정 후에는 같은 프레임의 가이드 안 사진을 그대로 전달하고 범용 객체 검출이 다른 제품 영역으로 교체하지 않습니다. 정지 사진은 선택한 크롭만 바코드/OCR로 분석합니다. 공개 DB 미등록/연결 실패는 바코드 인식 실패와 구분하고, 결과/기록/검색어는 바코드 번호를 우선합니다. 기존 DB 스키마와 저장 기록은 변경하지 않습니다.
