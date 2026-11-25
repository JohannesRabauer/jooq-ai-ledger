package dev.rabauer.ledger.service;

import static dev.rabauer.ledger.jooq.Tables.EXCHANGE_RATE;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The exchange_rate table is shared with the other test classes, which use rates around "now". These tests live in
 * the 1980s so that no other rate can be the "latest" one for them.
 */
class ValuationServiceTest extends AbstractLedgerTest {

    @Autowired
    ValuationService valuation;

    private static OffsetDateTime at(int year, int month, int day, int hour) {
        return OffsetDateTime.of(year, month, day, hour, 0, 0, 0, ZoneOffset.UTC);
    }

    private void rate(String base, OffsetDateTime from, String rate) {
        dsl.insertInto(EXCHANGE_RATE)
                .set(EXCHANGE_RATE.BASE_CURRENCY, base)
                .set(EXCHANGE_RATE.QUOTE_CURRENCY, "CHF")
                .set(EXCHANGE_RATE.VALID_FROM, from)
                .set(EXCHANGE_RATE.RATE, new BigDecimal(rate))
                .execute();
    }

    private static ValuationService.AccountValuation of(List<ValuationService.AccountValuation> all, long id) {
        return all.stream().filter(v -> v.accountId() == id).findFirst().orElseThrow();
    }

    @Test
    void valuesEachPostingAtItsOwnRateAndAtTheLatestRate() {
        long customer = newCustomer("Valuation " + java.util.UUID.randomUUID());
        long chf = newAccount(customer, "CHF", true);
        long eur = newAccount(customer, "EUR", true);
        long usd = newAccount(customer, "USD", true);
        long jpy = newAccount(customer, "JPY", true);
        long emptyEur = newAccount(customer, "EUR", true);
        long otherCustomer = newAccount("EUR", true); // must not show up

        // EUR: 0.90 from January, 1.00 from February, 2.00 only from March (after the valuation time)
        rate("EUR", at(1985, 1, 1, 0), "0.90000000");
        rate("EUR", at(1985, 2, 1, 0), "1.00000000");
        rate("EUR", at(1985, 3, 1, 0), "2.00000000");
        // USD: the first rate exists only from February
        rate("USD", at(1985, 2, 1, 0), "0.50000000");

        deposit(chf, "CHF", "100.00", at(1985, 1, 5, 12), "chf");
        deposit(eur, "EUR", "100.00", at(1985, 1, 15, 12), "eur at 0.90");  // 90
        deposit(eur, "EUR", "50.00", at(1985, 2, 10, 12), "eur at 1.00");   // 50
        deposit(eur, "EUR", "999.00", at(1985, 2, 25, 12), "after the valuation time");
        deposit(usd, "USD", "10.00", at(1985, 1, 20, 12), "usd before the first rate");
        deposit(usd, "USD", "10.00", at(1985, 2, 5, 12), "usd at 0.50");
        deposit(jpy, "JPY", "1000", at(1985, 1, 25, 12), "jpy, no rate at all");
        deposit(otherCustomer, "EUR", "1.00", at(1985, 1, 25, 12), "other");

        OffsetDateTime asOf = at(1985, 2, 20, 0);
        List<ValuationService.AccountValuation> result = valuation.valuation(customer, asOf);

        assertThat(result).extracting(ValuationService.AccountValuation::accountId)
                .containsExactly(chf, eur, usd, jpy, emptyEur);

        ValuationService.AccountValuation vChf = of(result, chf);
        assertThat(vChf.balance()).isEqualByComparingTo("100.00");
        assertThat(vChf.valueAtBookingRates()).isEqualByComparingTo("100.00"); // no rate needed
        assertThat(vChf.valueAtLatestRate()).isEqualByComparingTo("100.00");
        assertThat(vChf.difference()).isEqualByComparingTo("0");

        // the rate changed between the two postings: 100*0.90 + 50*1.00, latest 150*1.00
        ValuationService.AccountValuation vEur = of(result, eur);
        assertThat(vEur.balance()).isEqualByComparingTo("150.00");
        assertThat(vEur.valueAtBookingRates()).isEqualByComparingTo("140.00");
        assertThat(vEur.valueAtLatestRate()).isEqualByComparingTo("150.00");
        assertThat(vEur.difference()).isEqualByComparingTo("10.00");

        // a posting booked before the first rate cannot be valued: no historic value, latest value is there
        ValuationService.AccountValuation vUsd = of(result, usd);
        assertThat(vUsd.balance()).isEqualByComparingTo("20.00");
        assertThat(vUsd.valueAtBookingRates()).isNull();
        assertThat(vUsd.valueAtLatestRate()).isEqualByComparingTo("10.00");
        assertThat(vUsd.difference()).isNull();

        // a currency without any rate: the account stays in the result, values are null, balance is still known
        ValuationService.AccountValuation vJpy = of(result, jpy);
        assertThat(vJpy.currency()).isEqualTo("JPY");
        assertThat(vJpy.balance()).isEqualByComparingTo("1000");
        assertThat(vJpy.valueAtBookingRates()).isNull();
        assertThat(vJpy.valueAtLatestRate()).isNull();
        assertThat(vJpy.difference()).isNull();

        // an account without postings is worth zero where a rate exists
        ValuationService.AccountValuation vEmpty = of(result, emptyEur);
        assertThat(vEmpty.balance()).isEqualByComparingTo("0");
        assertThat(vEmpty.valueAtBookingRates()).isEqualByComparingTo("0");
        assertThat(vEmpty.valueAtLatestRate()).isEqualByComparingTo("0");
    }

    @Test
    void latestRateFollowsThePointInTime() {
        long customer = newCustomer("Valuation 2 " + java.util.UUID.randomUUID());
        long eur = newAccount(customer, "EUR", true);
        rate("EUR", at(1987, 1, 1, 0), "0.80000000");
        rate("EUR", at(1987, 6, 1, 0), "0.60000000");
        deposit(eur, "EUR", "100.00", at(1987, 1, 10, 12), "d");

        ValuationService.AccountValuation early = valuation.valuation(customer, at(1987, 3, 1, 0)).getFirst();
        assertThat(early.valueAtLatestRate()).isEqualByComparingTo("80.00");
        ValuationService.AccountValuation late = valuation.valuation(customer, at(1987, 7, 1, 0)).getFirst();
        assertThat(late.valueAtBookingRates()).isEqualByComparingTo("80.00");
        assertThat(late.valueAtLatestRate()).isEqualByComparingTo("60.00");
        assertThat(late.difference()).isEqualByComparingTo("-20.00");
        // a rate that is valid exactly at the posting's booking time counts (valid_from <= booked_at)
        rate("EUR", at(1987, 7, 15, 12), "0.50000000");
        deposit(eur, "EUR", "100.00", at(1987, 7, 15, 12), "d2");
        ValuationService.AccountValuation exact = valuation.valuation(customer, at(1987, 8, 1, 0)).getFirst();
        assertThat(exact.valueAtBookingRates()).isEqualByComparingTo("130.00"); // 100*0.80 + 100*0.50
    }
}
