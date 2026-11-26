/* SPDX-License-Identifier: GPL-3.0-or-later */
package henize.proto;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.Stack;
import java.util.zip.CRC32;

import org.objenesis.ObjenesisStd;

import henize.proto.lib.bank.Account;
import henize.proto.lib.bank.Bank;
import henize.proto.lib.commodities.Coal;
import henize.proto.lib.commodities.Commodity;
import henize.proto.lib.commodities.Food;
import henize.proto.lib.commodities.Iron;
import henize.proto.lib.commodities.Labor;
import henize.proto.lib.commodities.Tool;
import henize.proto.lib.commodities.Transportation;
import henize.proto.lib.directory.Directory;
import henize.proto.lib.directory.DirectoryList;
import henize.proto.lib.map.MapDataPair;
import henize.proto.lib.map.Traveler;
import henize.proto.lib.sim.Access;
import henize.proto.lib.sim.Config;
import henize.proto.lib.sim.Simulator;
import henize.proto.lib.stats.BankStats;
import henize.proto.lib.stats.ComStats;
import henize.proto.lib.stats.Graph;
import henize.proto.lib.stats.TraderStats;
import henize.proto.lib.traders.CoalTrader;
import henize.proto.lib.traders.ComType;
import henize.proto.lib.traders.FoodTrader;
import henize.proto.lib.traders.IronTrader;
import henize.proto.lib.traders.LaborTrader;
import henize.proto.lib.traders.MasterTrader;
import henize.proto.lib.traders.QTP;
import henize.proto.lib.traders.SpecialOrder;
import henize.proto.lib.traders.TSPair;
import henize.proto.lib.traders.ToolTrader;
import henize.proto.lib.traders.Trader;
import henize.proto.lib.traders.TransportationService;
import henize.proto.pathfinder.Coordinate;

/**
 * Flat object table for the simulator's cyclic graph. File input can only select a numbered
 * model type, reference an existing table entry, or supply bounded scalar/collection data.
 * Field access is checked by the VM. No classes are looked up by names supplied by the file.
 */
final class EconomyModelCodec {
    private static final int MAX_OBJECTS = 150_000;
    private static final int MAX_ENTRIES = 1_000_000;
    private static final int MAX_STRING_CHARS = 1_000_000;

    // Wire IDs. Append new types, never reorder these entries.
    private static final List<Class<?>> TYPES = Arrays.asList(
            Simulator.class, Access.class, Config.class, henize.proto.lib.map.Map.class,
            Coordinate.class, MapDataPair.class, Traveler.class, Directory.class, DirectoryList.class,
            Bank.class, Account.class, BankStats.class, ComStats.class, TraderStats.class, Graph.class,
            Trader.class, LaborTrader.class, FoodTrader.class, ToolTrader.class, CoalTrader.class,
            IronTrader.class, TransportationService.class, MasterTrader.class,
            masterIndustry(1), masterIndustry(2), masterIndustry(3), masterIndustry(4),
            SpecialOrder.class, QTP.class, TSPair.class, Commodity.class, Labor.class, Food.class,
            Tool.class, Coal.class, Iron.class, Transportation.class, ComType.class, Account.TaxCode.class,
            ArrayList.class, Stack.class, byte[][].class, byte[].class, boolean[][].class,
            boolean[].class, Trader[].class, Coordinate[].class, String.class, Boolean.class, Random.class);

    private static final Map<Class<?>, Field[]> FIELDS = new HashMap<>();
    // Reject a changed field layout before allocating anything. Retain this decoder and
    // version its layout explicitly when adding a model migration to the file format.
    private static final long SCHEMA;

