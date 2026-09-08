# Installed Proto 1.99c (Patch 1): UI parity audit and runtime checklist

The reference is the installed `henize.proto_c` package copied to `reference/apk/`. The source is the GPL upstream project in `app/src/main/`. This is a source/resource audit; unchecked items below require runtime evidence from the rebuilt app. They are not recorded as passed merely because source matches. Keep original and clone app data separate when checking reset, economy selection, and saved states.

## Source and resource findings

- The installed `MainView.run()` adds `Thread.sleep(16L)` immediately before `App.sim.Next()`. The initial upstream source had no delay. This affects animation and simulation rate; the reconstruction includes this pacing change.
- The Info string's first line was old. It now matches the installed text exactly: `Version 1.99 (Patch 1) released Febuary 8, 2024`. Every other source-owned string matched after normalizing Android XML quoting, escaping, and whitespace.
- All 16 source layout XMLs and the menu XML match installed effective layouts on API 17+ after normalizing AAPT-generated representations, resource ID syntax, dimension notation, and flag ordering. The only residual attribute difference is the source's explicit `focusedByDefault=false` on the bootstrap log; false is the default.
- Source `AppTheme` and the three app colors match the installed resource values.
- All 17 installed language splits were decoded. Their string tables contain Android support-library text only, with no app-owned string overrides. The upstream `translations/` directory is not an active Android resource source set and should remain inactive for installed-version parity.
- All 23 app-owned PNG/BMP assets were preserved directly from installed `base.apk`, with SHA256 hashes in `ui-resource-parity.json`. Twelve BMPs already matched byte for byte. Eleven PNG representations differed and were replaced; this does not by itself establish that their decoded pixels differed.
- The persistence audit replaced the raw serialized economy templates with the installed bytes. A final independent comparison verified `big.proto`, `big2.proto`, and `classic.proto` byte-for-byte against `base.apk`; hashes are recorded under `raw_presets` in `ui-resource-parity.json`.
- Inspected `MainActivity`, `TraderView`, `CurrencyView`, `SpecialOrderingView`, `CentralBankView`, `ControlView`, `SuperStatsView`, `ListAdapter`, and `protoui` behavior matches the installed decompiled code apart from the MainView timing change above. Currency distribution's apparent inverted conditions in JADX are a decompiler error: installed smali confirms selections 0..5 dispatch to Labor, Food, Tool, Coal, Iron, Transportation. The apparent integer division in `Container.ScaleTranslator` is also a decompiler error: smali performs floating-point division as source does.
- Advertising/support controls and the unlock menu item are hidden in both source and installed layouts. The app must not surface them as new features.

## Entry, toolbar, and navigation

- [ ] Fresh launch displays the Info page, original circuit background, original typography, and patched version line. Portrait orientation and screen-on behavior match.
- [ ] Overflow entries appear in this order: Map, Graph, Trade, Special Ordering, Currency, Banking, Stats, Super Stats, Control, Info, How To..., Reset. Unlock stays hidden.
- [ ] Control submenu contains Full View, Supply & Demand, Economies, System Log, Save/Load State in that order. Full View and Supply & Demand show their current check states.
- [ ] Toolbar contains scale entry initially `75`, tablet switch, speed control initially disabled, and play/pause icon. Switching views does not duplicate toolbar actions.
- [ ] Play starts a fresh simulation and changes the icon to pause. Pause stops simulation advancement and disables speed. Speed changes title/check state and animation rate as in the installed app.
- [ ] Starting playback from Trade, Special Ordering, Currency, Economies, System Log, Save/Load State, Info, or How To... switches to Map. Banking, Stats, Super Stats, Map, and Graph remain on their pages when playback starts.
- [ ] Economies, System Log, and Save/Load State explicitly pause the simulation when selected. Economies also turns speed off.
- [ ] Back traverses previously selected pages, does not add duplicate consecutive page entries, and shows the original exit dialog when no prior page remains. Positive exit backgrounds the app; cancel leaves it open.
- [ ] Background/resume retains the intended current page, turns speed off, and reloads the simulation without a blank view, crash, or duplicate renderer thread. Saved-state correctness is tracked separately.

## Map, graph, and tablet mode

- [ ] Map shows the exact ground, house, food, tool, coal, iron, shipping, crown, tree, car, and truck artwork. World alignment and nearest-neighbor pixel scaling match the original phone screen size.
- [ ] Fresh Map shows the yellow `PRESS PLAY TO START` and `TAP UNIT ICONS FOR INFORMATION` overlay. After play, vehicles rotate and travel along paths and summary statistics update.
- [ ] Drag in each direction pans the map and clamps correctly at all four world edges. Verify both normal and Full View modes.
- [ ] Tapping a unit opens its information window with trader ID, price, account balance, debt, units for sale, units sold, and most recent attempted trade when available. Tapping the popup closes it. Neighboring-cell fallback selection and vibration match.
- [ ] Full View hides the summary panel and expands the visible map area; toggling it back restores the summary panel.
- [ ] Graph displays each commodity trace and the summary panel. Horizontal dragging changes the graph's transaction spread, and tapping resets to infinite spread. The original instruction toast appears on entry.
- [ ] Stats displays the original advanced text statistics while the simulation continues. Values change with actual simulation activity.
- [ ] Tablet switch creates bordered movable panels, sets Full View, and uses the scale entry. Turning it off restores normal panel positions and clears Full View.
- [ ] At scales 75 and 50, drag a window by its border, bring overlapping windows to the foreground, use bottom window icons, and verify scaled unit hit testing and popup closing.
- [ ] Scale values 1..99 retain original behavior; empty/unparseable input is ignored. Out-of-range values show the original message and use 50 internally. Preserve the original mismatch where the message mentions 100 but code accepts a maximum of 99.

