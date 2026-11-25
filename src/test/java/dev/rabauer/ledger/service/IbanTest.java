package dev.rabauer.ledger.service;

import static dev.rabauer.ledger.jooq.Tables.ACCOUNT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class IbanTest extends AbstractLedgerTest {

    @Test
    void acceptsValidIbansAndNormalizesSpacesAndCase() {
        assertThat(Iban.of("CH93 0076 2011 6238 5295 7").value()).isEqualTo("CH9300762011623852957");
        assertThat(Iban.of("de89370400440532013000").value()).isEqualTo("DE89370400440532013000");
        assertThat(Iban.of("GB82WEST12345698765432").value()).isEqualTo("GB82WEST12345698765432");
    }

    @Test
    void rejectsBadCheckDigitsAndBadFormat() {
        assertThatThrownBy(() -> Iban.of("CH9400762011623852957")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("check digits");
        assertThatThrownBy(() -> Iban.of("DE89370400440532013001")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Iban.of("TEST-123")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("format");
        assertThatThrownBy(() -> Iban.of("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Iban.of(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void generatedAccountFieldIsTypedAndRoundTrips() {
        Iban iban = Iban.of("CH9300762011623852957");
        long customer = newCustomer("Iban test");
        dsl.insertInto(ACCOUNT)
                .set(ACCOUNT.CUSTOMER_ID, customer)
                .set(ACCOUNT.IBAN, iban)
                .set(ACCOUNT.CURRENCY, "CHF")
                .execute();

        Iban read = dsl.select(ACCOUNT.IBAN).from(ACCOUNT).where(ACCOUNT.IBAN.eq(iban)).fetchSingle().value1();
        assertThat(read).isEqualTo(iban);
        // stored as plain text in the database
        String raw = dsl.select(org.jooq.impl.DSL.field("iban", String.class)).from("account")
                .where(org.jooq.impl.DSL.field("customer_id").eq(customer)).fetchSingle().value1();
        assertThat(raw).isEqualTo("CH9300762011623852957");
    }

    @Test
    void fxClearingAccountsGetValidIbans() {
        long eur = newAccount("EUR", false);
        long chf = newAccount("CHF", false);
        deposit(eur, "EUR", "100.00");
        dsl.insertInto(dev.rabauer.ledger.jooq.Tables.EXCHANGE_RATE)
                .set(dev.rabauer.ledger.jooq.Tables.EXCHANGE_RATE.BASE_CURRENCY, "EUR")
                .set(dev.rabauer.ledger.jooq.Tables.EXCHANGE_RATE.QUOTE_CURRENCY, "CHF")
                .set(dev.rabauer.ledger.jooq.Tables.EXCHANGE_RATE.VALID_FROM,
                        java.time.OffsetDateTime.now().minusYears(30))
                .set(dev.rabauer.ledger.jooq.Tables.EXCHANGE_RATE.RATE, new java.math.BigDecimal("0.95"))
                .onConflictDoNothing().execute();
        ledger.transferCrossCurrency(eur, chf, new java.math.BigDecimal("10.00"), "fx");

        Iban clearing = Iban.fromBban("XF", "FXCLEARINGEUR");
        assertThat(dsl.fetchExists(ACCOUNT, ACCOUNT.IBAN.eq(clearing))).isTrue();
    }
}
