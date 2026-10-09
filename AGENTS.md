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
  `TradingRepository`, `SettingsRepository`), `PreferencesStore` (DataStore),
  `DatasetImporter`, `CatchUpProcessor`, `AccountCheckpoint`. Tests are Robolectric + in-memory Room.
- `app` — Hilt/Compose app: `TradingViewModel`, screens, `LiveClock` (minute ticker),
  `CatchUpWorker`/`CatchUpScheduler`/`Notifier`.

### Theming

All app colours come from `app/ui/theme/Theme.kt` (`TradeQuestColors` + `LocalTradeQuestColors`).
`MainActivity` wraps the UI in `TradeQuestTheme(controller.state.theme)`, so the app palette
follows the chart theme (dark, light, OLED, colour-blind). Screens must **not** hard-code
`Color(...)`; use the tokens (`surface`, `surfaceVariant`, `onSurface`, `onSurfaceVariant`,
`outline`, `accent`, `onAccent`, `positive`, `negative`, `warning`). Text inputs must use
`TradeNumberField`/`tradeFieldColors()` (explicit `OutlinedTextFieldDefaults.colors`) so
input text, labels and placeholders stay readable. The selected theme is persisted in
DataStore under `theme_id` (`PreferencesStore`) and applied before the first frame via
`TradingViewModel.bootstrap`; `themeForId` maps the id back to a `ChartTheme`, defaulting
to Dark. `ThemeContrastTest` asserts >= 4.5:1 for text tokens and >= 3:1 for `outline`;
`OrderSheetThemeUiTest` (debug-only) types into the sheet in all four themes.

## Build & test

JDK 17 or 21 and the Android SDK are required. In this environment the sandbox `$HOME`
can be wiped between sessions, so the toolchain is kept on the persistent `/workspace`
mount at `/workspace/toolchain/jdk-21.0.12.1+1` and `/workspace/toolchain/android-sdk`
(`local.properties` points `sdk.dir` there):

```bash
export JAVA_HOME=/workspace/toolchain/jdk-21.0.12.1+1
export ANDROID_HOME=/workspace/toolchain/android-sdk ANDROID_SDK_ROOT=/workspace/toolchain/android-sdk
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
- `gradlew clean` reinstalls nothing; the JDK/Android SDK are not part of the repo. They are
  (re)installed to `/workspace/toolchain/…` (see Build & test), not `$HOME`, because the
  sandbox home is not durable across sessions.

## Invariants

- Chart geometry has exactly one source of truth: `ChartMath.plotRect(canvasWidth,
  canvasHeight, axisWidthPx, bottomAxisPx)` returns the plot area (everything except the
  right price gutter and the bottom time axis). `geometryFor` builds `ChartGeometry` from it
  and every draw path, hit test and label derives from `geo.plot` / the `priceToY(.., PlotRect)`
  / `yToPrice(.., PlotRect)` overloads. The plot layers are wrapped in `clipRect(plot..)` and
  the gutter labels in `clipRect(plot.right .. size.width)`, so nothing can bleed over a
  neighbouring strip/tab regardless of layout.
- A candle may only be exposed when `ts <= ClockEngine.lastVisibleCandleTs(histNow)`
  (`CandleRepository` enforces this; never hand it a raw `histNow`).
- Catch-up is deterministic: `AccountCheckpoint` (serialised `AccountState`) is written in
  the same Room transaction as `Season.lastProcessedTs`, so an interrupted run resumes
  exactly. Keep both in one `withTransaction`.
- Orders are evaluated from the next candle after placement.
- `OrderType.MARKET` carries no side, so a market `OrderRequest` must set `side`
  explicitly; the order sheet's `initialType`/`initialSide` seed the form but never fix the
  side, and both Buy and Sell actions are always rendered for a market order.
- Catch-up runs on the minute tick as well as on open/resume (`TradingViewModel.observeTicker`).
  The tick is what makes a market order fill when the next candle closes; without it fills
  would only happen on reopen. Running catch-up rewrites the order tables, so every trade
  mutation (`placeOrder`, `cancelOrder`, `closePosition`, `editStops`, `applyLevelOutcome`,
  `debugTimeTravel`) takes the same `catchUpMutex` to avoid a lost-write race.
- Chart order levels (Phase 3) are pure data: `ChartOverlays.kt` defines `ChartOrderLine`
  (`handles` lists the missing "+SL"/"+TP" affordances), `ChartOverlayState` (lines, markers,
  `bid`, `spread`) and `DragPreview`. `LevelEdit.kt`/`LevelRules` snap to 0.01, validate
  side/entry/market and emit a `LevelOutcome`; `LevelHitTest` resolves a touch (close box >
  handle > line/tag) from the same `LevelGeometry` constants the renderer draws with. The
  gesture loop is the **only** place a level drag begins; `ChartController.beginLevelDrag` /
  `updateLevelDrag` / `endLevelDrag` own the preview, which survives `setOverlay` rebuilds so
  an in-flight drag is never clobbered. `TradeQuestScreen` confirms every `Set` via the chip
  before `TradingViewModel.applyLevelOutcome` persists it through `TradingRepository`.
- Every closed trade carries `closedAt` = the timestamp of the candle whose processing
  closed it (`ClosedPosition.closeTs`): SL/TP/stop-out use the candle that triggered them,
  a manual close uses the season's last visible candle. `closedAt` is never wall-clock and
  never null for rows written by the engine. Legacy null rows are not migrated; use the
  debug "Reset season" action instead.
- Process death must lose nothing: all authoritative state lives in Room, not in memory.
- Debug-only actions ("Reset season", "Time travel +N") are gated by `BuildConfig.DEBUG`
  in the UI. Time travel only shifts `Season.offsetMs` backwards (advancing `histNow`); it
  touches no candle or trade data, is clamped so the last visible candle is never beyond
  the dataset's last candle, and then runs the normal catch-up.
