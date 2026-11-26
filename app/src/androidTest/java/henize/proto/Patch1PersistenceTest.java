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
import android.util.Base64;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Field;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.text.DecimalFormat;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
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

        runOnMain(new Runnable() {
            @Override public void run() {
                App.sim.access.taxAccount.__edit(999L);
                App.sim.access.bank.reserveRate = 0.99;
                App.sim.access.config.collectTax = false;
                App.sim.graph.virt_days.clear();
                App.sim.graph.addPoint(new GraphData(1, 2, 3, 4, 5, 6));
                // Another slot saved later must not replace the named slot's history.
                activity.serialize(testStateFile);
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

        runOnMain(() -> activity.deserialize(testStateFile));
        awaitGraphLoad();
        assertEquals(1, App.sim.graph.virt_days.size());
        assertEquals(6L, App.sim.graph.virt_days.get(0).transport);
    }

    public void testLegacyLocalEconomyReadsSharedGraph() throws Exception {
        populateSnapshot(827L, 0.22, -0.17);
        try (ObjectOutputStream state = new ObjectOutputStream(context.openFileOutput(
                namedSlot, Context.MODE_PRIVATE));
             DataOutputStream graph = new DataOutputStream(context.openFileOutput(
                     GRAPH_FILE, Context.MODE_PRIVATE))) {
            state.writeObject(App.sim);
            for (GraphData point : App.sim.graph.virt_days) {
                graph.writeLong(point.labor);
                graph.writeLong(point.food);
                graph.writeLong(point.tool);
                graph.writeLong(point.coal);
                graph.writeLong(point.iron);
                graph.writeLong(point.transport);
            }
        }
        Simulator before = App.sim;
        Simulator legacy = LocalEconomyStore.loadLegacy(context, namedSlot);
        runOnMain(() -> activity.applyEconomy(legacy));
        awaitGraphLoad();
        assertNotSame(before, App.sim);
        assertSnapshot(827L, 0.22, -0.17);
    }

    public void testEditedAndReplacedPrivateSaveCannotReplaceRunningEconomy() throws Exception {
        populateSnapshot(1057L, 0.21, -0.09);
        runOnMain(() -> activity.serialize(namedSlot));
        byte[] original = readOptionalFile(namedSlot);
        assertNotNull(original);
        Simulator active = App.sim;
        runOnMain(() -> activity.serialize(testStateFile));
        byte[] otherSlot = readOptionalFile(testStateFile);
        assertNotNull(otherSlot);
        try (FileOutputStream out = context.openFileOutput(namedSlot, Context.MODE_PRIVATE)) {
            out.write(otherSlot);
        }
        runOnMain(() -> activity.deserialize(namedSlot));
        assertSame("A private economy is bound to its saved slot", active, App.sim);
        for (int offset : new int[] { 0, original.length / 2, original.length - 1 }) {
            byte[] altered = original.clone();
            altered[offset] ^= 1;
            try (FileOutputStream out = context.openFileOutput(namedSlot, Context.MODE_PRIVATE)) {
                out.write(altered);
            }
            runOnMain(() -> activity.deserialize(namedSlot));
            assertSame(active, App.sim);
        }
        try (ObjectOutputStream output = new ObjectOutputStream(context.openFileOutput(
                namedSlot, Context.MODE_PRIVATE))) {
            output.writeObject(active);
        }
        runOnMain(() -> activity.deserialize(namedSlot));
        assertSame("A protected slot must refuse a substituted plaintext save", active, App.sim);
        assertSnapshot(1057L, 0.21, -0.09);
    }

    public void testPortableEconomyCanResumeWithoutLocalFiles() throws Exception {
        runOnMain(() -> {
            for (int i = 0; i < 12; i++) {
                App.sim.Next();
                App.sim.map.Tick();
            }
            App.sim.interrupt(); // Wait for route calculations as the real pause path does.
        });
        populateSnapshot(21346L, -0.14, -0.07);
        Simulator source = App.sim;
        long savedCash = source.access.taxAccount.getBalance();
        int cycle = source.access.bank.cycle;
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        String key = EconomyArchive.write(source, file);
        context.deleteFile(GRAPH_FILE);

        Simulator restored = EconomyArchive.read(new ByteArrayInputStream(file.toByteArray()), key);
        assertNotSame(source, restored);
        assertSame("Reading a file must not replace the active economy", source, App.sim);
        assertSame(restored, restored.access.sim);
        assertSame(restored.access.taxAccount, restored.access.master.foodTrader.account);
        assertEquals(savedCash, restored.access.taxAccount.getBalance());
        assertEquals(-0.14, restored.access.bank.reserveRate, 0.0);
        assertEquals(-0.07, restored.access.bank.interestRate, 0.0);
        assertEquals(source.access.dir.getAll().length, restored.access.dir.getAll().length);
        assertEquals(source.map.traveler_list.size(), restored.map.traveler_list.size());
        Field randomField = source.map.getClass().getDeclaredField("rnd");
        randomField.setAccessible(true);
        assertEquals(((Random) randomField.get(source.map)).nextLong(),
                ((Random) randomField.get(restored.map)).nextLong());
        assertEquals(2, restored.graph.virt_days.size());
        assertEquals(101L, restored.graph.virt_days.get(0).labor);
        assertEquals(666L, restored.graph.virt_days.get(1).transport);
        assertEquals(cycle, restored.access.bank.cycle);
        restored.Next();
        restored.map.Tick();
        restored.interrupt();
        assertEquals((cycle + 1) % 11, restored.access.bank.cycle);
        assertEquals(cycle, source.access.bank.cycle);
    }

    public void testPortableImportAuthenticatesWholeArchiveBeforeUse() throws Exception {
        populateSnapshot(663L, 0.12, -0.2);
        Simulator before = App.sim;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        String key = EconomyArchive.write(before, output);
        byte[] bytes = output.toByteArray();
        ByteArrayOutputStream secondExport = new ByteArrayOutputStream();
        String other = EconomyArchive.write(before, secondExport);
        assertFalse(key.equals(other));
        assertFalse(Arrays.equals(bytes, secondExport.toByteArray()));
        assertArchiveRejected(bytes, other);
        assertArchiveRejected(bytes, "");
        assertArchiveRejected(Arrays.copyOf(bytes, bytes.length - 1), key);
        assertArchiveRejected(Arrays.copyOf(bytes, bytes.length + 1), key);
        for (int offset : new int[] { 0, bytes.length / 2, bytes.length - 1 }) {
            byte[] corrupted = bytes.clone();
            corrupted[offset] ^= 1;
            assertArchiveRejected(corrupted, key);
        }
        // Even a matching file fingerprint cannot substitute for a valid GCM tag.
        byte[] alteredTag = bytes.clone();
        alteredTag[alteredTag.length - 1] ^= 1;
        int flags = Base64.NO_PADDING | Base64.NO_WRAP | Base64.URL_SAFE;
        byte[] code = Base64.decode(key, flags);
        byte[] fingerprint = MessageDigest.getInstance("SHA-256").digest(alteredTag);
        System.arraycopy(fingerprint, 0, code, code.length - fingerprint.length, fingerprint.length);
        try {
            EconomyArchive.read(new ByteArrayInputStream(alteredTag), Base64.encodeToString(code, flags));
            fail("Modified GCM tag was accepted");
        } catch (IOException rejected) {
            assertTrue("AES-GCM must reject the bytes after fingerprint verification",
                    rejected.getCause() instanceof GeneralSecurityException);
        }
        ByteArrayOutputStream plaintext = new ByteArrayOutputStream();
        EconomySnapshot.write(before, plaintext);
        try (DataInputStream header = new DataInputStream(
                new ByteArrayInputStream(plaintext.toByteArray()))) {
            assertEquals(0x50524f54, header.readInt());
            assertEquals(1, header.readInt());
            assertEquals("Version 1 economy schema must remain readable", 0x03cb643dL, header.readLong());
        }
        assertArchiveRejected(plaintext.toByteArray(), key);
        assertSame(before, App.sim);
        assertSnapshot(663L, 0.12, -0.2);
    }

    private static void assertArchiveRejected(byte[] bytes, String key) throws Exception {
        try {
            EconomyArchive.read(new ByteArrayInputStream(bytes), key);
            fail("Invalid archive or transfer key was accepted");
        } catch (IOException expected) {
            // The UI handles this error without installing the candidate.
        }
    }

    public void testFinishAndRelaunchRestoresDefaultSnapshot() throws Exception {
        populateSnapshot(765432L, 0.42, 0.06);
        final Simulator savedInstance = App.sim;
        finishActivity(); // Real onPause performs the automatic default-slot save.
        assertTrue("Lifecycle must create the default state file", file(testStateFile).length() > 0);
        launchActivity(); // Real onResume loads the automatic state.
        awaitGraphLoad();
        assertNotSame(savedInstance, App.sim);
        assertSnapshot(765432L, 0.42, 0.06);
        assertTrue("Relaunch must remain paused", activity.simulator.simPaused);
    }

    public void testBankingAppliesAndRestoresNegativeRates() throws Throwable {
        runTestOnUiThread(() -> {
            CentralBankView bankView = activity.bankView;
            bankView.interestRate.getText().replace(0, bankView.interestRate.length(), "-29");
            bankView.reserveRate.getText().replace(0, bankView.reserveRate.length(), "-57");
            bankView.applyBtn.performClick();
            assertEquals(-0.29, App.sim.access.bank.interestRate, 0.0);
            assertEquals(-0.57, App.sim.access.bank.reserveRate, 0.0);
        });

        final Simulator previous = App.sim;
        finishActivity();
        launchActivity();
        assertNotSame(previous, App.sim);
        runTestOnUiThread(() -> {
            assertEquals(-0.29, App.sim.access.bank.interestRate, 0.0);
            assertEquals(-0.57, App.sim.access.bank.reserveRate, 0.0);
            assertEquals("-29", activity.bankView.interestRate.getText().toString());
            assertEquals("-57", activity.bankView.reserveRate.getText().toString());
        });
    }

    public void testBankingValidatesBothRatesBeforeApplying() throws Throwable {
        runTestOnUiThread(() -> {
            CentralBankView bankView = activity.bankView;
            bankView.interestRate.setText("-100");
            bankView.reserveRate.setText("100");
            bankView.applyBtn.performClick();
            assertEquals(-1.0, App.sim.access.bank.interestRate, 0.0);
            assertEquals(1.0, App.sim.access.bank.reserveRate, 0.0);

            bankView.interestRate.setText("0");
            bankView.reserveRate.setText("-0.5");
            bankView.applyBtn.performClick();
            assertEquals(-1.0, App.sim.access.bank.interestRate, 0.0);
            assertEquals(1.0, App.sim.access.bank.reserveRate, 0.0);
        });
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
        Simulator restored = LocalEconomyStore.load(context, namedSlot);
        assertEquals(size, restored.access.config.MAP_SIZE);
        assertEquals(labor + 1, restored.access.dir.getListFromType(ComType.LABOR).getList().length);
        assertEquals(10000L, restored.access.bankStats.totalCurrency);
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
