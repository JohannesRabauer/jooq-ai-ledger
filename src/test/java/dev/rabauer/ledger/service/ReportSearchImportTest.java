package dev.rabauer.ledger.service;

import static dev.rabauer.ledger.jooq.Tables.LEDGER_TRANSACTION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ReportSearchImportTest extends AbstractLedgerTest {

    @Autowired
    ReportService reports;
    @Autowired
    SearchService search;

    @Test
    void monthlyReportNestsAccountsWithCreditsDebitsAndClosingBalance() {
        YearMonth month = YearMonth.of(2031, 3);
        OffsetDateTime inMarch = OffsetDateTime.of(2031, 3, 10, 12, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime inFebruary = OffsetDateTime.of(2031, 2, 20, 12, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime inApril = OffsetDateTime.of(2031, 4, 2, 12, 0, 0, 0, ZoneOffset.UTC);

        long customer = newCustomer("Report " + UUID.randomUUID());
        long a1 = newAccount(customer, "EUR", true);
        long a2 = newAccount(customer, "USD", true);
        long emptyCustomer = newCustomer("No accounts " + UUID.randomUUID());
        long other = newAccount("EUR", true);

        deposit(a1, "EUR", "100.00", inFebruary, "feb deposit");
        deposit(a1, "EUR", "40.00", inMarch, "march deposit");
        ledger.book("march out", inMarch.plusDays(1), null, List.of(
                new PostingSpec(a1, new BigDecimal("-25.00")), new PostingSpec(other, new BigDecimal("25.00"))));
        deposit(a1, "EUR", "999.00", inApril, "april deposit");
        // a2 has nothing at all

        List<ReportService.CustomerReport> report = reports.monthlyReport(month);

        ReportService.CustomerReport mine = report.stream().filter(r -> r.customerId() == customer).findFirst().orElseThrow();
        assertThat(mine.accounts()).hasSize(2);
        ReportService.AccountSummary s1 = mine.accounts().get(0);
        assertThat(s1.accountId()).isEqualTo(a1);
        assertThat(s1.credits()).isEqualByComparingTo("40.00");
        assertThat(s1.debits()).isEqualByComparingTo("25.00");
        assertThat(s1.closingBalance()).isEqualByComparingTo("115.00"); // April is not included
        ReportService.AccountSummary s2 = mine.accounts().get(1);
        assertThat(s2.accountId()).isEqualTo(a2);
        assertThat(s2.credits()).isEqualByComparingTo("0");
        assertThat(s2.debits()).isEqualByComparingTo("0");
        assertThat(s2.closingBalance()).isEqualByComparingTo("0");

        ReportService.CustomerReport empty = report.stream().filter(r -> r.customerId() == emptyCustomer).findFirst().orElseThrow();
        assertThat(empty.accounts()).isEmpty();
    }

    @Test
    void searchAppliesOnlyTheGivenFilters() {
        String tag = "srch" + UUID.randomUUID().toString().substring(0, 8);
        long a = newAccount("EUR", true);
        long b = newAccount("EUR", true);
        OffsetDateTime t = OffsetDateTime.of(2032, 5, 1, 10, 0, 0, 0, ZoneOffset.UTC);
        ledger.book("Invoice " + tag + " small", t, null, pair(a, b, "10.00"));
        ledger.book("Invoice " + tag + " medium", t.plusDays(10), null, pair(a, b, "50.00"));
        ledger.book("Rent " + tag.toUpperCase() + " big", t.plusDays(20), null, pair(b, a, "500.00"));

        // text only (case-insensitive)
        assertThat(descriptions(new SearchService.Criteria(null, null, null, null, tag.toUpperCase(), null)))
                .containsExactly("Rent " + tag.toUpperCase() + " big", "Invoice " + tag + " medium",
                        "Invoice " + tag + " small");
        // date range
        assertThat(descriptions(new SearchService.Criteria(t.plusDays(5), t.plusDays(15), null, null, tag, null)))
                .containsExactly("Invoice " + tag + " medium");
        // amount range (absolute)
        assertThat(descriptions(new SearchService.Criteria(null, null, new BigDecimal("20"), new BigDecimal("100"), tag, null)))
                .containsExactly("Invoice " + tag + " medium");
        assertThat(descriptions(new SearchService.Criteria(null, null, new BigDecimal("100"), null, tag, null)))
                .containsExactly("Rent " + tag.toUpperCase() + " big");
        // account
        assertThat(descriptions(new SearchService.Criteria(null, null, null, null, tag, a))).hasSize(3);
        assertThat(descriptions(new SearchService.Criteria(null, null, null, null, tag, newAccount("EUR", true)))).isEmpty();
        // nothing: still works, returns transactions
        assertThat(search.search(new SearchService.Criteria(null, null, null, null, null, null), 2)).hasSize(2);

        // postings are nested in the result
        SearchService.TransactionView view = search.search(
                new SearchService.Criteria(null, null, null, null, "medium " .trim() + "", a), 1).getFirst();
        assertThat(view.postings()).hasSize(2);
    }

    @Test
    void searchSqlContainsOnlyGivenFilters() {
        String none = sql(new SearchService.Criteria(null, null, null, null, null, null));
        assertThat(none).doesNotContain("where").doesNotContain("like").doesNotContain("exists");

        String textOnly = sql(new SearchService.Criteria(null, null, null, null, "abc", null));
        assertThat(textOnly).contains("like").doesNotContain("booked_at\" >=").doesNotContain("booked_at\" <")
                .doesNotContain("abs(").doesNotContain("account_id\" =").doesNotContain("exists");

        String dateOnly = sql(new SearchService.Criteria(OffsetDateTime.now().minusDays(1), null, null, null, null, null));
        assertThat(dateOnly).contains("booked_at\" >=").doesNotContain("like").doesNotContain("exists");

        String accountOnly = sql(new SearchService.Criteria(null, null, null, null, null, 7L));
        assertThat(accountOnly).contains("exists").contains("account_id\" = ").doesNotContain("abs(").doesNotContain("like");

        String amountMin = sql(new SearchService.Criteria(null, null, BigDecimal.ONE, null, null, null));
        assertThat(amountMin).contains("abs(").contains(">=").doesNotContain("<= ").doesNotContain("account_id\" =");
    }

    @Test
    void importingTheSameFileTwiceBooksNothingTwice() {
        long a = newAccount("EUR", true);
        long b = newAccount("EUR", true);
        String k1 = "bank-" + UUID.randomUUID();
        String k2 = "bank-" + UUID.randomUUID();
        List<LedgerService.ImportEntry> file = List.of(
                new LedgerService.ImportEntry(k1, "SEPA 1", OffsetDateTime.now().minusDays(1), pair(a, b, "12.00")),
                new LedgerService.ImportEntry(k2, "SEPA 2", OffsetDateTime.now().minusDays(1), pair(b, a, "2.00")));

        LedgerService.ImportResult first = ledger.importBatch(file);
        LedgerService.ImportResult second = ledger.importBatch(file);

        assertThat(first).isEqualTo(new LedgerService.ImportResult(2, 0));
        assertThat(second).isEqualTo(new LedgerService.ImportResult(0, 2));
        assertThat(dsl.fetchCount(LEDGER_TRANSACTION, LEDGER_TRANSACTION.IDEMPOTENCY_KEY.in(k1, k2))).isEqualTo(2);
        assertThat(balance(a)).isEqualByComparingTo("-10.00");
        assertThat(balance(b)).isEqualByComparingTo("10.00");
        assertThat(unbalancedTransactions()).isZero();
    }

    @Test
    void partiallyKnownBatchBooksOnlyTheNewEntries() {
        long a = newAccount("EUR", true);
        long b = newAccount("EUR", true);
        String k1 = "bank-" + UUID.randomUUID();
        String k2 = "bank-" + UUID.randomUUID();
        ledger.importBatch(List.of(new LedgerService.ImportEntry(k1, "one", null, pair(a, b, "1.00"))));

        LedgerService.ImportResult r = ledger.importBatch(List.of(
                new LedgerService.ImportEntry(k1, "one", null, pair(a, b, "1.00")),
                new LedgerService.ImportEntry(k2, "two", null, pair(a, b, "2.00"))));

        assertThat(r).isEqualTo(new LedgerService.ImportResult(1, 1));
        assertThat(balance(b)).isEqualByComparingTo("3.00");
    }

    @Test
    void invalidEntryRollsBackTheWholeBatch() {
        long a = newAccount("EUR", true);
        long b = newAccount("EUR", true);
        String k1 = "bank-" + UUID.randomUUID();
        String k2 = "bank-" + UUID.randomUUID();

        assertThatThrownBy(() -> ledger.importBatch(List.of(
                new LedgerService.ImportEntry(k1, "good", null, pair(a, b, "1.00")),
                new LedgerService.ImportEntry(k2, "unbalanced", null, List.of(
                        new PostingSpec(a, new BigDecimal("-1.00")), new PostingSpec(b, new BigDecimal("2.00")))))))
                .isInstanceOf(LedgerException.class);

        assertThat(dsl.fetchCount(LEDGER_TRANSACTION, LEDGER_TRANSACTION.IDEMPOTENCY_KEY.in(k1, k2))).isZero();
        assertThat(balance(b)).isEqualByComparingTo("0");
    }

    @Test
    void concurrentImportsOfTheSameKeyBookOnce() throws Exception {
        long a = newAccount("EUR", true);
        long b = newAccount("EUR", true);
        String k = "bank-" + UUID.randomUUID();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(6);
        List<java.util.concurrent.Future<LedgerService.ImportResult>> fs = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            fs.add(pool.submit(() -> ledger.importBatch(List.of(
                    new LedgerService.ImportEntry(k, "once", null, pair(a, b, "7.00"))))));
        }
        int booked = 0;
        for (var f : fs) {
            booked += f.get().booked();
        }
        pool.shutdown();
        assertThat(booked).isEqualTo(1);
        assertThat(balance(b)).isEqualByComparingTo("7.00");
    }

    private List<PostingSpec> pair(long from, long to, String amount) {
        BigDecimal x = new BigDecimal(amount);
        return List.of(new PostingSpec(from, x.negate()), new PostingSpec(to, x));
    }

    private List<String> descriptions(SearchService.Criteria c) {
        return search.search(c, 100).stream().map(SearchService.TransactionView::description).toList();
    }

    private String sql(SearchService.Criteria c) {
        String full = search.buildQuery(c, 10).getSQL(org.jooq.conf.ParamType.INLINED).toLowerCase();
        // the multiset subquery has its own where clause, so look only at the outer query after its from
        return full.substring(full.lastIndexOf(") from \"public\".\"ledger_transaction\""));
    }
}
