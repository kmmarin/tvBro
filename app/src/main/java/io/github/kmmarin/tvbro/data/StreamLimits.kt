// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

import androidx.compose.runtime.mutableStateMapOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * A provider's account and stream limit, kept per source (encrypted storage, with the sources), from the Xtream
 * `player_api.php` `user_info`. [reported]: `max_connections`, null when the provider does not say; [user]: the user's
 * own number for a provider that does not report one; [active]: `active_cons`; [status] ("Active", "Expired"...),
 * [exp] and [created] (unix seconds, null: not given / unlimited), [trial], [username]: the account as the provider
 * describes it. [checkedAt]: when it was asked (ms, 0: never). The password is never kept here.
 */
@Serializable
data class StreamLimit(
    val reported: Int? = null, val active: Int? = null, val user: Int? = null, val checkedAt: Long = 0,
    val status: String? = null, val exp: Long? = null, val created: Long? = null, val trial: Boolean? = null, val username: String? = null,
)

/** Stream limit rules, plain JVM and unit-tested. One source of truth: every limit check goes through [limit]. */
object StreamRules {
    /** A provider that reports nothing and the user has not set: one stream. */
    const val DEFAULT = 1
    /** The most the user can set for a provider that does not report a limit. */
    const val USER_MAX = 4
    /** The account info warns this many days before the expiry date. */
    const val EXPIRY_WARN_DAYS = 14
    /** Latest account date accepted from a provider (2100-01-01, unix seconds); later values are treated as none. */
    const val MAX_DATE_SEC = 4_102_444_800L

    /** Streams the provider allows: its own number, else the user's, else [DEFAULT]. */
    fun limit(l: StreamLimit?): Int = l?.reported?.takeIf { it > 0 } ?: l?.user?.takeIf { it > 0 } ?: DEFAULT

    /** True when the number comes from the provider (false: the user's own, or the default). */
    fun fromProvider(l: StreamLimit?): Boolean = (l?.reported ?: 0) > 0

    /** "3 (from provider)" / "not reported, set to 2". */
    fun label(l: StreamLimit?): String =
        l?.reported?.takeIf { it > 0 }?.let { "$it (from provider)" } ?: "not reported, set to ${limit(l)}"

