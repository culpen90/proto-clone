/* SPDX-License-Identifier: GPL-3.0-or-later */
package henize.proto.lib.sim;

import org.junit.After;
import org.junit.Test;

import henize.proto.App;
import henize.proto.lib.commodities.Food;
import henize.proto.lib.map.Traveler;
import henize.proto.lib.traders.ComType;
import henize.proto.lib.traders.MarketOrder;
import henize.proto.lib.traders.SpecialOrder;
import henize.proto.lib.traders.Trader;

import static org.junit.Assert.*;

public class AutopilotSpecialOrdersTest {
    private final Simulator previousSim = App.sim;
    private final Simulator sim = new Simulator(12, null, new Config()) {
        @Override public void initNonSerializable() {}
    };
    private final Access access = sim.access;
    private final AutopilotSpecialOrders orders = new AutopilotSpecialOrders();

    public AutopilotSpecialOrdersTest() {
        App.sim = sim;
    }

    @After public void restoreSim() {
        sim.interrupt();
        App.sim = previousSim;
    }

    @Test public void ordersOneAffordableMarketLotOfEachMissingGood() {
        assertFalse(orders.place(access));
        access.taxAccount.__transfer(access.bank.account, 1000);
        for (ComType type : AutopilotTradePolicy.GOODS) {
            assertTrue(orders.place(access));
            assertFalse(orders.place(access)); // Do not stack priority chains.
            Trader buyer = MarketOrder.traderFor(access.master, type);
            int quantity = 0;
            for (Trader producer : access.dir.getListFromType(type).getList()) {
                for (SpecialOrder order : producer.specialOrders) {
                    assertSame(buyer, order.trader);
                    quantity += order.quant;
                }
            }
            assertEquals(type == ComType.FOOD ? access.config.MIN_FOOD_SELL : 1, quantity);
            sim.breakPriority();
        }
    }

    @Test public void breaksStrandedObligationsAndWaitsForTradeBeforeRetrying() {
        access.taxAccount.__transfer(access.bank.account, 1000);
        assertTrue(sim.placePriorityOrder(ComType.TOOL, 1));
        Trader toolMaker = null;
        for (Trader trader : access.dir.getListFromType(ComType.TOOL).getList()) {
            if (!trader.specialOrders.isEmpty()) toolMaker = trader;
        }
        assertNotNull(toolMaker);
        toolMaker.execSpecialPrioritySystem(); // Create the labor, coal and iron suborders.
        assertTrue(toolMaker.execSpecial);
        access.dir.laborOrderForTrade.push(new SpecialOrder(toolMaker, 1));
        sim.map.traveler_list.add(new Traveler(access, sim.map, toolMaker.address, access.master.address));
        assertFalse(orders.breakStalled(access));
        sim.map.traveler_list.clear();
        assertTrue(orders.breakStalled(access));
        assertTrue(sim.specialPriority.isEmpty());
        assertTrue(access.dir.laborOrderForTrade.isEmpty());
        for (Trader trader : access.dir.getAll()) {
            assertTrue(trader.specialOrders.isEmpty());
            assertTrue(trader.processingSpecialOrders.isEmpty());
            assertFalse(trader.execSpecial);
        }
        assertFalse(orders.place(access));
        access.bankStats.transactionCount++;
        assertTrue(orders.place(access));
    }

    @Test public void skipsGoodsWithStandingOrdersOrPendingDeliveries() {
        access.taxAccount.__transfer(access.bank.account, 1000);
        access.master.toolTrader.buyEnabled(true);
        access.master.coalTrader.sellEnabled(true);
        access.master.ironTrader.sellEnabled(true);
        Trader farm = null;
        for (Trader trader : access.dir.getListFromType(ComType.FOOD).getList()) {
            if (trader != access.master.foodTrader) farm = trader;
        }
        assertNotNull(farm);
        farm.com_sold.push(new Food(access.comStats));
        assertFalse(orders.place(access));
        farm.com_sold.pop();
        assertTrue(orders.place(access));
    }

    @Test public void deliversPriorityProductionIntoTheTreasuryInventory() {
        access.taxAccount.__transfer(access.bank.account, 1000);
        access.config.warp = true;
        assertTrue(orders.place(access));
        for (int i = 0; i < 100 && access.master.foodTrader.com.size() < access.config.MIN_FOOD_SELL; i++) {
            sim.Next();
            sim.map.Tick();
        }
        assertEquals(access.config.MIN_FOOD_SELL, access.master.foodTrader.com.size());
        assertTrue(access.bankStats.transactionCount > 0);
        assertTrue(access.taxAccount.getBalance() < 1000);
        assertTrue(sim.specialPriority.isEmpty());
    }

    @Test public void losingAWorkerLeavesItsObligationForAReplacement() {
        Trader farm = access.master.foodTrader;
        farm.account.__transfer(access.bank.account, 100);
        Trader worker = access.dir.getListFromType(ComType.LABOR).getAtIndex(0);
        assertTrue(farm.buySpecialPriority(ComType.LABOR, 1));
        worker.com.clear();
        worker.act();
        assertTrue(worker.isDead);
        assertEquals(1, sim.specialPriority.size());
        assertEquals(1, access.dir.laborOrderForTrade.size());
        Trader replacement = access.dir.getListFromType(ComType.LABOR).getAtIndex(0);
        replacement.act();
        replacement.execSpecialPrioritySystem();
        assertTrue(access.dir.laborOrderForTrade.isEmpty());
        assertTrue(sim.specialPriority.isEmpty());
        assertTrue(replacement.sale);
    }
}
