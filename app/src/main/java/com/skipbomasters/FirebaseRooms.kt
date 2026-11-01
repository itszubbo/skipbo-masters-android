package com.skipbomasters

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.functions.FirebaseFunctions
import java.security.SecureRandom

/** Room metadata and read-only realtime listeners. Match mutations go through callable Cloud Functions. */
data class OnlineRoom(
    val code: String,
    val hostUid: String,
    val hostName: String,
    val guestUid: String?,
    val guestName: String?,
    val status: String
) {
    val isReady: Boolean get() = guestUid != null && (status == "READY" || status == "PLAYING" || status == "FINISHED")
}

data class OnlinePrivateState(val revision: Long, val hand: List<Int>)
data class OnlineCommit(val revision: Long, val payload: String, val hand: List<Int>)
data class SavedRoomSession(val code: String, val playerName: String)

object FirebaseRooms {
    private const val ROOM_COLLECTION = "rooms"
    private const val FUNCTIONS_REGION = "europe-west1"
    private const val CODE_LENGTH = 6
    private const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private val random = SecureRandom()


    private const val SESSION_PREFS = "skipbo_online_session"
    private const val SESSION_CODE = "last_room_code"
    private const val SESSION_NAME = "last_player_name"

    /** Remember only the room code and display name; Firebase Auth stores the identity. */
    fun saveLastRoomSession(context: Context, code: String, playerName: String) {
        context.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE).edit()
            .putString(SESSION_CODE, normalizeCode(code))
            .putString(SESSION_NAME, cleanName(playerName))
            .apply()
    }

    fun lastRoomSession(context: Context): SavedRoomSession? {
        val prefs = context.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE)
        val code = normalizeCode(prefs.getString(SESSION_CODE, "") ?: "")
        val name = cleanName(prefs.getString(SESSION_NAME, "") ?: "")
        return if (code.length == CODE_LENGTH && name.isNotBlank()) SavedRoomSession(code, name) else null
    }

    fun clearLastRoomSession(context: Context) {
        context.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE).edit()
            .remove(SESSION_CODE)
            .remove(SESSION_NAME)
            .apply()
    }

    fun isConfigured(context: Context): Boolean = try {
        FirebaseApp.getApps(context).isNotEmpty()
    } catch (_: Exception) { false }

    fun currentUid(context: Context): String? = try {
        if (isConfigured(context)) FirebaseAuth.getInstance().currentUser?.uid else null
    } catch (_: Exception) { null }

    private fun requireFirebase(context: Context, onReady: (FirebaseAuth, FirebaseFirestore) -> Unit, onError: (String) -> Unit) {
        if (!isConfigured(context)) {
            onError("Firebase is nog niet gekoppeld. Voeg app/google-services.json toe en schakel Anoniem aanmelden en Cloud Firestore in.")
            return
        }
        try {
            val auth = FirebaseAuth.getInstance()
            val db = FirebaseFirestore.getInstance()
            if (auth.currentUser != null) onReady(auth, db)
            else auth.signInAnonymously()
                .addOnSuccessListener { onReady(auth, db) }
                .addOnFailureListener { onError(it.localizedMessage ?: "Anoniem aanmelden is mislukt.") }
        } catch (error: Exception) {
            onError(error.localizedMessage ?: "Firebase kon niet worden gestart.")
        }
    }

    fun createRoom(context: Context, playerName: String, callback: (Result<OnlineRoom>) -> Unit) {
        val safeName = cleanName(playerName)
        if (safeName.isBlank()) {
            callback(Result.failure(IllegalArgumentException("Vul eerst een spelersnaam in.")))
            return
        }
        requireFirebase(context, { auth, db ->
            val uid = auth.currentUser?.uid ?: run {
                callback(Result.failure(IllegalStateException("Geen spelerssessie beschikbaar."))); return@requireFirebase
            }
            val code = newCode()
            val ref = db.collection(ROOM_COLLECTION).document(code)
            val data = hashMapOf<String, Any?>(
                "hostUid" to uid, "hostName" to safeName, "guestUid" to null, "guestName" to null,
                "status" to "WAITING", "createdAt" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
            )
            ref.set(data)
                .addOnSuccessListener { callback(Result.success(OnlineRoom(code, uid, safeName, null, null, "WAITING"))) }
                .addOnFailureListener { callback(Result.failure(it)) }
        }, { callback(Result.failure(IllegalStateException(it))) })
    }

    fun joinRoom(context: Context, playerName: String, rawCode: String, callback: (Result<OnlineRoom>) -> Unit) {
        val safeName = cleanName(playerName)
        val code = normalizeCode(rawCode)
        if (safeName.isBlank()) {
            callback(Result.failure(IllegalArgumentException("Vul eerst een spelersnaam in."))); return
        }
        if (code.length != CODE_LENGTH) {
            callback(Result.failure(IllegalArgumentException("Een kamercode bestaat uit zes tekens."))); return
        }
        requireFirebase(context, { auth, db ->
            val uid = auth.currentUser?.uid ?: run {
                callback(Result.failure(IllegalStateException("Geen spelerssessie beschikbaar."))); return@requireFirebase
            }
            val ref = db.collection(ROOM_COLLECTION).document(code)
            db.runTransaction<Void?> { transaction ->
                val snapshot = transaction.get(ref)
                if (!snapshot.exists()) throw IllegalStateException("Deze kamer bestaat niet (meer).")
                val hostUid = snapshot.getString("hostUid") ?: throw IllegalStateException("Ongeldige kamerdata.")
                val guestUid = snapshot.getString("guestUid")
                if (hostUid == uid || guestUid == uid) {
                    // A participant can reconnect to an existing live room.
                } else if (guestUid == null) {
                    if (snapshot.getString("status") != "WAITING") {
                        throw IllegalStateException("Deze kamer accepteert geen nieuwe spelers meer.")
                    }
                    transaction.update(ref, mapOf(
                        "guestUid" to uid, "guestName" to safeName, "status" to "READY",
                        "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                    ))
                } else throw IllegalStateException("Deze kamer is al vol.")
                null
            }.addOnSuccessListener {
                ref.get().addOnSuccessListener { callback(Result.success(it.toRoom(code))) }
                    .addOnFailureListener { callback(Result.failure(it)) }
            }.addOnFailureListener { callback(Result.failure(it.cause ?: it)) }
        }, { callback(Result.failure(IllegalStateException(it))) })
    }

    fun listenRoom(context: Context, code: String, callback: (Result<OnlineRoom>) -> Unit): ListenerRegistration? {
        if (!isConfigured(context)) {
            callback(Result.failure(IllegalStateException("Firebase is niet gekoppeld."))); return null
        }
        if (FirebaseAuth.getInstance().currentUser == null) {
            callback(Result.failure(IllegalStateException("Geen spelerssessie. Maak of open eerst een kamer."))); return null
        }
        return FirebaseFirestore.getInstance().collection(ROOM_COLLECTION).document(normalizeCode(code))
            .addSnapshotListener { snapshot, error ->
                when {
                    error != null -> callback(Result.failure(error))
                    snapshot == null || !snapshot.exists() -> callback(Result.failure(IllegalStateException("De kamer is verwijderd.")))
                    else -> callback(runCatching { snapshot.toRoom(normalizeCode(code)) })
                }
            }
    }

    fun callFunction(
        context: Context,
        functionName: String,
        arguments: Map<String, Any>,
        callback: (Result<Any?>) -> Unit
    ) {
        requireFirebase(context, { _, _ ->
            try {
                val functions = FirebaseFunctions.getInstance(FirebaseApp.getInstance(), FUNCTIONS_REGION)
                functions.getHttpsCallable(functionName).call(arguments)
                    .addOnSuccessListener { callback(Result.success(it.data)) }
                    .addOnFailureListener { callback(Result.failure(it)) }
            } catch (error: Exception) {
                callback(Result.failure(error))
            }
        }, { callback(Result.failure(IllegalStateException(it))) })
    }

    fun startOnlineMatch(context: Context, code: String, callback: (Result<OnlineCommit>) -> Unit) {
        callMatchFunction(context, "startOnlineMatch", mapOf("code" to normalizeCode(code)), callback)
    }

    fun resumeOnlineMatch(context: Context, code: String, callback: (Result<OnlineCommit>) -> Unit) {
        callMatchFunction(context, "resumeOnlineMatch", mapOf("code" to normalizeCode(code)), callback)
    }

    fun submitOnlinePlay(context: Context, code: String, revision: Long, source: String, index: Int,
                         callback: (Result<OnlineCommit>) -> Unit) {
        if (source !in setOf("stock", "hand", "discard")) {
            callback(Result.failure(IllegalArgumentException("Ongeldige kaartbron."))); return
        }
        callMatchFunction(context, "submitOnlineMove", mapOf(
            "code" to normalizeCode(code), "expectedRevision" to revision, "actionType" to "play",
            "source" to source, "index" to index
        ), callback)
    }

    fun submitOnlineDiscard(context: Context, code: String, revision: Long, handIndex: Int, discardPileIndex: Int,
                            callback: (Result<OnlineCommit>) -> Unit) {
        callMatchFunction(context, "submitOnlineMove", mapOf(
            "code" to normalizeCode(code), "expectedRevision" to revision, "actionType" to "discard",
            "handIndex" to handIndex, "discardPileIndex" to discardPileIndex
        ), callback)
    }

    private fun callMatchFunction(context: Context, functionName: String, arguments: Map<String, Any>, callback: (Result<OnlineCommit>) -> Unit) {
        requireFirebase(context, { _, _ ->
            try {
                val functions = FirebaseFunctions.getInstance(FirebaseApp.getInstance(), FUNCTIONS_REGION)
                functions.getHttpsCallable(functionName).call(arguments)
                    .addOnSuccessListener { result ->
                        callback(runCatching { result.data.toOnlineCommit() })
                    }
                    .addOnFailureListener { callback(Result.failure(it)) }
            } catch (error: Exception) {
                callback(Result.failure(error))
            }
        }, { callback(Result.failure(IllegalStateException(it))) })
    }

    private fun Any?.toOnlineCommit(): OnlineCommit {
        val map = this as? Map<*, *> ?: throw IllegalStateException("Ongeldig antwoord van de spelserver.")
        val revision = (map["revision"] as? Number)?.toLong() ?: throw IllegalStateException("Serverantwoord mist revisie.")
        val payload = map["payload"] as? String ?: throw IllegalStateException("Serverantwoord mist spelstatus.")
        val privatePlayer = map["privatePlayer"] as? Map<*, *> ?: throw IllegalStateException("Serverantwoord mist privékaarten.")
        val hand = (privatePlayer["hand"] as? List<*>)?.map {
            (it as? Number)?.toInt() ?: throw IllegalStateException("Ongeldige kaartdata van server.")
        } ?: throw IllegalStateException("Serverantwoord mist de privéhand.")
        return OnlineCommit(revision, payload, hand)
    }

    fun listenPrivatePlayer(context: Context, code: String, callback: (Result<OnlinePrivateState?>) -> Unit): ListenerRegistration? {
        if (!isConfigured(context)) {
            callback(Result.failure(IllegalStateException("Firebase is niet gekoppeld."))); return null
        }
        val uid = currentUid(context)
        if (uid == null) {
            callback(Result.failure(IllegalStateException("Meld je eerst aan om je privékaarten te laden."))); return null
        }
        return FirebaseFirestore.getInstance().collection(ROOM_COLLECTION).document(normalizeCode(code))
            .collection("privatePlayers").document(uid)
            .addSnapshotListener { snapshot, error ->
                when {
                    error != null -> callback(Result.failure(error))
                    snapshot == null || !snapshot.exists() -> callback(Result.success(null))
                    else -> callback(runCatching {
                        OnlinePrivateState(
                            snapshot.getLong("revision") ?: 0L,
                            snapshot.intList("hand")
                        )
                    })
                }
            }
    }

    fun listenGameState(context: Context, code: String, callback: (Result<Pair<Long, String>?>) -> Unit): ListenerRegistration? {
        if (!isConfigured(context) || FirebaseAuth.getInstance().currentUser == null) {
            callback(Result.failure(IllegalStateException("Meld je eerst aan en open een online kamer."))); return null
        }
        return FirebaseFirestore.getInstance().collection(ROOM_COLLECTION).document(normalizeCode(code))
            .collection("state").document("current")
            .addSnapshotListener { snapshot, error ->
                when {
                    error != null -> callback(Result.failure(error))
                    snapshot == null || !snapshot.exists() -> callback(Result.success(null))
                    else -> {
                        val revision = snapshot.getLong("revision") ?: 0L
                        val payload = snapshot.getString("payload")
                        if (payload == null) callback(Result.failure(IllegalStateException("Ongeldige online spelstatus.")))
                        else callback(Result.success(revision to payload))
                    }
                }
            }
    }

    private fun DocumentSnapshot.intList(field: String): List<Int> = (get(field) as? List<*>)?.map {
        val value = (it as? Number)?.toInt() ?: throw IllegalStateException("Ongeldige $field-data.")
        require(value in 0..12) { "Ongeldige kaartwaarde in $field." }
        value
    } ?: throw IllegalStateException("Privéspel mist $field.")

    private fun DocumentSnapshot.toRoom(code: String) = OnlineRoom(
        code, getString("hostUid") ?: "", getString("hostName") ?: "Speler 1",
        getString("guestUid"), getString("guestName"), getString("status") ?: "WAITING"
    )

    private fun cleanName(name: String) = name.trim().take(24)
    private fun normalizeCode(code: String) = code.trim().uppercase().filter { it.isLetterOrDigit() }
    private fun newCode() = buildString { repeat(CODE_LENGTH) { append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]) } }
}
