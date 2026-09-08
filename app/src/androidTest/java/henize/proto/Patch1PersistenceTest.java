/* Proto - Economy Simulator clone regression tests
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package henize.proto;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.lang.reflect.Field;
import java.text.DecimalFormat;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import henize.proto.lib.bank.Account;
import henize.proto.lib.sim.Simulator;
import henize.proto.lib.sim.Config;
import henize.proto.lib.stats.Graph;
import henize.proto.lib.stats.GraphData;
import henize.proto.lib.traders.ComType;

/** Exercises the app's actual persistence entry points on the Android runtime. */
public class Patch1PersistenceTest extends InstrumentationTestCase {
    private static final String CLONE_PACKAGE = "local.proto.clone";
    private static final String GRAPH_FILE = "henize.proto.graph";
    private static final long TIMEOUT_MS = 15000;

    private Context context;
    private MainActivity activity;
    private String originalStateFile;
    private String testStateFile;
    private String namedSlot;
    private String preferenceFile;
    private byte[] originalGraph;
    private Map<String, ?> originalPreferences;
    private boolean isolated;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        context = getInstrumentation().getTargetContext();
        // Check this before launching an activity or changing any private files.
        assertEquals("These tests may run only against the separate clone", CLONE_PACKAGE,
                context.getPackageName());
        assertTrue("Lifecycle tests require Activity.isDestroyed() (API 17+)",
                Build.VERSION.SDK_INT >= 17);
        assertEquals("Patch 1 must use the installed release's state identifier",
                "henize.proto.state53", App.stateFile);
        originalStateFile = App.stateFile;
        originalGraph = readOptionalFile(GRAPH_FILE);
        originalPreferences = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
                .getAll();
        String suffix = Long.toHexString(System.nanoTime());
        testStateFile = "patch1-test-default-" + suffix;
        namedSlot = "patch1-test-named-" + suffix;
        preferenceFile = "patch1-test-preferences-" + suffix;
        App.stateFile = testStateFile;
        isolated = true;
        launchActivity();
    }

    public void testNamedSaveRestoresEconomyAndGraphHistory() throws Exception {
        populateSnapshot(123456L, 0.37, 0.08);
        final Simulator savedInstance = App.sim;
        final long savedCurrency = App.sim.access.bankStats.totalCurrency;
        runOnMain(new Runnable() {
            @Override public void run() {
                activity.serialize(namedSlot);
            }
        });
        assertTrue("Named state must exist on disk", file(namedSlot).length() > 0);
        assertEquals("Two six-value graph records must be persisted", 96L,
                file(GRAPH_FILE).length());

        runOnMain(new Runnable() {
            @Override public void run() {
                App.sim.access.taxAccount.__edit(999L);
                App.sim.access.bank.reserveRate = 0.99;
                App.sim.access.config.collectTax = false;
                App.sim.graph.virt_days.clear();
                activity.deserialize(namedSlot);
            }
        });
        awaitGraphLoad();
        assertNotSame("Loading must replace the mutated simulation", savedInstance, App.sim);
        assertSnapshot(123456L, 0.37, 0.08);
        assertEquals(savedCurrency, App.sim.access.bankStats.totalCurrency);
        assertSame("Bank and traders must share the restored simulation graph",
                App.sim, App.sim.access.sim);
        assertSame(App.sim.access.taxAccount, App.sim.access.master.account);
        assertNotNull("Transient statistics must be rebuilt", App.sim.textStats);
        assertNotNull(App.sim.advTextStats);
        assertNull("Patch 1 excludes the runtime formatter from saved state",
                graphField("df").get(App.sim.graph));
    }

    public void testFinishAndRelaunchRestoresDefaultSnapshot() throws Exception {
        populateSnapshot(765432L, 0.42, 0.06);
        final Simulator savedInstance = App.sim;
        finishActivity(); // Real onPause performs the automatic default-slot save.
        assertTrue("Lifecycle must create the default state file", file(testStateFile).length() > 0);
        App.sim = null; // A successful assertion cannot come from the old in-memory instance.
        launchActivity(); // Real onResume loads the automatic state.
        awaitGraphLoad();
        assertNotSame(savedInstance, App.sim);
        assertSnapshot(765432L, 0.42, 0.06);
        assertTrue("Relaunch must remain paused", activity.simulator.simPaused);
    }

    public void testPreferencesPersistAcrossInstances() {
        SharedPreferences preferences = context.getSharedPreferences(preferenceFile,
                Context.MODE_PRIVATE);
        StoredPreferences first = new StoredPreferences(preferences, R.id.info_item, true);
        assertEquals(R.id.info_item, first.getView());
        first.setView(R.id.graph_item).setUnlocked(false);
        StoredPreferences second = new StoredPreferences(context.getSharedPreferences(
                preferenceFile, Context.MODE_PRIVATE), R.id.map_item, true);
        assertEquals("The stored view must override a new wrapper's default",
                R.id.graph_item, second.getView());
        assertFalse("The unlock setter must commit its backing preference",
                preferences.getBoolean("UNLOCKED", true));
        // The installed version's getUnlocked() returns its constructor default.
        // Test persistence without silently changing that existing behavior.
        assertTrue(second.getUnlocked());
    }

    public void testAllBundledPresetsInitializeTheirEconomies() throws Exception {
        // Resource names resolve on the installed target, so this also verifies the
        // unchanged-payload clone when its original numeric resource IDs differ.
        assertPreset("classic", 20, 19, 3, 2, 2, 2, false);
        assertPreset("big", 40, 80, 4, 3, 2, 2, true);
        assertPreset("big2", 40, 30, 4, 2, 2, 3, true);
    }

    private void assertPreset(String name, final int size, final int labor,
            final int food, final int tools, final int coal, final int iron,
            final boolean fullScreen) throws Exception {
        String resourcePackage = context.getResources().getResourcePackageName(
                context.getApplicationInfo().icon);
        final int resource = context.getResources().getIdentifier(name, "raw", resourcePackage);
        assertTrue("Missing bundled preset " + name, resource != 0);
        final Config config;
        final String map;
        try (ObjectInputStream input = new ObjectInputStream(
                context.getResources().openRawResource(resource))) {
            config = (Config) input.readObject();
            map = (String) input.readObject();
        }
        assertEquals(size, config.MAP_SIZE);
        assertEquals(10000L, config.INIT_CASH);
        assertEquals(10, config.TRANSPORT_MAX);
        assertEquals(70, config.MAX_LABOR);
        assertEquals(fullScreen, config.FULL_SCREEN);
        assertEquals(size * size + size - 1, map.length());
        String[] rows = map.split(" ");
        assertEquals(size, rows.length);
        for (String row : rows) assertEquals(size, row.length());

        final Simulator previous = App.sim;
        runOnMain(new Runnable() {
            @Override public void run() {
                // Use the same entry point as the Economies screen.
                App.initSimWithFile(resource);
                activity.initUIFromState();
            }
        });
        assertNotSame("Preset loading must not silently retain the preceding economy",
                previous, App.sim);
        assertEquals(size, App.sim.access.config.MAP_SIZE);
        assertEquals(labor + 1, App.sim.access.dir.getListFromType(ComType.LABOR).getList().length);
        assertEquals("Industry registry also includes the sovereign trader", food + 1, App.sim.access.dir.getListFromType(ComType.FOOD).getList().length);
        assertEquals("Industry registry also includes the sovereign trader", tools + 1, App.sim.access.dir.getListFromType(ComType.TOOL).getList().length);
        assertEquals("Industry registry also includes the sovereign trader", coal + 1, App.sim.access.dir.getListFromType(ComType.COAL).getList().length);
        assertEquals("Industry registry also includes the sovereign trader", iron + 1, App.sim.access.dir.getListFromType(ComType.IRON).getList().length);
        assertEquals(1, App.sim.access.dir.getListFromType(ComType.TRANSPORTATION).getList().length);
        assertEquals(10000L, App.sim.access.bankStats.totalCurrency);
        runOnMain(new Runnable() {
            @Override public void run() {
                activity.serialize(namedSlot);
            }
        });
        try (ObjectInputStream input = new ObjectInputStream(context.openFileInput(namedSlot))) {
            Simulator restored = (Simulator) input.readObject();
            assertEquals(size, restored.access.config.MAP_SIZE);
            assertEquals(labor + 1, restored.access.dir.getListFromType(ComType.LABOR).getList().length);
            assertEquals(10000L, restored.access.bankStats.totalCurrency);
        }
    }

    private void populateSnapshot(final long amount, final double reserve,
            final double interest) throws Exception {
        runOnMain(new Runnable() {
            @Override public void run() {
                Account createdCurrency = new Account(App.sim.access, amount);
                App.sim.access.taxAccount.__transfer(createdCurrency, amount);
                App.sim.access.bank.reserveRate = reserve;
                App.sim.access.bank.interestRate = interest;
                App.sim.access.config.collectTax = true;
                App.sim.access.config.universalTaxRate = 0.13;
                App.sim.graph.virt_days.clear();
                App.sim.graph.addPoint(new GraphData(101, 202, 303, 404, 505, 606));
                App.sim.graph.addPoint(new GraphData(111, 222, 333, 444, 555, 666));
            }
        });
        // A formatter exists after viewing a loading graph. Saving that real runtime
        // state used to serialize the formatter; Patch 1 makes it transient.
        graphField("df").set(App.sim.graph, new DecimalFormat("0"));
    }

    private void assertSnapshot(long amount, double reserve, double interest) {
        assertEquals("Player cash must restore", amount, App.sim.access.taxAccount.getBalance());
        assertEquals(reserve, App.sim.access.bank.reserveRate, 0.0);
        assertEquals(interest, App.sim.access.bank.interestRate, 0.0);
        assertTrue(App.sim.access.config.collectTax);
        assertEquals(0.13, App.sim.access.config.universalTaxRate, 0.0);
        assertEquals("Graph history must restore from its persisted records", 2,
                App.sim.graph.virt_days.size());
        GraphData first = App.sim.graph.virt_days.get(0);
        assertEquals(101L, first.labor);
        assertEquals(202L, first.food);
        assertEquals(303L, first.tool);
        assertEquals(404L, first.coal);
        assertEquals(505L, first.iron);
        assertEquals(606L, first.transport);
        assertEquals(666L, App.sim.graph.virt_days.get(1).transport);
    }

    private void launchActivity() throws Exception {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setClassName(CLONE_PACKAGE, MainActivity.class.getName());
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity = (MainActivity) getInstrumentation().startActivitySync(intent);
        getInstrumentation().waitForIdleSync();
        awaitBootstrap();
        runOnMain(new Runnable() {
            @Override public void run() {
                activity.simulator.simPaused = true;
                activity.stop();
            }
        });
        awaitGraphLoad();
    }

    private void finishActivity() throws Exception {
        if (activity == null) return;
        final MainActivity finishingActivity = activity;
        runOnMain(new Runnable() {
            @Override public void run() {
                activity.finish();
            }
        });
        long deadline = SystemClock.uptimeMillis() + TIMEOUT_MS;
        while (!finishingActivity.isDestroyed()) {
            assertTrue("Activity destruction did not finish", SystemClock.uptimeMillis() < deadline);
            getInstrumentation().waitForIdleSync();
            SystemClock.sleep(10);
        }
        awaitBootstrap();
        activity = null;
    }

    private void awaitBootstrap() {
        long deadline = SystemClock.uptimeMillis() + TIMEOUT_MS;
        while (App.loading || App.saving) {
            assertTrue("Bootstrap did not finish", SystemClock.uptimeMillis() < deadline);
            SystemClock.sleep(10);
        }
        getInstrumentation().waitForIdleSync();
    }

    private void awaitGraphLoad() throws Exception {
        if (App.sim == null || App.sim.graph == null) return;
        Thread loader = (Thread) graphField("_thread").get(App.sim.graph);
        if (loader != null) {
            loader.join(TIMEOUT_MS);
            assertFalse("Graph history loader did not finish", loader.isAlive());
        }
    }

    private static Field graphField(String name) throws Exception {
        Field field = Graph.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private void runOnMain(Runnable action) {
        getInstrumentation().runOnMainSync(action);
    }

    private File file(String name) {
        return new File(context.getFilesDir(), name);
    }

    private byte[] readOptionalFile(String name) throws Exception {
        if (!file(name).exists()) return null;
        try (FileInputStream input = context.openFileInput(name);
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            if (isolated) {
                finishActivity();
                awaitGraphLoad();
                context.deleteFile(testStateFile);
                context.deleteFile(namedSlot);
                if (originalGraph == null) {
                    context.deleteFile(GRAPH_FILE);
                } else {
                    try (FileOutputStream output = context.openFileOutput(GRAPH_FILE,
                            Context.MODE_PRIVATE)) {
                        output.write(originalGraph);
                    }
                }
                SharedPreferences.Editor editor = context.getSharedPreferences("MainActivity",
                        Context.MODE_PRIVATE).edit().clear();
                for (Map.Entry<String, ?> entry : originalPreferences.entrySet()) {
                    Object value = entry.getValue();
                    if (value instanceof Integer) editor.putInt(entry.getKey(), (Integer) value);
                    else if (value instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) value);
                    else if (value instanceof Long) editor.putLong(entry.getKey(), (Long) value);
                    else if (value instanceof Float) editor.putFloat(entry.getKey(), (Float) value);
                    else if (value instanceof String) editor.putString(entry.getKey(), (String) value);
                    else if (value instanceof Set) {
                        Set<String> values = new HashSet<>();
                        for (Object item : (Set<?>) value) values.add((String) item);
                        editor.putStringSet(entry.getKey(), values);
                    }
                }
                assertTrue("Activity preferences must be restored", editor.commit());
                context.getSharedPreferences(preferenceFile, Context.MODE_PRIVATE)
                        .edit().clear().commit();
            }
        } finally {
            if (originalStateFile != null) App.stateFile = originalStateFile;
            super.tearDown();
        }
    }
}
