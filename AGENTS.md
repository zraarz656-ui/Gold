# TradeQuest — agent notes

Offline, single-user Android app. Kotlin + Jetpack Compose, MVVM, Hilt, Room,
Coroutines/Flow, WorkManager, DataStore. XAUUSD only; a "live" market is replayed from
bundled 1-minute history using a whole-week time offset.

## Modules

- `engine` — pure Kotlin/JVM (no Android). `ClockEngine`, `MarketCalendar`, `Aggregator`,
  `FillEngine`, `AccountState`, constants (`MarketTime`, lot/commission/spread levels).
  Tests run on the JVM with JUnit 5.
- `chart` — Compose candlestick chart. `ChartController` holds `ChartState` (bars, viewport,
  theme, `orderLines`, `markers`, `marketClosed`); `ChartRenderer` draws it. `ChartOverlays.kt`
  defines `ChartOrderLine` / `ChartMarker`.
- `data` — Room database, DAOs, repositories (`CandleRepository`, `SeasonRepository`,
  `TradingRepository`, `SettingsRepository`), `DatasetImporter`, `CatchUpProcessor`,
  `AccountCheckpoint`. Tests are Robolectric + in-memory Room.
- `app` — Hilt/Compose app: `TradingViewModel`, screens, `LiveClock` (minute ticker),
  `CatchUpWorker`/`CatchUpScheduler`/`Notifier`.

## Build & test

JDK 17 or 21 and the Android SDK are required. The toolchain is preinstalled at
`~/tools/jdk-21.0.12.1+1` and `~/android-sdk` in this environment:

```bash
export JAVA_HOME=$HOME/tools/jdk-21.0.12.1+1
export ANDROID_HOME=$HOME/android-sdk ANDROID_SDK_ROOT=$HOME/android-sdk
./gradlew :data:test :engine:test :app:assembleDebug
```

## Phase 1/2 recovery from the debug APK

The original Phase 1/2 sources were never pushed and no longer exist on disk. The provided
debug APK (`/workspace/recovered/apk/app-debug.apk`) is the **only** source of truth. To
inspect it:

```bash
# Standard class decompile (composable bodies are often dumped as bytecode: "Method dump skipped")
java -jar jadx/bin/jadx --no-res -d out <apk>
# Reconstructable Kotlin for a single class whose body was skipped:
java -jar jadx/bin/jadx --no-res --decompilation-mode simple -d out --single-class com.tradequest.chart.ChartScreenKt <apk>
```

The chart's gesture semantics (one pointer loop: pan/fling/pinch/gutter drag+scale,
long-press crosshair, double-tap auto-fit, news tap) mirror `CandleChartKt$chartGestures$2`.
Engine models (`Candle(ts,o,h,l,c,v)`, `EquitySnapshot(ts,equity,balance,usedMargin)`,
`NewsEvent(ts,title,impact)`), `ChartController`, `ChartState`, `Viewport`, `ChartMath`
constants and `GestureMath` were diffed field-by-field against the APK and match.

## Gotchas

- Assets named `*.gz` are transparently gunzipped by AGP when merged into the APK, so the
  bundled `app/src/main/assets/xauusd_m1.csv.gz` is delivered as `assets/xauusd_m1.csv`.
  `DatasetImporter` reads both names and sniffs the gzip magic; never assume the `.gz` name
  survives to the device.
- `gradlew clean` reinstalls nothing; the JDK/Android SDK are not part of the repo. In this
  environment they were (re)installed to `~/tools/jdk-21.0.12.1+1` and `~/android-sdk`.

## Invariants

- A candle may only be exposed when `ts <= ClockEngine.lastVisibleCandleTs(histNow)`
  (`CandleRepository` enforces this; never hand it a raw `histNow`).
- Catch-up is deterministic: `AccountCheckpoint` (serialised `AccountState`) is written in
  the same Room transaction as `Season.lastProcessedTs`, so an interrupted run resumes
  exactly. Keep both in one `withTransaction`.
- Orders are evaluated from the next candle after placement.
- Process death must lose nothing: all authoritative state lives in Room, not in memory.
