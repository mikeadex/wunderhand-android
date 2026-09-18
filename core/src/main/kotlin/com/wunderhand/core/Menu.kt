package com.wunderhand.core

import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode

// The menu (chairtime `app/(pro)/menu`).

/** A service as the Menu tab lists it: the facts a pro scans for, and the two things that stop it being booked. */
@Serializable
data class MenuService(
    val id: String,
    val name: String,
    val description: String? = null,
    val categoryId: String? = null,
    val categoryName: String? = null,
    override val pricingMode: String = "fixed",
    override val pricePence: Pence? = null,
    override val hourlyRatePence: Pence? = null,
    override val minutes: Int,
    override val durationMode: String = "fixed",
    override val minMinutes: Int? = null,
    override val maxMinutes: Int? = null,
    val bookableOnline: Boolean = true,
    val minAgeYears: Int? = null,
    val requiresConsent: Boolean = false,
    val prerequisiteName: String? = null,
    val prerequisiteLeadHours: Int? = null,
    /** A step where the pro is free — develop time, a soak. */
    val hasDevelopGap: Boolean = false,
    /** Zero means nobody can book it, online or in the diary. */
    val performerCount: Int = 0,
) : ServiceFacts {
    val isUnbookable: Boolean get() = performerCount == 0

    /** The line under the name (`describeMeta`): the length, then what makes
     *  this awkward to book — a develop gap, a patch test, an age floor. */
    val metaLine: String
        get() = buildList {
            add(durationLabel)
            if (hasDevelopGap) add("has a develop gap")
            prerequisiteName?.let { add(prerequisiteLeadHours?.let { h -> "$it ${h}h before" } ?: "$it first") }
            minAgeYears?.let { add("$it+") }
            if (requiresConsent) add("consent required")
        }.joinToString(" · ")
}

/** `GET /api/v1/menu` */
@Serializable
data class MenuResponse(val categories: List<Category> = emptyList(), val services: List<MenuService> = emptyList()) {
    @Serializable
    data class Category(val id: String, val name: String)

    fun services(categoryId: String?): List<MenuService> = if (categoryId == null) services else services.filter { it.categoryId == categoryId }

    /** The web's header figures: "12 services", and what needs attention. */
    val countLabel: String get() = "${services.size} ${if (services.size == 1) "service" else "services"}"
    val unassignedCount: Int get() = services.count { it.isUnbookable }
    val offlineCount: Int get() = services.count { !it.bookableOnline && !it.isUnbookable }
}

/** `GET /api/v1/menu/{id}`: one service as the web's detail page shows it. */
@Serializable
data class MenuServiceResponse(
    val service: Detail,
    val segments: List<Segment> = emptyList(),
    val performers: List<Performer> = emptyList(),
    val addons: List<Addon> = emptyList(),
    val timesBooked: Int = 0,
) {
    @Serializable
    data class Detail(
        val id: String,
        val name: String,
        val description: String? = null,
        /** The ids behind the names, so an edit form can put a choice back. */
        val categoryId: String? = null,
        val categoryName: String? = null,
        override val pricingMode: String = "fixed",
        override val pricePence: Pence? = null,
        override val hourlyRatePence: Pence? = null,
        override val minutes: Int,
        override val durationMode: String = "fixed",
        override val minMinutes: Int? = null,
        override val maxMinutes: Int? = null,
        val prerequisiteServiceId: String? = null,
        val requiresResourceTypeId: String? = null,
        /** Left the menu. Still readable, because appointments refer to it. */
        val isArchived: Boolean? = null,
        val depositPence: Pence? = null,
        val depositPercent: Int? = null,
        val bookableOnline: Boolean = true,
        /** at_venue, at_client or either. */
        val locationMode: String = "at_venue",
        val prerequisiteName: String? = null,
        val prerequisiteLeadHours: Int? = null,
        val minAgeYears: Int? = null,
        val requiresConsent: Boolean = false,
        /** A room, a bed, a colour bar the booking must also secure. */
        val resourceTypeName: String? = null,
    ) : ServiceFacts

    /** One step of the service. A step the pro is not busy for is time that can be sold. */
    @Serializable
    data class Segment(val seq: Int, val minutes: Int, val staffBusy: Boolean = true, val label: String? = null) {
        val name: String get() = label ?: "Step $seq"
    }

    @Serializable
    data class Performer(
        val id: String, val name: String,
        /** Their own price where they charge one, else the service's. */
        val pricePence: Pence? = null,
        /** Their own price, or null when they charge the service's. */
        val overridePence: Pence? = null,
    )

    @Serializable
    data class Addon(val id: String, val name: String, val pricePence: Pence, val minutes: Int = 0) {
        /** "adds 15m", or "no extra time". */
        val timeLabel: String get() = if (minutes > 0) "adds ${Durations.short(minutes)}" else "no extra time"
    }

    val totalMinutes: Int get() = segments.sumOf { it.minutes }
    val busyMinutes: Int get() = segments.filter { it.staffBusy }.sumOf { it.minutes }
    /** Some of the chair time is the pro's own to sell. */
    val sellsFreeTime: Boolean get() = busyMinutes < totalMinutes

    /** The line under the title: category · price · length · how often booked. */
    fun summaryLine(currency: String): String =
        listOfNotNull(service.categoryName, service.priceLabel(currency), service.durationLabel, MenuWords.booked(timesBooked)).joinToString(" · ")
}

