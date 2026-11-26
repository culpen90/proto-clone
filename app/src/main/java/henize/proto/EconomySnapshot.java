/* SPDX-License-Identifier: GPL-3.0-or-later */
package henize.proto;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import java.util.zip.CheckedOutputStream;

import henize.proto.lib.sim.Simulator;
import henize.proto.lib.stats.GraphData;

/** Portable model and transaction history, independent of the installation's private files. */
final class EconomySnapshot {
    private static final int MAGIC = 0x50524f54; // PROT
    private static final int VERSION_ORIGINAL = 1;
    private static final int VERSION_AUTOPILOT = 2;
    private static final int VERSION_AUTOPILOT_OFFERS = 3;
    private static final int MAX_PORTABLE_HISTORY = 1_000_000;

    static void write(Simulator sim, OutputStream destination) throws IOException {
        BufferedOutputStream buffer = new BufferedOutputStream(destination);
        CRC32 checksum = new CRC32();
        DataOutputStream out = new DataOutputStream(new CheckedOutputStream(buffer, checksum));
        out.writeInt(MAGIC);
        boolean automated = sim.autopilot().isEnabled();
        int offers = sim.autopilot().ownedOffers();
        // Keep writing the earlier formats when a save has no managed listings to restore.
        int version = automated ? VERSION_AUTOPILOT : VERSION_ORIGINAL;
        if (offers != 0) version = VERSION_AUTOPILOT_OFFERS;
        out.writeInt(version);
        if (automated) out.writeBoolean(true);
        if (offers != 0) out.writeByte(offers);
        EconomyModelCodec.write(sim, out);
        if (sim.graph.virt_days.size() > MAX_PORTABLE_HISTORY) {
            throw new IOException("Graph history is too large to export");
        }
        writeHistory(sim.graph.virt_days, out);
        new DataOutputStream(buffer).writeLong(checksum.getValue());
        buffer.flush();
    }

    static Simulator read(InputStream source) throws IOException {
        BufferedInputStream buffer = new BufferedInputStream(source);
        CRC32 checksum = new CRC32();
        DataInputStream in = new DataInputStream(new CheckedInputStream(buffer, checksum));
        if (in.readInt() != MAGIC) throw new IOException("This file is not a Proto economy");
        int version = in.readInt();
        if (version < VERSION_ORIGINAL || version > VERSION_AUTOPILOT_OFFERS) {
            throw new IOException("Unsupported economy version");
        }
        boolean automated = version >= VERSION_AUTOPILOT && in.readBoolean();
        int offers = version >= VERSION_AUTOPILOT_OFFERS ? in.readUnsignedByte() : 0;
        if (!automated && offers != 0) throw new IOException("Invalid managed trade orders");
        Simulator sim = EconomyModelCodec.read(in);
        int count = in.readInt();
        if (count > MAX_PORTABLE_HISTORY) throw new IOException("Graph history is too large");
        sim.graph.virt_days = readHistory(in, count);
        if (new DataInputStream(buffer).readLong() != checksum.getValue() || buffer.read() != -1) {
            throw new IOException("Damaged economy file");
        }
        sim.initNonSerializable();
        sim.autopilot().setEnabled(automated);
        sim.autopilot().restoreOffers(offers);
        if (sim.autopilot().ownedOffers() != offers) throw new IOException("Inconsistent managed trade orders");
        return sim;
    }

    static void writeHistory(List<GraphData> history, DataOutput out) throws IOException {
        out.writeInt(history.size());
        for (GraphData point : history) {
            out.writeLong(point.labor);
            out.writeLong(point.food);
            out.writeLong(point.tool);
            out.writeLong(point.coal);
            out.writeLong(point.iron);
            out.writeLong(point.transport);
        }
    }

    static List<GraphData> readHistory(DataInput in) throws IOException {
        return readHistory(in, in.readInt());
    }

    static List<GraphData> readHistory(DataInput in, int count) throws IOException {
        if (count < 0) throw new IOException("Invalid graph history length");
        // Read records before allocating their storage, including for truncated legacy files.
        List<GraphData> history = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            history.add(new GraphData(in.readLong(), in.readLong(), in.readLong(),
                    in.readLong(), in.readLong(), in.readLong()));
        }
        return history;
    }

    private EconomySnapshot() {}
}
