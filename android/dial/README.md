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

Все пять нарисованы по своим макетам (размеры, цвета и движение — из галереи, общие правила судьи — ниже, «Как
портировать тему», п. 3); отличия от макетов — тексты ламп из ядра (`Lamp.title` / `valueText` / `detail`, а не слова
макета) и заливка не ярче `NIGHT_FILL`. Что делает каждая тема на часах — [`../wear/README.md`](../wear/README.md),
«Темы циферблата».

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
- Темы, кроме самого рисования, держат раскладку кадра чистой функцией времени (её проверяют JVM-тесты, любой кадр —
  точный, смена темы посреди движения картинку не сбивает):
  - «Крупные лампы» — `StackScene.kt` (`Stack` — тайминги stack2, `StackTimeline` — что тема показала и когда,
    `StackScene.build` — кадр) и `StackCheck.kt` (проверки раскладки для тестов `:dial` и `:wear`); `Stack.LAND_MS`
    (470 мс) — на столько часы откладывают вибрацию лампы с этой темой, чтобы мотор шёл, когда фишка садится;
  - «Захват экрана» — `TakeoverLayout` в `FaceTakeover.kt`;
  - «Растущие лампы» — `FaceGrowLayout.kt` (раскладка макета, сверена с его selftest до 0,01 px) и `FaceGrowMotion.kt`
    (`GrowScene` / `GrowMotion`: движение в замкнутой форме);
  - «Кольцо» — `RingLayout` / `RingPen` / `RingPick` в `FaceRing.kt` (касание экрана часов показывает следующую горящую
    лампу на 6 с; считается только касание, которое никто не забрал: свайп пейджера и выбор превью на телефоне — нет).

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

## Тесты

`./gradlew :dial:testDebugUnitTest` — `DialStateTest` (сборка состояния, история ламп, сценарии, кривые, выбор темы), по файлу на тему:
`FaceStackTest`, `FaceTakeoverTest`, `FaceGrowLayoutTest` + `FaceGrowMotionTest` (с `grow-golden.json` — выборка
раскладок макета; `GROW_GOLDEN=<дамп>` сверяет все 1032), `FaceRingTest`. Каждый гоняет все 11 сценариев кадр за кадром
и случайные наборы ламп: ничего не выходит за круг и не перекрывается, главная лампа — `TellTales.top`, без движения
кадров нет, ambient статичен. Поездки из логов (20.10 «до нуля», 15.10 с перегревом, 16.10 вдвоём) — в `:wear`:
`StackReplayTest`, `TakeoverReplayTest`, `FaceGrowReplayTest`, `RingReplayTest`.

## Снимки

```sh
cd android
ANDROID_HOME=/root/android-sdk ./gradlew --no-daemon :wear:testDebugUnitTest -Pshots --tests '*FaceShots*' -PshotFace=stack
python3 wear/shots_sheet.py --face stack                 # wear/build/shots/faces/stack.png + index.html
python3 wear/shots_sheet.py --by scenario                # все темы рядом по сценариям
```

Каждая тема × 11 сценариев × кадры 0, 150, 350, 600, 1200 мс после смены — `wear/build/shots/faces/<тема>/<сценарий>-<мс>.png`
(480×480, как на Ultra). Без `-PshotFace` — все пять; одна связка — `--tests '*FaceShots*stack-amber*'`. Свои снимки тем
(тоже с `-Pshots`): `StackShots` — шкалы stack2 (повышение уровня, прокрутка числа, повтор красной, ✓) и поездки,
`wear/build/shots/stack/`; `TakeoverReplayShots`, `FaceGrowReplayShots`, `RingShots` — поездки из логов и касания,
`wear/build/shots/faces/<тема>-replay/`.
