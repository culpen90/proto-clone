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
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RelativeLayout;
import android.widget.Spinner;
import android.widget.Toast;
import android.widget.ToggleButton;

import java.util.ArrayList;
import java.util.List;

import henize.proto.lib.bank.Account;
import henize.proto.lib.bank.Account.TaxCode;
import henize.proto.lib.bank.TaxRates;
import henize.proto.lib.traders.ComType;

/**
 * Created by ACR411 on 12/21/2017.
 */

public class CurrencyView extends RelativeLayout  {

    private String lastTaxRate;
    ToggleButton taxButton;
    Button setButton, createButton, destroyButton, stimButton;
    EditText taxRate, currAmount, stimAmount, taxRateList;

    Spinner spinnerSelectStim; int spinPosStim;
    Spinner spinnerSelectTax;  int spinPosTax;

    String spinMode = getResources().getString(R.string.currSpinDistLAB);
    private static final TaxCode[] TAX_CODES = {TaxCode.NULL, TaxCode.LABOR, TaxCode.FOOD, TaxCode.TOOL,
            TaxCode.COAL, TaxCode.IRON, TaxCode.TRANSPORT};
    String[] taxCodes = {"% Universal rate", "% Labor rate", "% Food rate", "% Tool rate",
            "% Coal rate", "% Iron rate", "% Transportation rate"};


    public CurrencyView(Context context) {
        super(context);

        inflate(getContext(), R.layout.currency_panel, this);
        final AlertDialog.Builder builder = new AlertDialog.Builder(getContext());

        //AdServer.instance.displayBanner(findViewById(R.id.adViewCurr));

        createButton = findViewById(R.id.btnCreate);
        destroyButton = findViewById(R.id.btnDestroy);
        taxButton =  findViewById(R.id.taxButton);
        setButton = findViewById(R.id.setTaxbutton);
        taxRate = findViewById(R.id.taxRate);
        currAmount = findViewById(R.id.currAmountTxt);
        stimButton = findViewById(R.id.btnStim);
        stimAmount = findViewById(R.id.currAmountStim);
        spinnerSelectStim = findViewById(R.id.spinner);
        spinnerSelectTax = findViewById(R.id.spinnerTaxCode);
        taxRateList = findViewById(R.id.editTextTaxList);

        initTaxSpinner();
        initStimSpinner();


        initButtonListeners(builder);
        initFromState();
    }

    private void initButtonListeners(final AlertDialog.Builder builder) {
        App.onEconomyClick(setButton, new OnClickListener() {
            @Override
            public void onClick(View view) {
                try {
                    double value = Double.parseDouble(taxRate.getText().toString());
                    setTaxRate(spinPosTax, value);
                    showSelectedRate();
                    refreshTaxList();
                    App.vibrate();
                    checkTaxValues();
                 } catch (Exception e) {
                    App.showBasicDialogue(R.string.currErr2, R.string.val0to100);
                }
            }
        });
        App.onEconomyClick(stimButton, new OnClickListener() {
            @Override
            public void onClick(View view) {
                long amount = 0;
                try {
                    amount = App.sim.DtoC(stimAmount.getText().toString());
                    if (amount < 0)
                        throw new RuntimeException();
                } catch (Exception e) {
                    App.showBasicDialogue( R.string.currErr2, R.string.currErr1);
                }
                try {
                    switch (spinPosStim) {
                        case 0: {
                            App.sim.access.taxAccount.distribute(ComType.LABOR, amount, null);
                            break;
                        }
                        case 1: {
                            App.sim.access.taxAccount.distribute(ComType.FOOD, amount, null);
                            break;
                        }
                        case 2: {
                           App.sim.access.taxAccount.distribute(ComType.TOOL, amount, null);
                            break;
                        }
                        case 3: {
                            App.sim.access.taxAccount.distribute(ComType.COAL, amount, null);
                            break;
                        }
                        case 4: {
                            App.sim.access.taxAccount.distribute(ComType.IRON, amount, null);
                            break;
                        }
                        case 5: {
                            App.sim.access.taxAccount.distribute(ComType.TRANSPORTATION, amount, null);
                            break;
                        }

                    }
                    Toast.makeText(getContext(), R.string.stimDist, Toast.LENGTH_SHORT).show();
                    App.vibrate();

                } catch (Throwable e){
                    App.showBasicDialogue(R.string.insFunds, R.string.checkBal);
                }

            }
        });
        App.onEconomyClick(taxButton, new OnClickListener() {
            @Override
            public void onClick(View view) {
                    App.vibrate();
                    App.sim.access.config.collectTax = taxButton.isChecked();
                    checkTaxValues();

            }
        });

         App.onEconomyClick(createButton, new OnClickListener() {
            @Override
            public void onClick(View view) {
                try{
                    long amount = App.sim.DtoC(currAmount.getText().toString());
                    if(amount > 1000000 * 100 || amount < 0) {
                        throw new RuntimeException();
                    }
                    Account acct = new Account(App.sim.access, amount);
                    App.sim.access.taxAccount.__transfer(acct, amount);
                    Toast.makeText(getContext(), getResources().getText(R.string.currSymbol) + App.sim.CtD(amount) + getResources().getText(R.string.moneyCreated), Toast.LENGTH_SHORT).show();
                    App.vibrate();
                }catch (Exception e) {
                    App.showBasicDialogue(R.string.currErr2, R.string.val0to1000000);
                }
            }
        });

        App.onEconomyClick(destroyButton, new OnClickListener() {
            @Override
            public void onClick(View view) {
                try {
                    long amount = App.sim.DtoC(currAmount.getText().toString());
                    if (amount < 0) throw new RuntimeException();
                    App.sim.access.taxAccount.destroy(amount);
                    Toast.makeText(getContext(), getResources().getText(R.string.currSymbol) + App.sim.CtD(amount) + getResources().getText(R.string.moneyDestroyed), Toast.LENGTH_SHORT).show();
                    App.vibrate();
                } catch (Exception e) {
                    if (e.getMessage() == "Insuffencient Funds") {
                        App.showBasicDialogue( R.string.checkBal, R.string.insFunds);

                    } else if (e.getMessage() == "Not enough reserves") {
                        App.showBasicDialogue( "Bank reserve fund is too low", "Bank Alert");
                    }else{
                        App.showBasicDialogue(R.string.currErr2, R.string.val0to1000000);
                    }
                }
            }
        });
    }

