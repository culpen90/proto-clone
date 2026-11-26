/* Proto - Economy Simulator
Copyright (C) 2024 Joshua Henize

This program is free software: you can redistribute it and/or modify it under the terms of the
GNU General Public License as published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
See the GNU General Public License for more details.

You should have received a copy of the GNU General Public License along with this program.
If not, see https://www.gnu.org/licenses/. */
package henize.proto;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Resources;
import android.os.Vibrator;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.Toast;

import android.net.Uri;

import java.io.IOException;
import java.util.Stack;

import henize.proto.lib.sim.Simulator;

import static henize.proto.MainView.GRAPH;
import static henize.proto.MainView.MAP;


public class MainActivity extends AppCompatActivity implements AlertDialog.OnClickListener {

    public MainView simulator;
    TraderView traderView;
    CurrencyView currencyView;
    ControlView controlView;
    SuperStatsView superStatsView;
    CentralBankView bankView;
    SpecialOrderingView specialOrderingView;
    StateLoaderView stateLoaderView;
    EconomyDocuments economyDocuments;
    //BillingView billingView;
    public BootstrapView bootView;

    MenuItem menuPlayPause;
    MenuItem menuSpeed;
    MenuItem menuSupplyDemand;
    MenuItem menuFullScreen;

    Stack<Object> backStack;
    boolean switchToMapOnPlay;

