/* SPDX-License-Identifier: GPL-3.0-or-later */
package henize.proto.lib.sim;

import org.junit.After;
import org.junit.Test;

import henize.proto.App;
import henize.proto.lib.commodities.Coal;
import henize.proto.lib.commodities.Food;
import henize.proto.lib.traders.CoalTrader;
import henize.proto.lib.traders.ComType;
import henize.proto.lib.traders.FoodTrader;
import henize.proto.lib.traders.MarketOrder;
import henize.proto.lib.traders.Trader;

import static henize.proto.lib.sim.EconomyAutopilot.Status.BUYING_STOCK;
import static henize.proto.lib.sim.EconomyAutopilot.Status.CANCELLING_TRADE;
import static henize.proto.lib.sim.EconomyAutopilot.Status.PLACING_BUY_ORDER;
import static henize.proto.lib.sim.EconomyAutopilot.Status.SELLING_STOCK;
import static org.junit.Assert.*;

public class AutopilotTradePolicyTest {
    private final Simulator previous = App.sim;
    private final Simulator sim = new Simulator(12, null, new Config()) {
        @Override public void initNonSerializable() {}
    };
    private final Access access = sim.access;
    private final AutopilotTradePolicy policy = new AutopilotTradePolicy();

    public AutopilotTradePolicyTest() {
        App.sim = sim;
        access.config.warp = true;
    }

    @After public void tearDown() {
        sim.interrupt();
        App.sim = previous;
    }

    private FoodTrader stockFarm(int units, int unitPrice) {
        FoodTrader farm = new FoodTrader(access, "Test farm") {{
            for (int i = 0; i < units; i++) com.push(new Food(access.comStats));
            isStocked = true;
            setPrice(unitPrice);
        }};
        access.dir.add(ComType.FOOD, farm);
        return farm;
    }

    @Test public void buysOneAffordableFoodLotAndOffersItWhenDelivered() {
        int lot = access.config.MIN_FOOD_SELL;
        FoodTrader farm = stockFarm(2 * lot, 40);
        access.taxAccount.__transfer(access.bank.account, 5000);
        long currency = access.bankStats.totalCurrency;
        long price = farm.getPrice() * lot + access.dir.getAveragePriceOf(ComType.TRANSPORTATION);
        assertEquals(BUYING_STOCK, policy.review(access, false));
        assertEquals(5000 - price, access.taxAccount.getBalance());
        assertEquals(currency, access.bankStats.totalCurrency);
        assertEquals(lot, farm.com.size());
        assertEquals(lot, farm.com_sold.size());
        assertNull(policy.review(access, false));

        Trader shipper = access.dir.getCheapestTraderOf(ComType.TRANSPORTATION);
        shipper.act();
        sim.map.Tick();
        sim.map.Tick();
        assertEquals(lot, access.master.foodTrader.com.size());
        assertEquals(SELLING_STOCK, policy.review(access, false));
        access.master.act();
        assertTrue(access.master.foodTrader.isStocked());
        assertEquals(MarketOrder.DISCOUNT, access.master.f_sellCond);
        assertTrue(access.master.foodTrader.getPrice() < access.dir.getAveragePriceOf(ComType.FOOD));

        Trader worker = access.dir.getCheapestTraderOf(ComType.LABOR);
        assertTrue(access.master.foodTrader.sellTo(worker, worker.account, lot));
        assertTrue(policy.cancelFilledOffer(access));
        assertFalse(access.master.fs_enabled);
    }

    @Test public void preservesPlayersReplacementOfferAndActiveBuysAcrossAllGoods() {
        for (ComType type : AutopilotTradePolicy.GOODS) {
            Trader trader = MarketOrder.traderFor(access.master, type);
            trader.buyEnabled(true);
            // Listing decisions only inspect stock counts. No shipment is made in this test.
            for (int i = 0; i < AutopilotTradePolicy.lotSize(access, type); i++)
                trader.com.push(new Food(access.comStats));
            assertNull(policy.review(access, false));
        }
        access.master.ironTrader.buyEnabled(false);
        assertEquals(SELLING_STOCK, policy.review(access, false));
        access.master.ironTrader.setSellCond(MarketOrder.FIXED);
        access.master.ironTrader.setSellValues(1, 123);
        access.master.ironTrader.com.clear();
        assertFalse(policy.cancelFilledOffer(access));
        assertTrue(access.master.is_enabled);
        assertEquals(123, access.master.is_setValue);
    }

