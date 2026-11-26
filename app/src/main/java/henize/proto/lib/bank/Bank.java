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
import java.math.BigDecimal;
import java.util.ArrayList;

import henize.proto.lib.sim.Access;

/**
 * Created by ACR411 on 8/14/2019.
 */

public class Bank implements Serializable{
    public long reserve;
    public Account account;
    private Access access;
    public int cycle;
    public double minPayPercent = 0.1, interestRate = 0.15, reserveRate = 0.2;
    public ArrayList<Account> accounts;
    public boolean fail, creditMeltdown;


    public Bank(Access access) {
        accounts = new ArrayList<>();
        this.access = access;

    }

    public void initBanksAccount(long amount){
        account = new Account(access, amount);
    }

    public void act() {
        if(++cycle > 10) {
            cycle = 0;
            double cycleRate = interestRate;
            for(Account t : accounts) {
                if(t.debt > 0) {

                    long payment = (long)(t.debt * minPayPercent);
                    if (cycleRate < 0) {
                        // Credit the principal first. Only the uncovered installment is due in cash.
                        long credit = rateAmount(t.debt, -cycleRate);
                        t.debt -= credit;
                        access.bankStats.totalDebt -= credit;
                        if (credit >= payment) {
                            t.freeze = false;
                            continue;
                        }
                        payment -= credit;
                    } else {
                        payment += (long)(t.debt * cycleRate);
                    }
                    long negBal =  payment - t.getBalance();
                    if(negBal > 0) {
                        if(!issueLoanTo(t, negBal)) {
                            payOnLoan(t, t.getBalance());
                            t.freeze = true;
                        }
                    } else {
                        payOnLoan(t, payment);
                        t.freeze = false;
                    }

                }
            }
        }
    }

    public void addAccount(Account acct) {
        accounts.add(acct);
        addReserve(acct.getBalance());
    }

    public void removeAccount(Account acct) {
        accounts.remove(acct);
        removeReserve(acct.getBalance());
    }
    public void addReserve(long amount) {
        reserve += amount;
    }
    public void removeReserve(long amount) {
        if(!enoughReserves(amount)) {
            throw new RuntimeException("Not enough reserves");
        }
        reserve -= amount;
    }
    public boolean enoughReserves(long amount) {
        // Depositors can use the required reserves as well as any authorized overdraft.
        return canDrawReserves(amount, Math.min(0, reserveRate));
    }

    private boolean canDrawReserves(long amount, double rate) {
        if (amount == 0) return true;
        if (rate < 0) {
            long overdraftLimit = rateAmount(access.bankStats.totalCurrency, -rate);
            return reserve >= amount - overdraftLimit;
        }
        return reserve >= amount && (rate == 0 || reserve >= access.bankStats.totalCurrency * rate + amount);
    }

    private static long rateAmount(long amount, double rate) {
        return BigDecimal.valueOf(amount).multiply(BigDecimal.valueOf(rate)).longValue();
    }

    public boolean issueLoanTo(Account acct, long amount) {
        if(acct.taxCode == Account.TaxCode.MASTER) return false;
       // if(acct.freeze) return false;
        long resReq = amount - account.getBalance();
        creditMeltdown = true; // will turn off if loan success
        if(resReq > 0) {
            if (!canDrawReserves(resReq, reserveRate)) return false;
            reserve -= resReq;
            account.__edit(resReq);
        }
        acct.__transfer(account, amount);
        acct.debt +=  amount;
        access.bankStats.totalDebt += amount;
        creditMeltdown = false;
        return true;
    }
    public void payOnLoan(Account acct, long amount) {
        long total = access.bankStats.totalCurrency;
        // Legacy economies could retire deposits while keeping their backing reserves.
        long resGap = reserve >= total ? 0 : total - reserve;
        if(amount <= resGap) {
            acct.__edit(-amount);
            addReserve(amount);
        }else {
            long toAcct = amount - resGap;
            acct.__edit(-resGap);
            addReserve(resGap);
            account.__transfer(acct, toAcct);
        }
        acct.debt-=amount;
        access.bankStats.totalDebt -= amount;
    }

}
