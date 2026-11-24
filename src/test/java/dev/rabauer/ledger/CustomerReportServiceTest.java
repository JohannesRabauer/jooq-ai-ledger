package dev.rabauer.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import dev.rabauer.ledger.CustomerReportService.AccountMonth;
import dev.rabauer.ledger.CustomerReportService.CustomerMonth;

class CustomerReportServiceTest extends AbstractLedgerTest {

    @Autowired
    TransferService transfers;
    @Autowired
    CustomerReportService reports;

    @Test
    void monthlyTotalsAndClosingBalancePerAccount() {
        // far-away month so other tests' data does not matter
        YearMonth month = YearMonth.of(2019, 5);
        OffsetDateTime inMonth = OffsetDateTime.of(2019, 5, 10, 12, 0, 0, 0, ZoneOffset.UTC);
        long c = customer("Report Customer");
        long checking = account(c, "EUR", true);
        long savings = account(c, "EUR", true);
        long empty = account(c, "USD", false);
        long external = account(customer("Report External"), "EUR", true);
        long noAccounts = customer("Report No Accounts");

        transfers.transfer(external, checking, new BigDecimal("200.00"), "before", inMonth.minusMonths(1));
        transfers.transfer(external, checking, new BigDecimal("50.00"), "in", inMonth);
        transfers.transfer(checking, savings, new BigDecimal("80.00"), "in", inMonth.plusDays(1));
        transfers.transfer(checking, external, new BigDecimal("10.00"), "in", inMonth.plusDays(2));
        transfers.transfer(external, checking, new BigDecimal("999.00"), "after", inMonth.plusMonths(1));

        List<CustomerMonth> report = reports.report(month);

        CustomerMonth mine = report.stream().filter(m -> m.customerId() == c).findFirst().orElseThrow();
        assertThat(mine.accounts()).extracting(AccountMonth::accountId).containsExactly(checking, savings, empty);
        AccountMonth chk = mine.accounts().get(0);
        assertThat(chk.credits()).isEqualByComparingTo("50.00");
        assertThat(chk.debits()).isEqualByComparingTo("-90.00");
        assertThat(chk.closingBalance()).isEqualByComparingTo("160.00"); // 200 + 50 - 80 - 10, "after" excluded
        AccountMonth sav = mine.accounts().get(1);
        assertThat(sav.credits()).isEqualByComparingTo("80.00");
        assertThat(sav.debits()).isEqualByComparingTo("0");
        assertThat(sav.closingBalance()).isEqualByComparingTo("80.00");
        AccountMonth none = mine.accounts().get(2);
        assertThat(none.credits()).isEqualByComparingTo("0");
        assertThat(none.closingBalance()).isEqualByComparingTo("0");

        CustomerMonth lonely = report.stream().filter(m -> m.customerId() == noAccounts).findFirst().orElseThrow();
        assertThat(lonely.accounts()).isEmpty();
    }
}