## Trade

- [ ] Buy and Sell tabs render their original fields and disabled multiline order displays; each starts with quantity `1` and set price `0.01`.
- [ ] Both commodity dropdowns list Food, Tools, Coal, Iron, in that order.
- [ ] Buy conditions are Now at market value, Price is below [set price], Price is above [set price]. Each can be selected and submitted with a valid whole-number quantity.
- [ ] Sell conditions are Now at market value, Now at [set price], Now at [set price] above market value, Now at [set price] below market value, Price is below [set price], Price is above [set price]. Each can be selected and submitted.
- [ ] Buy ORDER and SELL enable the selected commodity order, update the corresponding text display with localized condition/price, and vibrate. Test all four commodities and multiple simultaneous enabled orders.
- [ ] CANCEL disables only the selected commodity's buy or sell order and updates its display. Invalid quantity or price shows the original invalid-value toast.
- [ ] With adequate currency and commodities, submitted market orders actually change holdings, balances, and order-related statistics; zero-price selling supports the original welfare scenario.

## Special Ordering

- [ ] Page shows the original explanatory text, Food/Tools/Coal/Iron dropdown, quantity field, ORDER button, order history, and BREAK OBLIGATIONS button.
- [ ] Valid integer orders for each commodity append trader ID, quantity, and commodity to history and initiate priority ordering. Empty or invalid input produces the original dialog.
- [ ] With adequate credit and funds, priority orders reserve production, trigger credit use, deliver goods, and complete. BREAK OBLIGATIONS returns activity to ordinary trading without removing unrelated state.

## Currency

- [ ] Tax selector lists Universal, Labor, Food, Tool, Coal, Iron, Transportation. Changing selection displays that rate; SET applies it and updates all seven tax-list rows.
- [ ] START TAXING/STOP TAXING toggles collection and the button label; actual tax reaches the master account while simulation runs.
- [ ] Universal plus any industry tax above 100% shows the original warning, unchecks the toggle, and disables collection. Invalid numeric input shows the original error dialog.
- [ ] Currency Create accepts an amount from zero through $1,000,000 per action, increases the master balance and monetary supply, and displays the original confirmation toast. Out-of-range/invalid input is rejected with the original dialog.
- [ ] Destroy reduces master funds and supply when sufficient funds and reserves exist. Insufficient funds/reserves produce the respective original dialogs.
- [ ] Financial Stimulus lists Labor, Food, Tools, Coal, Iron, Transportation. Distribute transfers the entered amount to the selected industry and displays the original toast; insufficient funds produces the original warning.
- [ ] Scrolling and keyboard appearance leave Create, Destroy, Distribute, and the stimulus selector accessible on the phone.

## Banking, statistics, and controls

- [ ] Banking shows original interest/reserve explanatory text, two rate fields, Apply, and the live bank information area.
- [ ] Apply accepts whole-number rates 0..100 and updates actual interest/reserve settings. Out-of-range input shows `Enter a value between 0 and 100`; decimal/unparseable input shows `Use whole numbers`.
- [ ] Bank information refreshes about once per second and includes balance, reserves, debt holdings, minimum payment, interest rate, reserve rate, and insolvency hits. Bank failure/credit meltdown labels appear when their engine flags are set.
- [ ] Super Stats shows each trader's icon, type, ID, account balance/frozen marker, debt, price, units for sale, sale count, and traveling state. It refreshes about once per second while retaining scroll position, adds new labor traders, and removes expired traders.
- [ ] Economies shows only CLASSIC, BIG ECONOMY, BIG ECONOMY II and loading/status text. Each loads its corresponding template and produces the expected map/population after Play. Support/advertising/boot-log buttons stay hidden.
- [ ] Supply & Demand toggles the pricing model and shows the corresponding original economy-stability toast.
- [ ] Reset pauses, disables speed, resets engine state and graph spread, refreshes UI values, and displays the original reset toast. Verify using disposable clone state.

## Information, log, and saved-state UI

- [ ] Info and How To... scroll through their full original text and graphics. How To includes baseline, currency creation, trade, priority orders, inflation, deflation, and welfare instructions.
- [ ] System Log shows the bootstrap log and original explanatory text. Its red DELETE STATE FILE AND TERMINATE control remains present; its destructive behavior must be tested only with disposable clone data.
- [ ] Save/Load State shows the compatibility warning, file list, Save, and Load. Save creates a timestamped entry; selecting an entry vibrates; Load restores the selected state and updates visible controls. Dedicated persistence audit owns cross-version/serialization checks.

For each runtime check, retain the device/API/density, app version, starting economy/state, observed result, and screenshots or logcat where relevant. No device runtime result was produced by this UI audit while the phone was disconnected.
