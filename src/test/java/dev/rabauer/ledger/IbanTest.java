package dev.rabauer.ledger;

import static dev.rabauer.ledger.jooq.Tables.ACCOUNT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class IbanTest extends AbstractLedgerTest {

    @Test
    void acceptsValidIbanAndNormalizes() {
        assertThat(Iban.of("de89 3704 0044 0532 0130 00").value()).isEqualTo("DE89370400440532013000");
        assertThat(Iban.of("CH9300762011623852957").value()).isEqualTo("CH9300762011623852957");
    }

    @Test
    void rejectsWrongCheckDigitsAndBadFormat() {
        assertThatThrownBy(() -> Iban.of("DE89370400440532013001")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("check digits");
        assertThatThrownBy(() -> Iban.of("TEST-123")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("format");
        assertThatThrownBy(() -> Iban.of(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void generatedIbanIsValid() {
        assertThat(Iban.generate("CH", "FXCLEARINGCHF").value()).hasSize(17);
        assertThat(Iban.generate("CH", "00000000000000001")).isEqualTo(Iban.of(Iban.generate("CH", "00000000000000001").value()));
    }

    @Test
    void roundTripsThroughGeneratedAccountTable() {
        long id = account(customer("iban"), "CHF", false);
        Iban iban = dsl.select(ACCOUNT.IBAN).from(ACCOUNT).where(ACCOUNT.ID.eq(id)).fetchSingle(ACCOUNT.IBAN);
        assertThat(iban).isInstanceOf(Iban.class);
        assertThat(dsl.fetchCount(ACCOUNT, ACCOUNT.IBAN.eq(iban))).isEqualTo(1);
    }

    @Test
    void invalidValueInDatabaseFailsOnRead() {
        long id = account(customer("bad"), "CHF", false);
        dsl.execute("update account set iban = 'NOT-AN-IBAN' where id = ?", id);
        assertThatThrownBy(() -> dsl.selectFrom(ACCOUNT).where(ACCOUNT.ID.eq(id)).fetchSingle())
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }
}
