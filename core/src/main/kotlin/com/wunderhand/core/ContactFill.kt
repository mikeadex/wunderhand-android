package com.wunderhand.core

/**
 * A contact as the phone's own picker hands it over, before it is a client.
 * Plain values, so how it becomes a client can be tested without a phone.
 */
data class PickedContact(
    val givenName: String = "",
    val familyName: String = "",
    val nickname: String = "",
    val organisation: String = "",
    val phones: List<Labelled> = emptyList(),
    val emails: List<Labelled> = emptyList(),
    /** Often without a year: a birthday is not always a date of birth. */
    val birthday: Birthday? = null,
) {
    /** @param label what the address book calls it: "mobile", "work", "home"… */
    data class Labelled(val label: String?, val value: String)

    data class Birthday(val year: Int?, val month: Int, val day: Int)
}

/** A new client, started from somebody already in the phone's contacts. */
object ContactFill {
    /** "Wren Halloway"; a nickname, then a company, when a contact has no name. */
    fun name(c: PickedContact): String? {
        val full = listOf(c.givenName, c.familyName).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        return listOf(full, c.nickname, c.organisation).map { it.trim() }.firstOrNull { it.isNotEmpty() }
    }

    /** A mobile first: it is the number a shop texts, and the one a client is
     *  known by. Anything else only if there is no mobile. */
    fun phone(c: PickedContact): String? {
        val numbers = c.phones.filter { it.value.isNotBlank() }
        val mobile = numbers.firstOrNull { n -> n.label?.lowercase()?.let { "mobile" in it || "iphone" in it } == true }
        return (mobile ?: numbers.firstOrNull())?.value?.trim()
    }

    /** Their own address before a work one. */
    fun email(c: PickedContact): String? {
        val emails = c.emails.filter { "@" in it.value }
        val personal = emails.firstOrNull { it.label?.lowercase()?.contains("work") != true }
        return (personal ?: emails.firstOrNull())?.value?.trim()
    }

    /** "1991-03-04" — only when the contact has the year. "4 March" is a
     *  birthday; an age-restricted service needs a date of birth. */
    fun dateOfBirth(c: PickedContact): String? {
        val b = c.birthday ?: return null
        val year = b.year ?: return null
        if (b.month !in 1..12 || b.day !in 1..31 || year <= 1900) return null
        return "%04d-%02d-%02d".format(year, b.month, b.day)
    }

    data class Filled(val input: ClientInput, val what: List<String>)

    /** Fills what the contact has and leaves the rest as it was typed. Says
     *  what it filled, in the order the form shows them. */
    fun apply(c: PickedContact, to: ClientInput): Filled {
        var input = to
        val what = mutableListOf<String>()
        name(c)?.let { input = input.copy(name = it); what += "name" }
        phone(c)?.let { input = input.copy(phone = it); what += "mobile" }
        email(c)?.let { input = input.copy(email = it); what += "email" }
        dateOfBirth(c)?.let { input = input.copy(dateOfBirth = it); what += "date of birth" }
        return Filled(input, what)
    }

    /** What the form says once it has been filled. */
    fun said(filled: List<String>): String {
        if (filled.isEmpty()) return "That contact has no name, number or email to use."
        val list = if (filled.size == 1) filled[0] else filled.dropLast(1).joinToString(", ") + " and " + filled.last()
        return "Filled the $list from your contacts. Check them before adding."
    }

    /** A birthday as Android's contacts write one: "1991-03-04", or "--03-04" with no year. */
    fun birthday(raw: String?): PickedContact.Birthday? {
        val text = raw?.trim().orEmpty()
        Regex("""^(\d{4})-(\d{2})-(\d{2})""").find(text)?.destructured?.let { (y, m, d) -> return PickedContact.Birthday(y.toInt(), m.toInt(), d.toInt()) }
        Regex("""^--(\d{2})-(\d{2})""").find(text)?.destructured?.let { (m, d) -> return PickedContact.Birthday(null, m.toInt(), d.toInt()) }
        return null
    }
}
