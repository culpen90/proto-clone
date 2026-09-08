# Device screenshot review

An independent visual review compared the installed Proto screenshots with screenshots of the exact installed-package clone. All reviewed files are native 1440 × 3120 phone captures; the image viewer scaled their previews proportionally. This review did not interact with the phone.

The reviewed pairs show matching application content geometry, colors, artwork, fonts, and text wrapping. The intentional title change (`Proto` to `Proto Clone`, ellipsized as `Proto Cl...`) and differing simulation state are excluded from content-parity judgments. This is visual inspection, not a claim that entire screenshot files are pixel-identical.

## Reviewed screenshots

| Reference | Exact clone | Observation |
| --- | --- | --- |
| `reference/screenshots/01-launch.png` | `artifacts/screenshots/exact-01-launch.png` | Grass texture, tree/crown/house/industry artwork, tile scale, map viewport boundary, and black summary-panel placement match. The same commodity colors and monospace statistics columns appear in the same positions. Reference is an established economy; clone is fresh, so its yellow start instructions, population, industry placements, vehicles, and numeric statistics differ as expected. |
| `reference/screenshots/01-launch.png` | `artifacts/screenshots/exact-02-played.png` | Clone's start overlay has disappeared and vehicles/transactions are now present. The map/statistics layout still matches. This pair demonstrates a visibly advanced clone state, not equal-time simulation equivalence. |
| `reference/screenshots/04-banking.png` | `artifacts/screenshots/exact-03-banking.png` | Central Bank heading, explanatory paragraph line wrapping, blue gradient, interest/reserve input columns, green Apply button, second paragraph, bank-statistics labels, and bottom underline all align visually. Rates and balances differ because the simulations have different states/settings. Reference's blue toolbar is blank in this capture while the clone's controls are visible; see the limitation below. |
| `reference/screenshots/05-trade.png` | `artifacts/screenshots/exact-04-trade.png` | Buy/Sell tabs, orange strip, pink selected-tab underline, Buy heading, two dropdowns and arrows, Set Price field with selected `0.01`, Order/Cancel buttons, and empty order-area underline match in position, color, size, and spacing. No content-layout difference was observed. |
| `reference/screenshots/06-special-ordering.png` | `artifacts/screenshots/exact-05-special-ordering.png` | Heading and explanatory line wrapping, blue gradient, Unit/Food selector, Quantity field, green Order button, blank history, break-obligations explanatory text, and purple Break Obligations button match. Both captures also reproduce the partial toolbar containing only the focused `75` field. |
| `reference/screenshots/07-currency.png` | `artifacts/screenshots/exact-06-currency.png` | Tax heading/selector, Rate/Set row, Start Taxing button, seven rate rows, Currency Control amount and Create/Destroy buttons, Financial Stimulus amount/Distribute row and industry selector match. Reference shows full toolbar while clone's toolbar is blank in this capture. |
| `reference/screenshots/08-graph.png` | `artifacts/screenshots/exact-07-graph.png` | Both captures still show the Map and summary panel with the same graph-instruction toast. Neither contains a rendered graph; graph visual parity is not established by these images. |
| `reference/screenshots/09-statistics.png` | `artifacts/screenshots/exact-08-statistics.png` | Both captures still show the Map and ordinary summary panel, rather than advanced statistics. Advanced-statistics visual parity is not established by these images. |

## Observed toolbar difference

The original Banking capture has a blank blue toolbar. Both original and clone Special Ordering captures show only a focused `75` scale entry in that toolbar; the remaining actions/title are absent in those images. Original and clone Map and Trade captures show the complete toolbar. The reviewed clone Banking capture shows the complete toolbar, while clone Currency has a blank toolbar and original Currency has complete controls.

These captures establish a difference in visible toolbar state, but do not establish a persistent implementation difference or its cause. The original and clone were captured at different times, with different focus and screen-transition states. The source/resource audit and installed-package payload comparison are separate evidence; they cannot turn this screenshot observation into a matched-frame visual pass.

## Material limits

- These are individual screenshots, not synchronized captures of identical saved states, elapsed simulation time, focus, keyboard, or menu-transition state.
- File names alone do not establish which view actually rendered. The images named Graph and Statistics above still display Map content in both original and clone. The source's main loop changes the selected surface view during unpaused simulation; a running-state capture is needed to inspect those views.
- Phone status-bar clock, battery, network indicators, and other system chrome differ between captures and are outside the game-content comparison.
- Screenshots show rendered controls and values. They do not by themselves prove successful trading, bank calculations, save/load round trips, all dropdown options, long-run stability, performance, or every branch of input validation.
- Content below a page's captured viewport, alternate tabs, expanded dropdowns, tablet mode, unit popups, graph interaction, and unpictured menus require their own evidence before being marked visually reviewed.
- The editable upstream-source rebuild and the exact installed-package clone are distinct artifacts. This document reviews `exact-*.png`; it does not transfer those results to the source rebuild.

The broader unexecuted behavior inventory remains in `UI_PARITY_CHECKLIST.md`. Raw resource integrity is recorded separately in `ui-resource-parity.json`, including independent final byte-for-byte checks of all three `.proto` economy templates against the installed base APK.


## Later runtime evidence

A later inspection confirmed `artifacts/screenshots/exact-12-statistics-rendered.png` (actual assets/commodity Statistics view) and `artifacts/screenshots/exact-14-graph-active.png` (actual six-series Graph view). Those captures resolve the earlier missing-rendering evidence; the earlier paired screenshots remain documented as captured.
