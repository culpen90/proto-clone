package henize.proto.lib.map;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import henize.proto.lib.sim.Access;
import henize.proto.lib.sim.Config;
import henize.proto.pathfinder.Coordinate;

import static org.junit.Assert.*;

public class TravelerParityTest {
    @Test
    public void restoredTravelerContinuesItsExistingRoute() {
        Access access = new Access();
        access.config = new Config();
        Map map = new Map(access, new byte[20][20], 24, null);
        Coordinate start = new Coordinate(2, 3);
        Coordinate middle = new Coordinate(3, 3);
        Coordinate end = new Coordinate(4, 3);
        Traveler traveler = new Traveler(access, map, start, end);
        traveler.path = new Coordinate[]{start, middle, end};
        traveler.curr_step = 1;
        traveler.loc = middle;
        traveler.currXOffset = 23;

        traveler.Tick();

        assertSame(end, traveler.loc);
        assertEquals(2, traveler.curr_step);
        assertNull(traveler.thread);
    }

    @Test
    public void everyDirectionMovesOnePixelPerTickAtDifferentScales() throws Exception {
        for (int scale : new int[]{8, 24, 72}) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx == 0 && dy == 0) continue;
                    Access access = new Access();
                    access.config = new Config();
                    // Patch 1 movement is independent of both configurable leap values.
                    access.config.SCALE_LEAP = 7;
                    access.config.SCALE_LEAP_DIAG = 11;
                    Map map = new Map(access, new byte[20][20], scale, null);
                    Coordinate start = new Coordinate(10, 10);
                    Coordinate end = new Coordinate(10 + dx, 10 + dy);
                    Traveler traveler = new Traveler(access, map, start, end);
                    traveler.path = new Coordinate[]{start, end};
                    Method step = Traveler.class.getDeclaredMethod("Step");
                    step.setAccessible(true);
                    List<ArrivalPoint> arrivals = new ArrayList<>();
                    traveler.addListener((sender, point) -> arrivals.add(point));

                    for (int tick = 1; tick < scale; tick++) {
                        assertEquals(false, step.invoke(traveler));
                        assertSame(start, traveler.loc);
                        assertEquals(dx * tick, traveler.currXOffset);
                        assertEquals(dy * tick, traveler.currYOffset);
                        assertTrue(arrivals.isEmpty());
                    }
                    assertEquals(false, step.invoke(traveler));
                    assertSame(end, traveler.loc);
                    assertEquals(0, traveler.currXOffset);
                    assertEquals(0, traveler.currYOffset);
                    assertEquals(true, step.invoke(traveler));
                    assertEquals(1, arrivals.size());
                    assertEquals(ArrivalPoint.END, arrivals.get(0));
                }
            }
        }
    }

    @Test
    public void mapRemovalPreservesOtherAddressesAndUpdatesOnNextTick() {
        Access access = new Access();
        access.config = new Config();
        Map map = new Map(access, new byte[20][20], 24, null);
        Coordinate removed = map.GenerateAddress(1, new Coordinate(2, 3));
        Coordinate retained = map.GenerateAddress(2, new Coordinate(5, 7));
        map.RemoveAddress(removed, 1);
        map.Tick(false);
        assertEquals(1, map.stationary_list.size());
        assertSame(retained, map.stationary_list.get(0).address);
        assertEquals(0, map.getAtCoordinate(removed));
        assertEquals(2, map.getAtCoordinate(retained));
    }
}
