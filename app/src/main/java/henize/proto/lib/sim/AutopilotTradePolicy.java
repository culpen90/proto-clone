/* SPDX-License-Identifier: GPL-3.0-or-later */
package henize.proto.lib.sim;

import java.util.EnumMap;

import henize.proto.lib.traders.ComType;
import henize.proto.lib.traders.MarketOrder;
import henize.proto.lib.traders.MasterTrader;
import henize.proto.lib.traders.Trader;

/** The treasury trades the same four goods as the Trade panel. */
final class AutopilotTradePolicy {
    static final ComType[] GOODS = {ComType.FOOD, ComType.TOOL, ComType.COAL, ComType.IRON};

    private final EnumMap<ComType, Long> offerDiscounts = new EnumMap<>(ComType.class);

    // At most one standing bid reserves the treasury's purchase budget at a time.
    private ComType bidType;
    private long bidTrigger;

    EconomyAutopilot.Status review(Access access, boolean healthy) {
        if (!access.sim.specialPriority.isEmpty() || access.taxAccount.freeze) return null;
        MasterTrader master = access.master;
        for (ComType type : GOODS) {
            Trader trader = MarketOrder.traderFor(master, type);
            if (!hasStandingOrder(master, type) && trader.com.size() >= lotSize(access, type)
                    && (!healthy || privateStocksLow(access, type))) {
                // Undercut competing offers to put idle treasury goods back into circulation.
                long discount = access.dir.getCheapestTraderOf(type, false) == null
                        ? 0 : access.dir.getAveragePriceOf(type) / 10;
                trader.setSellCond(sellCondition(discount));
                trader.setSellValues(trader.com.size(), discount);
                trader.sellEnabled(true);
                offerDiscounts.put(type, discount);
                return EconomyAutopilot.Status.SELLING_STOCK;
            }
        }

        if (bidType != null) return null;
        long shipping = access.dir.getAveragePriceOf(ComType.TRANSPORTATION);
        for (ComType type : GOODS) {
            Trader buyer = MarketOrder.traderFor(master, type);
            int lot = lotSize(access, type);
            long limit = affordableUnitPrice(access, lot, shipping);
            if (limit < 1 || hasStandingOrder(master, type) || buyer.com.size() >= lot) continue;
            Trader seller = surplusSeller(access, type, lot, healthy);
            if (seller == null) continue;
            long market = access.dir.getAveragePriceOf(type);
            // BELOW is a strict comparison. A one-cent-higher trigger includes the affordable price.
            long trigger = limit + 1;
            if (!MarketOrder.matches(MarketOrder.BELOW, market, trigger)) {
                buyer.setBuyCond(MarketOrder.BELOW);
                buyer.setBuyValues(lot, trigger);
                bidType = type;
                bidTrigger = trigger;
                buyer.buyEnabled(true);
                return EconomyAutopilot.Status.PLACING_BUY_ORDER;
            }
            if (buyLot(access, seller, buyer, lot, limit, shipping)) return EconomyAutopilot.Status.BUYING_STOCK;
        }
        return null;
    }

    EconomyAutopilot.Status reviewPendingBuy(Access access, boolean healthy) {
        if (bidType == null) return null;
        if (!isOurBid(access.master)) {
            bidType = null;
            return null;
        }
        Trader buyer = MarketOrder.traderFor(access.master, bidType);
        int lot = lotSize(access, bidType);
        long shipping = access.dir.getAveragePriceOf(ComType.TRANSPORTATION);
        long limit = affordableUnitPrice(access, lot, shipping);
        Trader seller = surplusSeller(access, bidType, lot, healthy);
        if (access.taxAccount.freeze || !access.sim.specialPriority.empty() || buyer.com.size() >= lot
                || hasSellOrder(access.master, bidType) || seller == null || limit < bidTrigger - 1) {
            cancelOwnedBuys(access);
            return EconomyAutopilot.Status.CANCELLING_TRADE;
        }
        long market = access.dir.getAveragePriceOf(bidType);
        if (MarketOrder.matches(MarketOrder.BELOW, market, bidTrigger)
                && buyLot(access, seller, buyer, lot, bidTrigger - 1, shipping)) {
            cancelOwnedBuys(access);
            return EconomyAutopilot.Status.BUYING_STOCK;
        }
        return null;
    }

    private static long affordableUnitPrice(Access access, int lot, long shipping) {
        long budget = access.taxAccount.getBalance() / 10;
        return shipping >= 0 && shipping < budget ? (budget - shipping) / lot : 0;
    }

    private static Trader surplusSeller(Access access, ComType type, int lot, boolean healthy) {
        Trader[] sellers = access.dir.getListFromType(type).getList();
        if (hasPendingDelivery(sellers)) return null;
        long buffer = healthy ? Math.max(lot, access.config.MIN_STOCK) : lot;
        for (Trader seller : sellers) {
            if (seller.account != access.taxAccount && seller.isStocked() && !seller.isWaiting()
                    && !seller.execSpecial && !seller.account.freeze && seller.com.size() >= buffer + lot
                    && seller.getPrice() > 0) return seller;
        }
        return null;
    }

    private static boolean buyLot(Access access, Trader seller, Trader buyer, int lot, long limit, long shipping) {
        long price = seller.getPrice();
        return price > 0 && price <= limit && access.bank.enoughReserves(price * lot + shipping)
                && seller.sellTo(buyer, access.taxAccount, lot);
    }

