package app.dak.automations.forwarding

import app.dak.automations.rule.ActionSpec
import app.dak.automations.rule.Condition
import app.dak.automations.rule.Rule
import app.dak.automations.rule.Trigger
import app.dak.automations.rule.otpExclusion
import app.dak.automations.rule.topLevelConjuncts
import app.dak.core.model.Category
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * One source channel of a forwarding rule: a display conversation (a folded sender group such as "HDFC Bank",
 * `m:HDFCBK`, or a person's thread `t:42`) plus the raw addresses it had when chosen (used for loop checks and as a
 * fallback match if the thread id changes).
 */
@Serializable
public data class ForwardingSource(
    val conversationId: String,
    val name: String,
    val mergeKey: String? = null,
    val addresses: List<String> = emptyList(),
)

/** Who receives the forwards. [name] is display-only (from the contact picker). */
@Serializable
public data class ForwardingRecipient(val number: String, val name: String? = null) {
    /** What the UI shows: the contact name, else the number. */
    val label: String get() = name?.takeIf { it.isNotBlank() } ?: number
}

/** Where a forwarding rule is in its life, for the "Forwarding rules" list. */
public enum class ForwardingStatus { ACTIVE, SCHEDULED, PAUSED, ENDED }

/**
 * The model behind the "Forwarding rules" screen: time-boxed SMS forwarding of chosen channels (e.g. "Tax docs for
 * CA": HDFC Bank + Zerodha + Income Tax Dept → the CA, 1 Jul – 31 Jul). It is stored as an ordinary [Rule] (so the
 * [app.dak.automations.RuleEngine] runs it with everything else) with `meta["kind"] = "forwarding"`:
 *
 * - trigger [Trigger.MessageReceived]; conditions `All(ActiveBetween, SenderInGroups, [Any(CategoryIs…)],
 *   [BodyContains], [Not(HasOtp), Not(CategoryIs(OTP))])` — OTPs are excluded unless [includeOtp];
 * - one [ActionSpec.ForwardSms] per recipient, all from [subId], with [template].
 *
 * Forwarding runs on-device from the user's own SIM, so it is a free feature.
 *
 * @param startMillis inclusive start (the UI passes local start-of-day of the chosen date).
 * @param endMillis inclusive end (local end-of-day), or null for "until I stop".
 * @param categories optional category filter; empty = any category.
 * @param keyword optional case-insensitive body filter; blank = none.
 */