    @Test public void buysRespectBudgetMarketTriggerAndAvailableSurplus() {
        int lot = access.config.MIN_FOOD_SELL;
        FoodTrader farm = stockFarm(2 * lot - 1, 1);
        access.taxAccount.__transfer(access.bank.account, 100);
        assertNull(policy.review(access, false));
        farm.com.push(new Food(access.comStats));
        farm.priceOffset = 2;
        assertNull(policy.review(access, false));
        farm.priceOffset = 0;
        // A single low-priced farm is affordable, but the market price is over the limit.
        stockFarm(2 * lot, 100);
        assertEquals(PLACING_BUY_ORDER, policy.review(access, false));
        assertTrue(access.master.fb_enabled);
        assertEquals(MarketOrder.BELOW, access.master.f_buyCond);
        assertEquals(2, access.master.fb_setValue);
        assertEquals(100, access.taxAccount.getBalance());
        assertEquals(0, access.bankStats.transactionCount);
    }

    @Test public void restoredConditionalBidWaitsForReviewAndFillsExactlyOneLot() {
        int lot = access.config.MIN_FOOD_SELL;
        FoodTrader farm = stockFarm(4 * lot, 1);
        farm.priceOffset = 50;
        access.taxAccount.__transfer(access.bank.account, 500);
        assertEquals(PLACING_BUY_ORDER, policy.review(access, true));
        EconomyAutopilot restored = sim.autopilot();
        restored.setEnabled(true);
        assertEquals(1 << 4, policy.ownedOffers(access));
        restored.restoreOffers(policy.ownedOffers(access));

        farm.priceOffset = 0;
        access.master.act(); // The manual engine normally buys the full seller inventory here.
        assertEquals(4 * lot, farm.com.size());
        for (int i = 0; i <= EconomyAutopilot.REVIEW_TICKS; i++) restored.onTick(access);
        assertEquals(BUYING_STOCK, restored.getStatus());
        assertEquals(3 * lot, farm.com.size());
        assertFalse(access.master.fb_enabled);
        assertFalse(restored.ownsBuy(ComType.FOOD));
    }

    @Test public void cancelsUnaffordableBidAndDisarmsPendingBidWhenSwitchedOff() {
        FoodTrader farm = stockFarm(30, 1);
        farm.priceOffset = 50;
        access.taxAccount.__transfer(access.bank.account, 500);
        assertEquals(PLACING_BUY_ORDER, policy.review(access, true));
        access.bank.account.__transfer(access.taxAccount, 200);
        assertEquals(CANCELLING_TRADE, policy.reviewPendingBuy(access, true));
        assertFalse(access.master.fb_enabled);
        assertFalse(policy.ownsBuy(ComType.FOOD));

        assertEquals(PLACING_BUY_ORDER, policy.review(access, true));
        sim.autopilot().setEnabled(true);
        sim.autopilot().restoreOffers(policy.ownedOffers(access));
        sim.autopilot().setEnabled(false);
        farm.priceOffset = 0;
        access.master.act();
        assertFalse(access.master.fb_enabled);
        assertEquals(30, farm.com.size());
    }

    @Test public void healthyPurchasesLeavePrivateWorkingStockAndWaitForPendingDeliveries() {
        int lot = access.config.MIN_FOOD_SELL;
        FoodTrader farm = stockFarm(access.config.MIN_STOCK + lot - 1, 3);
        access.taxAccount.__transfer(access.bank.account, 1000);
        assertNull(policy.review(access, true));
        farm.com.push(new Food(access.comStats));
        stockFarm(access.config.MIN_STOCK + lot, 3);
        assertEquals(BUYING_STOCK, policy.review(access, true));
        assertEquals(lot, farm.com_sold.size());
        assertNull(policy.review(access, true));
    }

