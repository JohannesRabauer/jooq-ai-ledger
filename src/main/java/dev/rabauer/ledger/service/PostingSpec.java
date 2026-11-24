package dev.rabauer.ledger.service;

import java.math.BigDecimal;

/** One signed posting: negative debits the account, positive credits it. */
public record PostingSpec(long accountId, BigDecimal amount) {
}