object MenuWords {
    /** "never booked", "booked once", "booked 12 times". */
    fun booked(times: Int): String = when (times) {
        0 -> "never booked"
        1 -> "booked once"
        else -> "booked $times times"
    }
}

/** Money as somebody types it: "28.50", "£1,200", "28". Null when it is not an amount. */
object MoneyInput {
    fun pence(text: String): Int? {
        val cleaned = text.trim().replace("£", "").replace(",", "")
        if (cleaned.isEmpty()) return 0
        val value = cleaned.toBigDecimalOrNull() ?: return null
        if (value < BigDecimal.ZERO) return null
        return value.movePointRight(2).setScale(0, RoundingMode.HALF_UP).toInt()
    }

    /** "28.50" — what goes back into a money field. */
    fun pounds(pence: Pence): String = "%.2f".format(pence.value / 100.0)
}

// region Editing the menu

enum class PricingMode(val raw: String, val label: String) {
    Fixed("fixed", "Fixed"), From("from", "From"), Hourly("hourly", "By the hour"), PerPerson("per_person", "Each");
    companion object { fun of(raw: String) = entries.firstOrNull { it.raw == raw } ?: Fixed }
}

enum class DurationMode(val raw: String, val label: String) {
    Fixed("fixed", "Fixed"), Ranged("ranged", "A range"), Hourly("hourly", "Open-ended");
    companion object { fun of(raw: String) = entries.firstOrNull { it.raw == raw } ?: Fixed }
}

enum class LocationMode(val raw: String, val label: String) {
    AtVenue("at_venue", "Here"), AtClient("at_client", "At theirs"), Either("either", "Either");
    companion object { fun of(raw: String) = entries.firstOrNull { it.raw == raw } ?: AtVenue }
}

/** `GET /api/v1/menu/options`: what the service form chooses between. An owner's to read. */
@Serializable
data class MenuOptions(
    val categories: List<Choice> = emptyList(),
    /** Live services, as candidates for "needs another service first". */
    val services: List<Choice> = emptyList(),
    val resourceTypes: List<Choice> = emptyList(),
    /** Everybody who takes bookings. */
    val staff: List<Person> = emptyList(),
) {
    @Serializable data class Choice(val id: String, val name: String)
    @Serializable data class Person(val id: String, val name: String, val roleTitle: String? = null)
}

/** `POST /api/v1/menu` and `PUT /api/v1/menu/{id}`. */
@Serializable
data class ServiceWrite(
    val name: String,
    val description: String? = null,
    val categoryId: String? = null,
    val newCategory: String? = null,
    val durationMode: String,
    val minMinutes: Int? = null,
    val maxMinutes: Int? = null,
    val pricingMode: String,
    val pricePence: Int? = null,
    val hourlyRatePence: Int? = null,
    val depositPence: Int? = null,
    val locationMode: String,
    val requiresResourceTypeId: String? = null,
    val prerequisiteServiceId: String? = null,
    val prerequisiteLeadHours: Int? = null,
    val minAgeYears: Int? = null,
    val requiresConsent: Boolean,
    val bookableOnline: Boolean,
)

