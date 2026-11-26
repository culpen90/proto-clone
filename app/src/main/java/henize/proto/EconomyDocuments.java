/* SPDX-License-Identifier: GPL-3.0-or-later */
package henize.proto;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.support.v4.content.FileProvider;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Routes Android document picker results after the activity has restored its economy. */
final class EconomyDocuments {
    private static final int EXPORT = 401;
    private static final int IMPORT = 402;
    private static final String MIME_TYPE = "application/octet-stream";
    private static final String REQUEST = "economyDocument.request";
    private static final String RETURNED = "economyDocument.returned";
    private static final String RESULT = "economyDocument.result";
    private static final String URI_KEY = "economyDocument.uri";

    private static final String EXPORT_KEY = "economyDocument.unacknowledgedKey";
    private static final String SHARE_URI = "economyDocument.shareUri";

    private final MainActivity activity;
    private final SharedPreferences receipts;
    private int request;
    private boolean returned;
    private int result;
    private Uri uri;
    private AlertDialog confirmation;
    private AlertDialog keyDialog;
    private String exportKey;
    private Uri shareUri;
    private Runnable afterKey;

    EconomyDocuments(MainActivity activity, Bundle state) {
        this.activity = activity;
        receipts = activity.getSharedPreferences("pending_economy_export", Context.MODE_PRIVATE);
        if (state != null) {
            request = state.getInt(REQUEST);
            returned = state.getBoolean(RETURNED);
            result = state.getInt(RESULT);
            uri = state.getParcelable(URI_KEY);
            shareUri = state.getParcelable(SHARE_URI);
        }
    }

    void saveInstanceState(Bundle state) {
        state.putInt(REQUEST, request);
        state.putBoolean(RETURNED, returned);
        state.putInt(RESULT, result);
        state.putParcelable(URI_KEY, uri);
        state.putParcelable(SHARE_URI, shareUri);
    }

    boolean isPending() {
        return request != 0 || receipts.contains(EXPORT_KEY);
    }

