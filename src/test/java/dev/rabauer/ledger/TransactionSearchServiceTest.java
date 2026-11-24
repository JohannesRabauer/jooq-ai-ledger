package dev.rabauer.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import dev.rabauer.ledger.TransactionSearchService.Filter;
import dev.rabauer.ledger.TransactionSearchService.TransactionRow;

class TransactionSearchServiceTest extends AbstractLedgerTest {

    @Autowired
    TransferService transfers;
    @Autowired
    TransactionSearchService search;

    @Test
    void onlyGivenFiltersAppearInTheSql() {
        String none = search.query(new Filter(null, null, null, null, null, null)).getSQL();
        assertThat(none).doesNotContain("where").doesNotContain("having").doesNotContain("exists");

        String text = search.query(new Filter(null, null, null, null, "rent", null)).getSQL().toLowerCase();
        assertThat(text).contains("description").doesNotContain("having").doesNotContain("exists")
                .doesNotContain("booked_at >=").doesNotContain("booked_at <");

        String amounts = search.query(new Filter(null, null, new BigDecimal("1"), null, null, null)).getSQL().toLowerCase();
        assertThat(amounts).contains("having").doesNotContain("where");
    }

    @Test
    void filtersCombine() {
        long c = customer("search");
        long a = account(c, "EUR", true);
        long b = account(c, "EUR", true);
        long z = account(c, "EUR", true);
        OffsetDateTime t = OffsetDateTime.of(2018, 6, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        transfers.transfer(a, b, new BigDecimal("10.00"), "Zq Rent June", t);
        transfers.transfer(b, a, new BigDecimal("250.00"), "zq groceries", t.plusDays(10));
        transfers.transfer(z, b, new BigDecimal("99.99"), "zq other account", t.plusDays(20));

        OffsetDateTime from = t.minusDays(1);
        OffsetDateTime to = t.plusYears(1);
        assertThat(descriptions(search.search(new Filter(from, to, null, null, "zq", null)))).hasSize(3);
        assertThat(descriptions(search.search(new Filter(from, to, null, null, "RENT", null))))
                .containsExactly("Zq Rent June");
        assertThat(descriptions(search.search(new Filter(from, to, new BigDecimal("100"), null, "zq", null))))
                .containsExactly("zq groceries");
        assertThat(descriptions(search.search(new Filter(from, to, null, new BigDecimal("100"), "zq", null))))
                .containsExactly("Zq Rent June", "zq other account");
        assertThat(descriptions(search.search(new Filter(from, to, null, null, "zq", z))))
                .containsExactly("zq other account");
        assertThat(descriptions(search.search(new Filter(t.plusDays(5), t.plusDays(15), null, null, "zq", null))))
                .containsExactly("zq groceries");
        // % in the search text is literal, not a wildcard
        assertThat(search.search(new Filter(from, to, null, null, "%", null))).isEmpty();
    }

    private static List<String> descriptions(List<TransactionRow> rows) {
        return rows.stream().map(TransactionRow::description).toList();
    }
}
