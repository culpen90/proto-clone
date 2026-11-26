/* SPDX-License-Identifier: GPL-3.0-or-later */
package henize.proto.lib.sim;

import henize.proto.lib.bank.Account.TaxCode;
import henize.proto.lib.bank.TaxRates;
import henize.proto.lib.traders.ComType;
import henize.proto.lib.traders.Trader;

/** Builds a treasury from active trade and returns cash to cash-poor industries. */
final class AutopilotTaxPolicy {
    private static final double DEFAULT_RATE_PERCENT = 5;
    private static final double SECTOR_RATE_PERCENT = 2;
    private static final double MAX_START_RATE_PERCENT = 10;
    private static final double RATE_CUT_PERCENT = 10;

    private AutopilotTaxPolicy() {}

    static boolean adjust(Access access, boolean trading, boolean underPressure) {
        Config config = access.config;
        long treasury = access.taxAccount.getBalance();
        // Next() updates adj_totalCurrency from non-master traders' balances before each tick.
        double circulatingCash = (double) treasury + access.bankStats.adj_totalCurrency;
        boolean validRates = TaxRates.isValid(config);
        boolean treasuryFull = circulatingCash > 0 && treasury >= circulatingCash * 0.10;
        if (config.collectTax && (underPressure || treasuryFull || !validRates)) {
            config.collectTax = false;
            return true;
        }
        boolean needsRevenue = trading && circulatingCash > 0 && treasury < circulatingCash * 0.05;
        if (!validRates || underPressure || !needsRevenue) return false;

        // Ease a high schedule over successive reviews. If collection is off, keep it off
        // until every sector's combined rate is affordable.
        double universal = config.universalTaxRate;
        if (universal > MAX_START_RATE_PERCENT) {
            config.universalTaxRate = Math.max(DEFAULT_RATE_PERCENT, universal - RATE_CUT_PERCENT);
            return true;
        }
        boolean hasRate = universal > 0;
        for (TaxCode code : TaxCode.values()) {
            if (code == TaxCode.NULL || code == TaxCode.MASTER) continue;
            double sector = TaxRates.get(config, code);
            if (universal + sector > MAX_START_RATE_PERCENT) {
                TaxRates.set(config, code, Math.max(MAX_START_RATE_PERCENT - universal, sector - RATE_CUT_PERCENT));
                return true;
            }
            hasRate |= sector > 0;
        }
        if (!config.collectTax || !hasRate) {
            if (!hasRate) config.universalTaxRate = DEFAULT_RATE_PERCENT;
            config.collectTax = true;
            return true;
        }
        return rebalanceSectorRates(access);
    }

    private static boolean rebalanceSectorRates(Access access) {
        Config config = access.config;
        double surcharge = Math.min(SECTOR_RATE_PERCENT, MAX_START_RATE_PERCENT - config.universalTaxRate);
        Industry richest = null;
        for (ComType type : ComType.values()) {
            Industry industry = industry(access, type);
            if (industry == null) continue;
            double rate = TaxRates.get(config, industry.taxCode);
            if (industry.coverage() < 1 && rate > 0) {
                TaxRates.set(config, industry.taxCode, 0);
                return true;
            }
            if (industry.coverage() >= 2 && rate < surcharge
                    && (richest == null || industry.coverage() > richest.coverage())) {
                richest = industry;
            }
        }
        if (richest == null) return false;
        TaxRates.set(config, richest.taxCode, surcharge);
        return true;
    }

    /** Funding is limited to one input purchase per member and 10% of treasury per review. */
    static ComType stimulateIndustry(Access access) {
        long budget = access.taxAccount.getBalance() / 10;
        if (budget <= 0) return null;
        Industry poorest = null;
        for (ComType type : ComType.values()) {
            Industry industry = industry(access, type);
            if (industry != null && industry.coverage() < 1
                    && (poorest == null || industry.coverage() < poorest.coverage())) {
                poorest = industry;
            }
        }
        if (poorest == null) return null;
        long amount = Math.min(budget, (long) (poorest.target - poorest.cash));
        if (amount <= 0) return null;
        access.taxAccount.distribute(poorest.type, amount, null);
        return poorest.type;
    }

    /** Cash needed for each worker to buy a food lot, or for each other trader to hire labor. */
    private static Industry industry(Access access, ComType type) {
        ComType input = type == ComType.LABOR ? ComType.FOOD : ComType.LABOR;
        Trader supplier = access.dir.getCheapestTraderOf(input, false);
        if (supplier == null || supplier.getPrice() <= 0) return null;
        double purchase = supplier.getPrice();
        if (type == ComType.LABOR) {
            Trader shipping = access.dir.getCheapestTraderOf(ComType.TRANSPORTATION);
            if (shipping == null) return null;
            purchase = purchase * Math.max(1, access.config.MIN_FOOD_SELL) + shipping.getPrice();
        }

        Industry industry = new Industry(type);
        for (Trader trader : access.dir.getListFromType(type).getList()) {
            if (trader.isDead || trader.account == access.taxAccount) continue;
            industry.taxCode = trader.account.taxCode;
            industry.cash += trader.account.getBalance();
            industry.target += purchase;
        }
        return industry.target > 0 ? industry : null;
    }

    private static final class Industry {
        final ComType type;
        TaxCode taxCode;
        double cash, target;

        Industry(ComType type) {
            this.type = type;
        }

        double coverage() {
            return cash / target;
        }
    }
}