    private boolean appUnlocked;
    private boolean resumed;
    private boolean initialLoad = true;
    private boolean protectUnreadableAutosave;
    private final Handler controlsHandler = new Handler();
    private final Runnable refreshEconomicControls = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            synchronized (App.sim) {
                if (menuSupplyDemand != null) menuSupplyDemand.setChecked(App.sim.access.config.supplyAndDemandEnabled);
                if (!simulator.simPaused) {
                    if (currencyView.getParent() != null) currencyView.refreshTaxPolicy();
                    if (traderView.getParent() != null) traderView.updateDisplay();
                    if (specialOrderingView.getParent() != null) specialOrderingView.refresh();
                }
            }
            controlsHandler.postDelayed(this, 1000);
        }
    };
    private final EconomyTransfer economyTransfer = new EconomyTransfer(this);

    //AdServer adServer;
    StoredPreferences pref;

    float prevX, prevY;
    private boolean isScroling;
    int barOffset;
    private MenuItem menuSwitch;
    public float setScale = -0.25f;
    public boolean tabMode;



    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        economyDocuments = new EconomyDocuments(this, savedInstanceState);
        Thread.currentThread().setPriority(10);

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        bootView = new BootstrapView(this);
        App.activity = this;
        App.vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        App.map = getResources().getString(R.string.map);
        App._record_serialization();

        bootView.initApp();

    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        economyDocuments.saveInstanceState(state);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (!economyDocuments.receiveResult(requestCode, resultCode, data)) {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

   public void initUI() {

        App.initSim();

        simulator = new MainView(this);

        //adServer = new AdServer(this, App.sim.access.config.USE_TEST_ADS);

        traderView = new TraderView(this);
        currencyView = new CurrencyView(this);
        controlView = new ControlView(this);
        superStatsView = new SuperStatsView(this);
        bankView = new CentralBankView(this);
        specialOrderingView = new SpecialOrderingView(this);
        stateLoaderView = new StateLoaderView(this);
        //billingView = new BillingView(this);

        backStack = new Stack<>();

        simulator.simPaused = true;

        pref = new StoredPreferences(getPreferences(MODE_PRIVATE), R.id.info_item, true);
    }
    public int dpiToPx(float dpi) {
        return (int)(dpi * ((float)getResources().getDisplayMetrics().densityDpi / DisplayMetrics.DENSITY_DEFAULT));

    }
    private int getStatusBarSize() {
        Resources resources = getResources();
        int resourceId = resources.getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            return resources.getDimensionPixelSize(resourceId);
        }
        return 0;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if(switchToMapOnPlay) return true;

        event.setLocation(event.getX(), event.getY() - barOffset);
        simulator.puiDesktop.postScreenTouchEvent(event);
        switch (event.getAction()) {
            case MotionEvent.ACTION_MOVE: {
                int px = (int)event.getX() - (int)prevX;
                int py = (int)event.getY() - (int)prevY;


                if(simulator.getViewMode() == GRAPH) {
                    if(App.sim == null || App.sim.graph == null || App.sim.graph.virt_days == null) return true;
                    //if(Math.abs(px) < 10 ) break;
                    int m = simulator.graphSpread / 1000 + 1;
                    int s = simulator.graphSpread + px * m;

                    if(simulator.graphSpread > 0 && px < 0) {
                        if(s < 1) s = 1;
                    } else {
                        if (s < 0) s = App.sim.graph.virt_days.size() + s;
                    }
                    if(!(simulator.graphSpread == 0 && px > 0)) {


                        if (s >= App.sim.graph.virt_days.size()) s = 0;
                        simulator.graphSpread = s;
                        App.sim.graph.instantRefresh = true;
                    }
                }
                //App.sim.map.moveFrame(ix * -1, iy * -1);
                prevX = event.getX();
                prevY = event.getY();
                isScroling = true;
                break;
            }
            case MotionEvent.ACTION_DOWN: {
                prevX = event.getX();
                prevY = event.getY();
                isScroling = false;
/*                if(simulator.getViewMode() == GRAPH) {
                    simulator.graphSpread = 0;
                    App.sim.graph.instantRefresh = true;
                    //Toast.makeText(this, "Graph Spread is " + (simulator.graphSpread == 0 ? "infinite." : Integer.toString(simulator.graphSpread) + " transactions."), Toast.LENGTH_SHORT).show();
                    App.vibrate();
                }*/
                break;
            }
            case MotionEvent.ACTION_UP: {
                if(!isScroling) {
                    simulator.graphSpread = 0;
                    App.vibrate();

                }
                break;
            }

        }
        return true;
    }
    public void deserialize() {
        deserialize(App.stateFile);
    }

    public void deserialize(String slot) {
        loadLocalEconomy(slot);
    }

    boolean loadLocalEconomy(String slot) {
        try {
            applyEconomy(LocalEconomyStore.load(this, slot));
            protectUnreadableAutosave = false;
            return true;
        } catch (IOException | ClassNotFoundException | RuntimeException e) {
            reportSaveError(R.string.economy_load_failed, e);
            return false;
        }
    }

    void serialize() {
        serialize(App.stateFile);
    }

    void serialize(String slot) {
        saveLocalEconomy(slot);
    }

    boolean saveLocalEconomy(String slot) {
        try {
            App.sim.interrupt();
            LocalEconomyStore.save(this, slot, App.sim);
            if (App.stateFile.equals(slot)) protectUnreadableAutosave = false;
            return true;
        } catch (IOException | RuntimeException e) {
            reportSaveError(R.string.economy_save_failed, e);
            return false;
        }
    }

    void applyEconomy(Simulator restored) {
        Simulator previous = App.sim;
        try {
            App.sim = restored;
            // The file can come from a device with different screen dimensions.
            if (simulator.scale > 0) {
                restored.setScale(simulator.scale);
                restored.map.setFrameSize(20, simulator.height / simulator.scale);
            }
            simulator.graphSpread = 0;
            initUIFromState();
        } catch (RuntimeException e) {
            App.sim = previous;
            initUIFromState();
            throw e;
        }
    }

    void showRestoredEconomy() {
        pref.setView(R.id.map_item);
        if (resumed) selectOption(R.id.map_item, true);
    }

    void reportSaveError(int message, Exception error) {
        String detail = error.getLocalizedMessage();
        bootView.log(getString(message) + (detail == null ? "" : " " + detail));
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    void exportEconomy(Uri destination) {
        exportEconomy(destination, null);
    }

    void exportEconomy(Uri destination, Runnable onSuccess) {
        economyTransfer.exportFile(destination, onSuccess);
    }

    void importEconomy(Uri source, String key) {
        economyTransfer.importFile(source, key);
    }

    @Override
    protected void onPause() {
        resumed = false;
        controlsHandler.removeCallbacks(refreshEconomicControls);
        turnOffSpeed();
        stop();
        if (!protectUnreadableAutosave) serialize();
        savePrefs();
        super.onPause();
    }

    boolean economyTransferBusy() {
        return economyTransfer.isBusy();
    }

    boolean isResumedForTransfer() {
        return resumed;
    }

    @Override
    protected void onDestroy() {
        bankView.timer.cancel();
        economyTransfer.destroy();
        economyDocuments.dismissDialogs();
        super.onDestroy();
    }

    private void savePrefs() {
        if(backStack.size() > 0) {
            pref.setView((int)backStack.peek());
        }
        pref.setUnlocked(appUnlocked);
    }

    @Override
    protected  void onResume() {
        super.onResume();
        resumed = true;
        if (initialLoad) {
            initialLoad = false;
            try {
                LocalEconomyStore.registerLegacySlots(this);
                if (getFileStreamPath(App.stateFile).exists()) {
                    protectUnreadableAutosave = !loadLocalEconomy(App.stateFile);
                }
            } catch (IOException e) {
                protectUnreadableAutosave = true;
                reportSaveError(R.string.economy_load_failed, e);
            }
            pref.setView(R.id.map_item);
        }
        _resume();
        controlsHandler.post(refreshEconomicControls);
        TypedValue tv = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.actionBarSize, tv, true))
        {
            barOffset = TypedValue.complexToDimensionPixelSize(tv.data,getResources().getDisplayMetrics()) + getStatusBarSize();

        }

    }

    public void _resume() {
        selectOption(economyDocuments.isPending() ? R.id.state_item : pref.getView(), true);
        appUnlocked = pref.getUnlocked();
        economyTransfer.onResume();
        economyDocuments.onEconomyReady();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater mi = getMenuInflater();
        mi.inflate(R.menu.menu, menu);
        menuPlayPause = menu.findItem(R.id.play_pause_item);
        menuSpeed = menu.findItem(R.id.speed_item);
        menuSupplyDemand = menu.findItem(R.id.priceModel);
        menuSupplyDemand.setChecked(App.sim.access.config.supplyAndDemandEnabled);
        menuFullScreen = menu.findItem(R.id.fullScreen);
        menuFullScreen.setChecked(App.sim.access.config.FULL_SCREEN);
        menuSwitch = menu.findItem(R.id.app_bar_switch_item);
        Switch switchTabMode = (Switch) menuSwitch.getActionView().findViewById(R.id.switch_item);
        switchTabMode.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                tabMode = isChecked;
                if(isChecked) {
                    simulator.puiDesktop.scale = setScale;
                    App.sim.access.config.FULL_SCREEN = true;
                } else {
                    simulator.puiDesktop.scale = 0.0f;
                    App.sim.access.config.FULL_SCREEN = false;
                }
                simulator.puiDesktop.updateScale = true;

                menuFullScreen.setChecked(App.sim.access.config.FULL_SCREEN);
                App.sim.map.setFullScreen(App.sim.access.config.FULL_SCREEN);
            }
        });

        EditText scaleText = (EditText)(menu.findItem(R.id.scale_text_item).getActionView().findViewById(R.id.scale_text_box_item));
        scaleText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {

            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {

            }

            @Override
            public void afterTextChanged(Editable s) {
                try {
                float value = Float.parseFloat(s.toString()) ;
                if(value < 1 || value > 99) {
                    App.showBasicDialogue("Enter a value between 1 and 100.", "");
                    value = 50f;
                }
                value = 100 - value;
                value /= 100;
                setScale = -value;
                if(tabMode) {
                    simulator.puiDesktop.scale = setScale;
                    simulator.puiDesktop.updateScale = true;
                } } catch (Exception e) {

                }
            }
        });
        return super.onCreateOptionsMenu(menu);

    }

    @Override
    public void onBackPressed() {
        if(backStack.size() > 1) {
            backStack.pop();
            selectOption((int) backStack.peek(), false);

        } else {
            App.showExitDialgue();
        }
      }

    @Override
    public void onClick(DialogInterface dialogInterface, int i) {
        if(i == DialogInterface.BUTTON_POSITIVE) {
            moveTaskToBack(true);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        boolean _return = false;
        switch (id) {

            case R.id.play_pause_item: {
                _return = true;
                App.sim.map.setFullScreen(App.sim.access.config.FULL_SCREEN);
                togglePlayPause();
                break;
            }
            case R.id.speed_item: {
                _return = true;
                toggleSpeed();
                break;
            }
            case R.id.reset_item: {
                _return = true;
                if(simulator.simPaused == false)
                    togglePlayPause();
                turnOffSpeed();
                stop();
                App.sim.reset();
                initUIFromState();
                simulator.graphSpread = 0;
                start();
                Toast.makeText(this, R.string.simreset, Toast.LENGTH_SHORT).show();
                break;
            }
            case R.id.priceModel: {
                _return = true;
                synchronized (App.sim) {
                    App.sim.access.config.supplyAndDemandEnabled = !App.sim.access.config.supplyAndDemandEnabled;
                    item.setChecked(App.sim.access.config.supplyAndDemandEnabled);
                }

                if (App.sim.access.config.supplyAndDemandEnabled) {
                    Toast.makeText(this, "The economy will respond to supply and demand.", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "The economy will be more stable.", Toast.LENGTH_SHORT).show();
                }
                break;
            }
            case R.id.fullScreen: {
                _return = true;
                App.sim.access.config.FULL_SCREEN = !App.sim.access.config.FULL_SCREEN;
                item.setChecked(App.sim.access.config.FULL_SCREEN);
                App.sim.map.setFullScreen(App.sim.access.config.FULL_SCREEN);
                break;
            }
            case R.id.unlock_item: {
                _return = true;
               //adServer.displayRewardVid();
                break;
            }

        }
        if(!_return) selectOption(id, true);
        return super.onOptionsItemSelected(item);
    }

    public void initUIFromState() {
        traderView.initFromState();
        specialOrderingView.refresh();
        currencyView.initFromState();
        bankView.initFromState();
        controlView.refresh();
        //App.sim.graph.initUIFromState();
        if(menuFullScreen != null && menuSupplyDemand != null) {
            menuSupplyDemand.setChecked(App.sim.access.config.supplyAndDemandEnabled);
            menuFullScreen.setChecked(App.sim.access.config.FULL_SCREEN);
        }
    }

    private void togglePlayPause() {
        if(App.sim.access.config.fresh == true) App.sim.access.config.fresh = false;
        //If unpaused and on Trade, Currency, or Info screen...switch to map
        if (switchToMapOnPlay && simulator.simPaused) {
            selectOption(R.id.map_item, true);
        }
        simulator.simPaused = !simulator.simPaused;   //toggle paused
        menuSpeed.setEnabled(!simulator.simPaused); //enable speed button
        //toggle play pause icon
        menuPlayPause.setIcon(simulator.simPaused ? android.R.drawable.ic_media_play : android.R.drawable.ic_media_pause);
    }

    private void turnOffSpeed() {
        if(simulator.speed != -1) {
            toggleSpeed();
        }
    }
    private void toggleSpeed() {
        MenuItem item = menuSpeed;
        if(simulator.speed == -1) {
            simulator.speed = simulator.scale;
            App.sim.setScale(0);
            App.sim.access.config.speed = true;
            item.setChecked(true);
            item.setTitle(R.string.SPEEDON);
            Toast.makeText(this, R.string.SPEEDON, Toast.LENGTH_SHORT).show();
        } else {
            App.sim.setScale(simulator.speed);
            App.sim.access.config.speed = false;
            simulator.speed = -1;
            item.setChecked(false);
            item.setTitle(R.string.SPEEDOFF);
           // Toast.makeText(this, "Speed off", Toast.LENGTH_SHORT).show();
        }

    }

    private void selectOption(int id, boolean push) {
        if (push) {
            if (backStack.size() > 0) {
                if ((int) backStack.peek() != id) {
                    backStack.push(id);
                }
            } else {
                backStack.push(id);
            }
        }

        switch (id) {

            case R.id.map_item: {
                stop();
                simulator.setViewMode(MAP);
                setContentView(simulator);
                start();
                switchToMapOnPlay = false;
                //adServer.displayMainAd();
                //App.sim.map.updateAll();
                break;
            }
            case R.id.graph_item: {
                stop();
                simulator.setViewMode(GRAPH);
                setContentView(simulator);
                start();
                switchToMapOnPlay = false;
                Toast.makeText(this, R.string.tapgraphspread, Toast.LENGTH_SHORT).show();
                break;
            }
            case R.id.special_order_item: {
                stop();
                specialOrderingView.refresh();
                setContentView(specialOrderingView);
                start();
                switchToMapOnPlay = true;
                break;
            }
            case R.id.trader_item: {
                stop();
                traderView.initFromState();
                setContentView(traderView);
                start();
                switchToMapOnPlay = true;
                break;
            }
            case R.id.currency_item: {
                stop();
                currencyView.initFromState();
                setContentView(currencyView);
                switchToMapOnPlay = true;
                start();
                break;
            }
            case R.id.bank_item: {
                stop();
                bankView.refreshRates();
                setContentView(bankView);
                switchToMapOnPlay = false;
                start();
                break;
            }
            case R.id.advanced_stats_item: {
                stop();
                simulator.setViewMode(MainView.STATS);
                setContentView(simulator);
                switchToMapOnPlay = false;
                start();
                break;
            }
            case R.id.super_stats_item: {
                stop();
                setContentView(superStatsView);
                switchToMapOnPlay = false;
                start();
                break;
            }


            case R.id.control_item: {
                pauseSim();
                turnOffSpeed();
                stop();
                setContentView(controlView);
                switchToMapOnPlay = true;
                start();
                break;
            }
            case R.id.state_item: {
                pauseSim();
                turnOffSpeed();
                stop();
                stateLoaderView.refresh();
                setContentView(stateLoaderView);
                switchToMapOnPlay = true;
                break;
            }
            case R.id.log_item: {
                pauseSim();
                stop();
                setContentView(bootView);
                switchToMapOnPlay = true;
                start();
                break;
            }
//            case R.id.support_item: {
//                pauseSim();
//                stop();
//                setContentView(billingView);
//                billingView.update();
//                switchToMapOnPlay = true;
//                start();
//                break;
//            }
            case R.id.info_item: {
                stop();
                setContentView(R.layout.information_panel);
                switchToMapOnPlay = true;
                start();
                break;
            }
            case R.id.howto_item: {
                stop();
                setContentView(R.layout.howto_panel);
                switchToMapOnPlay = true;
                start();
                break;
            }
            default: {

            }
        }
    }

    private void pauseSim() {
        if(!simulator.simPaused)
            togglePlayPause();
    }

    public void start() {
        if (resumed && !economyTransfer.isBusy() && !simulator.running) simulator.start();
    }

    public void stop() {

        simulator.stop();
        try {
            if(simulator.thread != null) {
                simulator.thread.interrupt();
                simulator.thread.join();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }


    public void rewardVideoCallBack() {

    }




}
