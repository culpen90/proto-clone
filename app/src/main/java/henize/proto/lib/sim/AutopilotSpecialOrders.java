/* SPDX-License-Identifier: GPL-3.0-or-later */
package henize.proto.lib.sim;

import henize.proto.lib.traders.ComType;
import henize.proto.lib.traders.MarketOrder;
import henize.proto.lib.traders.Trader;

import static henize.proto.lib.sim.AutopilotTradePolicy.GOODS;
import static henize.proto.lib.sim.AutopilotTradePolicy.lotSize;

/** Uses priority production to replenish goods that cannot currently be bought on the market. */
final class AutopilotSpecialOrders {
    private int nextGood;
    private long tradesAtLastBreak = -1;

    boolean breakStalled(Access access) {
        if (access.sim.specialPriority.isEmpty() || !access.sim.map.traveler_list.isEmpty()) return false;
        access.sim.breakPriority();
        tradesAtLastBreak = access.bankStats.transactionCount;
        return true;
    }

    boolean place(Access access) {
        // Priority orders take over production. Let existing obligations and deliveries finish first.
        if (!access.sim.specialPriority.isEmpty() || !access.sim.map.traveler_list.isEmpty()
                || tradesAtLastBreak == access.bankStats.transactionCount || access.taxAccount.freeze) return false;
        long budget = access.taxAccount.getBalance() / 10;
        if (budget <= 0 || access.dir.getCheapestTraderOf(ComType.LABOR, false) == null) return false;
        Trader shipper = access.dir.getCheapestTraderOf(ComType.TRANSPORTATION, false);
        if (shipper == null || shipper.getPrice() < 0) return false;

        for (int offset = 0; offset < GOODS.length; offset++) {
            int index = (nextGood + offset) % GOODS.length;
            ComType type = GOODS[index];
            if (AutopilotTradePolicy.hasStandingOrder(access.master, type)) continue;
            int lot = lotSize(access, type);
            Trader buyer = MarketOrder.traderFor(access.master, type);
            int quantity = lot - buyer.com.size();
            if (quantity <= 0 || !needsProduction(access, type, lot, quantity, shipper.getPrice(), budget)) continue;
            if (access.sim.placePriorityOrder(type, quantity)) {
                nextGood = (index + 1) % GOODS.length;
                return true;
            }
        }
        return false;
    }

    private static boolean needsProduction(Access access, ComType type, int lot, int quantity,
                                           long shipping, long budget) {
        long highestQuote = 0;
        for (Trader producer : access.dir.getListFromType(type).getList()) {
            // Sold goods may still be waiting for a carrier before they appear on the map.
            if (!producer.com_sold.empty()) return false;
            if (producer.account == access.taxAccount) continue;
            if (producer.isStocked() && producer.com.size() >= lot) return false;
            long quote = producer.getPrice();
            if (quote <= 0 || producer.account.freeze) return false;
            highestQuote = Math.max(highestQuote, quote);
        }
        // The engine may split the order between producers and charge shipping on each sale.
        // Compare before multiplying so even extreme saved prices cannot overflow the budget.
        if (highestQuote == 0 || highestQuote > budget / quantity - shipping) return false;
        return access.bank.enoughReserves((highestQuote + shipping) * quantity);
    }
}
