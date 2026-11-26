/* SPDX-License-Identifier: GPL-3.0-or-later */
package henize.proto.lib.sim;

import org.junit.After;
import org.junit.Test;

import henize.proto.App;
import henize.proto.lib.bank.Account;
import henize.proto.lib.commodities.Coal;
import henize.proto.lib.commodities.Food;
import henize.proto.lib.map.Traveler;
import henize.proto.lib.traders.ComType;
import henize.proto.lib.traders.Trader;
import henize.proto.pathfinder.Coordinate;

import static henize.proto.lib.sim.EconomyAutopilot.REVIEW_TICKS;
import static henize.proto.lib.sim.EconomyAutopilot.Status.*;
import static org.junit.Assert.*;

public class EconomyAutopilotTest {
    private final Simulator previousSim = App.sim;
    private final Simulator sim = createSim();
    private final Access access = sim.access;
    private final EconomyAutopilot pilot = sim.autopilot();

    private static Simulator createSim() {
        Config config = new Config();
        App.sim = new Simulator(12, null, config) {
            @Override public void initNonSerializable() {} // Only skip the Android text renderers.
        };
        return App.sim;
    }

    @After public void restoreSim() {
        sim.interrupt();
        App.sim = previousSim;
    }

    private void observe(int ticks) {
        for (int i = 0; i < ticks; i++) pilot.onTick(access);
    }

    private void runMarket(int ticks) {
        for (int i = 0; i < ticks; i++) {
            sim.Next();
            sim.map.Tick();
        }
    }

    private void putBankCashInTreasury() {
        access.taxAccount.__transfer(access.bank.account, access.bank.account.getBalance());
    }

    private void reviewWithTrade() {
        access.bankStats.transactionCount++;
        observe(REVIEW_TICKS);
    }

    @Test public void oneCentMoveMattersForCheapGoodsAndFallsWithinTheBandForExpensiveGoods() {
        AutopilotPricePolicy prices = new AutopilotPricePolicy();
        for (Trader mine : access.dir.getListFromType(ComType.IRON).getList()) mine.priceOffset = 99;
        prices.observe(access);
        for (Trader farm : access.dir.getListFromType(ComType.FOOD).getList()) farm.priceOffset++;
        for (Trader mine : access.dir.getListFromType(ComType.IRON).getList()) mine.priceOffset++;
        prices.observe(access);
        assertEquals(1.0, prices.pressure(ComType.FOOD), 0);
        assertEquals(0.0, prices.pressure(ComType.IRON), 0);
    }

    @Test public void autopilotSoftensCashDrivenWageChangesWithoutResettingExistingQuotes() {
        Trader worker = access.dir.getListFromType(ComType.LABOR).getAtIndex(0);
        access.bankStats.adj_totalCurrency = (long) access.dir.getCount() * 1000;
        access.config.supplyAndDemandEnabled = false;
        worker.act();
        long manualTarget = worker.targetOffset;
        assertEquals(100, manualTarget);
        worker.priceOffset = 60;
        pilot.setEnabled(true);
        observe(1);
        worker.act();
        assertEquals(60, worker.targetOffset);
        assertEquals(60, worker.priceOffset);
        access.bankStats.adj_totalCurrency *= 2;
        worker.act();
        assertEquals(80, worker.targetOffset); // One fifth of the 100-cent increase.
        assertEquals(61, worker.priceOffset);
        pilot.setEnabled(false);
        worker.act();
        assertEquals(2 * manualTarget, worker.targetOffset);
        assertEquals(62, worker.priceOffset);
    }

    @Test public void recoveredMarketRestoresBankTermsOneRateAtATime() {
        putBankCashInTreasury();
        access.bank.reserveRate = 0.10;
        access.bank.interestRate = 0.05;
        pilot.setEnabled(true);
        observe(1);
        reviewWithTrade();
        assertEquals(STABILIZING_PRICES, pilot.getStatus());
        reviewWithTrade();
        assertEquals(RESTORING_RESERVE_RATE, pilot.getStatus());
        assertEquals(0.20, access.bank.reserveRate, 0);
        assertEquals(0.05, access.bank.interestRate, 0);
        reviewWithTrade();
        assertEquals(RESTORING_INTEREST_RATE, pilot.getStatus());
        assertEquals(0.15, access.bank.interestRate, 0);
    }

    @Test public void selectsStablePricingOnFirstTickAndKeepsItThroughCalmTrading() {
        putBankCashInTreasury();
        observe(2 * REVIEW_TICKS);
        assertTrue(access.config.supplyAndDemandEnabled);
        pilot.setEnabled(true);
        observe(1);
        assertEquals(STABILIZING_PRICES, pilot.getStatus());
        assertFalse(access.config.supplyAndDemandEnabled);
        for (int i = 0; i < 8; i++) reviewWithTrade();
        assertFalse(access.config.supplyAndDemandEnabled);
        access.config.supplyAndDemandEnabled = true;
        reviewWithTrade();
        assertFalse(access.config.supplyAndDemandEnabled);
        pilot.setEnabled(false);
        access.config.supplyAndDemandEnabled = true;
        observe(2 * REVIEW_TICKS);
        assertTrue(access.config.supplyAndDemandEnabled);
    }

