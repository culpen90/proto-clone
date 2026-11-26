/* SPDX-License-Identifier: GPL-3.0-or-later */
package henize.proto;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InvalidClassException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectStreamClass;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

import henize.proto.lib.sim.Simulator;
import henize.proto.lib.stats.GraphData;

/** Encrypted private slots with one-time migration of files already present at upgrade. */
final class LocalEconomyStore {
    private static final String MIGRATION = "legacy-economy-inventory-v1";
    private static final String INVENTORIED = ":inventoried";
    private static final String LEGACY_GRAPH = "henize.proto.graph";

    private static SharedPreferences inventory(Context context) {
        return context.getSharedPreferences(MIGRATION, Context.MODE_PRIVATE);
    }

    static void registerLegacySlots(Context context) throws IOException {
        SharedPreferences migration = inventory(context);
        if (migration.getBoolean(INVENTORIED, false)) return;
        SharedPreferences.Editor entries = migration.edit();
        File[] files = context.getFilesDir().listFiles();
        if (files == null) throw new IOException("Could not read saved economies");
        for (File file : files) {
            String name = file.getName();
            if (!file.isFile() || name.startsWith(".") || name.equals("dat")) continue;
            if (name.equals(LEGACY_GRAPH) || isObjectStream(file)) {
                entries.putString(name, fingerprint(file));
            }
        }
        if (!entries.putBoolean(INVENTORIED, true).commit()) throw new IOException("Could not register old saves");
    }

    static void save(Context context, String slot, Simulator economy) throws IOException {
        File target = context.getFileStreamPath(slot);
        File pending = File.createTempFile(".economy-", ".tmp", target.getParentFile());
        try {
            PrivateEconomyFile.write(context, slot, economy, pending);
            // On Android this replaces the slot atomically, after the complete file was synced.
            if (!pending.renameTo(target)) throw new IOException("Could not replace the saved economy");
        } finally {
            if (pending.exists()) pending.delete();
        }
        if (inventory(context).contains(slot)) inventory(context).edit().remove(slot).commit();
    }

    static Simulator load(Context context, String slot) throws IOException, ClassNotFoundException {
        File source = context.getFileStreamPath(slot);
        if (!isObjectStream(source)) return PrivateEconomyFile.read(context, slot, source);

        // Never accept newly introduced plain object streams, including as replacements for sealed saves.
        String known = inventory(context).getString(slot, null);
        if (known == null || !known.equals(fingerprint(source))) throw new IOException("Unrecognized legacy save");
        File graph = context.getFileStreamPath(LEGACY_GRAPH);
        if (graph.exists() && !fingerprint(graph).equals(inventory(context).getString(LEGACY_GRAPH, null))) {
            throw new IOException("Legacy graph history changed");
        }
        Simulator restored = loadLegacy(context, slot);
        save(context, slot, restored);
        return restored;
    }

    static Simulator loadLegacy(Context context, String slot) throws IOException, ClassNotFoundException {
        try (ObjectInputStream in = new LegacyInput(new BufferedInputStream(context.openFileInput(slot)))) {
            Simulator economy = (Simulator) in.readObject();
            economy.graph.virt_days = readLegacyHistory(context);
            economy.initNonSerializable();
            return economy;
        }
    }

    private static List<GraphData> readLegacyHistory(Context context) throws IOException {
        File graph = context.getFileStreamPath(LEGACY_GRAPH);
        if (!graph.exists()) return new ArrayList<>();
        long bytes = graph.length();
        int recordSize = 6 * 8;
        if (bytes % recordSize != 0 || bytes / recordSize > Integer.MAX_VALUE) {
            throw new IOException("Incomplete graph history");
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(graph)))) {
            return EconomySnapshot.readHistory(in, (int) (bytes / recordSize));
        }
    }

    /** Only for inventoried app-private saves created by older releases. */
    private static final class LegacyInput extends ObjectInputStream {
        LegacyInput(InputStream input) throws IOException { super(input); }

        @Override protected Class<?> resolveClass(ObjectStreamClass descriptor)
                throws IOException, ClassNotFoundException {
            String name = descriptor.getName();
            // Arrays use JVM descriptors. Keep the same allowlist for their component type.
            if (name.startsWith("[")) {
                int index = name.lastIndexOf('[') + 1;
                String component = name.substring(index);
                if (component.length() == 1 && "ZBSCIJFD".contains(component)) return super.resolveClass(descriptor);
                if (component.startsWith("L") && component.endsWith(";")) {
                    name = component.substring(1, component.length() - 1);
                }
            }
            if (name.startsWith("henize.proto.lib.") || name.equals("henize.proto.pathfinder.Coordinate")
                    || name.equals("java.util.ArrayList") || name.equals("java.util.Stack")
                    || name.equals("java.util.Vector") || name.equals("java.util.Random")
                    || name.equals("java.lang.Boolean") || name.equals("java.lang.Enum")
                    || name.equals("java.lang.String") || name.equals("java.lang.Object")) {
                return super.resolveClass(descriptor);
            }
            throw new InvalidClassException("Unexpected class in legacy economy");
        }

        @Override protected Class<?> resolveProxyClass(String[] interfaces) throws IOException {
            throw new InvalidClassException("Unexpected proxy in legacy economy");
        }
    }

    private static boolean isObjectStream(File file) throws IOException {
        if (file.length() < 4) return false;
        try (DataInputStream in = new DataInputStream(new FileInputStream(file))) {
            return in.readInt() == 0xaced0005;
        }
    }

    private static String fingerprint(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[16384];
            int size;
            while ((size = in.read(buffer)) != -1) hash.update(buffer, 0, size);
            return Base64.encodeToString(hash.digest(), Base64.NO_WRAP);
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private LocalEconomyStore() {}
}