/** What a form could not read, and which field it was. */
class DraftProblem(val field: String, override val message: String) : Exception(message)

/**
 * What somebody has typed into the service form: text where they type, choices
 * where they choose. [write] turns it into what chairtime wants, or says which
 * field it could not read.
 */
data class ServiceDraft(
    val name: String = "",
    val description: String = "",
    val categoryId: String? = null,
    val newCategory: String = "",
    val pricingMode: PricingMode = PricingMode.Fixed,
    val price: String = "",
    val hourlyRate: String = "",
    val deposit: String = "",
    val durationMode: DurationMode = DurationMode.Fixed,
    val minMinutes: String = "",
    val maxMinutes: String = "",
    val locationMode: LocationMode = LocationMode.AtVenue,
    val bookableOnline: Boolean = true,
    val requiresConsent: Boolean = false,
    val prerequisiteServiceId: String? = null,
    val prerequisiteLeadHours: String = "",
    val minAgeYears: String = "",
    val requiresResourceTypeId: String? = null,
) {
    constructor(s: MenuServiceResponse.Detail) : this(
        name = s.name, description = s.description.orEmpty(), categoryId = s.categoryId,
        pricingMode = PricingMode.of(s.pricingMode), price = s.pricePence?.let(MoneyInput::pounds).orEmpty(),
        hourlyRate = s.hourlyRatePence?.let(MoneyInput::pounds).orEmpty(), deposit = s.depositPence?.let(MoneyInput::pounds).orEmpty(),
        durationMode = DurationMode.of(s.durationMode), minMinutes = s.minMinutes?.toString().orEmpty(), maxMinutes = s.maxMinutes?.toString().orEmpty(),
        locationMode = LocationMode.of(s.locationMode), bookableOnline = s.bookableOnline, requiresConsent = s.requiresConsent,
        prerequisiteServiceId = s.prerequisiteServiceId, prerequisiteLeadHours = s.prerequisiteLeadHours?.toString().orEmpty(),
        minAgeYears = s.minAgeYears?.toString().orEmpty(), requiresResourceTypeId = s.requiresResourceTypeId,
    )

    @Throws(DraftProblem::class)
    fun write(): ServiceWrite {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw DraftProblem("name", "A name is needed")
        val isHourly = pricingMode == PricingMode.Hourly
        val isFixedLength = durationMode == DurationMode.Fixed
        val shortest = if (isFixedLength) null else whole(minMinutes, "minMinutes")
        val longest = if (isFixedLength) null else whole(maxMinutes, "maxMinutes")
        if (shortest != null && longest != null && shortest > longest) throw DraftProblem("maxMinutes", "The longest cannot be shorter than the shortest.")
        val category = newCategory.trim()
        return ServiceWrite(
            name = trimmed, description = description.trim().ifEmpty { null },
            // A name typed wins over one picked, as on the web.
            categoryId = if (category.isEmpty()) categoryId else null, newCategory = category.ifEmpty { null },
            durationMode = durationMode.raw, minMinutes = shortest, maxMinutes = longest, pricingMode = pricingMode.raw,
            // One answer for the price: by the hour has a rate and no price.
            pricePence = if (isHourly) null else money(price, "pricePence"),
            hourlyRatePence = if (isHourly) money(hourlyRate, "hourlyRatePence") else null,
            depositPence = money(deposit, "depositPence"), locationMode = locationMode.raw,
            requiresResourceTypeId = requiresResourceTypeId, prerequisiteServiceId = prerequisiteServiceId,
            prerequisiteLeadHours = if (prerequisiteServiceId == null) null else whole(prerequisiteLeadHours, "prerequisiteLeadHours"),
            minAgeYears = whole(minAgeYears, "minAgeYears"), requiresConsent = requiresConsent, bookableOnline = bookableOnline,
        )
    }

    companion object {
        /** Blank is null; anything else has to be an amount. */
        fun money(text: String, field: String): Int? {
            val t = text.trim()
            if (t.isEmpty()) return null
            return MoneyInput.pence(t) ?: throw DraftProblem(field, "Enter an amount like 28.50")
        }

        fun whole(text: String, field: String): Int? {
            val t = text.trim()
            if (t.isEmpty()) return null
            return t.toIntOrNull()?.takeIf { it >= 0 } ?: throw DraftProblem(field, "Enter a whole number")
        }
    }
}