    private void initTaxSpinner() {
        List<String> spinOptions = new ArrayList<String>();
        spinOptions.add("Universal");
        spinOptions.add("Labor");
        spinOptions.add("Food");
        spinOptions.add("Tool");
        spinOptions.add("Coal");
        spinOptions.add("Iron");
        spinOptions.add("Transportation");

        ArrayAdapter<String> adapterB = new ArrayAdapter<String>(getContext(), R.layout.spinner_big, spinOptions);
        adapterB.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerSelectTax.setAdapter(adapterB);
        spinnerSelectTax.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {

                spinPosTax = i;
                showSelectedRate();
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {

            }
        });
    }

    private void initStimSpinner() {
        List<String> spinOptions = new ArrayList<String>();
        spinOptions.add(getResources().getString(R.string.currSpinDistLAB));
        spinOptions.add(getResources().getString(R.string.currSpinDistFOOD));
        spinOptions.add(getResources().getString(R.string.currSpinDistTOOL));
        spinOptions.add(getResources().getString(R.string.currSpinDistCOAL));
        spinOptions.add(getResources().getString(R.string.currSpinDistIRON));
        spinOptions.add(getResources().getString(R.string.currSpinDistSHIP));

        ArrayAdapter<String> adapterB = new ArrayAdapter<String>(getContext(), R.layout.spinner_big, spinOptions);
        adapterB.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerSelectStim.setAdapter(adapterB);
        spinnerSelectStim.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                spinMode = (String)adapterView.getItemAtPosition(i);
                spinPosStim = i;
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {

            }
        });
    }

    private double getTaxRate(int taxCodeID) {
        return TaxRates.get(App.sim.access.config, TAX_CODES[taxCodeID]);
    }

    private void setTaxRate(int taxCodeID, double value) {
        TaxRates.set(App.sim.access.config, TAX_CODES[taxCodeID], value);
    }

    private void checkTaxValues() {
        if (!TaxRates.isValid(App.sim.access.config)) {
            taxButton.setChecked(false);
            App.sim.access.config.collectTax = false;
            App.showBasicDialogue("Check values", "Tax rates must be between 0% and 100%, including the combined rate for each sector.");
        }
    }

    private void refreshTaxList() {
        String text = "";
        for(int i = 0; i <= 6; i++) {
            text +=  Double.toString(getTaxRate(i)) + taxCodes[i] + '\n';
        }
        if (!text.contentEquals(taxRateList.getText())) taxRateList.setText(text);
    }

    private void showSelectedRate() {
        lastTaxRate = Double.toString(getTaxRate(spinPosTax));
        taxRate.setText(lastTaxRate);
    }

    void refreshTaxPolicy() {
        String current = Double.toString(getTaxRate(spinPosTax));
        if (!current.equals(lastTaxRate) && lastTaxRate.contentEquals(taxRate.getText())) taxRate.setText(current);
        lastTaxRate = current;
        taxButton.setChecked(App.sim.access.config.collectTax);
        refreshTaxList();
    }

    public void initFromState(){
        showSelectedRate();
        taxButton.setChecked(App.sim.access.config.collectTax);
        refreshTaxList();
        checkTaxValues();
    }

}
