/* SPDX-License-Identifier: GPL-3.0-or-later */
package henize.proto.lib.sim;

import henize.proto.lib.bank.Account;
import henize.proto.lib.bank.Bank;
import henize.proto.lib.directory.DirectoryList;
import henize.proto.lib.traders.ComType;
import henize.proto.lib.traders.Trader;

/** Reviews the running economy and makes at most one intervention per review. */
public final class EconomyAutopilot {
    public enum Status { MONITORING, EASING_CREDIT, RELIEVING_DEBT, FUNDING_TRADE, REBUILDING_RESERVES, LIMITED }

    static final int REVIEW_TICKS = 120;
    private static final int RATE_STEP_PERCENT = 10;
    private static final int RESERVE_FLOOR_PERCENT = -20;
    private static final int INTEREST_FLOOR_PERCENT = -10;
    // The game's Classic guide recommends $5,000 to establish a functioning credit market.
    private static final long MONEY_SUPPLY_LIMIT_CENTS = 500_000L;
    private static final long MIN_RESERVE_ISSUE_CENTS = 10_000L;

    private volatile boolean enabled;
    private volatile Status status = Status.MONITORING;
    private int ticks;
    private long lastTrades;
    private int lastFailures;
    private boolean hadCreditPressure, hadFrozenDebt, hadStall;

    public boolean isEnabled() {
        return enabled;
    }

    public Status getStatus() {
        return status;
    }

    public synchronized void setEnabled(boolean enabled) {
        if (this.enabled == enabled) return;
        this.enabled = enabled;
        ticks = 0;
        hadCreditPressure = hadFrozenDebt = hadStall = false;
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
        if (relieveDebt && bank.interestRate > INTEREST_FLOOR_PERCENT / 100.0) {
            bank.interestRate = lowerRate(bank.interestRate, INTEREST_FLOOR_PERCENT);
            status = Status.RELIEVING_DEBT;
        } else if (easeCredit && bank.reserveRate > RESERVE_FLOOR_PERCENT / 100.0) {
            // Positive reserve requirements restrict lending only. A blocked cash payment
            // needs overdraft room, so stepping down from a positive rate alone cannot help.
            double rate = failedPayments || bank.fail ? Math.min(0, bank.reserveRate) : bank.reserveRate;
            bank.reserveRate = lowerRate(rate, RESERVE_FLOOR_PERCENT);
            status = Status.EASING_CREDIT;
        } else if (restartTrade && fundFoodTrade(access)) {
            status = Status.FUNDING_TRADE;
        } else if (restartTrade && easeCredit && rebuildReserves(access)) {
            status = Status.REBUILDING_RESERVES;
        } else if (easeCredit || relieveDebt || restartTrade) {
            status = Status.LIMITED;
        } else if (status == Status.LIMITED) {
            status = Status.MONITORING;
        }

        hadCreditPressure = creditPressure;
        hadFrozenDebt = frozenDebt;
        hadStall = stalled;
        lastTrades = access.bankStats.transactionCount;
        lastFailures = access.bankStats.insolvencyHits;
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
