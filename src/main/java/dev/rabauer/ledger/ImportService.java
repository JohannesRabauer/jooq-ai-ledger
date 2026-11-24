package dev.rabauer.ledger;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;

import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

import dev.rabauer.ledger.PostingBook.Leg;

@Service
public class ImportService {

    public record ImportItem(String idempotencyKey, OffsetDateTime bookedAt, String description,
            long fromAccountId, long toAccountId, BigDecimal amount) {
    }

    public record ImportResult(int booked, int skipped) {
    }

    private final DSLContext dsl;
    private final PostingBook book;

    public ImportService(DSLContext dsl, PostingBook book) {
        this.dsl = dsl;
        this.book = book;
    }

    /**
     * Imports the batch atomically. Items whose idempotency key is already booked (earlier run, or earlier in
     * the same batch) are skipped, so importing the same file twice books nothing twice.
     */
    public ImportResult importBatch(List<ImportItem> items) {
        for (ImportItem item : items) {
            if (item.idempotencyKey() == null || item.idempotencyKey().isBlank()) {
                throw new IllegalArgumentException("Every imported item needs an idempotency key");
            }
            TransferService.requirePositive(item.amount());
        }
        // Fixed key order so two concurrent imports of overlapping files wait on each other instead of deadlocking.
        List<ImportItem> ordered = items.stream().sorted(Comparator.comparing(ImportItem::idempotencyKey)).toList();
        return dsl.transactionResult(cfg -> {
            DSLContext tx = cfg.dsl();
            int booked = 0;
            for (ImportItem item : ordered) {
                boolean inserted = book.book(tx, item.bookedAt(), item.description(), item.idempotencyKey(),
                        List.of(new Leg(item.fromAccountId(), item.amount().negate()),
                                new Leg(item.toAccountId(), item.amount()))).isPresent();
                if (inserted) {
                    booked++;
                }
            }
            return new ImportResult(booked, ordered.size() - booked);
        });
    }
}
