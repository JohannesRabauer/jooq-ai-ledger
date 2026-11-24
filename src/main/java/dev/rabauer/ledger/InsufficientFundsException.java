package dev.rabauer.ledger;

public class InsufficientFundsException extends RuntimeException {

    public InsufficientFundsException(long accountId) {
        super("Account " + accountId + " does not allow overdraft");
    }
}
