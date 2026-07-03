# Android 빌드, 설치, 로컬 릴리스 APK 가이드

확인일: 2026-07-03 KST

이 문서는 Baby Gallery Android 앱을 로컬에서 빌드하고, Android 태블릿이나 에뮬레이터에 직접 설치하는 절차를 정리합니다. 이 프로젝트의 기본 배포 방식은 Play Console 업로드가 아니라, 서명된 release APK를 만들어 가족 기기에 직접 설치하는 흐름입니다.

## 프로젝트 기준

| 항목 | 값 |
| --- | --- |
| 프로젝트 루트 | `D:\src\personal\baby_gallery` |
| 앱 모듈 | `:app` |
| 패키지 ID | `com.kjyeop.babygallery` |
| Gradle Wrapper | `gradle-9.6.1` |
| Android Gradle Plugin | `9.2.1` |
| Kotlin Compose Plugin | `2.4.0` |
| JDK | `17` |
| `compileSdk` | `37` |
| `targetSdk` | `36` |
| `minSdk` | `26` |
| 릴리스 서명 | `keystore.properties`가 있으면 `release-keystore.jks`로 release APK 서명 |

## 빠른 빌드

Windows PowerShell에서 프로젝트 루트로 이동한 뒤 Gradle Wrapper를 사용합니다.

```powershell
.\gradlew.bat :app:assembleDebug
```

디버그 APK 산출물:

```text
app\build\outputs\apk\debug\app-debug.apk
```

디버그 빌드는 개발 중 빠르게 확인할 때 사용합니다. 가족 기기에 오래 설치해 둘 배포본은 아래의 release APK를 사용합니다.

## 개발 환경 준비

필수 도구:

- Android Studio 또는 Android SDK Command-line Tools
- JDK 17
- Android SDK Platform 37
- USB 디버깅이 켜진 Android 기기 또는 Android Emulator

로컬 SDK 경로는 `local.properties`에 들어갑니다. 이 파일은 개발자 PC마다 달라서 Git에 커밋하지 않습니다.

```properties
sdk.dir=D\:\\tools\\install\\android-sdk
```

새 PC에서 빌드가 실패하면 Android Studio의 SDK Manager에서 Android SDK Platform 37과 최신 Build Tools가 설치되어 있는지 먼저 확인하세요.

## 디버그 설치

연결된 기기 또는 실행 중인 에뮬레이터에 바로 설치:

```powershell
.\gradlew.bat :app:installDebug
```

APK 파일을 직접 설치:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

설치 상태 확인:

```powershell
adb devices
adb shell pm list packages | Select-String babygallery
```

삭제 후 다시 설치해야 할 때:

```powershell
adb uninstall com.kjyeop.babygallery
.\gradlew.bat :app:installDebug
```

## 테스트 미디어 준비

앱은 MediaStore에서 기기 안의 사진과 영상을 읽습니다. 테스트용 파일을 기기에 넣으려면 다음 흐름을 사용할 수 있습니다.

```powershell
adb shell mkdir -p /sdcard/Pictures/BabyGallery
adb push .\sample-media\ /sdcard/Pictures/BabyGallery/
```

기기 갤러리나 파일 관리자에서 파일이 보이지 않으면 잠시 기다리거나 기기를 재부팅해 MediaStore 스캔이 끝나게 합니다.

## 로컬 릴리스 APK 빌드

가족 태블릿에 설치할 배포본은 release APK로 만듭니다.

```powershell
.\gradlew.bat :app:assembleRelease
```

릴리스 APK 산출물:

```text
app\build\outputs\apk\release\app-release.apk
```

현재 이 프로젝트는 로컬 `keystore.properties`와 `release-keystore.jks`가 있으면 release variant가 `baby-gallery-release` alias로 서명됩니다. 서명 설정이 빠져 있으면 `app-release-unsigned.apk`가 생성될 수 있으며, 이 파일은 가족 기기 배포용으로 사용하지 않습니다.

## 로컬 릴리스 APK 설치

USB로 연결된 기기에 release APK를 업데이트 설치합니다.

```powershell
adb install -r app\build\outputs\apk\release\app-release.apk
```

여러 기기가 연결되어 있으면 대상 기기를 지정합니다.

```powershell
adb devices
adb -s <device-id> install -r app\build\outputs\apk\release\app-release.apk
```

기기 준비:

1. Android 기기에서 개발자 옵션을 켭니다.
2. USB 디버깅을 켭니다.
3. PC에 연결한 뒤 기기 화면의 RSA 디버깅 허용 팝업을 승인합니다.
4. release APK를 설치합니다.
5. 앱 실행 후 사진/영상 접근 권한을 허용합니다.
6. 필요한 경우 Android App Pinning으로 화면을 고정합니다.

APK 파일을 기기에 복사해서 파일 관리자에서 설치할 수도 있지만, USB 디버깅이 가능한 개발/가족 기기라면 `adb install -r` 흐름이 가장 단순하고 재현성이 좋습니다.

## 릴리스 서명 키

Google Play를 사용하지 않는 로컬 APK 배포에서는 `release-keystore.jks`가 곧 앱의 실제 릴리스 서명 키입니다. Android는 같은 `applicationId`의 앱을 업데이트할 때 이전 앱과 새 APK의 서명이 같은지 확인합니다. 따라서 가족 기기에 설치된 앱을 계속 업데이트하려면 같은 키를 계속 보관하고 사용해야 합니다.

현재 이 PC에는 로컬 전용 `release-keystore.jks`와 `keystore.properties`를 생성해 두었습니다. 둘 다 `.gitignore`에 의해 Git에 올라가지 않습니다. 공유 가능한 템플릿은 `keystore.properties.example`입니다.

