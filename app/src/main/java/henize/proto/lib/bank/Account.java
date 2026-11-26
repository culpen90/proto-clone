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
package henize.proto.lib.bank;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import henize.proto.lib.sim.Access;
import henize.proto.lib.traders.ComType;
import henize.proto.lib.traders.Trader;

/**
 * Created by ACR411 on 12/13/2017.
 */

public class Account implements Serializable {
    public enum TaxCode {LABOR, FOOD, TOOL, COAL, IRON, TRANSPORT, NULL, MASTER}

    private List<TransactionListener> tl_list = new ArrayList<TransactionListener>();
    private Access access;
    private long balance;
    public TaxCode taxCode;
    public long debt;
    public boolean freeze;
    /**
     * Create a new account.
     * @param access central simulator access
     * @param balance balance of new account
     */
    public Account(Access access, long balance){
        this.access = access;
        this.balance = balance;
        access.bankStats.totalCurrency += balance;
        access.bank.addAccount(this);
        addListener(access.sim);
    }

    public void setTaxCode(TaxCode taxCode) {
        this.taxCode = taxCode;
    }

    //Get balance of account.
    public long getBalance(){
        return balance;
    }

    //Close account, destroy balance, remove transaction listener.
    public void close(){
        destroy(balance);
        //access.bank.removeAccount(this);
        removeListener(access.sim);
    }

    //Subtract balance from total currency statistic and zero out balance.
    public void cancel(){
        access.bankStats.totalCurrency -= balance;
        balance = 0;
    }

    /**
     * Destroy a specified amount of currency, and update currency statistics.
     * @param amount
     * @throws RuntimeException
     */
    public void destroy(long amount) throws RuntimeException {

        if (balance < amount) {
            throw new RuntimeException("Insuffencient Funds");
        }
        // Issuing currency added the same amount to reserves. Retiring it reverses both entries.
        access.bank.removeReserve(amount);
        balance -= amount;
        access.bankStats.totalCurrency -= amount;
        access.bankStats.lostCurrency += amount;
    }

    /**
     * Refund a specified amount of currency to specified account, take back taxes paid, and update statistics accordingly.
     * @param to
     * @param amount
     */
    public void refund(Account to, long amount) {
        //if taxation enabled then take back taxes paid.
        if (access.config.collectTax)
        {
            __transfer(access.taxAccount, getTaxAmount(amount, TaxCode.NULL));
            __transfer(access.taxAccount, getTaxAmount(amount, this.taxCode));
        }
        //refund currency and negate stats.
        to.__transfer(this, amount);
        access.bankStats.totalTrValue -= amount;
        access.bankStats.transactionCount--;
        sendTransactionSignal(true);
    }

    /**
     * Send specified amount of currency to specified account. Will collect taxes if taxation enabled.
     * @param to
     * @param amount
     */
    public boolean send(Account to, long amount){
        if(freeze) return false;
        if(access.bank.enoughReserves(amount)){
            to.receive(this, amount);
            access.bank.fail = false;
            return true;
        } else {
            access.bank.fail = true;
            access.bankStats.insolvencyHits++;
            return false;
        }
    }

    /**
     * Send specified amount of currency to specified account and collect taxes.
     * @param to
     * @param amount
     * @param tax true to collect tax
     * @param isTax true of sending money already taxed
     */
    private void send(Account to, long amount, boolean tax, boolean isTax) {

        to.receive(this, amount, tax, isTax);
    }

    /**
     * Recieve specified amount of currency from specified account
     * @param from
     * @param amount
     */
    private void receive(Account from, long amount){
        receive(from, amount, access.config.collectTax, false);
    }

    /**
     * Receive specified amount of currency from specified account, collect taxes if applicable.
     * @param from
     * @param amount
     * @param collectTax true to collect tax
     * @param isTax true of is money already taxed
     */
    private void receive(Account from, long amount, boolean collectTax, boolean isTax){
        __transfer(from, amount);
        processTransaction(amount, collectTax, isTax);
    }

    /**
     * Low level method to transfer money without updating statistics or collecting taxes.
     * @param from
     * @param amount
     * @throws RuntimeException
     */
    public void __transfer(Account from, long amount) throws RuntimeException {
        if (from.balance < amount) {
            throw new RuntimeException("Insuffencient funds");
        }
        from.balance -= amount;
        this.balance += amount;
    }
    public void __edit(long amount) {
        balance += amount;
    }
    public void getLoan(long amount) {
         access.bank.issueLoanTo(this, amount);
    }

    /**
     * Evenly distribute a specified amount of currency to all traders of a specified type
     * @param t
     * @param amount
     * @param excluded trader to omit, such as a worker distributing their estate
     */
    public void distribute(ComType t, long amount, Trader excluded) {
        if (amount < 0 || amount > balance) throw new IllegalArgumentException("Invalid distribution amount");
        if (amount == 0) return;
        List<Account> recipients = new ArrayList<>();
        for (Trader trader : access.dir.getListFromType(t).getList()) {
            if (trader != excluded && !trader.isDead && trader.account != this && !"MASTER".equals(trader.id)) {
                recipients.add(trader.account);
            }
        }
        if (recipients.isEmpty()) return;
        long share = amount / recipients.size();
        long remainder = amount % recipients.size();
        for (int i = 0; i < recipients.size(); i++) {
            recipients.get(i).__transfer(this, share + (i < remainder ? 1 : 0));
        }
    }

    /**
     * Collect taxes if specified, update statistics if applicable.
     * @param amount
     * @param collectTax
     * @param isTax
     * @throws RuntimeException
     */
    private void processTransaction(long amount, boolean collectTax, boolean isTax) throws RuntimeException {
        if (collectTax) {
            if (isTax) {
                throw new RuntimeException("Invalid bank operation");
            }
            collectTax(amount);
        }

        if (isTax) return;

        endTransaction(amount);
    }

    /**
     * Update statistics
     * @param amount
     */
    private void endTransaction(long amount){
        access.bankStats.transactionCount++;
        access.bankStats.totalTrValue += amount;
        sendTransactionSignal(false);
    }

    /**
     * Collect tax
     * @param amount
     */
    private void collectTax(long amount) {
        this.send(access.taxAccount, getTaxAmount(amount, TaxCode.NULL), false, true);
        this.send(access.taxAccount, getTaxAmount(amount, this.taxCode), false, true);
    }

    private long getTaxAmount(long amount, TaxCode taxCode) {
        return (long) (amount * (TaxRates.get(access.config, taxCode) / 100));
    }

    //Transaction event listeners.
    public void addListener(TransactionListener listener){
        tl_list.add(listener);
    }
    public void removeListener(TransactionListener listener){
        tl_list.remove(listener);
    }
    private void sendTransactionSignal(boolean refund){
        for(TransactionListener l : tl_list) l.transactionNotification(refund);
    }
}
