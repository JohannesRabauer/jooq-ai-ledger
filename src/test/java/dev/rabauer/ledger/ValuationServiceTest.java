package dev.rabauer.ledger;

import static dev.rabauer.ledger.jooq.Tables.EXCHANGE_RATE;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import dev.rabauer.ledger.ValuationService.AccountValuation;

class ValuationServiceTest extends AbstractLedgerTest {

    @Autowired
    ValuationService valuation;
    @Autowired
    TransferService transfers;

    private static OffsetDateTime utc(String iso) {
        return OffsetDateTime.parse(iso + "Z");
    }

    private void rate(String base, String validFrom, String rate) {
        dsl.insertInto(EXCHANGE_RATE).set(EXCHANGE_RATE.BASE_CURRENCY, base).set(EXCHANGE_RATE.QUOTE_CURRENCY, "CHF")
                .set(EXCHANGE_RATE.VALID_FROM, utc(validFrom)).set(EXCHANGE_RATE.RATE, new BigDecimal(rate))
                .onConflictDoNothing().execute();
    }

    private static AccountValuation of(List<AccountValuation> all, long accountId) {
        return all.stream().filter(v -> v.accountId() == accountId).findFirst().orElseThrow();
    }

    @Test
    void rateChangeBetweenTwoPostingsAndLatestRateDiffer() {
        rate("EUR", "2026-01-01T00:00:00", "0.95");
        rate("EUR", "2026-02-01T00:00:00", "1.00");
        rate("EUR", "2026-04-01T00:00:00", "1.10"); // after the valuation time, must be ignored
        long customer = customer("valuation");
        long eur = account(customer, "EUR", false);
        long chf = account(customer, "CHF", false);
        bookAt(transfers, eur, "EUR", "100", utc("2026-01-15T10:00:00"));  // rate 0.95
        bookAt(transfers, eur, "EUR", "100", utc("2026-02-01T00:00:00"));  // rate valid from exactly this instant: 1.00
        bookAt(transfers, chf, "CHF", "50", utc("2026-01-20T10:00:00"));

        List<AccountValuation> result = valuation.valuate(customer, utc("2026-03-01T00:00:00"));
        assertThat(result).hasSize(2);

        AccountValuation e = of(result, eur);
        assertThat(e.balance()).isEqualByComparingTo("200");
        assertThat(e.valueAtBookingRates()).isEqualByComparingTo("195");   // 100 * 0.95 + 100 * 1.00
        assertThat(e.valueAtLatestRate()).isEqualByComparingTo("200");     // 200 * 1.00
        assertThat(e.difference()).isEqualByComparingTo("5");
        assertThat(e.iban()).isNotNull();

        AccountValuation c = of(result, chf);
        assertThat(c.valueAtBookingRates()).isEqualByComparingTo("50");
        assertThat(c.valueAtLatestRate()).isEqualByComparingTo("50");
        assertThat(c.difference()).isEqualByComparingTo("0");

        // a later valuation time picks up the newer rate for the latest value only
        AccountValuation later = of(valuation.valuate(customer, utc("2026-05-01T00:00:00")), eur);
        assertThat(later.valueAtBookingRates()).isEqualByComparingTo("195");
        assertThat(later.valueAtLatestRate()).isEqualByComparingTo("220");
        assertThat(later.difference()).isEqualByComparingTo("25");
    }

    @Test
    void currencyWithoutRateStillAppearsWithNulls() {
        // only the inverse pair CHF -> JPY exists (TransferServiceTest); it is not used for valuation
        long customer = customer("no rate");
        long jpy = account(customer, "JPY", false);
        long chf = account(customer, "CHF", false);
        bookAt(transfers, jpy, "JPY", "10000", utc("2026-01-15T10:00:00"));
        bookAt(transfers, chf, "CHF", "10", utc("2026-01-15T10:00:00"));

        List<AccountValuation> result = valuation.valuate(customer, utc("2026-03-01T00:00:00"));
        assertThat(result).hasSize(2);
        AccountValuation j = of(result, jpy);
        assertThat(j.balance()).isEqualByComparingTo("10000");
        assertThat(j.valueAtBookingRates()).isNull();
        assertThat(j.valueAtLatestRate()).isNull();
        assertThat(j.difference()).isNull();
        assertThat(of(result, chf).valueAtLatestRate()).isEqualByComparingTo("10");
    }

    @Test
    void postingBookedBeforeTheFirstRateMakesTheBookingValueNull() {
        rate("USD", "2026-06-01T00:00:00", "0.90");
        long customer = customer("early");
        long usd = account(customer, "USD", false);
        bookAt(transfers, usd, "USD", "100", utc("2026-01-15T10:00:00"));   // no rate yet at booking time

        AccountValuation v = of(valuation.valuate(customer, utc("2026-07-01T00:00:00")), usd);
        assertThat(v.valueAtLatestRate()).isEqualByComparingTo("90");
        assertThat(v.valueAtBookingRates()).isNull();
        assertThat(v.difference()).isNull();

        // before the first rate nothing is valued at all
        AccountValuation before = of(valuation.valuate(customer, utc("2026-03-01T00:00:00")), usd);
        assertThat(before.valueAtLatestRate()).isNull();
    }

    @Test
    void accountWithoutPostingsIsWorthZero() {
        long customer = customer("empty");
        long chf = account(customer, "CHF", false);
        AccountValuation v = of(valuation.valuate(customer), chf);
        assertThat(v.balance()).isEqualByComparingTo("0");
        assertThat(v.valueAtBookingRates()).isEqualByComparingTo("0");
        assertThat(v.valueAtLatestRate()).isEqualByComparingTo("0");
    }
}
