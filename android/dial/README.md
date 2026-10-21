# Циферблаты часов (`:dial`)

Темы циферблата, «как watch faces, только внутри приложения»: часы рисуют выбранную, телефон показывает все живыми
превью. Android-библиотека только на Compose UI / foundation (без Wear): её берут и `:wear`, и `:phone`.

| Тема (`DialFace`) | Файл | Макет |
| --- | --- | --- |
| `STACK` «Крупные лампы» — **по умолчанию** | `FaceStack.kt` | `gallery/stack.html` |
| `TAKEOVER` «Захват экрана» | `FaceTakeover.kt` | `gallery/takeover.html` |
| `GROW` «Растущие лампы» | `FaceGrow.kt` | `gallery/grow.html` |
| `RING` «Кольцо» | `FaceRing.kt` | `gallery/ring.html` |
| `CLASSIC` «Классика» — циферблат v108 без изменений | `FaceClassic.kt` | `mock3.html` |

Пока четыре новые темы — заглушки: рисуют «Классику».

## Что где

- `DialFace.kt` — `enum DialFace` (ключ, название, строка описания), `@Composable DialFace(face, state, modifier, nowMs)`
  выбирает файл темы; `FaceChoice` — выбор с временем, побеждает последний (телефон ⇄ часы).
- `DialState.kt` — всё, что рисует тема: `DialState` (скорость в единицах телефона и в другой, предел режима и точки тапа /
  вибрации, мощность и состояние, заряд до десятой, запас хода, строка «В · °C · режим», лампы с историей, здоровье данных и
  строка статуса, ambient, сдвиг от выгорания, проверка ламп, события мотора — тап, вибрация, вибрация лампы, — часы,
  единицы) и `DialBuilder`, который его собирает и помнит историю ламп (когда загорелась, сменилась, погасла).
- `FaceKit.kt` — общее: сетка макета 480 px, цвета, глифы (`drawGlyph`, `FaceKit.icon` — перечёркнутая батарея для BMS),
  дуга мощности, шкала скорости с точкой, ограничителем и вибрацией, блок батареи, подбор размера текста и перенос в две
  строки, кривые (`ease`, `back`, `pop`, `pulse`, `bob`, `ripple`), часы темы `rememberFaceClock`, пружина `rememberSpring`,
  `FaceText`.
- `DialScenarios.kt` — 11 демо-сценариев галереи (`norm`, `warm`, `amber`, `critical`, `bms`, `many`, `limiter`,
  `overspeed`, `bulb`, `ambient`, `charging`) как цепочки `DialState` в mph, лампы — тексты `TellTales.lamp`.
- `FacePreview.kt` — тема, проигрывающая сценарии (превью на телефоне).
- `Dial.kt`, `Display.kt`, `Power.kt`, `Motion.kt`, `PhoneFlags.kt` — чистая логика циферблата, перенесённая из `:wear`.

## Как портировать тему

Правится **один файл** (`FaceStack.kt` и т. п.), больше ничего общего:

1. Нарисовать `DialState` на `Canvas` по макету (размеры в px макета × `FaceKit.unit(size)`), примитивами `FaceKit`.
   Заголовок, число и подпись лампы — `Lamp.title`, `Lamp.valueText`, `Lamp.detail` (текст не разбирать), порядок —
   `state.lamps.top` / `alerts` (как `TellTales.top`), история — `DialLamp.sinceMs` / `changedMs` / `before`, ушедшие —
   `lamps.gone`.
2. Время — `val clock = rememberFaceClock(state, nowMs, ::wakeAt)`, `clock.value` читать внутри `draw` (тогда за кадр
   перерисовывается только холст): кадры идут, только пока
   `wakeAt` говорит «сейчас» (переходы ≤ 700 мс, пульс красной, вибрация, проверка ламп); в покое кадров нет.
   `FaceKit.wakeAt` годится, если своей долгой анимации нет.
3. Общие правила (судья галереи): красная пульсирует 1 Гц в фазе с вибрацией (`FaceKit.redSince`, `pulse`: 55–100 %),
   текст не мигает; появление ≤ 700 мс; долгое движение — только у красной и вибрации; ▲ «быстро греется» подпрыгивает
   при появлении и раз в 10 с (`bob`); жёлтая / синяя / зелёная не бывают сплошными; заливка ночью ≤ 0,82
   (`NIGHT_FILL`); между числом и единицей NBSP. Ambient (`state.ambient`) — статично, контуры, без свечения;
   `health.alpha` гасит значения, `status` встаёт на место часов, `burnInDp` — сдвиг.
4. Свои тесты — новым файлом в `dial/src/test` или `wear/src/test` (`DialReplay` прогоняет настоящий лог в цепочку
   `DialState`, с тапами, вибрацией и вибрацией ламп, как на часах).

## Снимки

```sh
cd android
ANDROID_HOME=/root/android-sdk ./gradlew --no-daemon :wear:testDebugUnitTest -Pshots --tests '*FaceShots*' -PshotFace=stack
python3 wear/shots_sheet.py --face stack                 # wear/build/shots/faces/stack.png + index.html
python3 wear/shots_sheet.py --by scenario                # все темы рядом по сценариям
```

Каждая тема × 11 сценариев × кадры 0, 150, 350, 600, 1200 мс после смены — `wear/build/shots/faces/<тема>/<сценарий>-<мс>.png`
(480×480, как на Ultra). Без `-PshotFace` — все пять; одна связка — `--tests '*FaceShots*stack-amber*'`.