/** `PUT /api/v1/menu/{id}/steps`: the whole list, in order. */
@Serializable
data class StepsWrite(val steps: List<Step>) {
    @Serializable data class Step(val label: String? = null, val minutes: Int, val staffBusy: Boolean)
}

/** One row of the steps screen, as typed. */
data class StepDraft(val label: String = "", val minutes: String = "", val staffBusy: Boolean = true) {
    constructor(s: MenuServiceResponse.Segment) : this(s.label.orEmpty(), "${s.minutes}", s.staffBusy)

    val wholeMinutes: Int? get() = minutes.trim().let { if (it.isEmpty()) 0 else it.toIntOrNull() }

    companion object {
        /** The rows worth keeping: a blank or zero row is a step not used, as the web reads "0 minutes" as "remove it". */
        fun write(rows: List<StepDraft>): StepsWrite {
            val steps = rows.mapIndexedNotNull { i, row ->
                val minutes = row.wholeMinutes?.takeIf { it >= 0 } ?: throw DraftProblem("steps.$i.minutes", "A step is a whole number of minutes.")
                if (minutes == 0) null else StepsWrite.Step(row.label.trim().ifEmpty { null }, minutes, row.staffBusy)
            }
            if (steps.isEmpty()) throw DraftProblem("steps", "A service needs at least one block of time")
            return StepsWrite(steps)
        }

        /** "Holds the chair for 1h 30m, but only takes 55m of your time." — null when every minute is the pro's own. */
        fun summary(rows: List<StepDraft>): String? {
            val used = rows.mapNotNull { r -> r.wholeMinutes?.takeIf { it > 0 }?.let { it to r.staffBusy } }
            val held = used.sumOf { it.first }
            val busy = used.filter { it.second }.sumOf { it.first }
            return if (held > busy) "Holds the chair for ${Durations.short(held)}, but only takes ${Durations.short(busy)} of your time." else null
        }

        /** "35m is sellable." — the minutes somebody else can be booked into. Null when there are none. */
        fun sellable(rows: List<StepDraft>): String? {
            val free = rows.filter { !it.staffBusy }.sumOf { it.wholeMinutes?.takeIf { m -> m > 0 } ?: 0 }
            return if (free > 0) "${Durations.short(free)} is sellable." else null
        }

        /** The steps as they read now, not as they were saved: what the bar draws. */
        fun preview(rows: List<StepDraft>): List<MenuServiceResponse.Segment> = rows.mapIndexedNotNull { i, r ->
            r.wholeMinutes?.takeIf { it > 0 }?.let { MenuServiceResponse.Segment(i + 1, it, r.staffBusy, r.label.trim().ifEmpty { null }) }
        }
    }
}

/** `PUT /api/v1/menu/{id}/performers`. */
@Serializable
data class PerformersWrite(val performers: List<Entry>) {
    /** @param pricePence their own price, or null for the service's. */
    @Serializable data class Entry(val staffId: String, val pricePence: Int? = null)
}

/** `GET` and `PUT /api/v1/menu/{id}/extras`: every extra the shop still has, and whether this service offers it. */
@Serializable
data class ExtrasResponse(val extras: List<Extra> = emptyList()) {
    @Serializable
    data class Extra(val id: String, val name: String, val pricePence: Pence, val minutes: Int = 0, val offered: Boolean = false) {
        /** "£10 · adds 15m", or "£12 · no extra time". */
        fun line(currency: String): String = "${pricePence.formatted(currency)} · ${if (minutes > 0) "adds ${Durations.short(minutes)}" else "no extra time"}"
    }
}

@Serializable data class ExtraLinksWrite(val extraIds: List<String>)

/** `POST /api/v1/menu/extras` and `PUT /api/v1/menu/extras/{id}`. @param serviceId offer it on this service straight away. */
@Serializable data class ExtraWrite(val name: String, val pricePence: Int, val minutes: Int, val serviceId: String? = null)

@Serializable data class SavedId(val id: String)

// endregion
