package com.vitorpamplona.amethyst.model

import androidx.lifecycle.LiveData
import com.vitorpamplona.amethyst.lnurl.LnInvoiceUtil
import com.vitorpamplona.amethyst.service.NostrSingleEventDataSource
import com.vitorpamplona.amethyst.service.model.LnZapEvent
import com.vitorpamplona.amethyst.service.model.ReactionEvent
import com.vitorpamplona.amethyst.service.model.RepostEvent
import com.vitorpamplona.amethyst.ui.note.toShortenHex
import fr.acinq.secp256k1.Hex
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.vitorpamplona.amethyst.service.relays.Relay
import java.math.BigDecimal
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Matcher
import java.util.regex.Pattern
import nostr.postr.events.Event

val tagSearch = Pattern.compile("(?:\\s|\\A)\\#\\[([0-9]+)\\]")

class Note(val idHex: String) {
    // These fields are always available.
    // They are immutable
    val id = Hex.decode(idHex)
    val idDisplayNote = id.toNote().toShortenHex()

    // These fields are only available after the Text Note event is received.
    // They are immutable after that.
    var event: Event? = null
    var author: User? = null
    var mentions: List<User>? = null
    var replyTo: List<Note>? = null

    // These fields are updated every time an event related to this note is received.
    var replies = setOf<Note>()
        private set
    var reactions = setOf<Note>()
        private set
    var boosts = setOf<Note>()
        private set
    var reports = mapOf<User, Set<Note>>()
        private set
    var zaps = mapOf<Note, Note?>()
        private set

    var relays = setOf<String>()
        private set

    var channel: Channel? = null

    var lastReactionsDownloadTime: Long? = null

    fun loadEvent(event: Event, author: User, mentions: List<User>, replyTo: List<Note>) {
        this.event = event
        this.author = author
        this.mentions = mentions
        this.replyTo = replyTo

        live.invalidateData()
    }