주의:

- `release-keystore.jks`와 `keystore.properties`는 백업해 둡니다.
- 키를 잃으면 이미 설치된 release 앱을 같은 앱으로 업데이트할 수 없습니다.
- 키가 바뀌면 기존 앱을 삭제한 뒤 새 앱을 설치해야 하며, 앱 데이터도 사라질 수 있습니다.
- 디버그 빌드와 릴리스 빌드는 서로 서명 키가 다릅니다. debug 앱이 설치된 기기에 release APK를 바로 덮어쓰면 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`이 날 수 있습니다.

업로드 키라는 표현은 Play Console을 사용할 때 맞는 표현입니다. 이 프로젝트의 현재 운영 방식에서는 로컬 키를 “업로드 키”가 아니라 “릴리스 배포 키”로 취급합니다.

## 서명 설정 파일

`keystore.properties` 형식:

```properties
storeFile=release-keystore.jks
storePassword=여기에_스토어_비밀번호
keyAlias=baby-gallery-release
keyPassword=여기에_키_비밀번호
```

새 키를 만들어야 할 때의 예시:

```powershell
keytool -genkeypair `
  -v `
  -keystore release-keystore.jks `
  -storetype JKS `
  -keyalg RSA `
  -keysize 4096 `
  -validity 10000 `
  -alias baby-gallery-release
```

이 프로젝트의 `app/build.gradle.kts`는 이미 다음 흐름으로 설정되어 있습니다.

```kotlin
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.isFile) {
        keystorePropertiesFile.inputStream().use(::load)
    }
}

android {
    signingConfigs {
        if (keystorePropertiesFile.isFile) {
            create("release") {
                storeFile = rootProject.file(requireKeystoreProperty("storeFile"))
                storePassword = requireKeystoreProperty("storePassword")
                keyAlias = requireKeystoreProperty("keyAlias")
                keyPassword = requireKeystoreProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (keystorePropertiesFile.isFile) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}
```

서명 설정 확인:

```powershell
.\gradlew.bat :app:signingReport
```

release 항목에 다음처럼 표시되면 정상입니다.

```text
Variant: release
Config: release
Store: D:\src\personal\baby_gallery\release-keystore.jks
Alias: baby-gallery-release
```

## 버전 업데이트

로컬 APK 배포만 하더라도 배포 전 `app/build.gradle.kts`의 버전을 올리는 습관을 권장합니다.

```kotlin
defaultConfig {
    versionCode = 2
    versionName = "1.1"
}
```

규칙:

- `versionCode`는 배포할 때마다 증가시키는 것이 안전합니다.
- 기기에 이미 더 높은 `versionCode`가 설치되어 있으면 낮은 버전 APK는 일반 업데이트로 설치되지 않을 수 있습니다.
- `versionName`은 사람이 보는 버전이므로 가족 기기에 어떤 빌드가 들어갔는지 구분하기 쉽게 맞춥니다.

## 권장 릴리스 절차

1. 기능 변경을 마무리합니다.
2. `versionCode`와 `versionName`을 올립니다.
3. `.\gradlew.bat :app:assembleDebug`로 개발 빌드를 확인합니다.
4. 개발 기기에서 `installDebug`로 핵심 흐름을 확인합니다.
5. `.\gradlew.bat :app:signingReport`로 release 서명을 확인합니다.
6. `.\gradlew.bat :app:assembleRelease`로 release APK를 생성합니다.
7. `adb install -r app\build\outputs\apk\release\app-release.apk`로 가족 기기에 설치합니다.
8. 실제 태블릿에서 사진/영상 권한, 앨범 목록, 전체화면 보기, 영상 재생, 좌우 스와이프, App Pinning 흐름을 확인합니다.
9. 생성한 APK와 같은 시점의 소스 커밋, `versionName`, `versionCode`를 기록합니다.

## 문제 해결

`SDK location not found`

- `local.properties`의 `sdk.dir`를 현재 PC의 Android SDK 경로로 설정합니다.

`Failed to install ... device offline`

- USB 케이블을 다시 연결하고 기기에서 USB 디버깅 허용 팝업을 승인합니다.
- `adb kill-server` 후 `adb start-server`를 실행합니다.

`INSTALL_FAILED_UPDATE_INCOMPATIBLE`

- 기존 앱과 새 APK의 서명 키가 다릅니다.
- debug 앱이 설치되어 있다면 `adb uninstall com.kjyeop.babygallery` 후 release APK를 설치합니다.
- 이미 가족 기기에 release 앱을 배포했다면 같은 `release-keystore.jks`를 계속 사용해야 합니다.

`INSTALL_FAILED_VERSION_DOWNGRADE`

- 기기에 설치된 앱보다 새 APK의 `versionCode`가 낮습니다.
- 일반 배포에서는 `versionCode`를 올려 다시 빌드합니다.

`Release variant signing config is null`

- `keystore.properties`가 프로젝트 루트에 있는지 확인합니다.
- `keystore.properties`의 `storeFile`, `storePassword`, `keyAlias`, `keyPassword` 값을 확인합니다.
- `.\gradlew.bat :app:signingReport`로 release variant의 Store/Alias가 표시되는지 확인합니다.

`app-release-unsigned.apk`만 생성됨

- 릴리스 서명이 연결되지 않은 상태입니다.
- `keystore.properties`와 `release-keystore.jks`를 준비한 뒤 `.\gradlew.bat :app:assembleRelease`를 다시 실행합니다.

## 참고 공식 문서

- [Build your app from the command line](https://developer.android.com/build/building-cmdline)
- [Sign your app](https://developer.android.com/studio/publish/app-signing)
