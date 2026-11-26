/* SPDX-License-Identifier: GPL-3.0-or-later */
package henize.proto.lib.traders;

import henize.proto.lib.directory.DirectoryList;

/** The English condition names are also the values stored in economy saves. */
public final class MarketOrder {
    public static final String MARKET = "Now at market value";
    public static final String FIXED = "Now at [set price]";
    public static final String MARKUP = "Now at [set price] above market value";
    public static final String DISCOUNT = "Now at [set price] below market value";
    public static final String BELOW = "Price is below [set price]";
    public static final String ABOVE = "Price is above [set price]";

    private MarketOrder() {}

    public static Trader traderFor(MasterTrader master, ComType type) {
        switch (type) {
            case FOOD: return master.foodTrader;
            case TOOL: return master.toolTrader;
            case COAL: return master.coalTrader;
            case IRON: return master.ironTrader;
            default: throw new IllegalArgumentException("No treasury trader for " + type);
        }
    }

    public static boolean matches(String condition, long market, long setPrice) {
        if (condition == null) return false;
        switch (condition) {
            case MARKET: return true;
            case BELOW:
            case "Price is below [set value]": return market < setPrice;
            case ABOVE:
            case "Price is above [set value]": return market > setPrice;
            default: return false;
        }
    }

    static void updateOffer(Trader seller, ComType type, boolean enabled, String condition, long setPrice) {
        seller.isStocked = false;
        if (condition == null) return;
        // A trader adds sector and universal taxes in getPrice(). Relative offers
        // use producers' base quotes so those taxes are applied exactly once.
        long market = marketBasePrice(seller, type);
        long price;
        switch (condition) {
            case FIXED:
                price = setPrice;
                break;
            case MARKUP:
            case "Now at [set value] above market value":
                price = market + Math.min(Math.max(0, setPrice), Long.MAX_VALUE - market);
                break;
            case DISCOUNT:
            case "Now at [set value] below market value":
                price = market - Math.max(0, setPrice);
                break;
            default:
                if (!MARKET.equals(condition)
                        && !matches(condition, seller.access.dir.getAveragePriceOf(type), setPrice)) return;
                price = market;
        }
        seller.setPrice(price);
        seller.isStocked = enabled && !seller.com.empty();
    }

    private static long marketBasePrice(Trader seller, ComType type) {
        if (!seller.access.config.collectTax) return seller.access.dir.getAveragePriceOf(type);
        long sum = 0;
        int count = 0;
        DirectoryList producers = seller.access.dir.getListFromType(type);
        for (int i = 0; i < producers.getCount(); i++) {
            Trader producer = producers.getAtIndex(i);
            if (producer.account == seller.account) continue;
            sum += producer.getNonBidPrice();
            count++;
        }
        return count == 0 ? seller.access.config.MIN_PRICE : sum / count;
    }
}
