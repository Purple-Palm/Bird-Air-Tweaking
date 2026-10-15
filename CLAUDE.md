# Bird Air Lab — заметки для Claude

Отвечай владельцу по-русски, коротко и технически: он разработчик.

Проект: телеметрия и разбор BLE-протокола личного самоката Bird Air (VA00020, FCC ID
2AWV7029757739). BLE-модуль Wi-linktech WLT8266BM (Telink TLSR8266), BLE 4.2. Батарея 36 В
10S2P 5,2 А·ч, зарядка 42 В 2 А. Режимы TAKEOFF 9 mph / GLIDE 12,5 mph / FLY 16 mph. Телефон
владельца — Galaxy S21, Chrome. Сайт: https://purple-palm.github.io/Bird-Air-Tweaking/
(GitHub Pages из `main` или ветки-источника Pages, `.github/workflows/pages.yml`).

## Жёсткие правила

- Только чтение телеметрии и диагностика. Запись в самокат — только по явному действию владельца и
  только командами приложения Bird: видны в его логе (`tx` из `parse_btsnoop.py`) или проверены
  по его коду в `docs/bird-air-protocol.md` (`+MODE=n`, `+HLGT=n`, `+SVGT=?`).
  Никогда не писать в OTA/DFU-характеристики (`isDanger` в `assets/app.js`,
  `birdlog.is_danger`), не слать `KEY?`/код доступа (всё, что начинается с `AE 4B 45 59`: это
  мастер прошивки, на самокате вызвал ошибку 37) и не слать случайные байты «на пробу».
- Секреты не коммитить. `python3 tools/check_secrets.py` должен проходить; не пиши в файлы
  префиксы токенов GitHub даже в примерах (в коде они собираются конкатенацией).
- Статический сайт без фреймворков и сборки. Дизайн, цветовые токены (`:root` в
  `assets/app.css`), русский интерфейс и демо-режим сохраняем. Объект для отладки —
  `window.__bal`.
- Репозиторий публичный: логи только обезличенные (`birdlog.anonymize`, галочка на странице).

## Устройство

- `index.html` + `assets/app.js` (одна IIFE) + `assets/app.css`. Всё идёт через `pushEntry()` →
  `decodeEntry()`; `rebuildTelemetry()` перегоняет лог после правки декодера.
- Декодер: поля `{key, name, uuid, match, offset, type, scale, add}`; `match` — префикс пакета,
  `??` — любой байт. Слоты localStorage: `bal.decoder` («мой»), `bal.decoder.repo`
  («из репозитория»), `bal.decoder.active`, `bal.decoder.backup`, `bal.decoder.dismissed`.
- Формат лога — `logs/README.md` (`birdair-lab-log` v1). Python-модуль формата —
  `tools/birdlog.py`.
- Тесты: `python3 -m unittest discover -s tests` и `npx playwright test` (нужен `npm ci`).
  Браузеры Playwright 1.56.1 в облачном окружении уже стоят, `playwright install` не нужен.
- `android/` — отдельный Gradle-проект (сайт по-прежнему без сборки): `:core` (Kotlin JVM),
  `:phone`, `:wear`, общий applicationId и `android/debug.keystore`; сборка и тесты —
  `cd android && ANDROID_HOME=/root/android-sdk ./gradlew --no-daemon :core:test :phone:testDebugUnitTest :wear:testDebugUnitTest assembleDebug`
  (CI — `.github/workflows/android.yml`, APK в релизе `android-latest`; подробности — `android/README.md`).

## Рабочий цикл «новый лог»

Когда владелец пишет «новый лог»:

1. Подтяни свежие логи: `git fetch origin main && git merge origin/main` (телефон кладёт логи
   в `main`, папка `logs/`; если в настройках страницы другая ветка — её).
2. Прогони анализ свежих файлов: `python3 tools/analyze_log.py --new` (это логи, которых нет в
   `sources` у `decoders/current.json`). Для snoop-лога сначала
   `python3 tools/parse_btsnoop.py <файл> --label bird-app`.
3. Перепроверь кандидатов по сырым байтам (группа, байт, тип, масштаб, реакция на метки).
   Не переноси поля с низкой уверенностью без подтверждения; ничего не выдумывай.
4. Обнови `decoders/current.json`: подтверждённые поля из `decoders/candidate.json` (поля из
   `needs_uuid` и `needs_match` переносить только после того, как вручную вписан UUID или
   «Начало пакета»),
   `version` +1 (целое число), `updatedAt` — текущее время UTC, `note` — что изменилось
   по-русски, `sources` — все разобранные логи. Формат — `birdair-lab-decoder`.
5. Прогони тесты, закоммить («Декодер vN: …») и запушь; чтобы страница увидела декодер, он
   должен попасть в `main` (через PR).
6. Коротко расскажи владельцу, что нашлось: номера байтов, типы, масштабы и примеры значений
   (hex и что они значат). Скажи, что сделать на телефоне: открыть страницу, нажать
   «Применить» в баннере «В репозитории новый декодер». Если данных не хватает — какие действия
   с метками записать в следующий раз (раздел «Что записать в следующий раз» в выводе
   анализатора).

## Протокол Bird Air

Разбор BLE-протокола (код приложения Bird из репозитория darkneuralnet/red + логи владельца) —
в `docs/bird-air-protocol.md`. Главное: руль молчит, пока телефон не пришлёт команду `+…`;
после неё по `0xFFE1` идут кадры UART контроллера `3A адрес команда длина данные сумма16 0D 0A`
(декодер `"framing": "colon-sum16"`, смещения полей — от `3A`). Режим, напряжение, заряд и
пробег — в кадрах `3A 17 00 0D` и `3A 18 20 10`. Команды режима/фары — ASCII `+MODE=1`,
`+HLGT=1`; шифрования и серверного токена нет. OTA/DFU, `KEY?` и код доступа — никогда.
