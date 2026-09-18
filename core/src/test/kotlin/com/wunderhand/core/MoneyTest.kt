package com.wunderhand.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** Waitlist, gaps, the till and money. The iOS cases, one for one. */
class MoneyTest {
    private inline fun <reified T> decode(name: String): T =
        ChairtimeJson.decodeFromString(checkNotNull(javaClass.getResourceAsStream("/fixtures/$name.json")).bufferedReader().use { it.readText() })

    @Test fun `the fixtures decode`() {
        val waiting: WaitlistResponse = decode("waitlist")
        assertEquals("Thu, Fri, evenings", waiting.waiting.first().flexibilityLabel)
        assertEquals("Skin fade · Kit Alvarez · thu, fri, evenings", waiting.waiting.first().detail)

        val gap: GapResponse = decode("gap")
        assertEquals(90, gap.gapMinutes)
        assertEquals(45, gap.candidates.first().leftoverMinutes)
        assertEquals("leaves 45m", gap.candidates.first().fit)
        assertEquals(gap.candidates.take(gap.suggested).map { it.entryId }.toSet(), gap.preselected)

        val sent: OfferSent = decode("offer-sent")
        assertTrue(sent.toText.size == 1 && sent.emailed.isEmpty())
        assertTrue("/offer/" in sent.sent.first().url)

        val till: CheckoutResponse = decode("checkout")
        assertTrue(till.isSettled)
        assertEquals(Pence(4500), till.receipt?.takenPence)
        assertEquals(till.startsAt.minus(Duration.ofMinutes(30)), till.openFrom)

        val money: MoneyResponse = decode("money")
        assertTrue(money.isShop)
        assertEquals(money.month.earnedPence.value + money.till.month.extraPence.value, money.monthTook)
        assertFalse(money.nothingYet)
    }

    @Test fun `waiting is said as the web says it`() {
        val now = Instant.parse("2026-09-18T12:00:00Z")
        for ((daysAgo, expected) in listOf(0.0 to "today", 1.0 to "1 day", 9.0 to "9 days", 13.9 to "13 days", 14.0 to "2 wks", 30.0 to "4 wks")) {
            assertEquals(expected, WaitWords.waited(now.minusSeconds((daysAgo * 86_400).toLong()), now))
        }
    }

    @Test fun `joined is the gap screen's form`() {
        val now = Instant.parse("2026-09-18T12:00:00Z")
        assertEquals("joined today", WaitWords.joined(now, now))
        assertEquals("waiting 3 days", WaitWords.joined(now.minus(Duration.ofDays(3)), now))
        assertEquals("waiting 3 weeks", WaitWords.joined(now.minus(Duration.ofDays(21)), now))
    }

    @Test fun `the comparison is like for like`() {
        assertTrue(MoneyWords.comparison(1000, 0, 0, 4, "GBP").text.startsWith("First month with figures"))

        val early = MoneyWords.comparison(1120, 1000, 412_000, 4, "GBP")
        assertEquals("Up 12% on the same point last month", early.text)
        assertEquals(MoneyWords.Tone.Up, early.tone)
        assertEquals("Last month finished at £4,120.", early.finished)

        val late = MoneyWords.comparison(900, 1000, 412_000, 29, "GBP")
        assertEquals("Down 10% on last month", late.text)
        assertEquals(MoneyWords.Tone.Down, late.tone)
        assertNull(late.finished)

        val flat = MoneyWords.comparison(1004, 1000, 0, 10, "GBP")
        assertEquals("Level on the same point last month", flat.text)
        assertEquals(MoneyWords.Tone.Level, flat.tone)
    }

    @Test fun `a fall rounds the way a rise does`() {
        assertEquals("Down 11% on last month", MoneyWords.comparison(895, 1000, 0, 30, "GBP").text)
        assertEquals("Up 11% on last month", MoneyWords.comparison(1105, 1000, 0, 30, "GBP").text)
    }

    @Test fun `change and labels`() {
        assertEquals("Up 50% on last year", MoneyWords.change(1500, 1000, "on last year"))
        assertEquals("Level on last year", MoneyWords.change(1000, 1000, "on last year"))
        assertNull(MoneyWords.change(1000, 0, "on last year"))
        assertEquals("Jun", MoneyWords.monthLabel("2026-06"))
        assertEquals("Nov", MoneyWords.monthLabel("2026-11"))
        assertEquals("September", MoneyWords.monthName("2026-09"))
        assertEquals("soon", MoneyWords.monthLabel("soon"))
        assertEquals("Card", MoneyWords.methodLabel("card"))
        assertEquals("cheque", MoneyWords.methodLabel("cheque"))
    }

    @Test fun `till money lands as pence`() {
        for ((text, pence) in listOf("" to 0, "28" to 2800, "28.50" to 2850, "£28.50" to 2850, "28.505" to 2851, "1,200" to 120_000)) assertEquals(pence, MoneyInput.pence(text))
        assertNull(MoneyInput.pence("abc"))
        assertNull(MoneyInput.pence("-5"))
    }

    @Test fun `what to ask for moves as an extra and a tip are typed`() {
        val owing = Owing(subtotalPence = Pence(2800), paidBeforePence = Pence(500), balancePence = Pence(2300), duePence = Pence(2300))
        val more = owing.with(extraPence = 1200, tipPence = 300)
        assertEquals(Pence(3500), more.balancePence)
        assertEquals(Pence(3800), more.duePence)
        assertEquals(Pence(0), more.overpaidPence)

        // A deposit bigger than the bill: nothing to ask for but the tip, and the rest is the shop's to give back.
        val over = Owing(subtotalPence = Pence(1000), paidBeforePence = Pence(1500)).with(0, 200)
        assertEquals(Pence(0), over.balancePence)
        assertEquals(Pence(200), over.duePence)
        assertEquals(Pence(500), over.overpaidPence)
    }

    @Test fun `an offer by hand is one message with its link whole`() {
        val link = OfferSent.Link("Wren Halloway", "07700 900123", null, "https://wunderhand.com/offer/abc")
        val text = WaitWords.text(link, "Fold Barbers")
        assertTrue(text.startsWith("Hi Wren, a slot has come free at Fold Barbers."))
        assertTrue(text.endsWith("https://wunderhand.com/offer/abc"))
        assertTrue(text.length < 160 + link.url.length)
    }

    @Test fun `a scope or a method this build has not heard of is nobody's shop and its own word`() {
        val money = ChairtimeJson.decodeFromString(MoneyResponse.serializer(), """{"scope":"region","till":{"byMethod":[{"method":"voucher","pence":500,"settlements":1}]}}""")
        assertFalse(money.isShop)
        assertEquals("voucher", MoneyWords.methodLabel(money.till.byMethod.single().method))
        assertTrue(money.nothingYet)
    }
}
