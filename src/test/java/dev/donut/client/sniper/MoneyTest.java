package dev.donut.client.sniper;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MoneyTest {
    @Test
    void chatAmountsKeepTheirRoundingTolerance() {
        Money.Amount amount = Money.find("You bought 1 Diamond Pickaxe for $ 37.9K");
        assertEquals(37_900, amount.value(), 1e-6);
        assertTrue(amount.matches(37_850));
        assertTrue(amount.matches(37_949));
        assertFalse(amount.matches(38_000));
        assertEquals(1_250_000, Money.find("Price: $1.25M").value(), 1e-6);
        assertEquals(12_345, Money.find("Price: $12,345").value(), 1e-6);
        assertTrue(Money.find("Price: $12,345").matches(12_345.4));
        assertFalse(Money.find("Price: $12,345").matches(12_346));
        assertNull(Money.find("Seller: Notch"));
    }

    @Test
    void typedAmountsAcceptShortForms() {
        assertEquals(250_000, Money.parse("250k"));
        assertEquals(1_500_000, Money.parse("1.5M"));
        assertEquals(2e9, Money.parse("$2B"));
        assertEquals(12_000, Money.parse(" 12,000 "));
        assertEquals(5_000, Money.parse(Money.format(5_000)));
        assertTrue(Double.isNaN(Money.parse("lots")));
        assertTrue(Double.isNaN(Money.parse("")));
        assertTrue(Double.isNaN(Money.parse(null)));
    }

    @Test
    void formattingIsShort() {
        assertEquals("$950", Money.format(950));
        assertEquals("$37.9K", Money.format(37_900));
        assertEquals("$1.25M", Money.format(1_250_000));
        assertEquals("$250K", Money.format(250_000));
        assertEquals("-$5.00K", Money.format(-5_000));
        assertEquals("-", Money.format(Double.NaN));
    }
}
