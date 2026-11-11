# Сборка Nexura в Android Studio

## Что уже находится в этой ветке

- Android-клиент Nexura на Kotlin и Jetpack Compose.
- Интеграция LiveKit Android SDK для медиакомнаты звонка.
- Supabase Edge Function `livekit-token`, которая проверяет JWT пользователя и членство обоих участников в личном чате, после чего выдаёт токен комнаты на 10 минут.
- Существующая сигнализация Supabase Realtime для приглашения, ответа, отказа и завершения звонка.
- Старый WebRTC/TURN-код сохранён как путь отката; старую функцию `turn-credentials` не удалять до завершения тестирования.

## Требования

- Android Studio с Android SDK Platform 35.
- JDK 17.
- Gradle 8.9.
- Интернет для загрузки зависимостей Gradle/Maven.

## Открытие проекта

1. Скачайте ZIP этой ветки из GitHub и распакуйте.
2. В Android Studio выберите **Open** и укажите папку, где лежат `settings.gradle.kts` и `app/`.
3. В **Settings → Build, Execution, Deployment → Build Tools → Gradle** выберите JDK 17.
4. Если Android Studio сообщает, что Gradle Wrapper отсутствует, установите Gradle 8.9 и выполните в корне проекта `gradle wrapper --gradle-version 8.9`. Это создаст локальные wrapper-файлы; после этого нажмите **Sync Project with Gradle Files**.
5. Дождитесь окончания синхронизации и выберите **Build → Build Bundle(s) / APK(s) → Build APK(s)**.

APK после успешной сборки:
`app/build/outputs/apk/debug/app-debug.apk`.

Альтернативно из терминала корня проекта:
`gradle --no-daemon assembleDebug`.

## Настройка LiveKit на сервере

В Supabase Dashboard → Edge Functions → Secrets должны быть заданы:

- `LIVEKIT_URL` — WSS URL проекта LiveKit Cloud.
- `LIVEKIT_API_KEY` — API Key.
- `LIVEKIT_API_SECRET` — API Secret.

API Secret хранится только в Supabase. Не добавляйте его в Android Studio, `local.properties`, GitHub, APK или исходники.

Функция `livekit-token` уже присутствует в этой ветке и в подключённом Supabase-проекте. Она использует Supabase JWT для проверки пользователя и service-side membership lookup. Если функция отвечает `livekit_not_configured`, проверьте три секрета в Supabase.

## Что проверить после сборки

1. Установить APK на два Android-устройства с разными аккаунтами Nexura.
2. Убедиться, что аккаунты состоят в одном личном чате.
3. Проверить исходящий и входящий аудиозвонок, принятие, отказ и завершение.
4. Проверить видеозвонок, переключение камеры, микрофон и громкую связь.
5. Повторить тест на разных сетях (Wi-Fi ↔ мобильная сеть), при блокировке UDP и при сворачивании приложения.
6. Проверить логи Supabase Edge Function `livekit-token` и LiveKit Cloud.

## Статус безопасности

LiveKit использует TLS и DTLS-SRTP для транспортной защиты. Это само по себе **не означает**, что реализовано сквозное шифрование медиа Nexura. До отдельной реализации и проверки клиентского E2EE нельзя заявлять, что LiveKit SFU не может получить доступ к медиапотоку.

## Ограничения текущего этапа

Наличие LiveKit SDK, серверной выдачи токена и вызова `Room.connect` не заменяет испытание двух реальных устройств. Не переключайте всех пользователей на новый транспорт и не удаляйте старый TURN/WebRTC путь до успешного тестирования.
