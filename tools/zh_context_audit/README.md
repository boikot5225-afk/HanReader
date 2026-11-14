# Локальный китайский контекстный аудит

Проверяется реальный путь `EpubBook → BookAnalyzer → ZhEngine.chapter → RToken → russianSenses/cardTranslation`.
Компилируются производственные ReaderModel, BookVocabulary, сегментатор, словари, правила и EPUB-парсер.
Заменены только Android host API и интерфейс запуска; Compose-карточка сверена с этим путём и покрыта unit-проверками.
Это не эмуляция нажатия на телефоне. Для синтетических тестов используется настоящий движок.

## Воспроизведение

Нужны Python 3, JDK 17, Kotlin 2.2.21, jsoup, org.json, JUnit4 и coroutines. Один раз
`python tools/context_audit/setup.py` скачивает только компилятор и библиотеки в игнорируемую `.work`;
при готовом кеше скачивания не нужны. Android SDK для этих команд не требуется.

```sh
python tools/zh_context_audit/build.py --label current --tests
python tools/zh_context_audit/run.py extract /private/book.epub .work/zh-context-audit/blocks.jsonl
python tools/zh_context_audit/run.py names /private/book.epub .work/zh-context-audit/names.json
python tools/zh_context_audit/run.py evaluate /private/gold.jsonl /private/current.jsonl --names .work/zh-context-audit/names.json
python tools/zh_context_audit/score.py /private/gold.jsonl /private/current.jsonl /private/metrics.json
```

Сначала извлечь spine/блоки, исключить ненарративные главы, разметить и заморозить gold.
Запись gold: `id,book,volume,chapter,path,title,block,text,target,start,end,char_start,char_end,
expected_ru,acceptable_ru,expected_reading,unit,category,must_whole,confidence,provenance,reason,split`.
`start/end` — UTF-16 индексы Android, `char_start/end` — индексы Python. `text` — приватный исходный абзац.
`expected_ru/acceptable_ru` — проверяемые семейства русских значений, а не строки ответа приложения;
разделитель допустимых чтений `|`. `confidence=review` сохраняется как UNRESOLVED.
Оценщик требует ровно один результат для каждого ID. Подстрока внутри более длинного токена
не считается отдельным доступным словом: для многознаковых подстрок смысл UNRESOLVED;
односимвольные пробы используют реально доступный разбор по иероглифам.

Для сохранённого аудита этой сессии: `python tools/zh_context_audit/validate.py` проверяет
хеши 420 эталонов, источник/позицию, все семь томов, непересечение абзацев и полноту трасс.
Эталон, полные EPUB, извлечённые тексты, имена и трассы находятся только в `.work/zh-context-audit`;
они намеренно не входят в Git, CI или облачные сервисы. Повтор исходного измерения требует
эти приватные материалы; публично воспроизводимы 60 оригинальных synthetic-примеров и регрессии.

```sh
python tools/zh_context_audit/run.py evaluate tools/zh_context_audit/synthetic.jsonl .work/zh-context-audit/synthetic-current.jsonl
python tools/zh_context_audit/score.py tools/zh_context_audit/synthetic.jsonl .work/zh-context-audit/synthetic-current.jsonl .work/zh-context-audit/synthetic-current-metrics.json
```

`pairs.json` связывает эти 60 примеров в 40 пар с общими опорными примерами (не 80 независимых).
`contrasts.py` сравнивает сохранённые `baseline/current` classpath и baseline-assets.
Для другого checkout исходный jar собирается до изменений с соответствующей версией Main,
а словари копируются в приватный baseline-assets. В этой сессии исходный jar сохранён отдельно;
текущую утилиту нельзя компилировать со старым ReaderModel, не имеющим contextMeaning.

## Где выбранный смысл

Раньше китайский модуль выбирал чтение по контексту, но русские смыслы шли в словарном порядке.
Теперь существующий `ZhRules` поддерживает строки `sense<TAB>слово<TAB>условия<TAB>русский смысл`.
Условия соединяются через `&`; первое совпавшее правило выигрывает. `near:` ищет до 16 токенов
в каждую сторону до границы предложения, включая сцепление до четырёх соседних Han-токенов
для разбитой подсказки. Запятые не завершают предложение; точки, вопросительные/восклицательные
знаки, точка с запятой и newline завершают. `next~` проверяет вхождение в следующий токен.
Остальная грамматика `read/split` сохранена.

Смысл находится только в текущем `RToken.contextMeaning`; pinyin остаётся произношением.
Ключ hint-кеша `text|pinyin|meaning` различает разные смыслы. Пользовательские значения HERE/BOOK
сохраняют приоритет. Подсказка, карточка, сохранение карточки и сводка используют тот же смысл.
Без правила карточка честно обозначает словарный список. Это ограниченный набор локальных
правил, а не общее разрешение многозначности. Внешнего ИИ на клике нет; ru_ai.tsv — статический файл.
