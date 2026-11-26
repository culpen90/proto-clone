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

import android.content.Context;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RelativeLayout;
import android.widget.ScrollView;

/**
 * Created by ACR411 on 9/26/2018.
 */

public class BootstrapView extends RelativeLayout {

    EditText log;
    ScrollView scv;
    Button terminate;

    BootstrapView _this;

    public BootstrapView(Context context) {
        super(context);
        inflate(context, R.layout.bootstrap_panel, this);
        _this = this;
        log = findViewById(R.id.bootstrapLog);
        scv = findViewById(R.id.bootLogScrollView);
        terminate = findViewById(R.id.buttonTerminate);
        terminate.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                App.vibrate();
                App.showBigRedDialogue();
                App.deleteStateAndTerminate();
            }
        });
    }
    public void initApp() {
                App.activity.setContentView(_this);

                log.setText(log.getText().append("Proto bootstrap\ninitializing user interface...\n").toString());

        App.activity.initUI();
    }

    public void log(String text) {
        log.setText(log.getText().append(text += "\n").toString());
    }

    public void initSim(String state) {
        MainActivity activity = (MainActivity) getContext();
        activity.stop();
        if (activity.loadLocalEconomy(state)) {
            activity.showRestoredEconomy();
        }
    }

    public void saveSim(String state) {
        MainActivity activity = (MainActivity) getContext();
        activity.stop();
        if (activity.saveLocalEconomy(state)) {
            activity.stateLoaderView.refresh();
            android.widget.Toast.makeText(activity,
                    activity.getString(R.string.economy_saved_slot, state),
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }
}
