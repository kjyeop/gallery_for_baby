# 아기 갤러리

어린 아이가 Android 태블릿에서 가족 사진과 영상을 안전하게 볼 수 있도록 만든 보기 전용 갤러리 앱입니다.

부모가 기기에 사진과 영상을 미리 복사해 둔 뒤 앱을 실행하고, 필요한 경우 Android의 App Pinning 기능으로 화면을 고정해 아이에게 건네는 흐름을 목표로 합니다. 앱 안에는 삭제, 편집, 공유, 외부 앱으로 열기 같은 위험한 동작을 두지 않습니다.

## 주요 기능

- Android MediaStore 기반 로컬 사진/영상 조회
- 전체 사진 및 영상 보기
- 앨범 또는 폴더별 미디어 목록 보기
- 반응형 썸네일 그리드
- 영상 썸네일 재생 표시 및 길이 표시
- 사진 전체화면 보기
- 영상 전체화면 재생
- 전체화면에서 좌우 스와이프로 이전/다음 항목 이동
- `startLockTask()` 기반 App Pinning 시작 요청
- 마지막으로 열었던 앨범 또는 보기 상태 복원
- 권한 없음, 미디어 없음, 로딩, 오류 상태 처리

## 기술 스택

- Kotlin
- Jetpack Compose
- Android MediaStore
- AndroidX Media3 ExoPlayer
- Gradle Kotlin DSL

## 요구 사항

- Android Studio 또는 Android SDK가 설치된 개발 환경
- JDK 17
- Android SDK 36
- 최소 지원 Android 버전: Android 8.0, API 26

## 실행 방법

저장소를 받은 뒤 루트 디렉터리에서 Gradle Wrapper를 사용합니다.

```powershell
.\gradlew.bat :app:assembleDebug
```

연결된 기기 또는 에뮬레이터에 설치하려면 다음 명령을 사용할 수 있습니다.

```powershell
.\gradlew.bat :app:installDebug
```

Android Studio에서는 이 프로젝트를 열고 `app` 실행 구성을 선택해 바로 실행할 수 있습니다.

자세한 디버그 빌드, 기기 설치, 릴리스 APK 생성 및 로컬 설치 절차는 [Android 빌드/설치/배포 가이드](./docs/ANDROID_BUILD_INSTALL_DEPLOY.md)를 참고하세요.

## 권한

앱은 로컬 사진과 영상을 읽기 위한 권한만 요청합니다.

- Android 13 이상: `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`
- Android 14 이상: `READ_MEDIA_VISUAL_USER_SELECTED` 선택 미디어 접근 지원
- Android 12 이하: `READ_EXTERNAL_STORAGE`

쓰기 권한은 요청하지 않으며, 파일 삭제나 편집 기능도 제공하지 않습니다.

## 프로젝트 구조

```text
app/src/main/java/com/kjyeop/babygallery/
├─ MainActivity.kt
├─ data/
│  ├─ GalleryModels.kt
│  ├─ LastViewStore.kt
│  └─ MediaRepository.kt
├─ permissions/
│  └─ MediaPermissions.kt
└─ ui/
   ├─ BabyGalleryApp.kt
   └─ theme/
      └─ Theme.kt
```

## 사용 흐름

1. 태블릿에 가족 사진과 영상을 복사합니다.
2. 앱을 실행하고 사진/영상 접근 권한을 허용합니다.
3. 전체 미디어 또는 특정 앨범을 선택합니다.
4. `시청 시작`을 눌러 전체화면 시청을 시작합니다.
5. Android가 화면 고정 확인 UI를 표시하면 부모가 확인합니다.
6. 아이는 사진과 영상을 탐색하고 감상합니다.

## 제품 문서

상세한 제품 방향, 기능 범위, 테스트 계획은 [BABY_GALLERY_PRODUCT_PLAN.md](./docs/BABY_GALLERY_PRODUCT_PLAN.md)를 참고하세요.
빌드, 설치, 배포 절차는 [docs/ANDROID_BUILD_INSTALL_DEPLOY.md](./docs/ANDROID_BUILD_INSTALL_DEPLOY.md)를 참고하세요.
