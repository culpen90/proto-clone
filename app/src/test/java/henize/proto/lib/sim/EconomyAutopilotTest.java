/* SPDX-License-Identifier: GPL-3.0-or-later */
package henize.proto.lib.sim;

import org.junit.After;
import org.junit.Test;

import henize.proto.App;
import henize.proto.lib.bank.Account;
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
        assertEquals(MONITORING, pilot.getStatus());
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
        assertEquals(MONITORING, pilot.getStatus());
        sim.map.traveler_list.clear();
        observe(REVIEW_TICKS);
        access.bankStats.transactionCount++;
        observe(REVIEW_TICKS);
        assertEquals(MONITORING, pilot.getStatus());
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
