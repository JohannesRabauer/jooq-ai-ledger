package dev.rabauer.ledger;

import java.math.BigInteger;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A syntactically valid IBAN (country, two check digits, BBAN) with correct ISO 7064 mod-97 check digits.
 * Spaces are dropped and letters upper-cased on creation; an invalid value is rejected.
 */
public record Iban(String value) {

    private static final Pattern FORMAT = Pattern.compile("[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}");
    private static final BigInteger NINETY_SEVEN = BigInteger.valueOf(97);

    public Iban {
        if (value == null) {
            throw new IllegalArgumentException("IBAN must not be null");
        }
        value = value.replace(" ", "").toUpperCase(Locale.ROOT);
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid IBAN format: " + value);
        }
        if (mod97(value.substring(4) + value.substring(0, 4)) != 1) {
            throw new IllegalArgumentException("Invalid IBAN check digits: " + value);
        }
    }

    public static Iban of(String value) {
        return new Iban(value);
    }

    /** Builds a valid IBAN for {@code countryCode} and {@code bban} by computing the check digits. */
    public static Iban generate(String countryCode, String bban) {
        int check = 98 - mod97(bban + countryCode + "00");
        return new Iban(countryCode + (check < 10 ? "0" : "") + check + bban);
    }

    /** Remainder of the number obtained by replacing every letter with two digits (A=10 .. Z=35). */
    private static int mod97(String rearranged) {
        StringBuilder digits = new StringBuilder();
        for (char c : rearranged.toCharArray()) {
            digits.append(Character.digit(c, 36));
        }
        return new BigInteger(digits.toString()).mod(NINETY_SEVEN).intValue();
    }

    @Override
    public String toString() {
        return value;
    }
}
