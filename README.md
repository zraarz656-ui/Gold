# TradeQuest — Phase 1

Phase 1 delivers the data-preparation tooling and the pure Kotlin/JVM simulation
`engine` (no Android dependencies). Later phases add the Android UI on top of it.

## Layout

```
engine/    Kotlin/JVM library: models, clock, aggregation, execution engine
tools/     Node.js data preparation script
```

## Data prep

Download XAUUSD 1-minute candles into `xauusd_m1.csv` (`ts,o,h,l,c,v`, `ts` = UTC
epoch milliseconds):

```bash
cd tools && npm install && node prep-data.js 2024-01-01 2024-03-31 ../xauusd_m1.csv
```

The script sorts rows ascending, drops duplicate timestamps, and prints the row
count, the first and last timestamps, and the number of gaps longer than 5 minutes.

## Engine

Requires JDK 21. Run the test suite with:

```bash
./gradlew :engine:test
```

The engine is deterministic: the only randomness is a PRNG seeded from
`(orderId, candle.ts)`, so re-processing the same candles yields identical results.

### Modules

* `Models.kt` — `Candle`, `Timeframe`, `Side`, `OrderType`, `Impact`, `NewsEvent`.
* `MarketTime.kt` — clock constants and floor-division helpers.
* `MarketCalendar.kt` — resolves a timestamp to its bucket start (UTC for M15/H1,
  17:00 New York rollover for H4/D1/W1).
* `ClockEngine.kt` — maps real time onto the historical timeline.
* `Aggregator.kt` — builds higher timeframes from 1-minute candles.
* `FillEngine.kt` — order execution, spreads, slippage, margin and daily-loss rules.
