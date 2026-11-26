/* SPDX-License-Identifier: GPL-3.0-or-later */
package henize.proto.lib.directory;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;

import henize.proto.lib.bank.Bank;
import henize.proto.lib.sim.Access;
import henize.proto.lib.sim.Config;
import henize.proto.lib.stats.BankStats;
import henize.proto.lib.traders.ComType;
import henize.proto.lib.traders.Trader;

import static org.junit.Assert.assertArrayEquals;

public class DirectorySnapshotTest {
    @Test
    public void readingCurrentOrderLeavesSavedStateUntouched() throws Exception {
        Access access = new Access();
        access.config = new Config();
        access.bankStats = new BankStats();
        access.bank = new Bank(access);
        access.dir = new Directory();
        PricedTrader first = new PricedTrader(access, 10);
        PricedTrader second = new PricedTrader(access, 20);
        access.dir.add(ComType.FOOD, first);
        access.dir.add(ComType.FOOD, second);

        byte[] before = serialized(access.dir);
        assertArrayEquals(new Trader[]{first, second}, access.dir.getAll());
        assertArrayEquals(before, serialized(access.dir));

        second.changePrice(5);
        assertArrayEquals(new Trader[]{second, first}, access.dir.getAll());
    }

    private static byte[] serialized(Directory directory) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(directory);
        }
        return bytes.toByteArray();
    }

    private static final class PricedTrader extends Trader {
        PricedTrader(Access access, long price) {
            super(access);
            changePrice(price);
        }

        void changePrice(long price) {
            setPrice(price);
        }
    }
}