public data class ForwardingSpec(
    val id: String? = null,
    val name: String = "",
    val enabled: Boolean = true,
    val sources: List<ForwardingSource> = emptyList(),
    val categories: Set<Category> = emptySet(),
    val keyword: String = "",
    val recipients: List<ForwardingRecipient> = emptyList(),
    val subId: Int? = null,
    val startMillis: Long = 0L,
    val endMillis: Long? = null,
    val template: String = DEFAULT_TEMPLATE,
    val includeOtp: Boolean = false,
    val createdAt: Long? = null,
) {
    /** True when every required field is present (a name can be derived). */
    val isComplete: Boolean
        get() = sources.isNotEmpty() && recipients.any { it.number.isNotBlank() } &&
            (endMillis == null || endMillis >= startMillis)

    /** Status at [nowMillis]: ended beats paused, so an expired rule always reads "Ended". */
    public fun status(nowMillis: Long): ForwardingStatus = when {
        endMillis != null && nowMillis > endMillis -> ForwardingStatus.ENDED
        !enabled -> ForwardingStatus.PAUSED
        nowMillis < startMillis -> ForwardingStatus.SCHEDULED
        else -> ForwardingStatus.ACTIVE
    }

    /** Name shown when the user left it blank: "HDFC Bank, Zerodha → CA". */
    public fun derivedName(): String {
        val from = sources.take(2).joinToString(", ") { it.name } + if (sources.size > 2) " +${sources.size - 2}" else ""
        val to = recipients.joinToString(", ") { it.label }
        return "$from → $to"
    }

    /**
     * Builds the stored rule. Returns null when [isComplete] is false. With [includeOtp] and a category filter,
     * [Category.OTP] is added to the filter so "include OTPs" really includes them (and the rule then needs the
     * biometric confirmation every OTP-forwarding rule needs).
     */
    public fun toRule(nowMillis: Long, newId: () -> String): Rule? {
        if (!isComplete) return null
        val valid = recipients.filter { it.number.isNotBlank() }.distinctBy { it.number.trim() }
        val conditions = buildList {
            add(Condition.ActiveBetween(startMillis, endMillis))
            add(
                Condition.SenderInGroups(
                    mergeKeys = sources.mapNotNull { it.mergeKey }.distinct(),
                    conversationIds = sources.map { it.conversationId }.distinct(),
                    addresses = sources.flatMap { it.addresses }.distinct(),
                ),
            )
            val cats = if (includeOtp && categories.isNotEmpty()) categories + Category.OTP else categories
            if (cats.isNotEmpty()) add(Condition.Any(cats.sortedBy { it.ordinal }.map { Condition.CategoryIs(it) }))
            keyword.trim().takeIf { it.isNotEmpty() }?.let { add(Condition.BodyContains(it)) }
            if (!includeOtp) addAll(otpExclusion())
        }
        val tpl = template.ifBlank { DEFAULT_TEMPLATE }
        return Rule(
            id = id ?: newId(),
            name = name.trim().ifEmpty { derivedName() },
            enabled = enabled,
            trigger = Trigger.MessageReceived(),
            conditions = Condition.All(conditions),
            actions = valid.map { ActionSpec.ForwardSms(to = it.number.trim(), subId = subId, template = tpl) },
            createdAt = createdAt ?: nowMillis,
            updatedAt = nowMillis,
            meta = mapOf(
                META_KIND to KIND_FORWARDING,
                META_SOURCES to metaJson.encodeToString(ListSerializer(ForwardingSource.serializer()), sources),
                META_RECIPIENT_NAMES to metaJson.encodeToString(
                    MapSerializer(String.serializer(), String.serializer()),
                    valid.filter { !it.name.isNullOrBlank() }.associate { it.number.trim() to it.name!! },
                ),
            ),
        )
    }

    public companion object {
        public const val DEFAULT_TEMPLATE: String = "Fwd from {sender}: {body}"
        public const val META_KIND: String = "kind"
        public const val KIND_FORWARDING: String = "forwarding"
        private const val META_SOURCES = "forwarding.sources"
        private const val META_RECIPIENT_NAMES = "forwarding.recipientNames"

        private val metaJson = Json { ignoreUnknownKeys = true }

        /** True when [rule] was made by the forwarding screen. */
        public fun isForwarding(rule: Rule): Boolean = rule.meta[META_KIND] == KIND_FORWARDING

        /** The editor model for [rule], or null when it is not a forwarding rule (or is too damaged to edit). */
        public fun fromRule(rule: Rule): ForwardingSpec? {
            if (!isForwarding(rule)) return null
            val conjuncts = topLevelConjuncts(rule.conditions)
            val window = conjuncts.firstNotNullOfOrNull { it as? Condition.ActiveBetween }
            val groups = conjuncts.firstNotNullOfOrNull { it as? Condition.SenderInGroups } ?: return null
            val forwards = rule.actions.filterIsInstance<ActionSpec.ForwardSms>()
            if (forwards.isEmpty()) return null
            val sources = rule.meta[META_SOURCES]
                ?.let { runCatching { metaJson.decodeFromString(ListSerializer(ForwardingSource.serializer()), it) }.getOrNull() }
                ?: groups.conversationIds.map { ForwardingSource(conversationId = it, name = it) }
            val names = rule.meta[META_RECIPIENT_NAMES]
                ?.let { runCatching { metaJson.decodeFromString(MapSerializer(String.serializer(), String.serializer()), it) }.getOrNull() }
                .orEmpty()
            val categories = conjuncts.filterIsInstance<Condition.Any>()
                .flatMap { any -> any.children.filterIsInstance<Condition.CategoryIs>().map { it.category } }
                .toSet()
            val excludesOtp = otpExclusion().all { it in conjuncts }
            val shownCategories = if (!excludesOtp && categories.size > 1) categories - Category.OTP else categories
            return ForwardingSpec(
                id = rule.id,
                name = rule.name,
                enabled = rule.enabled,
                sources = sources,
                categories = shownCategories,
                keyword = conjuncts.firstNotNullOfOrNull { it as? Condition.BodyContains }?.keyword.orEmpty(),
                recipients = forwards.map { ForwardingRecipient(it.to, names[it.to]) },
                subId = forwards.first().subId,
                startMillis = window?.startMillis ?: rule.createdAt,
                endMillis = window?.endMillis,
                template = forwards.first().template,
                includeOtp = !excludesOtp,
                createdAt = rule.createdAt,
            )
        }
    }
}
