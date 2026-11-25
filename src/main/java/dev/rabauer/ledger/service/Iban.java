package dev.rabauer.ledger.service;

import java.math.BigInteger;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * An IBAN in electronic form (no spaces, upper case). Validates the general format (2 letters, 2 digits, 11 to 30
 * alphanumerics) and the mod-97 check digits. Country specific lengths are not checked.
 */
public record Iban(String value) {

    private static final Pattern FORMAT = Pattern.compile("[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}");

    public Iban {
        if (value == null) {
            throw new IllegalArgumentException("IBAN must not be null");
        }
        value = value.replace(" ", "").toUpperCase(Locale.ROOT);
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid IBAN format: " + value);
        }
        if (mod97(value) != 1) {
            throw new IllegalArgumentException("Invalid IBAN check digits: " + value);
        }
    }

    public static Iban of(String value) {
        return new Iban(value);
    }

    /** Builds an IBAN with the correct check digits from a country code and a basic bank account number. */
    public static Iban fromBban(String country, String bban) {
        String rearranged = bban.toUpperCase(Locale.ROOT) + country.toUpperCase(Locale.ROOT) + "00";
        int check = 98 - mod97Digits(rearranged);
        return new Iban(country.toUpperCase(Locale.ROOT) + String.format("%02d", check) + bban.toUpperCase(Locale.ROOT));
    }

    private static int mod97(String iban) {
        return mod97Digits(iban.substring(4) + iban.substring(0, 4));
    }

    private static int mod97Digits(String rearranged) {
        StringBuilder digits = new StringBuilder();
        for (char c : rearranged.toCharArray()) {
            digits.append(Character.isDigit(c) ? String.valueOf(c) : String.valueOf(c - 'A' + 10));
        }
        return new BigInteger(digits.toString()).mod(BigInteger.valueOf(97)).intValue();
    }

    @Override
    public String toString() {
        return value;
    }
}
