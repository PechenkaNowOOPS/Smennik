# Сборка Сменник (номер из version.properties)

## Требования

- Android SDK 36 с Build Tools 35.0.0
- Java 17
- Gradle 8.13

## Проверка

```bash
gradle :app:testDebugUnitTest
```

Обычная debug-сборка по-прежнему доступна разработчикам локально:
`gradle :app:assembleDebug`. Она **не предназначена для обновления
пользовательской установки** и не публикуется как релизный APK.

## Постоянно подписанный APK

См. [docs/SIGNING.md](docs/SIGNING.md): настройте два GitHub Actions secrets
один раз, затем запустите workflow **Android Stable APK**.

В GitHub Actions релиз собирается через `gradle :app:assembleRelease`,
проверяется `apksigner verify` и публикуется как
`Smennik-<версия>-signed`. Без секретов выпуск намеренно блокируется.

При локальной сборке задайте `SMENNIK_SIGNING_FILE` (путь к приватному
`.jks`) и `SMENNIK_SIGNING_PASSWORD`; после этого запускайте
`gradle :app:assembleRelease`.

Не добавляйте приватный ключ, пароль или секреты в репозиторий.

Перед сборкой выполните `python3 scripts/check_version.py` для проверки единой нумерации.