    fun formattedDateTime(timestamp: Long): String {
        return Instant.ofEpochSecond(timestamp).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("uuuu-MM-dd-HH:mm:ss"))
    }

    fun replyLevelSignature(): String {
        val replyTo = replyTo
        if (replyTo == null || replyTo.isEmpty()) {
            return "/" + formattedDateTime(event?.createdAt ?: 0) + ";"
        }

        return replyTo
            .map { it.replyLevelSignature() }
            .maxBy { it.length }.removeSuffix(";") + "/" + formattedDateTime(event?.createdAt ?: 0) + ";"
    }

    fun replyLevel(): Int {
        val replyTo = replyTo
        if (replyTo == null || replyTo.isEmpty()) {
            return 0
        }

        return replyTo.maxOf {
            it.replyLevel()
        } + 1
    }

    fun addReply(note: Note) {
        if (note !in replies) {
            replies = replies + note
            liveReplies.invalidateData()
        }
    }

    fun addBoost(note: Note) {
        if (note !in boosts) {
            boosts = boosts + note
            liveBoosts.invalidateData()
        }
    }

    fun addZap(zapRequest: Note, zap: Note?) {
        if (zapRequest !in zaps.keys) {
            zaps = zaps + Pair(zapRequest, zap)
            liveZaps.invalidateData()
        } else if (zapRequest in zaps.keys && zaps[zapRequest] == null) {
            zaps = zaps + Pair(zapRequest, zap)
            liveZaps.invalidateData()
        }
    }

    fun addReaction(note: Note) {
        if (note !in reactions) {
            reactions = reactions + note
            liveReactions.invalidateData()
        }
    }

    fun addReport(note: Note) {
        val author = note.author ?: return

        if (author !in reports.keys) {
            reports = reports + Pair(author, setOf(note))
            liveReports.invalidateData()
        } else if (reports[author]?.contains(note) == false) {
            reports = reports + Pair(author, (reports[author] ?: emptySet()) + note)
            liveReports.invalidateData()
        }
    }

    fun addRelay(relay: Relay) {
        if (relay.url !in relays) {
            relays = relays + relay.url
            liveRelays.invalidateData()
        }
    }

    fun isZappedBy(user: User): Boolean {
        // Zaps who the requester was the user
        return zaps.any { it.key.author == user }
    }

    fun isReactedBy(user: User): Boolean {
        return reactions.any { it.author == user }
    }

    fun isBoostedBy(user: User): Boolean {
        return boosts.any { it.author == user }
    }

    fun reportsBy(user: User): Set<Note> {
        return reports[user] ?: emptySet()
    }

    fun reportAuthorsBy(users: Set<User>): List<User> {
        return reports.keys.filter { it in users }
    }

    fun reportsBy(users: Set<User>): List<Note> {
        return reportAuthorsBy(users).mapNotNull {
            reports[it]
        }.flatten()
    }

    fun zappedAmount(): BigDecimal {
        return zaps.mapNotNull { it.value?.event }
            .filterIsInstance<LnZapEvent>()
            .mapNotNull {
                it.amount
            }.sumOf { it }
    }

    fun hasAnyReports(): Boolean {
        val dayAgo = Date().time / 1000 - 24*60*60
        return reports.isNotEmpty() ||
          (author?.reports?.values?.filter {
              it.firstOrNull { it.event?.createdAt ?: 0 > dayAgo } != null
          }?.isNotEmpty() ?: false)
    }

    fun directlyCiteUsers(): Set<User> {
        val matcher = tagSearch.matcher(event?.content ?: "")
        val returningList = mutableSetOf<User>()
        while (matcher.find()) {
            try {
                val tag = matcher.group(1)?.let { event?.tags?.get(it.toInt()) }
                if (tag != null && tag[0] == "p") {
                    returningList.add(LocalCache.getOrCreateUser(tag[1]))
                }
            } catch (e: Exception) {

            }
        }
        return returningList
    }

    fun directlyCites(userProfile: User): Boolean {
        return author == userProfile
          || (userProfile in directlyCiteUsers())
          || (event is ReactionEvent && replyTo?.lastOrNull()?.directlyCites(userProfile) == true)
          || (event is RepostEvent && replyTo?.lastOrNull()?.directlyCites(userProfile) == true)
    }

    fun isNewThread(): Boolean {
        return event is RepostEvent || replyTo == null || replyTo?.size == 0
    }

    fun hasZapped(loggedIn: User): Boolean {
        return zaps.any { it.key.author == loggedIn }
    }

    fun hasReacted(loggedIn: User, content: String): Boolean {
        return reactions.firstOrNull { it.author == loggedIn && it.event?.content == content } != null
    }

    fun hasBoosted(loggedIn: User): Boolean {
        val currentTime = Date().time / 1000
        return boosts.firstOrNull { it.author == loggedIn && (it.event?.createdAt ?: 0) > currentTime - (60 * 5)} != null // 5 minute protection
    }

    // Observers line up here.
    val live: NoteLiveData = NoteLiveData(this)

    val liveReactions: NoteLiveData = NoteLiveData(this)
    val liveBoosts: NoteLiveData = NoteLiveData(this)
    val liveReplies: NoteLiveData = NoteLiveData(this)
    val liveReports: NoteLiveData = NoteLiveData(this)
    val liveRelays: NoteLiveData = NoteLiveData(this)
    val liveZaps: NoteLiveData = NoteLiveData(this)
}

class NoteLiveData(val note: Note): LiveData<NoteState>(NoteState(note)) {
    // Refreshes observers in batches.
    var handlerWaiting = AtomicBoolean()

    @Synchronized
    fun invalidateData() {
        if (!hasActiveObservers()) return
        if (handlerWaiting.getAndSet(true)) return

        handlerWaiting.set(true)
        val scope = CoroutineScope(Job() + Dispatchers.Main)
        scope.launch {
            delay(100)
            refresh()
            handlerWaiting.set(false)
        }
    }

    fun refresh() {
        postValue(NoteState(note))
    }

    override fun onActive() {
        super.onActive()
        NostrSingleEventDataSource.add(note.idHex)
    }

    override fun onInactive() {
        super.onInactive()
        NostrSingleEventDataSource.remove(note.idHex)
    }
}

class NoteState(val note: Note)