    private static boolean privateStocksLow(Access access, ComType type) {
        int buffer = Math.max(lotSize(access, type), access.config.MIN_STOCK);
        for (Trader seller : access.dir.getListFromType(type).getList()) {
            if (seller.account != access.taxAccount && seller.isStocked() && !seller.isWaiting()
                    && seller.com.size() >= buffer) return false;
        }
        return true;
    }

    private static boolean hasPendingDelivery(Trader[] sellers) {
        // Goods enter com_sold at payment, before a truck exists, and leave only on delivery.
        for (Trader seller : sellers) if (!seller.com_sold.empty()) return true;
        return false;
    }

    /** Leave manual standing orders in place, including a player's replacement for our offer. */
    boolean cancelFilledOffer(Access access) {
        MasterTrader master = access.master;
        for (ComType type : GOODS) {
            Long discount = offerDiscounts.get(type);
            if (discount == null) continue;
            if (!isOurOffer(master, type, discount)) {
                offerDiscounts.remove(type);
            } else if (MarketOrder.traderFor(master, type).com.size() < lotSize(access, type)) {
                MarketOrder.traderFor(master, type).sellEnabled(false);
                offerDiscounts.remove(type);
                return true;
            }
        }
        return false;
    }

    private static String sellCondition(long discount) {
        return discount == 0 ? MarketOrder.MARKET : MarketOrder.DISCOUNT;
    }

    void forgetOffer(ComType type) {
        offerDiscounts.remove(type);
    }

    boolean ownsBuy(ComType type) {
        return bidType == type;
    }

    void forgetBuy(ComType type) {
        if (bidType == type) bidType = null;
    }

    void cancelOwnedBuys(Access access) {
        if (bidType != null && isOurBid(access.master)) MarketOrder.traderFor(access.master, bidType).buyEnabled(false);
        bidType = null;
    }

    private boolean isOurBid(MasterTrader m) {
        switch (bidType) {
            case FOOD: return m.fb_enabled && MarketOrder.BELOW.equals(m.f_buyCond) && m.fb_setValue == bidTrigger;
            case TOOL: return m.tb_enabled && MarketOrder.BELOW.equals(m.t_buyCond) && m.tb_setValue == bidTrigger;
            case COAL: return m.cb_enabled && MarketOrder.BELOW.equals(m.c_buyCond) && m.cb_setValue == bidTrigger;
            case IRON: return m.ib_enabled && MarketOrder.BELOW.equals(m.i_buyCond) && m.ib_setValue == bidTrigger;
            default: return false;
        }
    }

    // Save bits follow the fixed order of GOODS. The listing itself is already in the model.
    int ownedOffers(Access access) {
        int mask = 0;
        for (int i = 0; i < GOODS.length; i++) {
            Long discount = offerDiscounts.get(GOODS[i]);
            if (discount != null && isOurOffer(access.master, GOODS[i], discount)) mask |= 1 << i;
            if (ownsBuy(GOODS[i]) && isOurBid(access.master)) mask |= 1 << (i + GOODS.length);
        }
        return mask;
    }

    void restoreOffers(Access access, int mask) {
        offerDiscounts.clear();
        bidType = null;
        MasterTrader master = access.master;
        long[] discounts = {master.fs_setValue, master.ts_setValue, master.cs_setValue, master.is_setValue};
        long[] triggers = {master.fb_setValue, master.tb_setValue, master.cb_setValue, master.ib_setValue};
        for (int i = 0; i < GOODS.length; i++) {
            if ((mask & (1 << (i + GOODS.length))) != 0 && bidType == null && triggers[i] > 1) {
                bidType = GOODS[i];
                bidTrigger = triggers[i];
                if (!isOurBid(master)) bidType = null;
            }
            if ((mask & (1 << i)) != 0 && isOurOffer(master, GOODS[i], discounts[i])) {
                offerDiscounts.put(GOODS[i], discounts[i]);
            }
        }
    }

    private static boolean isOurOffer(MasterTrader m, ComType type, long discount) {
        String condition = sellCondition(discount);
        switch (type) {
            case FOOD: return m.fs_enabled && condition.equals(m.f_sellCond) && m.fs_setValue == discount;
            case TOOL: return m.ts_enabled && condition.equals(m.t_sellCond) && m.ts_setValue == discount;
            case COAL: return m.cs_enabled && condition.equals(m.c_sellCond) && m.cs_setValue == discount;
            case IRON: return m.is_enabled && condition.equals(m.i_sellCond) && m.is_setValue == discount;
            default: return false;
        }
    }

    static int lotSize(Access access, ComType type) {
        return type == ComType.FOOD ? Math.max(1, access.config.MIN_FOOD_SELL) : 1;
    }

    private static boolean hasSellOrder(MasterTrader master, ComType type) {
        switch (type) {
            case FOOD: return master.fs_enabled;
            case TOOL: return master.ts_enabled;
            case COAL: return master.cs_enabled;
            case IRON: return master.is_enabled;
            default: throw new IllegalArgumentException("No treasury trader for " + type);
        }
    }

    static boolean hasStandingOrder(MasterTrader master, ComType type) {
        switch (type) {
            case FOOD: return master.fb_enabled || master.fs_enabled;
            case TOOL: return master.tb_enabled || master.ts_enabled;
            case COAL: return master.cb_enabled || master.cs_enabled;
            case IRON: return master.ib_enabled || master.is_enabled;
            default: throw new IllegalArgumentException("No treasury trader for " + type);
        }
    }
}
