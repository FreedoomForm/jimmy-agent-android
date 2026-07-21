# ⚡ JimmyAgent — нативный Android APK (офлайн-агент)

Нативное Android-приложение: **Python-стек агента встроен в APK** (через [Chaquopy](https://chaquo.com/chaquopy/)) — агент, прокси `jimmy-proxy` и файловая песочница работают **полностью на телефоне, без облака**. UI открывается в WebView с локального сервера `http://127.0.0.1:8000` (порты loopback не требуют разрешений).

- Мозг: [ChatJimmy](https://chatjimmy.ai/) — Llama 3.1 8B на ASIC Taalas (~17K ток/с, без API-ключей). Интернет нужен только для ответов модели.
- CoT: рассуждения `<think>` в стиле [Grug Brained Developer](https://grugbrain.dev) 🦴
- Основано на [jimmy-proxy](https://github.com/Fadeleke57/jimmy-proxy).

## Сборка

APK собирается в GitHub Actions (`.github/workflows/build-apk.yml`) и публикуется артефактом + в Releases.

Локально (нужны Android SDK + JDK 17):

```bash
cd android
gradle assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Структура

```
android/
├── settings.gradle / build.gradle       # AGP + Chaquopy
└── app/src/main/
    ├── AndroidManifest.xml              # INTERNET + cleartext (для WebView на localhost)
    ├── java/com/jimmy/agent/MainActivity.java
    ├── res/drawable/ic_launcher.png
    └── python/                          # весь стек агента — исполняется на устройстве
        ├── android_main.py              # точка входа: прокси+сервер в потоках
        ├── agent_server.py              # агентский цикл + инструменты + UI API
        ├── proxy.py                     # jimmy-proxy (ChatJimmy → OpenAI API)
        └── static/                      # UI (PWA)
```

## Установка APK

1. Скачай APK из Actions artifact или Releases
2. Разреши «Установку из неизвестных источников»
3. Запусти ⚡ JimmyAgent — сервер поднимется сам, WebView откроет чат

## Ограничения на устройстве

- `run_shell` использует Android `sh` (toybox): `ls`, `cat`, `echo`, `uname` и т.п. работают; полный bash отсутствует.
- Отладочная подпись (debug APK) — для дистрибуции подпиши своим ключом.
