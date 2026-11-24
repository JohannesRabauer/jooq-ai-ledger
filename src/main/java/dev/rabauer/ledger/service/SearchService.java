package dev.rabauer.ledger.service;

import static dev.rabauer.ledger.jooq.Tables.LEDGER_TRANSACTION;
import static dev.rabauer.ledger.jooq.Tables.POSTING;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.multiset;
import static org.jooq.impl.DSL.noCondition;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectOne;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record4;
import org.jooq.Records;
import org.jooq.Select;
import org.springframework.stereotype.Service;

/** Transaction search where only the given filters show up in the SQL. */
@Service
public class SearchService {

    private final DSLContext dsl;

    public SearchService(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** All fields optional. Amount range and account apply to the same posting (absolute amount). */
    public record Criteria(OffsetDateTime from, OffsetDateTime to, BigDecimal minAmount, BigDecimal maxAmount,
                           String text, Long accountId) {
    }

    public record PostingView(long accountId, BigDecimal amount) {
    }

    public record TransactionView(long id, OffsetDateTime bookedAt, String description, List<PostingView> postings) {
    }

    public List<TransactionView> search(Criteria c, int limit) {
        return buildQuery(c, limit).fetch(Records.mapping(TransactionView::new));
    }

    Select<Record4<Long, OffsetDateTime, String, List<PostingView>>> buildQuery(Criteria c, int limit) {
        Condition where = noCondition();
        if (c.from() != null) {
            where = where.and(LEDGER_TRANSACTION.BOOKED_AT.ge(c.from()));
        }
        if (c.to() != null) {
            where = where.and(LEDGER_TRANSACTION.BOOKED_AT.lt(c.to()));
        }
        if (c.text() != null && !c.text().isBlank()) {
            where = where.and(LEDGER_TRANSACTION.DESCRIPTION.containsIgnoreCase(c.text()));
        }

        Condition postingCond = noCondition();
        if (c.minAmount() != null) {
            postingCond = postingCond.and(POSTING.AMOUNT.abs().ge(c.minAmount()));
        }
        if (c.maxAmount() != null) {
            postingCond = postingCond.and(POSTING.AMOUNT.abs().le(c.maxAmount()));
        }
        if (c.accountId() != null) {
            postingCond = postingCond.and(POSTING.ACCOUNT_ID.eq(c.accountId()));
        }
        if (c.minAmount() != null || c.maxAmount() != null || c.accountId() != null) {
            where = where.and(exists(selectOne().from(POSTING)
                    .where(POSTING.TRANSACTION_ID.eq(LEDGER_TRANSACTION.ID)).and(postingCond)));
        }

        return dsl.select(
                        LEDGER_TRANSACTION.ID,
                        LEDGER_TRANSACTION.BOOKED_AT,
                        LEDGER_TRANSACTION.DESCRIPTION,
                        multiset(select(POSTING.ACCOUNT_ID, POSTING.AMOUNT)
                                .from(POSTING)
                                .where(POSTING.TRANSACTION_ID.eq(LEDGER_TRANSACTION.ID))
                                .orderBy(POSTING.ID))
                                .convertFrom(r -> r.map(Records.mapping(PostingView::new))))
                .from(LEDGER_TRANSACTION)
                .where(where)
                .orderBy(LEDGER_TRANSACTION.BOOKED_AT.desc(), LEDGER_TRANSACTION.ID.desc())
                .limit(limit);
    }
}
