package dev.rabauer.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import dev.rabauer.ledger.StatementService.Cursor;
import dev.rabauer.ledger.StatementService.StatementLine;

class StatementServiceTest extends AbstractLedgerTest {

    @Autowired
    TransferService transfers;
    @Autowired
    StatementService statements;

    @Test
    void pagesThroughStatementWithRunningBalance() {
        long c = customer("stmt");
        long a = account(c, "EUR", true);
        long other = account(c, "EUR", true);
        OffsetDateTime base = OffsetDateTime.of(2021, 3, 1, 10, 0, 0, 0, ZoneOffset.UTC);

        // booked out of insertion order on purpose: booking order is by booked_at, then posting id
        transfers.transfer(other, a, new BigDecimal("50.00"), "third", base.plusDays(3));
        transfers.transfer(other, a, new BigDecimal("100.00"), "first", base.plusDays(1));
        transfers.transfer(a, other, new BigDecimal("30.00"), "second", base.plusDays(2));
        transfers.transfer(other, a, new BigDecimal("5.00"), "fourth", base.plusDays(4));
        transfers.transfer(a, other, new BigDecimal("1.00"), "fifth", base.plusDays(5));

        List<StatementLine> all = new ArrayList<>();
        Cursor cursor = null;
        int pages = 0;
        while (true) {
            List<StatementLine> page = statements.statement(a, cursor, 2);
            if (page.isEmpty()) {
                break;
            }
            assertThat(page).hasSizeLessThanOrEqualTo(2);
            all.addAll(page);
            cursor = page.getLast().cursor();
            pages++;
        }

        assertThat(pages).isEqualTo(3);
        assertThat(all).extracting(StatementLine::description)
                .containsExactly("first", "second", "third", "fourth", "fifth");
        assertThat(all).extracting(StatementLine::balanceAfter)
                .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactly(new BigDecimal("100.00"), new BigDecimal("70.00"), new BigDecimal("120.00"),
                        new BigDecimal("125.00"), new BigDecimal("124.00"));
        assertThat(all.getLast().balanceAfter()).isEqualByComparingTo(balance(a));
    }

    @Test
    void emptyAccountHasEmptyStatement() {
        long a = account(customer("empty"), "EUR", false);
        assertThat(statements.statement(a, null, 10)).isEmpty();
    }
}
