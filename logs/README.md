# Логи

Сюда страница кладёт логи кнопкой «Отправить лог в GitHub», сюда же сохраняют
`tools/parse_btsnoop.py` и `tools/ble_logger.py`.

Имена: `YYYYMMDD-HHMMSS-<метка>.json`, большие — `.json.gz`, очень большие режутся на
части `...-part1of3.json.gz`. Время — начало записи по часам телефона.

Формат `birdair-lab-log` v1:

```json
{
  "format": "birdair-lab-log", "version": 1,
  "startedAt": "2026-10-14T19:02:11.000Z",
  "device": { "name": "scooter", "id": "scooter" },
  "gatt": [{ "uuid": "<service>", "chars": [{ "uuid": "<char>", "props": { "read": true, "notify": true } }] }],
  "decoder": { "json": false, "fields": [] },
  "part": { "id": "…", "index": 1, "count": 3 },
  "entries": [
    { "t": 1234, "k": "rx", "s": "<service>", "u": "<char>", "h": "5A A5 0B 25 …" },
    { "t": 1300, "k": "mk", "n": "Газ" }
  ]
}
```

`t` — миллисекунды от начала записи. `k`: `rx` — уведомление от самоката, `rd` — чтение,
`tx` — запись в самокат, `mk` — метка, `sys` — служебная строка. Поля `part` нет, если лог
целый. Репозиторий публичный: логи отправляются обезличенными (имя и id устройства →
`scooter`).