    /**
     * The account of a `player_api.php` answer: numbers may come as numbers or strings, 0 / blank `max_connections`
     * means not reported, a 0 / blank / "null" date means none. Null when the answer has no `user_info` (wrong login,
     * not Xtream, not JSON).
     */
    fun parseAccount(body: String, now: Long = 0): StreamLimit? = try {
        val ui = (Json.parseToJsonElement(body) as? JsonObject)?.get("user_info") as? JsonObject
        fun str(k: String) = (ui?.get(k) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
        fun num(k: String) = str(k)?.toLongOrNull()
        if (ui == null) null else StreamLimit(
            reported = num("max_connections")?.takeIf { it in 1..1000 }?.toInt(),
            active = num("active_cons")?.takeIf { it in 0..1000 }?.toInt(),
            checkedAt = now,
            status = str("status")?.take(40),
            exp = num("exp_date")?.takeIf { it in 1..MAX_DATE_SEC },
            created = num("created_at")?.takeIf { it in 1..MAX_DATE_SEC },
            trial = str("is_trial")?.let { it == "1" || it.equals("true", true) },
            username = str("username")?.take(80),
        )
    } catch (e: Exception) { null } catch (e: StackOverflowError) { null }

    /** A fresh answer replaces the provider's numbers; the user's own number stays. */
    fun merge(old: StreamLimit?, fresh: StreamLimit): StreamLimit = fresh.copy(user = old?.user)

    /** "Active" (and similar) shows green; anything else (Expired, Banned, Disabled) red. */
    fun statusOk(status: String?): Boolean = status != null && status.equals("active", true)

    /**
     * The expiry warning for an account expiring at [expSec] (unix seconds) seen at [nowMs]: "Expired", "Expires today",
     * "Expires in 3 days"; null when there is no date or it is more than [EXPIRY_WARN_DAYS] days away.
     */
    fun expiryWarning(expSec: Long?, nowMs: Long): String? {
        if (expSec == null || expSec <= 0 || expSec > MAX_DATE_SEC) return null
        val left = expSec * 1000 - nowMs
        if (left <= 0) return "Expired"
        val days = (left / 86_400_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return when {
            days >= EXPIRY_WARN_DAYS -> null
            days == 0 -> "Expires today"
            days == 1 -> "Expires in 1 day"
            else -> "Expires in $days days"
        }
    }

    /**
     * The provider a recording's stream comes from: its channel key starts with the source id; older recordings without
     * a key by the channel's name. Null when unknown (it then counts against every provider: never too many streams).
     */
    fun recSource(chKey: String, channel: String, nameToSource: (String) -> String?): String? =
        chKey.substringBefore('|', "").ifEmpty { null } ?: nameToSource(channel)

    /**
     * The provider a download's [url] comes from: the Xtream source whose server the URL starts with and whose username
     * is in the path. Null when none matches (it then counts against every provider, like a recording of unknown source).
     */
    fun dlSource(url: String, sources: List<Source>): String? = sources.firstOrNull { s ->
        val base = s.server.trimEnd('/')
        s.isXtream && base.isNotEmpty() && s.user.isNotEmpty() && url.startsWith("$base/") && url.contains("/${s.user}/")
    }?.id

    /** Streams a provider has left: its limit minus its running recordings and downloads (unknown provider counts too). */
    fun free(limit: Int, sourceId: String, recordings: List<String?>): Int =
        (limit - recordings.count { it == null || it == sourceId }).coerceAtLeast(0)

    /**
     * Migration from the old single "Maximum simultaneous streams" setting ([old], null: never set): every source without
     * a number of its own takes it as its user number (it applies when the provider does not report one).
     */
    fun migrate(limits: Map<String, StreamLimit>, sourceIds: List<String>, old: Int?): Map<String, StreamLimit> {
        if (old == null || old <= DEFAULT) return limits
        val out = HashMap(limits)
        for (id in sourceIds) { val l = out[id] ?: StreamLimit(); if (l.user == null) out[id] = l.copy(user = old.coerceIn(1, USER_MAX)) }
        return out
    }
}

/** The stream limits / accounts of the sources (in memory for the UI; stored encrypted by [Store]). */
object StreamLimits {
    val items = mutableStateMapOf<String, StreamLimit>()
    private var store: Store? = null

    /** Reads the stored limits and migrates the old global setting once. */
    @Synchronized fun init(st: Store, sourceIds: List<String>) {
        if (store != null) return
        var m = st.streamLimits()
        val old = st.takeOldMaxStreams()
        if (old != null) { m = StreamRules.migrate(m, sourceIds, old); st.saveStreamLimits(m); Diag.log("stream limits: old setting $old moved to ${sourceIds.size} source(s)") }
        items.putAll(m)
        store = st
    }

    fun get(id: String): StreamLimit? = items[id]
    fun limit(id: String): Int = StreamRules.limit(items[id])

    @Synchronized fun put(id: String, l: StreamLimit) { items[id] = l; store?.saveStreamLimits(HashMap(items)) }
    @Synchronized fun remove(id: String) { if (items.remove(id) != null) store?.saveStreamLimits(HashMap(items)) }
    fun setUser(id: String, n: Int) = put(id, (items[id] ?: StreamLimit()).copy(user = n.coerceIn(1, StreamRules.USER_MAX)))
    /** A provider answer (null: not reachable, the stored numbers stay). */
    fun update(id: String, fresh: StreamLimit?) { if (fresh != null) put(id, StreamRules.merge(items[id], fresh)) }
}
