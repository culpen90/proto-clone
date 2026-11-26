/* SPDX-License-Identifier: GPL-3.0-or-later */
package henize.proto.lib.sim;

import org.junit.Before;
import org.junit.Test;

import henize.proto.App;
import henize.proto.lib.bank.Account;
import henize.proto.lib.bank.Account.TaxCode;
import henize.proto.lib.bank.Bank;
import henize.proto.lib.bank.TaxRates;
import henize.proto.lib.directory.Directory;
import henize.proto.lib.stats.BankStats;
import henize.proto.lib.traders.ComType;
import henize.proto.lib.traders.Trader;

import static org.junit.Assert.*;

public class AutopilotTaxPolicyTest {
    private final Access access = new Access();

    @Before public void setUp() {
        access.config = new Config();
        access.dir = new Directory();
        access.bankStats = new BankStats();
        access.bank = new Bank(access);
        access.bank.initBanksAccount(10_000);
        access.taxAccount = new Account(access, 0);
        access.taxAccount.setTaxCode(TaxCode.MASTER);
        access.taxAccount.removeListener(null);
        for (ComType type : ComType.values()) addTrader(type);
    }

    private Trader addTrader(ComType type) {
        Trader trader = new Trader(access) {{
            isStocked = true;
            price = 10;
        }};
        trader.account.removeListener(null);
        trader.account.setTaxCode(type == ComType.TRANSPORTATION ? TaxCode.TRANSPORT : TaxCode.valueOf(type.name()));
        access.dir.add(type, trader);
        return trader;
    }

    private Account account(ComType type) {
        return access.dir.getListFromType(type).getAtIndex(0).account;
    }

    private boolean adjust(boolean trading, boolean pressure) {
        // Match Simulator.Next's update before invoking the policy.
        access.bankStats.adj_totalCurrency = 0;
        for (Trader trader : access.dir.getAll()) {
            if (trader.account != access.taxAccount) access.bankStats.adj_totalCurrency += trader.account.getBalance();
        }
        return AutopilotTaxPolicy.adjust(access, trading, pressure);
    }

    @Test public void taxReceiptsArriveDuringOrdinarySimulatorTicks() {
        Simulator previous = App.sim;
        Config cfg = new Config();
        cfg.INIT_CASH = 1_000_000;
        cfg.MIN_PRICE = 100; // Ordinary transactions are large enough to produce whole cents of tax.
        App.sim = new Simulator(12, null, cfg) {
            @Override public void initNonSerializable() {} // Skip Android text renderers.
        };
        Simulator economy = App.sim;
        try {
            for (Trader trader : economy.access.dir.getAll()) {
                if (trader.account != economy.access.taxAccount) {
                    trader.account.__transfer(economy.access.bank.account, 5000);
                }
            }
            economy.autopilot().setEnabled(true);
            for (int tick = 0; tick < EconomyAutopilot.REVIEW_TICKS * 20 && !cfg.collectTax; tick++) {
                economy.Next();
                economy.map.Tick();
            }
            assertTrue(cfg.collectTax);
            assertEquals(EconomyAutopilot.Status.STARTING_TAXES, economy.autopilot().getStatus());
            for (int tick = 0; tick < EconomyAutopilot.REVIEW_TICKS * 3
                    && economy.access.taxAccount.getBalance() == 0; tick++) {
                economy.Next();
                economy.map.Tick();
            }
            assertTrue(economy.access.taxAccount.getBalance() > 0);
            economy.autopilot().setEnabled(false);
            cfg.collectTax = false;
            for (int tick = 0; tick <= EconomyAutopilot.REVIEW_TICKS * 3; tick++) {
                economy.Next();
                economy.map.Tick();
            }
            assertFalse(cfg.collectTax);
        } finally {
            economy.interrupt();
            App.sim = previous;
        }
    }

    @Test public void activeTradeBuildsTreasuryThenStopsAtTarget() {
        Account buyer = account(ComType.LABOR);
        Account seller = account(ComType.FOOD);
        buyer.__transfer(access.bank.account, 1000);
        assertFalse(adjust(false, false));
        assertTrue(adjust(true, false));
        assertEquals(5, access.config.universalTaxRate, 0);
        assertEquals(0, access.taxAccount.getBalance());
        assertTrue(buyer.send(seller, 1000));
        assertEquals(50, access.taxAccount.getBalance());
        assertEquals(950, seller.getBalance());

        assertFalse(adjust(true, false)); // At 5%, keep collecting until the 10% target.
        access.taxAccount.__transfer(seller, 50);
        assertTrue(adjust(true, false));
        assertFalse(access.config.collectTax);
        seller.__transfer(access.taxAccount, 49);
        assertFalse(adjust(true, false)); // At 5.1%, do not toggle collection back on.
        assertEquals(10_000, access.bankStats.totalCurrency);
        assertEquals(1, access.bankStats.transactionCount);
    }

