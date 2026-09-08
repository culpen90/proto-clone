# Proto 1.99c (Patch 1) engine parity audit

Audited on 2026-09-08. The implementation is the GPL upstream Java source under
`app/src/main/java/henize/proto/lib`. The comparison target is the installed
Android application's decoded bytecode under
`reference/decoded-base/smali/henize/proto/lib`, with JADX Java output under
`reference/decompiled/sources/henize/proto/lib` used for readability.

This is a manual semantic audit, not a proof of program equivalence. Decompiled
Java can contain invalid control flow and incorrect inferred arithmetic types;
suspected differences were checked against smali before changing production code.
Random placement, thread scheduling, rendering, and device lifecycle behavior need
separate runtime verification. The JVM tests below execute the production models;
they do not run the original installed APK or establish identical full simulation
trajectories.

## Changes required by the installed release

| Class | Upstream behavior | Installed behavior restored | Bytecode evidence |
| --- | --- | --- | --- |
| `sim.Config` | No `FPS_DELAY` field | `public static final boolean FPS_DELAY = true` | `Config.smali:11` |
| `sim.Simulator` | `setScaleLeap` stores the nearest divisor in each leap setting | Both settings are assigned `1` | `Simulator.smali:284,316,326` |
| `map.Traveler` | Movement increments are `direction * (map.scale / scaleLeap)` | Each nonzero direction component advances exactly one pixel per tick | `Traveler.smali:933,944` |
| `stats.Graph` | Decimal formatter is serialized and initialized in the constructor | Formatter is transient and initialized lazily while drawing the loading state | `Graph.smali:38,349,354` |

The Simulator and Traveler changes must be applied together: setting the leap to
one while retaining the upstream division would advance an entire tile per tick.
The installed release instead takes one tick per pixel in each moving coordinate.
The frame loop's use of `FPS_DELAY` is outside the library and is audited separately.

## Coverage

All 44 Java source files present in the installed library were covered:

| Package | Files | Reviewed behavior |
| --- | --- | --- |
| `sim` | 3 | Defaults, initialization, resets, action ordering, aggregate calculations, graph transaction notifications, scale settings |
| `bank` | 3 | Transfer and refund accounting, taxation, reserve and credit handling, interest/payment cycle, listener contract |
| `commodities` | 8 | Production ratios, consumed-input checks, current/cumulative counters, delivery-address notifications |
| `traders` | 13 | Price adjustment, loans and sales, transport orders, special priority orders, spawning/death, player buy/sell conditions, small data classes |
| `map` | 5 | Placement, boundaries, frame translation, obstacle lists, pathfinding handoff, movement and arrival notifications |
| `directory` | 5 | Price and inventory ordering, add/remove bookkeeping, priority groups, trader lookup and auctions |
| `stats` | 7 | Counters, displayed quantities/prices, graph layout and graph-file read/write behavior |

No further semantic differences were found in that manual review. Upstream's
`Config_BigMap`, `Config_BigMap2`, and `Config_ClassicMap` are not present in the
installed library and were not included in the 44-file parity claim. The separate
`pathfinder` package and Android activities/views are outside this audit's scope.

## Decompiled artifacts deliberately not copied

- JADX makes `Map.RemoveAddress` appear to remove an item during iteration and
  then remove null. Smali `Map.smali:668-712` confirms the upstream implementation:
  find the matching object, break, and remove it after the loop.
- JADX drops the special-order loop's index reset, apparently creating an infinite
  loop. Smali `Trader.smali:709-727` branches back to the index reset; the upstream
  `buySpecialPriority` implementation was retained.
- JADX prints the graph horizontal step as integer division. Smali
  `Graph.smali:513-524` converts both operands to float before division, matching
  upstream.
- JADX cannot generate `Commodity.ToolGen`; its simple production loop was checked
  against decoded bytecode, rather than replacing it with the invalid generated
  source.

## Focused verification

Command, using Homebrew OpenJDK 21:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  ./gradlew testDebugUnitTest --tests 'henize.proto.lib.*' --console=plain
```

Result: **8 tests passed, 0 failures, 0 errors, 0 skipped**.

- `TravelerParityTest`: tests all eight movement directions at tile scales 8, 24,
  and 72, including per-tick offsets, tile arrival timing, offset reset, and END
  notification; also verifies removal of one map address preserves the other.
- `BankEconomicsTest`: verifies taxed sales/refunds restore account balances and
  transaction statistics, cash-backed and reserve-backed loans/repayments track
  debt, and rejected loans/frozen accounts do not move money.
- `CommodityEconomicsTest`: executes coal/iron/tool/food/labor production and
  checks input consumption plus current/cumulative counters; rejects reuse of
  consumed labor without incrementing output counts.

Reports are generated in `app/build/test-results/testDebugUnitTest` and
`app/build/reports/tests/testDebugUnitTest`. Build output includes Java 8 target
deprecation warnings from JDK 21 and Gradle deprecation notices; the tests pass.
