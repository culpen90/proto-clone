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
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RelativeLayout;
import android.widget.Spinner;

import java.util.ArrayList;
import java.util.List;

import henize.proto.lib.sim.Simulator;
import henize.proto.lib.traders.ComType;
import henize.proto.lib.traders.Trader;
import henize.proto.lib.traders.SpecialOrder;

import static henize.proto.lib.traders.ComType.COAL;
import static henize.proto.lib.traders.ComType.FOOD;
import static henize.proto.lib.traders.ComType.IRON;
import static henize.proto.lib.traders.ComType.TOOL;

/**
 * Created by ACR411 on 12/22/2019.
 */

public class SpecialOrderingView extends RelativeLayout {
    Spinner comSelect;
    private static final ComType[] GOODS = {FOOD, TOOL, COAL, IRON};
    private Simulator historyEconomy;
    private final StringBuilder manualHistory = new StringBuilder();
    EditText quant, list;
    Button order, cancel;


    public SpecialOrderingView(Context context) {
        super(context);
        inflate(getContext(), R.layout.special_order_panel, this);

        comSelect = findViewById(R.id.spinnerComSelect);
        quant = findViewById(R.id.editTextQuant);
        list = findViewById(R.id.editTextOrderList);
        order = findViewById(R.id.buttonOrder);
        cancel = findViewById(R.id.buttonBreakPrioirty);

        List<String> comOptions = new ArrayList<String>();

        comOptions.add(getResources().getString(R.string.food));
        comOptions.add(getResources().getString(R.string.tools));
        comOptions.add(getResources().getString(R.string.coal));
        comOptions.add(getResources().getString(R.string.iron));

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(getContext(), R.layout.spinner_big, comOptions);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        comSelect.setAdapter(adapter);

        list.setFocusable(false);
        cancel.setOnClickListener(view -> {
            App.sim.breakPriority();
            refresh();
            App.vibrate();
        });
        order.setOnClickListener(view -> {
            int quantity;
            try {
                quantity = Integer.parseInt(quant.getText().toString());
            } catch (NumberFormatException e) {
                quantity = 0;
            }
            ComType type = GOODS[comSelect.getSelectedItemPosition()];
            if (!App.sim.placePriorityOrder(type, quantity)) {
                App.showBasicDialogue("Invalid order", "Choose an available commodity and enter a positive quantity.");
                return;
            }
            resetHistoryIfNeeded();
            manualHistory.append("MASTER order for ").append(quantity).append(' ').append(type).append('\n');
            refresh();
            App.vibrate();
        });
    }

    private void resetHistoryIfNeeded() {
        if (historyEconomy != App.sim) {
            manualHistory.setLength(0);
            historyEconomy = App.sim;
        }
    }

    void refresh() {
        resetHistoryIfNeeded();
        StringBuilder orders = new StringBuilder();
        synchronized (App.sim) {
            if (!App.sim.specialPriority.empty()) {
                for (ComType type : GOODS) {
                    long quantity = 0;
                    for (Trader seller : App.sim.access.dir.getListFromType(type).getList()) {
                        quantity += outstanding(seller.specialOrders) + outstanding(seller.processingSpecialOrders);
                    }
                    if (quantity > 0) orders.append(type).append(" × ").append(quantity).append('\n');
                }
            }
        }
        if (orders.length() > 0) orders.insert(0, getResources().getString(R.string.priority_pending) + "\n");
        if (manualHistory.length() > 0) {
            if (orders.length() > 0) orders.append('\n');
            orders.append(getResources().getString(R.string.priority_history)).append('\n').append(manualHistory);
        }
        if (!orders.toString().contentEquals(list.getText())) list.setText(orders);
    }

    private static long outstanding(List<SpecialOrder> orders) {
        long quantity = 0;
        for (SpecialOrder order : orders) {
            if (order.trader.account == App.sim.access.taxAccount) quantity += order.quant;
        }
        return quantity;
    }
}
