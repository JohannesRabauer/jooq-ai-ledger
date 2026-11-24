package dev.rabauer.ledger.service;

public class InsufficientFundsException extends LedgerException {
    public InsufficientFundsException(String message) {
        super(message);
    }
}
