package dev.rabauer.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import dev.rabauer.ledger.ImportService.ImportItem;
import dev.rabauer.ledger.ImportService.ImportResult;

class ImportServiceTest extends AbstractLedgerTest {

    @Autowired
    TransferService transfers;
    @Autowired
    ImportService importer;

    private List<ImportItem> file(long from, long to) {
        String run = UUID.randomUUID().toString();
        OffsetDateTime now = OffsetDateTime.now();
        return List.of(
                new ImportItem(run + "-1", now, "bank line 1", from, to, new BigDecimal("10.00")),
                new ImportItem(run + "-2", now, "bank line 2", from, to, new BigDecimal("20.00")),
                new ImportItem(run + "-3", now, "bank line 3", to, from, new BigDecimal("5.00")));
    }

    @Test
    void importingTheSameFileTwiceBooksOnce() {
        long c = customer("import");
        long a = account(c, "EUR", true);
        long b = account(c, "EUR", true);
        List<ImportItem> items = file(a, b);

        assertThat(importer.importBatch(items)).isEqualTo(new ImportResult(3, 0));
        assertThat(importer.importBatch(items)).isEqualTo(new ImportResult(0, 3));

        assertThat(balance(a)).isEqualByComparingTo("-25.00");
        assertThat(balance(b)).isEqualByComparingTo("25.00");
        assertThat(postingCount(a)).isEqualTo(3);
        assertAllTransactionsBalanced();
    }

    @Test
    void duplicateKeyInsideOneBatchAndOverlappingFilesBookOnce() {
        long c = customer("import2");
        long a = account(c, "EUR", true);
        long b = account(c, "EUR", true);
        List<ImportItem> items = file(a, b);
        ImportItem dup = items.get(0);

        importer.importBatch(List.of(items.get(0), dup));
        assertThat(postingCount(a)).isEqualTo(1);

        ImportResult overlap = importer.importBatch(items);
        assertThat(overlap).isEqualTo(new ImportResult(2, 1));
        assertThat(balance(a)).isEqualByComparingTo("-25.00");
    }

    @Test
    void concurrentImportsOfTheSameFileBookOnce() {
        long c = customer("import3");
        long a = account(c, "EUR", true);
        long b = account(c, "EUR", true);
        List<ImportItem> items = file(a, b);

        List<CompletableFuture<ImportResult>> runs = java.util.stream.IntStream.range(0, 6)
                .mapToObj(i -> CompletableFuture.supplyAsync(() -> importer.importBatch(items))).toList();
        int booked = runs.stream().mapToInt(f -> f.join().booked()).sum();

        assertThat(booked).isEqualTo(3);
        assertThat(postingCount(a)).isEqualTo(3);
        assertThat(balance(b)).isEqualByComparingTo("25.00");
    }

    @Test
    void failingItemRollsBackTheWholeBatch() {
        long c = customer("import4");
        long poor = account(c, "EUR", false);
        long b = account(c, "EUR", true);
        long usd = account(c, "USD", true);
        String run = UUID.randomUUID().toString();
        OffsetDateTime now = OffsetDateTime.now();

        List<ImportItem> overdraw = List.of(
                new ImportItem(run + "-a", now, "ok", b, poor, new BigDecimal("1.00")),
                new ImportItem(run + "-b", now, "overdraws", poor, b, new BigDecimal("5.00")));
        assertThatThrownBy(() -> importer.importBatch(overdraw)).isInstanceOf(InsufficientFundsException.class);
        assertThat(postingCount(poor)).isZero();

        // currency mismatch breaks the zero-sum-per-currency rule
        List<ImportItem> mismatch = List.of(new ImportItem(run + "-c", now, "x", b, usd, BigDecimal.ONE));
        assertThatThrownBy(() -> importer.importBatch(mismatch)).isInstanceOf(IllegalArgumentException.class);
        assertThat(importer.importBatch(List.of(overdraw.get(0)))).isEqualTo(new ImportResult(1, 0));
    }
}