    @Test public void priceInterventionsStartOnOneHealthyReviewAndPrecedeRateRestoration() {
        putBankCashInTreasury();
        access.bank.reserveRate = 0.10;
        access.bankStats.transactionCount = 1; // An established market when autopilot takes over.
        for (int i = 0; i < access.config.MIN_FOOD_SELL; i++) {
            access.master.foodTrader.com.push(new Food(access.comStats));
        }
        access.master.coalTrader.com.push(new Coal(access.comStats));
        pilot.setEnabled(true);
        observe(1);
        for (Trader farm : access.dir.getListFromType(ComType.FOOD).getList()) {
            if (farm.account != access.taxAccount) farm.priceOffset = 99;
        }
        reviewWithTrade();
        assertEquals(SELLING_STOCK, pilot.getStatus());
        assertTrue(access.master.fs_enabled);
        assertFalse(access.master.cs_enabled);

        for (Trader mine : access.dir.getListFromType(ComType.COAL).getList()) {
            if (mine.account != access.taxAccount) mine.priceOffset = 99;
        }
        reviewWithTrade(); // Two healthy windows also qualify the bank for a rate increase.
        assertEquals(SELLING_STOCK, pilot.getStatus());
        assertTrue(access.master.cs_enabled);
        assertEquals(0.10, access.bank.reserveRate, 0);
        reviewWithTrade();
        assertEquals(RESTORING_RESERVE_RATE, pilot.getStatus());
        assertEquals(0.20, access.bank.reserveRate, 0);
    }

    @Test public void activeEconomyRetiresOnlySurplusTreasuryCurrency() {
        Account surplus = new Account(access, 600_000);
        access.taxAccount.__transfer(surplus, 600_000);
        long supply = access.bankStats.totalCurrency;
        pilot.setEnabled(true);
        observe(1);
        reviewWithTrade();
        assertEquals(supply, access.bankStats.totalCurrency);
        reviewWithTrade();
        assertEquals(RETIRING_CURRENCY, pilot.getStatus());
        assertEquals(supply - 30_000, access.bankStats.totalCurrency);
        assertEquals(570_000, access.taxAccount.getBalance());
        for (int i = 0; i < 10; i++) reviewWithTrade();
        assertEquals(500_000, access.bankStats.totalCurrency);
        assertEquals(supply - 500_000, access.bankStats.lostCurrency);
        assertEquals(500_000, access.bank.reserve);
        Account borrower = access.dir.getListFromType(ComType.LABOR).getAtIndex(0).account;
        long bankCash = access.bank.account.getBalance();
        assertTrue(access.bank.issueLoanTo(borrower, 100));
        access.bank.payOnLoan(borrower, 100);
        assertEquals(bankCash, access.bank.account.getBalance());
        assertEquals(500_000, access.bank.reserve);
    }

    @Test public void stalledEconomyCanOrderMissingStockAndReleaseStuckProduction() {
        putBankCashInTreasury();
        pilot.setEnabled(true);
        observe(2 * REVIEW_TICKS + 1);
        assertEquals(FUNDING_TRADE, pilot.getStatus());
        observe(REVIEW_TICKS);
        assertEquals(PLACING_SPECIAL_ORDER, pilot.getStatus());
        assertFalse(sim.specialPriority.empty());
        observe(REVIEW_TICKS);
        assertFalse(sim.specialPriority.empty());
        observe(REVIEW_TICKS);
        assertEquals(BREAKING_ORDERS, pilot.getStatus());
        assertTrue(sim.specialPriority.empty());
    }

    @Test public void sustainedCreditLockRestartsRealMarketTrades() {
        // New traders have zero cash and rely on loans to bootstrap production.
        putBankCashInTreasury();
        access.bank.reserveRate = 1.0;
        runMarket(2 * REVIEW_TICKS + 1);
        assertTrue(access.bank.creditMeltdown);
        assertEquals(0, access.bankStats.transactionCount);
        assertEquals(1.0, access.bank.reserveRate, 0);

        pilot.setEnabled(true);
        runMarket(REVIEW_TICKS + 1);
        assertEquals(STABILIZING_PRICES, pilot.getStatus());
        assertEquals(0, access.bankStats.transactionCount);
        runMarket(REVIEW_TICKS);
        assertEquals(EASING_CREDIT, pilot.getStatus());
        assertEquals(0.90, access.bank.reserveRate, 0);
        assertTrue(access.bankStats.transactionCount > 0);
    }

    @Test public void debtReliefUnfreezesAnExistingBorrowerWithoutCurrencyCreation() {
        Account borrower = access.dir.getListFromType(ComType.LABOR).getAtIndex(0).account;
        assertTrue(access.bank.issueLoanTo(borrower, 1000));
        access.taxAccount.__transfer(borrower, 1000);
        putBankCashInTreasury();
        access.bank.reserveRate = 1.0;
        long currency = access.bankStats.totalCurrency;
        pilot.setEnabled(true);
        for (int tick = 0; tick < 4 * REVIEW_TICKS + 12; tick++) {
            access.bank.act();
            pilot.onTick(access);
        }
        assertEquals(-0.10, access.bank.interestRate, 0);
        assertFalse(borrower.freeze);
        assertTrue(borrower.debt < 1000);
        assertEquals(currency, access.bankStats.totalCurrency);
    }

