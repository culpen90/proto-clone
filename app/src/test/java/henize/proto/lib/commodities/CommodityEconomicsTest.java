package henize.proto.lib.commodities;

import org.junit.Test;

import henize.proto.lib.sim.Access;
import henize.proto.lib.sim.Config;
import henize.proto.lib.stats.ComStats;

import static org.junit.Assert.*;

public class CommodityEconomicsTest {
    private ComStats createStats() {
        ComStats stats = new ComStats();
        stats.access = new Access();
        stats.access.config = new Config();
        return stats;
    }

    @Test
    public void productionChainConsumesInputsAndPreservesCumulativeCounts() {
        ComStats stats = createStats();
        Coal coal = Coal.Generate(stats, new Labor(stats, null), null)[0];
        Iron iron = Iron.Generate(stats, new Labor(stats, null), null)[0];
        Tool tool = Tool.Generate(stats, new Labor[]{new Labor(stats, null)}, coal, iron);
        assertEquals(0, stats.coalCount);
        assertEquals(0, stats.ironCount);
        assertEquals(0, stats.laborCount);
        assertEquals(1, stats.toolCount);
        assertTrue(coal.isUsed());
        assertTrue(iron.isUsed());

        Food[] food = Food.Generate(stats, new Labor(stats, null), tool);
        assertEquals(20, food.length);
        assertEquals(20, stats.foodCount);
        assertEquals(0, stats.toolCount);
        assertEquals(0, stats.laborCount);
        assertTrue(tool.isUsed());
        Labor[] replenished = Labor.Generate(stats, food[0], null);
        assertEquals(1, replenished.length);
        assertEquals(19, stats.foodCount);
        assertEquals(1, stats.laborCount);
        assertEquals(5, stats.t_laborCount);
        assertEquals(20, stats.t_foodCount);
        assertEquals(1, stats.t_toolCount);
        assertEquals(1, stats.t_coalCount);
        assertEquals(1, stats.t_ironCount);
    }

    @Test
    public void consumedLaborCannotProduceTwice() {
        ComStats stats = createStats();
        Labor labor = new Labor(stats, null);
        assertEquals(1, Food.Generate(stats, labor, null).length);
        try {
            Food.Generate(stats, labor, null);
            fail("Consumed labor must be rejected");
        } catch (RuntimeException expected) {
            assertEquals("Com used", expected.getMessage());
        }
        assertEquals(1, stats.foodCount);
        assertEquals(1, stats.t_foodCount);
        assertEquals(0, stats.laborCount);
    }
}
