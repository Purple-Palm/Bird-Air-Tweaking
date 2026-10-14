# Bird Air Lab

Телеметрия и разбор Bluetooth-протокола личного самоката Bird Air (VA00020) через Web Bluetooth.
Статическая страница без сборки и фреймворков: открыл в Chrome на телефоне — подключился.

**Адрес:** https://purple-palm.github.io/Bird-Air-Tweaking/

## Включить GitHub Pages (один раз)

1. Pages на бесплатном аккаунте работает только для публичных репозиториев. Если репозиторий
   приватный: Settings → General → внизу «Danger Zone» → Change repository visibility →
   Change to public.
2. Settings → Pages → Build and deployment → Source: **GitHub Actions**.
3. Влей изменения в `main`. Workflow «Pages» (вкладка Actions) выложит сайт за минуту-две.
   Дальше каждый пуш в `main` обновляет сайт сам. Пуши только в `logs/`, `tools/`, `tests/`
   и `*.md` сайт не пересобирают.

Номер сборки (короткий хеш коммита) виден внизу страницы: так видно, что телефон получил свежую
версию. Если нет — потяни страницу вниз, чтобы обновить.

## Структура

| Путь | Что там |
| --- | --- |
| `index.html`, `assets/` | страница: разметка, стили, скрипт |
| `decoders/current.json` | декодер, который страница предлагает применить |
| `decoders/candidate.json` | черновик декодера от `tools/analyze_log.py` |
| `logs/` | записанные логи (формат — в `logs/README.md`) |
| `tools/` | анализ логов, разбор HCI snoop log, логгер для ПК |
| `tests/` | Playwright-тесты страницы и unittest для `tools/` |

## Открыть на компьютере

```sh
python3 -m http.server 8000
```

и http://localhost:8000/ в Chrome или Edge. Web Bluetooth работает только по https или
с localhost.

## Секреты

Токены GitHub в репозиторий не попадают. Проверка — `python3 tools/check_secrets.py`, она же
идёт в CI и перед выкладкой сайта. Хук перед коммитом включается один раз на клон:

```sh
git config core.hooksPath .githooks
```
