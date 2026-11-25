package dev.rabauer.ledger.service;

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
import static org.jooq.impl.DSL.val;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.Table;
import org.springframework.stereotype.Service;

/** End-of-day balances of every account for one calendar month (UTC). */
@Service
public class DailyBalanceService {

    private final DSLContext dsl;

    public DailyBalanceService(DSLContext dsl) {
        this.dsl = dsl;
    }

    public record DayBalance(LocalDate day, BigDecimal balance) {
    }

    /** average and lowest are computed in SQL over the days of the month. */
    public record AccountDailyBalances(long accountId, String currency, List<DayBalance> days,
                                       BigDecimal average, BigDecimal lowest) {
    }

    /**
     * One query: accounts cross join the days 1..length of month; the balance at the end of a day is a correlated
     * sum of everything booked before the start of the next day (UTC), so a posting at exactly 00:00 belongs to the
     * day that starts there and days without postings carry the previous balance. Average and minimum are window
     * functions over the days of each account; Java only reshapes the rows into one object per account.
     */
    public List<AccountDailyBalances> dailyBalances(YearMonth month) {
        OffsetDateTime monthStart = month.atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC);

        Table<?> days = generateSeries(1, month.lengthOfMonth()).asTable("d", "n");
        Field<Integer> dayNo = field(name("d", "n"), Integer.class);
        // start of the next day: month start plus n days
        Field<OffsetDateTime> nextDayStart = val(monthStart).plus(dayNo);

        Field<BigDecimal> dayEndBalance = coalesce(
                select(sum(POSTING.AMOUNT))
                        .from(POSTING)
                        .join(LEDGER_TRANSACTION).on(LEDGER_TRANSACTION.ID.eq(POSTING.TRANSACTION_ID))
                        .where(POSTING.ACCOUNT_ID.eq(ACCOUNT.ID))
                        .and(LEDGER_TRANSACTION.BOOKED_AT.lt(nextDayStart))
                        .asField(),
                BigDecimal.ZERO).as("balance");

        Table<?> daily = select(ACCOUNT.ID.as("account_id"), ACCOUNT.CURRENCY.as("currency"),
                dayNo.as("day_no"), dayEndBalance)
                .from(ACCOUNT)
                .crossJoin(days)
                .asTable("daily");

        Field<Long> accountId = daily.field("account_id", Long.class);
        Field<String> currency = daily.field("currency", String.class);
        Field<Integer> dayNumber = daily.field("day_no", Integer.class);
        Field<BigDecimal> balance = daily.field("balance", BigDecimal.class);

        Result<? extends Record> rows = dsl.select(accountId, currency, dayNumber, balance,
                        avg(balance).over().partitionBy(accountId).as("average"),
                        min(balance).over().partitionBy(accountId).as("lowest"))
                .from(daily)
                .orderBy(accountId, dayNumber)
                .fetch();

        Map<Long, AccountDailyBalances> result = new LinkedHashMap<>();
        for (Record r : rows) {
            long id = r.get(accountId);
            AccountDailyBalances acc = result.get(id);
            if (acc == null) {
                acc = new AccountDailyBalances(id, r.get(currency), new ArrayList<>(),
                        r.get("average", BigDecimal.class), r.get("lowest", BigDecimal.class));
                result.put(id, acc);
            }
            acc.days().add(new DayBalance(month.atDay(r.get(dayNumber)), r.get(balance)));
        }
        return new ArrayList<>(result.values());
    }
}
