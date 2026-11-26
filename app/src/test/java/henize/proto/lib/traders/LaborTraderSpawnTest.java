/* SPDX-License-Identifier: GPL-3.0-or-later */
package henize.proto.lib.traders;

import org.junit.Test;

import henize.proto.App;
import henize.proto.lib.sim.Config;
import henize.proto.lib.sim.Simulator;
import henize.proto.pathfinder.Coordinate;

import static org.junit.Assert.*;

public class LaborTraderSpawnTest {
    @Test(timeout = 5000) public void fullLaborDistrictSkipsSpawnUntilASiteOpens() {
        Simulator previous = App.sim;
        Config config = new Config();
        Simulator sim = new Simulator(12, null, config) {
            @Override public void initNonSerializable() {}
        };
        App.sim = sim;
        try {
            Trader worker = sim.access.dir.getTraderByID("TRIGGER");
            Object[] stock = worker.com.toArray();
            int workers = sim.access.traderStats.laborTraderCount;
            int accounts = sim.access.bank.accounts.size();
            int buildings = sim.map.stationary_list.size();

            Coordinate open = sim.map.tryGenerateLaborAddress();
            assertNotNull(open);
            sim.map.RemoveAddress(open, 1);
            sim.map.Tick(false);

            config.Lxreg = config.L_xreg = worker.address.x;
            config.Lyreg = config.L_yreg = worker.address.y;
            for (boolean sequential : new boolean[]{true, false}) {
                config.sequencial = sequential;
                worker.act();
                assertArrayEquals(stock, worker.com.toArray());
                assertEquals(workers, sim.access.traderStats.laborTraderCount);
                assertEquals(accounts, sim.access.bank.accounts.size());
                assertEquals(buildings, sim.map.stationary_list.size());
            }

            config.Lxreg = config.L_xreg = open.x;
            config.Lyreg = config.L_yreg = open.y;
            worker.act();
            assertEquals(stock.length - config.LAB_SPAWN_RATE / 2, worker.com.size());
            assertEquals(workers + 1, sim.access.traderStats.laborTraderCount);
            assertEquals(accounts + 1, sim.access.bank.accounts.size());
            assertEquals(buildings + 1, sim.map.stationary_list.size());
        } finally {
            sim.interrupt();
            App.sim = previous;
        }
    }
}
