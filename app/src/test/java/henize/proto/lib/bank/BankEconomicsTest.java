package henize.proto.lib.bank;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import henize.proto.lib.sim.Access;
import henize.proto.lib.sim.Config;
import henize.proto.lib.stats.BankStats;

import static org.junit.Assert.*;

public class BankEconomicsTest {
    private Access createBank(long cash) {
        Access access = new Access();
        access.config = new Config();
        access.bankStats = new BankStats();
        access.bank = new Bank(access);
        access.bank.initBanksAccount(cash);
        access.bank.account.removeListener(null);
        access.taxAccount = createAccount(access, 0, Account.TaxCode.MASTER);
        return access;
    }

    private Account createAccount(Access access, long cash, Account.TaxCode taxCode) {
        Account account = new Account(access, cash);
        // No Simulator is installed in this isolated bank fixture; observe transactions directly.
        account.removeListener(null);
        account.setTaxCode(taxCode);
        return account;
    }

    @Test
    public void taxedSaleAndRefundRestoreBalancesAndTransactionStatistics() {
        Access access = createBank(1000);
        Account buyer = createAccount(access, 100, Account.TaxCode.LABOR);
        Account seller = createAccount(access, 0, Account.TaxCode.FOOD);
        access.config.collectTax = true;
        access.config.universalTaxRate = 10;
        access.config.foodTaxRate = 20;
        List<Boolean> notifications = new ArrayList<>();
        seller.addListener(notifications::add);

        assertTrue(buyer.send(seller, 50));
        assertEquals(50, buyer.getBalance());
        assertEquals(35, seller.getBalance());
        assertEquals(15, access.taxAccount.getBalance());
        assertEquals(1, access.bankStats.transactionCount);
        assertEquals(50, access.bankStats.totalTrValue);
        assertEquals(1100, access.bankStats.totalCurrency);

        seller.refund(buyer, 50);
        assertEquals(100, buyer.getBalance());
        assertEquals(0, seller.getBalance());
        assertEquals(0, access.taxAccount.getBalance());
        assertEquals(0, access.bankStats.transactionCount);
        assertEquals(0, access.bankStats.totalTrValue);
        assertEquals(1100, access.bankStats.totalCurrency);
        assertEquals(2, notifications.size());
        assertEquals(Boolean.FALSE, notifications.get(0));
        assertEquals(Boolean.TRUE, notifications.get(1));
    }

    @Test
    public void loanFromBankCashAndRepaymentTrackDebt() {
        Access access = createBank(1000);
        Account borrower = createAccount(access, 0, Account.TaxCode.TOOL);
        assertTrue(access.bank.issueLoanTo(borrower, 100));
        assertEquals(100, borrower.getBalance());
        assertEquals(900, access.bank.account.getBalance());
        assertEquals(100, borrower.debt);
        assertEquals(100, access.bankStats.totalDebt);
        assertFalse(access.bank.creditMeltdown);

        access.bank.payOnLoan(borrower, 40);
        assertEquals(60, borrower.getBalance());
        assertEquals(940, access.bank.account.getBalance());
        assertEquals(60, borrower.debt);
        assertEquals(60, access.bankStats.totalDebt);
        assertEquals(1000, access.bank.reserve);
        assertEquals(1000, access.bankStats.totalCurrency);
    }

    @Test
    public void reserveFundedLoanAndRepaymentRestoreReserves() {
        Access access = createBank(0);
        createAccount(access, 10000, Account.TaxCode.LABOR);
        Account borrower = createAccount(access, 0, Account.TaxCode.TOOL);
        assertTrue(access.bank.issueLoanTo(borrower, 1000));
        assertEquals(9000, access.bank.reserve);
        assertEquals(1000, borrower.getBalance());
        assertEquals(1000, access.bankStats.totalDebt);
        access.bank.payOnLoan(borrower, 400);
        assertEquals(9400, access.bank.reserve);
        assertEquals(600, borrower.getBalance());
        assertEquals(600, access.bankStats.totalDebt);
        assertEquals(10000, access.bankStats.totalCurrency);
    }

    @Test
    public void refusedLoanAndFrozenPaymentDoNotMoveMoney() {
        Access access = createBank(0);
        Account depositor = createAccount(access, 10000, Account.TaxCode.LABOR);
        Account borrower = createAccount(access, 0, Account.TaxCode.TOOL);
        access.bank.reserveRate = 1;
        assertFalse(access.bank.issueLoanTo(borrower, 1000));
        assertTrue(access.bank.creditMeltdown);
        assertEquals(0, borrower.getBalance());
        assertEquals(0, borrower.debt);
        assertEquals(0, access.bankStats.totalDebt);
        assertEquals(10000, access.bank.reserve);
        depositor.freeze = true;
        assertFalse(depositor.send(borrower, 100));
        assertEquals(10000, depositor.getBalance());
        assertEquals(0, borrower.getBalance());
        assertEquals(0, access.bankStats.transactionCount);
        assertFalse(access.bank.issueLoanTo(access.taxAccount, 100));
    }
}
