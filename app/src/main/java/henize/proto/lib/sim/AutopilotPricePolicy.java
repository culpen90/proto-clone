/* SPDX-License-Identifier: GPL-3.0-or-later */
package henize.proto.lib.sim;

import java.util.List;
import henize.proto.lib.stats.GraphData;

/** Use the quotes already recorded with each transaction to review the price model. */
final class AutopilotPricePolicy {
    private double previousBasket;
    private int volatileReviews, stableReviews;

    void reset() {
        previousBasket = 0;
        volatileReviews = stableReviews = 0;
    }

    void observe(Access access, boolean healthyTrade) {
        List<GraphData> history = access.sim.graph.virt_days;
        if (!healthyTrade || history.isEmpty()) {
            reset();
            return;
        }
        GraphData quote = history.get(history.size() - 1);
        double basket = (double) quote.food + quote.tool + quote.coal + quote.iron;
        if (previousBasket > 0 && basket > 0) {
            double change = Math.abs(basket / previousBasket - 1);
            volatileReviews = change >= 0.25 ? Math.min(2, volatileReviews + 1) : 0;
            stableReviews = change <= 0.05 ? Math.min(3, stableReviews + 1) : 0;
        } else {
            reset();
        }
        previousBasket = basket;
    }

    EconomyAutopilot.Status adjust(Config config) {
        if (config.supplyAndDemandEnabled && volatileReviews >= 2) {
            config.supplyAndDemandEnabled = false;
            reset();
            return EconomyAutopilot.Status.STABILIZING_PRICES;
        }
        if (!config.supplyAndDemandEnabled && stableReviews >= 3) {
            config.supplyAndDemandEnabled = true;
            reset();
            return EconomyAutopilot.Status.RESTORING_MARKET_PRICES;
        }
        return null;
    }
}
