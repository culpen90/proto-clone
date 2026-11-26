/* SPDX-License-Identifier: GPL-3.0-or-later */
package henize.proto.lib.sim;

import henize.proto.lib.bank.Account;
import henize.proto.lib.bank.Bank;
import henize.proto.lib.directory.DirectoryList;
import henize.proto.lib.traders.ComType;
import henize.proto.lib.traders.Trader;

/** Reviews the running economy and makes at most one intervention per review. */
public final class EconomyAutopilot {
    public enum Status {
        MONITORING, EASING_CREDIT, RELIEVING_DEBT, RESTORING_RESERVE_RATE, RESTORING_INTEREST_RATE,
        FUNDING_TRADE, REBUILDING_RESERVES, RETIRING_CURRENCY, STIMULATING_INDUSTRY,
        BUYING_STOCK, PLACING_BUY_ORDER, SELLING_STOCK, CANCELLING_TRADE, PLACING_SPECIAL_ORDER, BREAKING_ORDERS,
        STARTING_TAXES, SUSPENDING_TAXES, ADJUSTING_TAX_RATES, STABILIZING_PRICES, RESTORING_MARKET_PRICES,
        LIMITED
    }

    static final int REVIEW_TICKS = 120;
    private static final int RATE_STEP_PERCENT = 10;
    private static final int RESERVE_FLOOR_PERCENT = -20;
    private static final int INTEREST_FLOOR_PERCENT = -10;
    // The game's Classic guide recommends $5,000 to establish a functioning credit market.
    private static final long MONEY_SUPPLY_LIMIT_CENTS = 500_000L;
    private static final long MIN_RESERVE_ISSUE_CENTS = 10_000L;

    private final Access economy;
    private AutopilotTradePolicy tradePolicy = new AutopilotTradePolicy();
    private AutopilotSpecialOrders specialOrders = new AutopilotSpecialOrders();
    private final AutopilotPricePolicy prices = new AutopilotPricePolicy();
    private volatile boolean enabled;
    private volatile Status status = Status.MONITORING;
    private int ticks;
    private long lastTrades;
    private int lastFailures;
    private boolean hadCreditPressure, hadFrozenDebt, hadStall, hadHealthyTrade, hadStalledOrder;