    @Test public void exhaustedCreditMarketCanBorrowAgainAfterABoundedReserveIssue() {
        long supply = access.bankStats.totalCurrency;
        // An outstanding loan has used the bank's cash, reserves and maximum policy overdraft.
        access.bank.reserveRate = -0.20;
        Account borrower = access.dir.getListFromType(ComType.LABOR).getAtIndex(0).account;
        assertTrue(access.bank.issueLoanTo(borrower, supply * 22 / 10));
        assertFalse(access.bank.issueLoanTo(borrower, 1));
        long reserves = access.bank.reserve;
        int accounts = access.bank.accounts.size();
        pilot.setEnabled(true);
        observe(2 * REVIEW_TICKS + 1);
        assertEquals(REBUILDING_RESERVES, pilot.getStatus());
        assertEquals(supply + 10_000, access.bankStats.totalCurrency);
        assertEquals(10_000, access.taxAccount.getBalance());
        assertEquals(reserves + 10_000, access.bank.reserve);
        assertEquals(accounts, access.bank.accounts.size());
        assertTrue(access.bank.issueLoanTo(borrower, 1));
    }

    @Test public void reserveIssuesStopAtTheMoneySupplyLimit() {
        new Account(access, 499_950 - access.bankStats.totalCurrency);
        access.bank.reserveRate = -0.20;
        Account borrower = access.dir.getListFromType(ComType.LABOR).getAtIndex(0).account;
        long available = access.bank.account.getBalance() + access.bank.reserve + 499_950 / 5;
        assertTrue(access.bank.issueLoanTo(borrower, available));
        assertFalse(access.bank.issueLoanTo(borrower, 1));
        pilot.setEnabled(true);
        observe(2 * REVIEW_TICKS + 1);
        assertEquals(REBUILDING_RESERVES, pilot.getStatus());
        assertEquals(500_000L, access.bankStats.totalCurrency);
        assertTrue(access.bank.issueLoanTo(borrower, 60));
        assertFalse(access.bank.issueLoanTo(borrower, 1));
        observe(REVIEW_TICKS);
        assertEquals(LIMITED, pilot.getStatus());
        assertEquals(500_000L, access.bankStats.totalCurrency);
    }

    @Test public void stalledFoodProducerGetsOnlyCostOfAvailableLaborFromTreasury() {
        putBankCashInTreasury();
        long treasury = access.taxAccount.getBalance();
        long currency = access.bankStats.totalCurrency;
        Trader worker = access.dir.getCheapestTraderOf(ComType.LABOR, false);
        pilot.setEnabled(true);
        observe(2 * REVIEW_TICKS + 1);

        assertEquals(FUNDING_TRADE, pilot.getStatus());
        assertEquals(treasury - worker.getPrice(), access.taxAccount.getBalance());
        assertEquals(worker.getPrice(), sim.bankSum(ComType.FOOD));
        assertEquals(currency, access.bankStats.totalCurrency);
        assertEquals(0, access.bankStats.transactionCount);
        assertEquals(0.20, access.bank.reserveRate, 0);
    }

    @Test public void shipmentsAndARecoveredMarketDoNotTriggerAssistance() {
        putBankCashInTreasury();
        long treasury = access.taxAccount.getBalance();
        access.bank.creditMeltdown = true; // A flag can outlive the loan attempt that set it.
        pilot.setEnabled(true);
        sim.map.traveler_list.add(new Traveler(access, sim.map, new Coordinate(1, 1), new Coordinate(2, 2)));
        observe(3 * REVIEW_TICKS + 1);
        assertEquals(STABILIZING_PRICES, pilot.getStatus());
        sim.map.traveler_list.clear();
        observe(REVIEW_TICKS);
        access.bankStats.transactionCount++;
        observe(REVIEW_TICKS);
        assertEquals(STABILIZING_PRICES, pilot.getStatus());
        assertEquals(treasury, access.taxAccount.getBalance());
        assertEquals(0.20, access.bank.reserveRate, 0);
    }

    @Test public void respectsPolicyFloorAndSwitchingOffClearsObservationHistory() {
        pilot.setEnabled(true);
        access.bank.fail = true;
        observe(2 * REVIEW_TICKS + 1);
        assertEquals(-0.10, access.bank.reserveRate, 0);
        pilot.setEnabled(false);
        observe(4 * REVIEW_TICKS);
        assertEquals(-0.10, access.bank.reserveRate, 0);
        pilot.setEnabled(true);
        observe(REVIEW_TICKS + 1);
        assertEquals(MONITORING, pilot.getStatus());
        assertEquals(-0.10, access.bank.reserveRate, 0);
        observe(4 * REVIEW_TICKS);
        assertEquals(LIMITED, pilot.getStatus());
        assertEquals(-0.20, access.bank.reserveRate, 0);
    }
}