    @Test public void suspensionKeepsScheduleAndInvalidRatesStayOff() {
        account(ComType.COAL).__transfer(access.bank.account, 1000);
        access.config.coalTaxRate = 3;
        assertTrue(adjust(true, false));
        assertEquals(0, access.config.universalTaxRate, 0);
        assertTrue(adjust(false, true));
        assertFalse(access.config.collectTax);
        assertEquals(3, access.config.coalTaxRate, 0);

        access.config.universalTaxRate = 98;
        assertFalse(adjust(true, false)); // 101% combined.
        access.config.universalTaxRate = 0;
        access.config.transportTaxRate = Double.NaN;
        assertFalse(adjust(true, false));
        assertFalse(access.config.collectTax);
    }

    @Test public void validHighRatesFallGraduallyBeforeCollectionResumes() {
        account(ComType.COAL).__transfer(access.bank.account, 1000);
        access.config.collectTax = true;
        access.config.universalTaxRate = 35;
        access.config.transportTaxRate = 23;
        assertTrue(adjust(false, true));
        assertFalse(access.config.collectTax);
        assertFalse(adjust(false, true)); // Leave the next intervention to debt/credit relief.
        assertTrue(adjust(true, false));
        assertEquals(25, access.config.universalTaxRate, 0);
        for (int review = 0; review < 4; review++) {
            assertTrue(adjust(true, false));
            assertFalse(access.config.collectTax);
        }
        assertTrue(adjust(true, false));
        assertEquals(5, access.config.universalTaxRate, 0);
        assertEquals(5, access.config.transportTaxRate, 0);
        assertTrue(access.config.collectTax);
    }

    @Test public void eachSectorCanBeTaxedWhenWellFundedAndRelievedWhenCashPoor() {
        access.config.universalTaxRate = 5;
        access.config.collectTax = true;
        for (ComType type : ComType.values()) {
            Account business = account(type);
            business.__transfer(access.bank.account, 1000);
            assertTrue(type.name(), adjust(true, false));
            assertEquals(type.name(), 2, TaxRates.get(access.config, business.taxCode), 0);
            access.bank.account.__transfer(business, 1000);
            // Another sector is still trading, keeping the market active.
            ComType other = type == ComType.LABOR ? ComType.FOOD : ComType.LABOR;
            account(other).__transfer(access.bank.account, 1);
            assertTrue(type.name(), adjust(true, false));
            assertEquals(type.name(), 0, TaxRates.get(access.config, business.taxCode), 0);
            access.bank.account.__transfer(account(other), 1);
        }
    }

    @Test public void stimulusReachesEachUnderfundedIndustryAndConservesCurrency() {
        access.taxAccount.__transfer(access.bank.account, 1000);
        for (ComType type : ComType.values()) account(type).__transfer(access.bank.account, 100);
        int accounts = access.bank.accounts.size();
        for (ComType type : ComType.values()) {
            Account recipient = account(type);
            access.bank.account.__transfer(recipient, 100);
            long treasury = access.taxAccount.getBalance();
            assertEquals(type, AutopilotTaxPolicy.stimulateIndustry(access));
            long cost = type == ComType.LABOR ? 60 : 10;
            assertEquals(cost, recipient.getBalance());
            assertEquals(treasury - cost, access.taxAccount.getBalance());
            recipient.__transfer(access.bank.account, 100 - cost);
        }
        assertNull(AutopilotTaxPolicy.stimulateIndustry(access));
        assertEquals(accounts, access.bank.accounts.size());
        assertEquals(0, access.bankStats.transactionCount);
        assertEquals(10_000, access.bankStats.totalCurrency);
    }

    @Test public void distributionsPayEveryCentAndOmitTheMasterAndDepartingTrader() {
        Trader worker = addTrader(ComType.LABOR);
        Account first = account(ComType.LABOR);
        Trader master = addTrader(ComType.LABOR);
        master.id = "MASTER";
        master.account = access.taxAccount;
        access.taxAccount.__transfer(access.bank.account, 5);
        int accounts = access.bank.accounts.size();
        access.taxAccount.distribute(ComType.LABOR, 5, null);
        assertEquals(3, first.getBalance());
        assertEquals(2, worker.account.getBalance());
        assertEquals(0, access.taxAccount.getBalance());
        worker.account.distribute(ComType.LABOR, 2, worker);
        assertEquals(5, first.getBalance());
        assertEquals(0, worker.account.getBalance());
        assertEquals(accounts, access.bank.accounts.size());
        assertEquals(0, access.bankStats.lostCurrency);
    }
}
