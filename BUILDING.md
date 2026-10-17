# Сборка Сменник 0.1.2

## Требования
- Android Studio с Android SDK 36 и Build Tools 35.0.0.
- JDK 17+ и Gradle 8.13 (Gradle можно установить через Android Studio или `gradle` CLI).

## Команды

```bash
gradle :app:testDebugUnitTest :app:assembleDebug
```

Результат: `app/build/outputs/apk/debug/app-debug.apk`. Либо откройте проект в Android Studio и выполните **Build → Build APK(s)**.

В GitHub Actions файл `.github/workflows/android.yml` запускает те же тесты и собирает APK; успешный APK находится в **Actions → Android Alpha CI → Artifacts**.

## Важные замечания

- Gradle wrapper properties включены, но JAR wrapper не включён: CLI-команда здесь использует установленный Gradle, а не `./gradlew`.
- Не считайте исходники готовым APK: сборку необходимо выполнить на машине с Android SDK или дождаться успешного CI.
- Приложение работает без аккаунта и интернет-разрешения; данные сохраняются локально.
