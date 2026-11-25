package dev.rabauer.ledger;

import static dev.rabauer.ledger.jooq.Tables.ACCOUNT;
import static dev.rabauer.ledger.jooq.Tables.LEDGER_TRANSACTION;
import static dev.rabauer.ledger.jooq.Tables.POSTING;
import static org.jooq.impl.DSL.avg;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.generateSeries;
import static org.jooq.impl.DSL.min;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.sum;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.val;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.types.DayToSecond;
import org.springframework.stereotype.Service;

@Service
public class DailyBalanceService {

    public record DayBalance(LocalDate day, BigDecimal endOfDayBalance) {
    }

    /** {@code average} and {@code lowest} are taken over the month's day-end balances. */
    public record AccountDailyBalances(long accountId, String currency, List<DayBalance> days,
            BigDecimal average, BigDecimal lowest) {
    }

    private final DSLContext dsl;

    public DailyBalanceService(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** All accounts. See {@link #report(YearMonth, Collection)}. */
    public List<AccountDailyBalances> report(YearMonth month) {
        return report(month, List.of());
    }

    /**
     * For each account (or only {@code accountIds} when not empty): the balance at the end of every day of
     * {@code month} (UTC), days without postings included, the average of these and the lowest one. One statement:
     * a generated day axis cross joined with the accounts, a correlated sum of everything booked before the end of
     * the day (so the opening balance and a posting exactly at midnight count for the right day), and window
     * functions for average and minimum. A balance is per account, so currencies are never mixed.
     */
    public List<AccountDailyBalances> report(YearMonth month, Collection<Long> accountIds) {
        OffsetDateTime start = month.atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        int length = month.lengthOfMonth();

        Table<?> days = generateSeries(0, length - 1).asTable("days", "n");
        Field<Integer> n = field(name("days", "n"), Integer.class);
        // Fixed 24-hour steps (not "1 day"), so the result does not depend on the session time zone or DST.
        Field<OffsetDateTime> dayEnd = val(start).add(inline(new DayToSecond(0, 1)).mul(n.plus(1).times(24)));

        Field<BigDecimal> balance = coalesce(
                select(sum(POSTING.AMOUNT))
                        .from(POSTING)
                        .join(LEDGER_TRANSACTION).on(LEDGER_TRANSACTION.ID.eq(POSTING.TRANSACTION_ID))
                        .where(POSTING.ACCOUNT_ID.eq(ACCOUNT.ID))
                        .and(LEDGER_TRANSACTION.BOOKED_AT.lt(dayEnd))
                        .asField(),
                BigDecimal.ZERO);

        Condition filter = accountIds.isEmpty() ? DSL.noCondition() : ACCOUNT.ID.in(accountIds);
        Table<?> daily = select(ACCOUNT.ID.as("account_id"), ACCOUNT.CURRENCY.as("currency"),
                n.as("n"), balance.as("balance"))
                .from(ACCOUNT)
                .crossJoin(days)
                .where(filter)
                .asTable("daily");

        Field<Long> accountId = daily.field("account_id", Long.class);
        Field<String> currency = daily.field("currency", String.class);
        Field<Integer> dayIndex = daily.field("n", Integer.class);
        Field<BigDecimal> dayBalance = daily.field("balance", BigDecimal.class);

        Map<Long, AccountDailyBalances> byAccount = new LinkedHashMap<>();
        Map<Long, List<DayBalance>> daysOf = new LinkedHashMap<>();
        for (Record r : dsl
                .select(accountId, currency, dayIndex, dayBalance,
                        avg(dayBalance).over(DSL.partitionBy(accountId)),
                        min(dayBalance).over(DSL.partitionBy(accountId)))
                .from(daily)
                .orderBy(accountId, dayIndex)
                .fetch()) {
            long id = r.get(accountId);
            List<DayBalance> list = daysOf.computeIfAbsent(id, k -> new ArrayList<>());
            list.add(new DayBalance(month.atDay(1).plusDays(r.get(dayIndex)), r.get(dayBalance)));
            byAccount.putIfAbsent(id, new AccountDailyBalances(id, r.get(currency), list,
                    r.get(4, BigDecimal.class), r.get(5, BigDecimal.class)));
        }
        return List.copyOf(byAccount.values());
    }
}
