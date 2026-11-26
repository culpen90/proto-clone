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

import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.RelativeLayout;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;

public class StateLoaderView extends RelativeLayout {
    private final MainActivity activity;
    private final ListView stateList;
    private final Button load;
    private String selected;

    public StateLoaderView(MainActivity activity) {
        super(activity);
        this.activity = activity;
        inflate(activity, R.layout.state_loader_panel, this);
        stateList = findViewById(R.id.stateList);
        load = findViewById(R.id.loadStateButton);

        findViewById(R.id.saveStateButton).setOnClickListener(view -> {
            App.vibrate();
            String stamp = new SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.US).format(new Date());
            String base = "Economy " + stamp;
            String name = base;
            for (int copy = 2; activity.getFileStreamPath(name).exists(); copy++) {
                name = base + " (" + copy + ")";
            }
            selected = name;
            activity.bootView.saveSim(name);
        });
        load.setOnClickListener(view -> {
            if (selected != null) {
                App.vibrate();
                activity.bootView.initSim(selected);
            }
        });
        findViewById(R.id.exportEconomyButton).setOnClickListener(view -> {
            App.vibrate();
            activity.economyDocuments.chooseExport();
        });
        findViewById(R.id.importEconomyButton).setOnClickListener(view -> {
            App.vibrate();
            activity.economyDocuments.chooseImport();
        });
        refresh();
    }

    public void refresh() {
        String[] names = activity.getFilesDir().list((dir, name) ->
                !name.startsWith(".") && !name.endsWith(".bak") && !name.endsWith(".new")
                        && !name.equals("dat") && !name.equals("henize.proto.graph")
                        && new File(dir, name).isFile());
        if (names == null) names = new String[0];
        Arrays.sort(names, Collections.reverseOrder());
        final String[] slots = names;
        String[] labels = slots.clone();
        int checked = -1;
        for (int i = 0; i < slots.length; i++) {
            if (slots[i].equals(App.stateFile)) {
                labels[i] = getResources().getString(R.string.automatic_economy);
            }
            if (slots[i].equals(selected)) checked = i;
        }

        stateList.setAdapter(new ArrayAdapter<>(activity,
                android.R.layout.simple_list_item_single_choice, labels));
        if (checked >= 0) stateList.setItemChecked(checked, true);
        else selected = null;
        load.setEnabled(selected != null);
        stateList.setOnItemClickListener((parent, view, position, id) -> {
            selected = slots[position];
            load.setEnabled(true);
            App.vibrate();
        });
    }
}
