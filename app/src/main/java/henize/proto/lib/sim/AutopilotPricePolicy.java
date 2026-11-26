/* SPDX-License-Identifier: GPL-3.0-or-later */
package henize.proto.lib.sim;

import henize.proto.lib.traders.ComType;

/** Measures private market quotes against recent prices for treasury trade decisions. */
final class AutopilotPricePolicy {
    private static final double PRICE_BAND = 0.05;
    private static final double NEW_QUOTE_WEIGHT = 0.25;
    private final double[] reference = new double[ComType.values().length];
    private final double[] pressure = new double[ComType.values().length];

    void observe(Access access) {
        for (ComType type : AutopilotTradePolicy.GOODS) {
            int index = type.ordinal();
            long quote = access.dir.getAveragePriceOf(type);
            double previous = reference[index];
            pressure[index] = 0;
            if (quote <= 0) continue;
            if (previous == 0) {
                reference[index] = quote;
                continue;
            }
            double difference = quote - previous;
            // A single cent can double the price of an inexpensive good.
            if (Math.abs(difference) >= Math.max(1, previous * PRICE_BAND)) {
                pressure[index] = difference / previous;
            }
            reference[index] = previous + NEW_QUOTE_WEIGHT * difference;
        }
    }

    double pressure(ComType type) {
        return pressure[type.ordinal()];
    }
}
