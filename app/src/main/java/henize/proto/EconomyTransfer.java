/* SPDX-License-Identifier: GPL-3.0-or-later */
package henize.proto;

import android.app.ProgressDialog;
import android.net.Uri;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import henize.proto.lib.sim.Simulator;

/** Owns one document transfer while the Save/Load screen holds the simulation paused. */
final class EconomyTransfer {
    private final MainActivity activity;
    private ProgressDialog progress;
    private boolean destroyed;
    private Runnable completed;

    EconomyTransfer(MainActivity activity) {
        this.activity = activity;
    }

    boolean isBusy() { return progress != null; }

    void exportFile(Uri destination, Runnable afterKey) {
        final Simulator economy = App.sim;
        transfer(R.string.economy_exporting, R.string.economy_export_failed, () -> {
            File staged = File.createTempFile("economy-", ".encrypted", activity.getCacheDir());
            try {
                String key;
                try (OutputStream temp = new FileOutputStream(staged)) {
                    key = EconomyArchive.write(economy, temp);
                }
                // Keep the transfer code recoverable if Android recreates the activity or kills
                // the process while a remote provider is still finishing the document.
                activity.economyDocuments.rememberExportKey(key);
                try (InputStream temp = new FileInputStream(staged);
                        OutputStream out = activity.getContentResolver().openOutputStream(destination, "wt")) {
                    if (out == null) throw new IOException("Document provider unavailable");
                    byte[] buffer = new byte[32768];
                    int count;
                    while ((count = temp.read(buffer)) != -1) out.write(buffer, 0, count);
                } catch (IOException | RuntimeException e) {
                    activity.economyDocuments.discardExportKey(key);
                    throw e;
                }
                return () -> activity.economyDocuments.showExportKey(key, afterKey);
            } finally {
                staged.delete();
            }
        });
    }

    void importFile(Uri source, String key) {
        transfer(R.string.economy_importing, R.string.economy_import_failed, () -> {
            Simulator imported;
            try (InputStream in = activity.getContentResolver().openInputStream(source)) {
                if (in == null) throw new IOException("Document provider unavailable");
                imported = EconomyArchive.read(in, key);
            }
            return () -> {
                Simulator previous = App.sim;
                activity.applyEconomy(imported);
                if (!activity.saveLocalEconomy(App.stateFile)) {
                    activity.applyEconomy(previous);
                    return;
                }
                activity.showRestoredEconomy();
                Toast.makeText(activity, R.string.economy_imported, Toast.LENGTH_SHORT).show();
            };
        });
    }

    private interface FileOperation {
        Runnable run() throws IOException;
    }

    private void transfer(int pending, int error, FileOperation operation) {
        if (isBusy()) return;
        activity.stop();
        App.sim.interrupt(); // Wait for traveler route computations as well as the simulation loop.
        progress = ProgressDialog.show(activity, null, activity.getText(pending), true, false);
        new Thread(() -> {
            Runnable result;
            try {
                result = operation.run();
            } catch (IOException | RuntimeException e) {
                result = () -> activity.reportSaveError(error, e);
            }
            final Runnable outcome = result;
            activity.runOnUiThread(() -> {
                if (destroyed || activity.isFinishing()) return;
                completed = () -> {
                    try {
                        outcome.run();
                    } catch (RuntimeException e) {
                        activity.reportSaveError(error, e);
                    }
                };
                if (activity.isResumedForTransfer()) onResume();
            });
        }, "Economy document transfer").start();
    }

    void onResume() {
        if (completed == null) return;
        Runnable deliver = completed;
        completed = null;
        progress.dismiss();
        progress = null;
        deliver.run();
    }

    void destroy() {
        destroyed = true;
        if (progress != null) progress.dismiss();
        completed = null;
    }
}