    @Test public void healthySalesWaitForAStockShortageAndOwnershipSurvivesReload() {
        int lot = access.config.MIN_FOOD_SELL;
        FoodTrader farm = stockFarm(access.config.MIN_STOCK, 15);
        for (int i = 0; i < lot; i++) access.master.foodTrader.com.push(new Food(access.comStats));
        assertNull(policy.review(access, true));
        farm.com.pop();
        assertEquals(SELLING_STOCK, policy.review(access, true));
        AutopilotTradePolicy loaded = new AutopilotTradePolicy();
        loaded.restoreOffers(access, policy.ownedOffers(access));
        access.master.foodTrader.com.clear();
        assertTrue(loaded.cancelFilledOffer(access));
        assertFalse(access.master.fs_enabled);
    }

    @Test public void allTradeScreenSellConditionsUseMarketQuotesForEveryGood() {
        for (ComType type : AutopilotTradePolicy.GOODS) {
            Trader trader = MarketOrder.traderFor(access.master, type);
            for (Trader supplier : access.dir.getListFromType(type).getList())
                if (supplier != trader) supplier.priceOffset = 49;
            trader.com.push(new Food(access.comStats));
            trader.sellEnabled(true);
            assertOffer(trader, MarketOrder.MARKET, 0, true, 50);
            assertOffer(trader, MarketOrder.FIXED, 40, true, 40);
            assertOffer(trader, MarketOrder.MARKUP, 10, true, 60);
            assertOffer(trader, MarketOrder.DISCOUNT, 10, true, 40);
            assertOffer(trader, MarketOrder.BELOW, 50, false, 0);
            assertOffer(trader, MarketOrder.BELOW, 51, true, 50);
            assertOffer(trader, MarketOrder.ABOVE, 50, false, 0);
            assertOffer(trader, MarketOrder.ABOVE, 49, true, 50);
            assertOffer(trader, "Now at [set value] below market value", 12, true, 38);
        }
    }

    private void assertOffer(Trader trader, String condition, long value, boolean offered, long price) {
        trader.setSellValues(1, value);
        trader.setSellCond(condition);
        trader.act();
        assertEquals(offered, trader.isStocked());
        if (offered) assertEquals(price, trader.getPrice());
    }

    @Test public void marketListingsIncludeSectorAndUniversalTaxesOnce() {
        Trader treasury = access.master.foodTrader;
        for (Trader farm : access.dir.getListFromType(ComType.FOOD).getList())
            if (farm != treasury) farm.priceOffset = 49;
        access.config.collectTax = true;
        access.config.universalTaxRate = 10;
        access.config.foodTaxRate = 20;
        treasury.com.push(new Food(access.comStats));
        treasury.sellEnabled(true);
        assertEquals(65, access.dir.getAveragePriceOf(ComType.FOOD));
        assertOffer(treasury, MarketOrder.MARKET, 0, true, 65);
        assertOffer(treasury, MarketOrder.DISCOUNT, 10, true, 52);
        assertOffer(treasury, MarketOrder.BELOW, 65, false, 0);
        assertOffer(treasury, MarketOrder.BELOW, 66, true, 65);
    }

    @Test public void manualConditionalBuysUseTheSameTriggersAsAutopilot() {
        CoalTrader mine = new CoalTrader(access, "Test mine") {{
            com.push(new Coal(access.comStats));
            isStocked = true;
        }};
        access.dir.add(ComType.COAL, mine);
        access.taxAccount.__transfer(access.bank.account, 100);
        Trader treasury = access.master.coalTrader;
        treasury.buyEnabled(true);
        treasury.setBuyCond(MarketOrder.ABOVE);
        treasury.setBuyValues(1, 1);
        treasury.act();
        assertEquals(1, mine.com.size());
        treasury.setBuyCond(MarketOrder.BELOW);
        treasury.act();
        assertEquals(1, mine.com.size());
        treasury.setBuyValues(1, 2);
        treasury.act();
        assertTrue(mine.com.empty());
        assertEquals(1, mine.com_sold.size());
    }
}