    void chooseExport() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            exportForSharing();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .setType(MIME_TYPE)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_TITLE, fileName());
        launch(intent, EXPORT);
    }

    void chooseImport() {
        String action = Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT
                ? Intent.ACTION_OPEN_DOCUMENT : Intent.ACTION_GET_CONTENT;
        // Document providers do not agree on a MIME type for .protoeconomy files.
        launch(new Intent(action).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), IMPORT);
    }

    private void launch(Intent intent, int operation) {
        request = operation;
        returned = false;
        try {
            activity.startActivityForResult(intent, operation);
        } catch (ActivityNotFoundException | SecurityException e) {
            clear();
            showMessage(R.string.economy_picker_unavailable);
        }
    }

    boolean receiveResult(int operation, int resultCode, Intent data) {
        if (operation != EXPORT && operation != IMPORT) return false;
        request = operation;
        returned = true;
        result = resultCode;
        uri = data == null ? null : data.getData();
        return true;
    }

    void onEconomyReady() {
        if (keyDialog == null && !activity.economyTransferBusy()) {
            String unacknowledged = receipts.getString(EXPORT_KEY, null);
            if (unacknowledged != null) showExportKey(unacknowledged, null);
        }
        if (!returned) return;
        if (result != Activity.RESULT_OK) {
            clear();
            return;
        }
        if (uri == null || !ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            clear();
            showMessage(R.string.economy_document_unavailable);
            return;
        }
        Uri document = uri;
        if (request == EXPORT) {
            clear();
            activity.exportEconomy(document);
        } else if (confirmation == null) {
            confirmImport(document);
        }
    }

    private void confirmImport(Uri document) {
        EditText key = new EditText(activity);
        key.setSingleLine(true);
        key.setHint(R.string.economy_key);
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            key.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING | EditorInfo.IME_ACTION_DONE);
            key.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        }
        confirmation = new AlertDialog.Builder(activity)
                .setTitle(R.string.open_economy)
                .setMessage(R.string.confirm_economy_import)
                .setView(inset(key))
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> clear())
                .setPositiveButton(R.string.open_economy, null)
                .setOnCancelListener(dialog -> clear())
                .create();
        confirmation.show();
        confirmation.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            String entered = key.getText().toString().trim();
            if (entered.isEmpty()) {
                key.setError(activity.getString(R.string.economy_key_required));
                return;
            }
            confirmation.dismiss();
            clear();
            activity.importEconomy(document, entered);
        });
    }

    /** Record the only key before a document provider receives its encrypted bytes. */
    void rememberExportKey(String key) throws IOException {
        synchronized (EconomyDocuments.class) {
            String share = shareUri == null ? null : shareUri.toString();
            if (!receipts.edit().putString(EXPORT_KEY, key).putString(SHARE_URI, share).commit()) {
                throw new IOException("Could not record the export key");
            }
        }
    }

    void discardExportKey(String key) {
        synchronized (EconomyDocuments.class) {
            if (key.equals(receipts.getString(EXPORT_KEY, null))) {
                receipts.edit().remove(EXPORT_KEY).remove(SHARE_URI).commit();
            }
        }
    }

    void showExportKey(String key, Runnable onDone) {
        if (keyDialog != null || !key.equals(receipts.getString(EXPORT_KEY, null))) return;
        String share = receipts.getString(SHARE_URI, null);
        shareUri = share == null ? null : Uri.parse(share);
        exportKey = key;
        afterKey = onDone;
        displayExportKey();
    }

    private void displayExportKey() {
        TextView key = new TextView(activity);
        key.setTextIsSelectable(true);
        key.setTypeface(Typeface.MONOSPACE);
        key.setTextSize(18);
        key.setText(exportKey);
        keyDialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.save_economy_key)
                .setMessage(R.string.keep_economy_key)
                .setView(inset(key))
                .setCancelable(false)
                .setNeutralButton(R.string.copy_economy_key, null)
                .setPositiveButton(R.string.economy_key_done, (dialog, which) -> {
                    Runnable callback = afterKey;
                    Uri deferredShare = shareUri;
                    discardExportKey(exportKey);
                    keyDialog = null;
                    afterKey = null;
                    exportKey = null;
                    shareUri = null;
                    if (callback != null) callback.run();
                    else if (deferredShare != null) shareFile(deferredShare);
                })
                .create();
        keyDialog.show();
        keyDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText(activity.getString(R.string.economy_key), exportKey);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                PersistableBundle extras = new PersistableBundle();
                extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
                clip.getDescription().setExtras(extras);
            }
            clipboard.setPrimaryClip(clip);
            keyDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setText(R.string.economy_key_copied);
        });
    }

    private FrameLayout inset(View child) {
        FrameLayout container = new FrameLayout(activity);
        int margin = activity.dpiToPx(20);
        container.setPadding(margin, activity.dpiToPx(8), margin, 0);
        container.addView(child);
        return container;
    }

    void dismissDialogs() {
        if (confirmation != null) confirmation.dismiss();
        if (keyDialog != null) keyDialog.dismiss();
    }

    private void clear() {
        request = 0;
        returned = false;
        uri = null;
        confirmation = null;
    }

    private String fileName() {
        String stamp = new SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US).format(new Date());
        return "Economy-" + stamp + ".protoeconomy";
    }

    // Android 4.1–4.3 has no create-document picker. A receiving app can keep a copy.
    private void exportForSharing() {
        File directory = new File(activity.getCacheDir(), "economy-exports");
        try {
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new IOException("Could not create the export folder");
            }
            File file = File.createTempFile("Economy-", ".protoeconomy", directory);
            Uri document = FileProvider.getUriForFile(activity,
                    activity.getPackageName() + ".economyexports", file);
            shareUri = document;
            activity.exportEconomy(document, () -> shareFile(document));
        } catch (IOException | IllegalArgumentException | SecurityException e) {
            showMessage(R.string.economy_share_failed);
        }
    }

    private void shareFile(Uri document) {
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType(MIME_TYPE)
                .putExtra(Intent.EXTRA_STREAM, document)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(ClipData.newRawUri(null, document));
        Intent chooser = Intent.createChooser(send, activity.getString(R.string.share_economy));
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        chooser.setClipData(send.getClipData());
        try {
            activity.startActivity(chooser);
        } catch (ActivityNotFoundException | SecurityException e) {
            showMessage(R.string.economy_picker_unavailable);
        }
    }

    private void showMessage(int resource) {
        Toast.makeText(activity, resource, Toast.LENGTH_LONG).show();
    }
}
