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
import android.support.constraint.ConstraintLayout;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RelativeLayout;
import android.widget.TextView;

import java.util.Timer;
import java.util.TimerTask;

/**
 * Created by ACR411 on 8/19/2019.
 */

public class CentralBankView extends RelativeLayout{
    EditText interestRate, reserveRate, info;
    private String lastInterest, lastReserve;
    Button applyBtn;
    Timer timer;

    public CentralBankView(Context context) {
        super(context);
        inflate(context, R.layout.bank_panel, this);
        interestRate = findViewById(R.id.editTextInterest);
        reserveRate = findViewById(R.id.editTextReserve);
        info = findViewById(R.id.editTextBankInfo);
        applyBtn = findViewById(R.id.buttonApply);
        initFromState();

        App.onEconomyClick(applyBtn, new OnClickListener() {
            @Override
            public void onClick(View view) {
                App.vibrate();
                try {
                    refreshRates();
                    int interest = Integer.parseInt(interestRate.getText().toString());
                    int reserve = Integer.parseInt(reserveRate.getText().toString());
                    if (interest < -100 || interest > 100 || reserve < -100 || reserve > 100) {
                        App.showBasicDialogue("Alert", "Enter a value between -100 and 100");
                        return;
                    }
                    App.sim.access.bank.interestRate = interest / 100.0;
                    App.sim.access.bank.reserveRate = reserve / 100.0;
                    initFromState();
                } catch (NumberFormatException e) {
                    App.showBasicDialogue("Alert", "Use whole numbers");
                }
            }
        });

        timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                App.activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        refreshRates();
                        String text = "";
                        text += "Bank Balance    : $" + App.sim.CtD(App.sim.access.bank.account.getBalance()) + "\n";
                        text += "Bank Reserve    : $" + App.sim.CtD(App.sim.access.bank.reserve) + "\n";
                        text += "Debt Holdings   : $" + App.sim.CtD(App.sim.access.bankStats.totalDebt) + "\n";
                        text += "Minimum Payment : " + Double.toString(App.sim.access.bank.minPayPercent * 100) + "%\n";
                        text += "Interest Rate   : " + wholePercent(App.sim.access.bank.interestRate) + "%\n";
                        text += "Reserve Rate    : " + wholePercent(App.sim.access.bank.reserveRate) + "%\n";
                        text += "Insolvency Hits : " + Integer.toString(App.sim.access.bankStats.insolvencyHits) + "\n";
                        if(App.sim.access.bank.fail) {
                            text += "*BANK FAILURE*" + "\n";
                        }
                        if(App.sim.access.bank.creditMeltdown) {
                            text += "*CREDIT METLDOWN*" + "\n";
                        }
                        info.setText(text);
                    }
                });
            }
        }, 0, 1000);
    }

    public void initFromState() {
        lastInterest = wholePercent(App.sim.access.bank.interestRate);
        lastReserve = wholePercent(App.sim.access.bank.reserveRate);
        interestRate.setText(lastInterest);
        reserveRate.setText(lastReserve);
    }

    void refreshRates() {
        lastInterest = refreshRate(interestRate, lastInterest, App.sim.access.bank.interestRate);
        lastReserve = refreshRate(reserveRate, lastReserve, App.sim.access.bank.reserveRate);
    }

    private static String refreshRate(EditText input, String previous, double rate) {
        String current = wholePercent(rate);
        // Autopilot may change one rate while the player is editing the other.
        if (!current.equals(previous) && previous.contentEquals(input.getText())) {
            input.setText(current);
        }
        return current;
    }

    private static String wholePercent(double rate) {
        return Long.toString(Math.round(rate * 100));
    }
}
