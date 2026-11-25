package dev.rabauer.ledger;

import org.jooq.impl.AbstractConverter;

/** Maps {@code account.iban} (TEXT) to {@link Iban}; wired in by a forced type in the code generation config. */
public class IbanConverter extends AbstractConverter<String, Iban> {

    public IbanConverter() {
        super(String.class, Iban.class);
    }

    @Override
    public Iban from(String databaseObject) {
        return databaseObject == null ? null : new Iban(databaseObject);
    }

    @Override
    public String to(Iban userObject) {
        return userObject == null ? null : userObject.value();
    }
}