    static {
        CRC32 schema = new CRC32();
        for (Class<?> type : TYPES) {
            updateSchema(schema, type.getName());
            if (!isModel(type)) continue;
            List<Field> fields = new ArrayList<>();
            for (Class<?> parent = type; parent != Object.class; parent = parent.getSuperclass()) {
                for (Field field : parent.getDeclaredFields()) {
                    int modifiers = field.getModifiers();
                    if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers)) continue;
                    field.setAccessible(true);
                    fields.add(field); // Include enclosing simulator/trader references in anonymous classes.
                }
            }
            Collections.sort(fields, (a, b) -> {
                int order = a.getDeclaringClass().getName().compareTo(b.getDeclaringClass().getName());
                return order != 0 ? order : a.getName().compareTo(b.getName());
            });
            FIELDS.put(type, fields.toArray(new Field[0]));
            for (Field field : fields) {
                updateSchema(schema, field.getName());
                updateSchema(schema, field.getType().getName());
            }
        }
        SCHEMA = schema.getValue();
    }

    private static void updateSchema(CRC32 checksum, String name) {
        for (int i = 0; i < name.length(); i++) checksum.update(name.charAt(i));
        checksum.update(0);
    }

    private static Class<?> masterIndustry(int index) {
        try {
            return Class.forName(MasterTrader.class.getName() + "$" + index);
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }

    private static boolean isModel(Class<?> type) {
        return !type.isEnum() && type.getName().startsWith("henize.proto.");
    }

    private static int length(Object value) {
        if (value instanceof List) return ((List<?>) value).size();
        return value.getClass().isArray() ? Array.getLength(value) : 0;
    }

    private static Object element(Object value, int index) {
        return value instanceof List ? ((List<?>) value).get(index) : Array.get(value, index);
    }

    private static class Table {
        final List<Object> objects = new ArrayList<>();
        final IdentityHashMap<Object, Integer> ids = new IdentityHashMap<>();

        void add(Object value) throws IOException {
            if (value == null || ids.containsKey(value)) return;
            require(objects.size() < MAX_OBJECTS && TYPES.contains(value.getClass()));
            objects.add(value);
            ids.put(value, objects.size()); // Zero is null.
        }

        int ref(Object value, Object location) throws IOException {
            if (value == null) return 0;
            Integer id = ids.get(value);
            if (id == null) throw new IOException("Economy changed while saving " + location);
            return id;
        }

        Table(Simulator sim) throws IOException, IllegalAccessException {
            add(sim);
            long entries = 0;
            long chars = 0;
            for (int i = 0; i < objects.size(); i++) {
                Object value = objects.get(i);
                Class<?> type = value.getClass();
                entries += length(value);
                if (value instanceof String) chars += ((String) value).length();
                require(entries <= MAX_ENTRIES && chars <= MAX_STRING_CHARS);
                if (FIELDS.containsKey(type)) {
                    for (Field field : FIELDS.get(type)) {
                        if (!field.getType().isPrimitive()) add(field.get(value));
                    }
                } else if (value instanceof List || value instanceof Object[]) {
                    for (int j = 0; j < length(value); j++) add(element(value, j));
                }
            }
        }
    }

    static void write(Simulator sim, DataOutputStream out) throws IOException {
        try {
            Table table = new Table(sim);
            // Do not replace a good checkpoint with one the reader would reject.
            validate(sim, table.objects);
            out.writeLong(SCHEMA);
            out.writeInt(table.objects.size());
            for (Object value : table.objects) {
                Class<?> type = value.getClass();
                out.writeByte(TYPES.indexOf(type));
                if (type.isArray() || value instanceof List) out.writeInt(length(value));
                else if (value instanceof String) out.writeUTF((String) value);
                else if (value instanceof Boolean) out.writeBoolean((Boolean) value);
                else if (type.isEnum()) out.writeUTF(((Enum<?>) value).name());
                else if (value instanceof Random) RandomState.write((Random) value, out);
            }
            for (Object value : table.objects) {
                if (FIELDS.containsKey(value.getClass())) {
                    for (Field f : FIELDS.get(value.getClass())) {
                        writeField(f, value, table, out);
                    }
                } else if (value instanceof byte[]) out.write((byte[]) value);
                else if (value instanceof boolean[]) {
                    for (boolean flag : (boolean[]) value) out.writeBoolean(flag);
                } else {
                    for (int i = 0; i < length(value); i++) {
                        out.writeInt(table.ref(element(value, i), value.getClass()));
                    }
                }
            }
        } catch (IllegalAccessException | RuntimeException e) {
            throw new IOException("Could not encode the economy", e);
        }
    }

    private static void writeField(Field f, Object bean, Table table, DataOutput out)
            throws IllegalAccessException, IOException {
        Class<?> type = f.getType();
        if (!type.isPrimitive()) out.writeInt(table.ref(f.get(bean), f));
        else if (type == boolean.class) out.writeBoolean(f.getBoolean(bean));
        else if (type == byte.class) out.writeByte(f.getByte(bean));
        else if (type == short.class) out.writeShort(f.getShort(bean));
        else if (type == int.class) out.writeInt(f.getInt(bean));
        else if (type == long.class) out.writeLong(f.getLong(bean));
        else if (type == double.class) out.writeDouble(f.getDouble(bean));
        else throw new IOException("Unknown scalar type");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static Simulator read(DataInputStream in) throws IOException {
        try {
            require(in.readLong() == SCHEMA);
            int count = in.readInt();
            require(count > 0 && count <= MAX_OBJECTS);
            Object[] objects = new Object[count + 1];
            int[] lengths = new int[objects.length];
            long entries = 0;
            long chars = 0;
            boolean hasRandom = false;
            ObjenesisStd allocator = new ObjenesisStd();
            for (int i = 1; i <= count; i++) {
                int id = in.readUnsignedByte();
                require(id < TYPES.size());
                Class<?> type = TYPES.get(id);
                require(i != 1 || type == Simulator.class);
                if (type.isArray() || List.class.isAssignableFrom(type)) {
                    int size = in.readInt();
                    require(size >= 0 && (entries += size) <= MAX_ENTRIES);
                    lengths[i] = size;
                    if (type.isArray()) objects[i] = Array.newInstance(type.getComponentType(), size);
                    else objects[i] = type == Stack.class ? new Stack<>() : new ArrayList<>();
                } else if (type == String.class) {
                    String text = in.readUTF();
                    require((chars += text.length()) <= MAX_STRING_CHARS);
                    objects[i] = text;
                } else if (type == Boolean.class) objects[i] = in.readBoolean();
                else if (type.isEnum()) {
                    // writeUTF uses a two-byte length and plain ASCII for these enum names.
                    // Bound it separately so repeated enum records cannot expand a tiny input
                    // into arbitrarily many discarded string bytes.
                    int size = in.readUnsignedShort();
                    require(size > 0 && size <= 16);
                    StringBuilder name = new StringBuilder(size);
                    for (int j = 0; j < size; j++) name.append((char) in.readUnsignedByte());
                    objects[i] = Enum.valueOf((Class) type, name.toString());
                } else if (type == Random.class) {
                    require(!hasRandom);
                    objects[i] = RandomState.read(in);
                    hasRandom = true;
                } else objects[i] = allocator.newInstance(type);
            }
            for (int i = 1; i <= count; i++) {
                Object value = objects[i];
                if (FIELDS.containsKey(value.getClass())) {
                    for (Field field : FIELDS.get(value.getClass())) readField(field, value, objects, in);
                } else if (value instanceof byte[]) in.readFully((byte[]) value);
                else if (value instanceof boolean[]) {
                    boolean[] flags = (boolean[]) value;
                    for (int j = 0; j < flags.length; j++) flags[j] = in.readBoolean();
                } else {
                    for (int j = 0; j < lengths[i]; j++) {
                        Object item = readRef(objects, in);
                        if (value instanceof List) ((List) value).add(item);
                        else Array.set(value, j, item);
                    }
                }
            }
            Simulator sim = (Simulator) objects[1];
            validate(sim, Arrays.asList(objects));
            return sim;
        } catch (IllegalAccessException | RuntimeException e) {
            throw new IOException("Invalid economy data", e);
        }
    }

    private static Object readRef(Object[] objects, DataInput in) throws IOException {
        int id = in.readInt();
        require(id >= 0 && id < objects.length);
        return objects[id];
    }

    private static void readField(Field f, Object bean, Object[] objects, DataInput in)
            throws IllegalAccessException, IOException {
        Class<?> type = f.getType();
        if (!type.isPrimitive()) f.set(bean, readRef(objects, in));
        else if (type == boolean.class) f.setBoolean(bean, in.readBoolean());
        else if (type == byte.class) f.setByte(bean, in.readByte());
        else if (type == short.class) f.setShort(bean, in.readShort());
        else if (type == int.class) f.setInt(bean, in.readInt());
        else if (type == long.class) f.setLong(bean, in.readLong());
        else if (type == double.class) f.setDouble(bean, in.readDouble());
        else throw new IOException("Unknown scalar type");
    }

    private static void validate(Simulator sim, Iterable<?> objects) throws IOException, IllegalAccessException {
        Access access = sim.access;
        require(access != null && access.sim == sim && access.config != null && sim.cfg != null
                && access.dir != null && access.bank != null && access.bank.account != null
                && access.master != null && access.taxAccount != null && access.bankStats != null
                && access.traderStats != null && access.comStats != null && sim.graph != null
                && sim.map != null && sim.specialPriority != null);
        Config config = access.config;
        int size = config.MAP_SIZE;
        require(size > 0 && size <= Byte.MAX_VALUE && sim.map.width == size && sim.map.height == size
                && config.DEF_FRAME_X > 0 && config.DEF_FRAME_X <= size
                && config.DEF_FRAME_Y > 0 && config.DEF_FRAME_Y <= size && config.TEXT_SIZE_DIV > 0
                && config.TEXT_SIZE_DIV_LIMITED_HEIGHT > 0 && config.TEXT_SIZE_DIV_LIMITED_HEIGHT_FULL_SCREEN > 0);
        for (Config settings : new Config[]{config, sim.cfg}) {
            require(settings.MAP_SIZE == size && settings.FIX_PRICE > 0 && settings.FIX_PRICE_SUPPLYDEMAND > 0
                    && settings.OFFSET_DIV > 0 && settings.REQ_LAB_FOR_TOOL > 0
                    && settings.REQ_LAB_FOR_TOOL <= MAX_ENTRIES && settings.LAB_SPAWN_RATE >= 2
                    && settings.LAB_SPAWN_RATE <= MAX_ENTRIES && settings.INIT_LABOR >= 0
                    && settings.INIT_LABOR <= MAX_OBJECTS && settings.INIT_LABOR_POP >= 0
                    && (long) settings.INIT_LABOR * (settings.INIT_LABOR_POP + 1L) <= MAX_OBJECTS
                    && settings.INIT_LABOR_POP < 1000 && settings.TOOL_POP > 0 && settings.TOOL_POP < 1000
                    && settings.IRON_POP > 0 && settings.IRON_POP < 1000 && settings.COAL_POP > 0
                    && settings.COAL_POP < 1000 && settings.FOOD_POP > 0);
        }
        require(sim.map.update != null && sim.map.update.length == size);
        for (boolean[] row : sim.map.update) require(row != null && row.length == size);
        Field tiles = field(henize.proto.lib.map.Map.class, "map");
        byte[][] cells = (byte[][]) tiles.get(sim.map);
        require(cells != null && cells.length == size);
        for (byte[] row : cells) require(row != null && row.length == size);
        require(field(henize.proto.lib.map.Map.class, "rnd").get(sim.map) instanceof Random);
        require(sim.map.stationary_list != null && sim.map.stationary_list.size() <= size * size
                && sim.map.unwalkable != null && sim.map.unwalkable.size() <= size * size);
        for (ComType type : ComType.values()) {
            DirectoryList list = access.dir.getListFromType(type);
            require(list != null && list.getCount() > 0 && list.getCount() <= 1000);
            for (int i = 0; i < list.getCount(); i++) require(list.getAtIndex(i) != null);
        }
        require(access.master.foodTrader != null && access.master.toolTrader != null
                && access.master.coalTrader != null && access.master.ironTrader != null);
        // A typed field can reference a List through erasure. Check its items once per type.
        IdentityHashMap<List<?>, Set<Class<?>>> checked = new IdentityHashMap<>();
        for (Object bean : objects) {
            if (bean == null) continue;
            if (bean instanceof Coordinate) {
                Coordinate point = (Coordinate) bean;
                require(point.x >= 0 && point.x < size && point.y >= 0 && point.y < size);
            } else if (bean instanceof Trader) {
                Trader trader = (Trader) bean;
                require(trader.account != null && trader.address != null && trader.id != null);
            } else if (bean instanceof MapDataPair) {
                require(((MapDataPair) bean).address != null);
            }
            Field[] fields = FIELDS.get(bean.getClass());
            if (fields == null) continue;
            for (Field f : fields) {
                if (f.getType() == Access.class) require(f.get(bean) == access);
                if (f.getType() == henize.proto.lib.map.Map.class) require(f.get(bean) == sim.map);
                if (!List.class.isAssignableFrom(f.getType())) continue;
                List<?> list = (List<?>) f.get(bean);
                require(list != null);
                Type type = ((ParameterizedType) f.getGenericType()).getActualTypeArguments()[0];
                Class<?> itemType = (Class<?>) type;
                Set<Class<?>> types = checked.get(list);
                if (types == null) {
                    types = new HashSet<>();
                    checked.put(list, types);
                }
                if (types.add(itemType)) {
                    // The bank opens an account before the simulator listener is installed.
                    // Its listener list legitimately contains null in existing economies.
                    boolean nullable = f.getDeclaringClass() == Account.class;
                    for (Object item : list) require((nullable && item == null) || itemType.isInstance(item));
                }
            }
        }
    }

    private static Field field(Class<?> type, String name) throws IOException {
        for (Field field : FIELDS.get(type)) if (field.getName().equals(name)) return field;
        throw new IOException("Missing model field");
    }

    private static void require(boolean condition) throws IOException {
        if (!condition) throw new IOException("Invalid or unsupported economy data");
    }

    /**
     * Random's public serialization contract exposes exactly these three primitive fields. Copy
     * them using a locally generated stream so no platform field access or external object stream
     * is needed. The map continues from the original random sequence on the destination.
     */
    private static class RandomState implements Serializable {
        private boolean haveNextNextGaussian;
        private double nextNextGaussian;
        private long seed;

        private void writeObject(ObjectOutputStream out) throws IOException {
            out.defaultWriteObject();
        }

        static void write(Random random, DataOutput out) throws IOException {
            RandomState state = (RandomState) convert(random, RandomState.class);
            out.writeBoolean(state.haveNextNextGaussian);
            out.writeDouble(state.nextNextGaussian);
            out.writeLong(state.seed);
        }

        static Random read(DataInput in) throws IOException {
            RandomState state = new RandomState();
            state.haveNextNextGaussian = in.readBoolean();
            state.nextNextGaussian = in.readDouble();
            state.seed = in.readLong();
            require(state.seed >= 0 && state.seed < (1L << 48));
            return (Random) convert(state, Random.class);
        }

        private static Object convert(Object source, final Class<?> target) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(128);
            try (ObjectOutputStream writer = new ObjectOutputStream(bytes)) {
                writer.writeObject(source);
            }
            try (ObjectInputStream reader = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray())) {
                @Override protected ObjectStreamClass readClassDescriptor() throws IOException, ClassNotFoundException {
                    super.readClassDescriptor();
                    return ObjectStreamClass.lookup(target);
                }
            }) {
                return reader.readObject();
            } catch (ClassNotFoundException e) {
                throw new IOException(e);
            }
        }
    }

    private EconomyModelCodec() {}
}