    EconomyAutopilot(Access economy) {
        this.economy = economy;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Status getStatus() {
        return status;
    }

    public synchronized int ownedOffers() {
        return enabled ? tradePolicy.ownedOffers(economy) : 0;
    }

    public synchronized void restoreOffers(int mask) {
        tradePolicy.restoreOffers(economy, mask);
    }

    public synchronized boolean ownsBuy(ComType type) {
        return tradePolicy.ownsBuy(type);
    }

    public synchronized void playerChangedBuy(ComType type) {
        tradePolicy.forgetBuy(type);
    }

    public synchronized void playerChangedOffer(ComType type) {
        tradePolicy.forgetOffer(type);
    }

    public synchronized void setEnabled(boolean enabled) {
        if (this.enabled == enabled) return;
        if (!enabled) tradePolicy.cancelOwnedBuys(economy);
        this.enabled = enabled;
        ticks = 0;
        hadCreditPressure = hadFrozenDebt = hadStall = hadHealthyTrade = hadStalledOrder = false;
        tradePolicy = new AutopilotTradePolicy();
        prices.reset();
        specialOrders = new AutopilotSpecialOrders();
        status = Status.MONITORING;
    }

    public synchronized void onTick(Access access) {
        if (!enabled) return;
        if (ticks == 0) {
            lastTrades = access.bankStats.transactionCount;
            lastFailures = access.bankStats.insolvencyHits;
        }
        if (++ticks <= REVIEW_TICKS) return;
        ticks = 1;

        Bank bank = access.bank;
        boolean idle = lastTrades == access.bankStats.transactionCount;
        // Deliveries take several hundred ticks on larger maps while a healthy market waits.
        boolean stalled = idle && access.sim.map.traveler_list.isEmpty();
        boolean failedPayments = access.bankStats.insolvencyHits != lastFailures;
        boolean creditPressure = failedPayments || (stalled && (bank.creditMeltdown || bank.fail));
        boolean frozenDebt = hasFrozenBorrower(access);

        boolean easeCredit = creditPressure && hadCreditPressure;
        boolean relieveDebt = frozenDebt && hadFrozenDebt;
        boolean restartTrade = stalled && hadStall;
        boolean healthyTrade = access.bankStats.transactionCount > lastTrades && !creditPressure && !frozenDebt;
        boolean recovered = healthyTrade && hadHealthyTrade;
        prices.observe(access, healthyTrade);
        boolean stalledOrder = stalled && !access.sim.specialPriority.empty();
        Status decision;
        if (stalledOrder && hadStalledOrder && specialOrders.breakStalled(access)) {
            decision = Status.BREAKING_ORDERS;
        } else {
            decision = intervene(access, failedPayments, easeCredit, relieveDebt, restartTrade, recovered);
        }
        hadStalledOrder = stalledOrder && !access.sim.specialPriority.empty();
        if (decision != null) status = decision;
        else if (status == Status.LIMITED) status = Status.MONITORING;

        hadCreditPressure = creditPressure;
        hadFrozenDebt = frozenDebt;
        hadStall = stalled;
        hadHealthyTrade = healthyTrade;
        lastTrades = access.bankStats.transactionCount;
        lastFailures = access.bankStats.insolvencyHits;
    }

    private Status intervene(Access access, boolean failedPayments, boolean easeCredit, boolean relieveDebt,
                             boolean restartTrade, boolean recovered) {
        Bank bank = access.bank;
        boolean trouble = easeCredit || relieveDebt || restartTrade;

        boolean wasTaxing = access.config.collectTax;
        if (AutopilotTaxPolicy.adjust(access, recovered, trouble)) {
            if (wasTaxing == access.config.collectTax) return Status.ADJUSTING_TAX_RATES;
            return wasTaxing ? Status.SUSPENDING_TAXES : Status.STARTING_TAXES;
        }
        if (relieveDebt && bank.interestRate > INTEREST_FLOOR_PERCENT / 100.0) {
            bank.interestRate = lowerRate(bank.interestRate, INTEREST_FLOOR_PERCENT);
            return Status.RELIEVING_DEBT;
        }
        if (easeCredit && bank.reserveRate > RESERVE_FLOOR_PERCENT / 100.0) {
            // Positive reserve requirements restrict lending only. A blocked cash payment
            // needs overdraft room, so stepping down from a positive rate alone cannot help.
            double rate = failedPayments || bank.fail ? Math.min(0, bank.reserveRate) : bank.reserveRate;
            bank.reserveRate = lowerRate(rate, RESERVE_FLOOR_PERCENT);
            return Status.EASING_CREDIT;
        }
        if (restartTrade && easeCredit && rebuildReserves(access)) return Status.REBUILDING_RESERVES;
        if (tradePolicy.cancelFilledOffer(access)) return Status.CANCELLING_TRADE;
        Status pending = tradePolicy.reviewPendingBuy(access, !restartTrade);
        if (pending != null) return pending;
        if (restartTrade) {
            Status trade = tradePolicy.review(access, false);
            if (trade != null) return trade;
            // Give priority production and sector-wide stimulus a turn between repeated grants.
            if (status != Status.FUNDING_TRADE && fundFoodTrade(access)) return Status.FUNDING_TRADE;
            if (!easeCredit && !relieveDebt && specialOrders.place(access)) return Status.PLACING_SPECIAL_ORDER;
            if (access.bank.enoughReserves(1) && AutopilotTaxPolicy.stimulateIndustry(access) != null)
                return Status.STIMULATING_INDUSTRY;
            if (status == Status.FUNDING_TRADE && fundFoodTrade(access)) return Status.FUNDING_TRADE;
        }
        if (recovered) {
            Status priceChange = prices.adjust(access.config);
            if (priceChange != null) return priceChange;
            if (retireSurplus(access)) return Status.RETIRING_CURRENCY;
            // Restore ordinary borrowing terms gradually once the bank can support them.
            if (bank.reserve >= access.bankStats.totalCurrency * 0.20) {
                if (bank.reserveRate < 0.20) {
                    bank.reserveRate = Math.min(20, Math.round(bank.reserveRate * 100) + RATE_STEP_PERCENT) / 100.0;
                    return Status.RESTORING_RESERVE_RATE;
                }
                if (bank.interestRate < 0.15) {
                    bank.interestRate = Math.min(15, Math.round(bank.interestRate * 100) + RATE_STEP_PERCENT) / 100.0;
                    return Status.RESTORING_INTEREST_RATE;
                }
            }
            return tradePolicy.review(access, true);
        }
        return trouble ? Status.LIMITED : null;
    }

    private static boolean retireSurplus(Access access) {
        long supply = access.bankStats.totalCurrency;
        if (supply <= MONEY_SUPPLY_LIMIT_CENTS || access.bank.reserve < 0) return false;
        long treasury = access.taxAccount.getBalance();
        // Keep a working treasury and retire at most 5% of its balance in a review.
        long amount = Math.min(supply - MONEY_SUPPLY_LIMIT_CENTS,
                Math.min(treasury / 20, treasury - supply / 5));
        if (amount <= 0 || amount > access.bank.reserve) return false;
        access.taxAccount.destroy(amount);
        return true;
    }

    private static double lowerRate(double rate, int floorPercent) {
        return Math.max(floorPercent, Math.round(rate * 100) - RATE_STEP_PERCENT) / 100.0;
    }

    private static boolean rebuildReserves(Access access) {
        long supply = access.bankStats.totalCurrency;
        if (supply < 0 || supply >= MONEY_SUPPLY_LIMIT_CENTS) return false;
        long amount = Math.min(MONEY_SUPPLY_LIMIT_CENTS - supply, Math.max(MIN_RESERVE_ISSUE_CENTS, supply / 10));
        if (access.bank.reserve >= amount || access.bank.account.getBalance() >= amount) return false;
        // As in Create Currency, opening a funded account records the new supply and reserves.
        Account issue = new Account(access, amount);
        access.taxAccount.__transfer(issue, amount);
        access.bank.removeAccount(issue);
        return true;
    }

    private static boolean hasFrozenBorrower(Access access) {
        // The bank's account list also contains closed accounts of traders who have died.
        for (Trader trader : access.dir.getAll()) {
            if (trader.account.freeze && trader.account.debt > 0 && trader.account != access.taxAccount) {
                return true;
            }
        }
        return false;
    }

    /** Fund one affordable purchase in the labor/food production cycle from existing tax receipts. */
    private static boolean fundFoodTrade(Access access) {
        long budget = access.taxAccount.getBalance() / 10;
        if (budget <= 0) return false;
        Trader worker = access.dir.getCheapestTraderOf(ComType.LABOR, false);
        DirectoryList farms = access.dir.getListFromType(ComType.FOOD);
        int lot = Math.max(1, access.config.MIN_FOOD_SELL);
        for (Trader farm : farms.getList()) {
            if (farm.account == access.taxAccount) continue;
            if (worker != null && farm.com.size() < lot
                    && fundPurchase(access, farm, worker.getPrice(), budget)) return true;
            if (!farm.isStocked() || farm.isWaiting() || farm.com.size() < lot) continue;
            long foodPrice = farm.getPrice();
            long shipping = access.dir.getAveragePriceOf(ComType.TRANSPORTATION);
            if (foodPrice <= 0 || shipping < 0 || foodPrice > (Long.MAX_VALUE - shipping) / lot) continue;
            long cost = foodPrice * lot + shipping;
            for (Trader buyer : access.dir.getListFromType(ComType.LABOR).getList()) {
                if (fundPurchase(access, buyer, cost, budget)) return true;
            }
        }
        return false;
    }

    private static boolean fundPurchase(Access access, Trader buyer, long cost, long budget) {
        Account account = buyer.account;
        long balance = account.getBalance();
        if (buyer.isWaiting() || buyer.execSpecial || account.freeze || balance < 0 || balance >= cost
                || !access.bank.enoughReserves(cost)) return false;
        long shortfall = cost - balance;
        if (shortfall > budget) return false;
        // A treasury grant is the same untaxed transfer used by the manual stimulus control.
        // It must not register a sale and make a still-stalled market appear to be trading.
        account.__transfer(access.taxAccount, shortfall);
        return true;
    }
}
