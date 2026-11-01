package com.skipbomasters

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata

internal data class CommunityProfile(
    val uid: String,
    val displayName: String,
    val friendCode: String,
    val rating: Int,
    val wins: Int,
    val losses: Int,
    val matchesPlayed: Int,
    val avatarPath: String? = null
)

internal data class CommunityPlayer(
    val uid: String,
    val displayName: String,
    val rating: Int,
    val wins: Int = 0,
    val losses: Int = 0,
    val matchesPlayed: Int = 0,
    val provisional: Boolean = false
)

internal data class FriendRequestItem(
    val requestId: String,
    val from: CommunityPlayer
)

internal data class GameInviteItem(
    val inviteId: String,
    val roomCode: String,
    val from: CommunityPlayer
)

internal data class CommunityOverview(
    val profile: CommunityProfile,
    val friends: List<CommunityPlayer>,
    val friendRequests: List<FriendRequestItem>,
    val gameInvites: List<GameInviteItem>
)

internal data class MatchHistoryItem(
    val matchId: String,
    val opponentName: String,
    val won: Boolean,
    val ratingChange: Int,
    val playedAtMs: Long
)

internal data class AchievementItem(
    val id: String,
    val title: String,
    val description: String,
    val category: String,
    val progress: Int,
    val target: Int,
    val unlocked: Boolean,
    val unlockedAtMs: Long?
)

internal data class LeaderboardCursor(val rating: Int, val uid: String)
internal data class LeaderboardPage(
    val players: List<CommunityPlayer>,
    val nextCursor: LeaderboardCursor?
)

/** All community mutations and ranked data are handled by callable Cloud Functions. */
internal object FirebaseCommunity {
    fun loadOverview(
        context: Context,
        displayName: String,
        callback: (Result<CommunityOverview>) -> Unit
    ) {
        FirebaseRooms.callFunction(
            context, "ensureFriendCode", mapOf("displayName" to displayName.trim().take(24))
        ) { ensured ->
            ensured.fold(
                onSuccess = {
                    FirebaseRooms.callFunction(context, "getCommunityOverview", emptyMap()) { loaded ->
                        callback(loaded.mapCatching { parseOverview(it) })
                    }
                },
                onFailure = { callback(Result.failure(it)) }
            )
        }
    }

    fun updateDisplayName(context: Context, displayName: String, callback: (Result<Unit>) -> Unit) {
        FirebaseRooms.callFunction(
            context, "updatePlayerDisplayName", mapOf("displayName" to displayName.trim().take(24))
        ) { callback(it.map { Unit }) }
    }

    fun requestFriend(context: Context, code: String, callback: (Result<Unit>) -> Unit) {
        FirebaseRooms.callFunction(context, "requestFriendByCode", mapOf("code" to code.trim().uppercase())) {
            callback(it.map { Unit })
        }
    }

    fun respondToFriend(
        context: Context,
        requestId: String,
        accept: Boolean,
        callback: (Result<Unit>) -> Unit
    ) {
        FirebaseRooms.callFunction(context, "respondToFriendRequest", mapOf(
            "requestId" to requestId,
            "action" to if (accept) "accept" else "decline"
        )) { callback(it.map { Unit }) }
    }

    fun createGameInvite(context: Context, friendUid: String, callback: (Result<String>) -> Unit) {
        FirebaseRooms.callFunction(context, "createFriendGameInvite", mapOf("friendUid" to friendUid)) { result ->
            callback(result.mapCatching { data ->
                val map = data.asMap()
                map.string("roomCode")
            })
        }
    }

    fun respondToGameInvite(
        context: Context,
        inviteId: String,
        accept: Boolean,
        callback: (Result<String?>) -> Unit
    ) {
        FirebaseRooms.callFunction(context, "respondToGameInvite", mapOf(
            "inviteId" to inviteId,
            "action" to if (accept) "accept" else "decline"
        )) { result ->
            callback(result.mapCatching { data ->
                val code = data.asMap()["roomCode"] as? String
                code?.takeIf { it.isNotBlank() }
            })
        }
    }

    fun uploadAvatar(
        context: Context,
        uri: Uri,
        callback: (Result<String>) -> Unit
    ) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid.isNullOrBlank()) {
            callback(Result.failure(IllegalStateException("Meld je eerst aan om een profielfoto te uploaden.")))
            return
        }

        val contentType = context.contentResolver.getType(uri)
        if (contentType !in setOf("image/jpeg", "image/png", "image/webp")) {
            callback(Result.failure(IllegalArgumentException("Kies een JPEG-, PNG- of WebP-afbeelding.")))
            return
        }

        val path = "profilePhotos/$uid/avatar"
        val reference = FirebaseStorage.getInstance().reference.child(path)
        val metadata = StorageMetadata.Builder()
            .setContentType(contentType)
            .build()

        reference.putFile(uri, metadata)
            .addOnSuccessListener {
                FirebaseRooms.callFunction(
                    context,
                    "setAvatarPath",
                    mapOf("storagePath" to path)
                ) { result ->
                    callback(result.map { path })
                }
            }
            .addOnFailureListener {
                callback(Result.failure(it))
            }
    }

    fun loadAvatar(
        storagePath: String,
        callback: (Result<Bitmap>) -> Unit
    ) {
        FirebaseStorage.getInstance().reference.child(storagePath)
            .getBytes(5L * 1024L * 1024L)
            .addOnSuccessListener { bytes ->
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap == null) {
                    callback(Result.failure(IllegalStateException("De profielfoto kon niet worden gelezen.")))
                } else {
                    callback(Result.success(bitmap))
                }
            }
            .addOnFailureListener {
                callback(Result.failure(it))
            }
    }

    fun loadMatchHistory(
        context: Context,
        callback: (Result<List<MatchHistoryItem>>) -> Unit
    ) {
        FirebaseRooms.callFunction(context, "getMyMatchHistory", mapOf("pageSize" to 20)) { result ->
            callback(result.mapCatching { data ->
                val root = data.asMap()
                root.list("matches").map { item ->
                    val map = item.asMap()
                    MatchHistoryItem(
                        matchId = map.string("matchId"),
                        opponentName = map.string("opponentName", "Speler"),
                        won = map["won"] as? Boolean ?: false,
                        ratingChange = map.int("ratingChange"),
                        playedAtMs = (map["playedAtMs"] as? Number)?.toLong() ?: 0L
                    )
                }
            })
        }
    }

    fun loadAchievements(
        context: Context,
        callback: (Result<List<AchievementItem>>) -> Unit
    ) {
        FirebaseRooms.callFunction(context, "getMyAchievements", emptyMap()) { result ->
            callback(result.mapCatching { data ->
                val root = data.asMap()
                root.list("achievements").map { item ->
                    val map = item.asMap()
                    AchievementItem(
                        id = map.string("id"),
                        title = map.string("title"),
                        description = map.string("description"),
                        category = map.string("category"),
                        progress = map.int("progress"),
                        target = map.int("target"),
                        unlocked = map["unlocked"] as? Boolean ?: false,
                        unlockedAtMs = (map["unlockedAtMs"] as? Number)?.toLong()
                    )
                }
            })
        }
    }

    fun loadLeaderboard(
        context: Context,
        cursor: LeaderboardCursor? = null,
        callback: (Result<LeaderboardPage>) -> Unit
    ) {
        val args = mutableMapOf<String, Any>("pageSize" to 25)
        if (cursor != null) args["cursor"] = mapOf("rating" to cursor.rating, "uid" to cursor.uid)
        FirebaseRooms.callFunction(context, "getLeaderboardPage", args) { result ->
            callback(result.mapCatching { parseLeaderboard(it) })
        }
    }

    private fun parseOverview(data: Any?): CommunityOverview {
        val root = data.asMap()
        val me = root["profile"].asMap()
        return CommunityOverview(
            profile = CommunityProfile(
                uid = me.string("uid"),
                displayName = me.string("displayName", "Skip-Bo speler"),
                friendCode = me.string("friendCode"),
                rating = me.int("rating", 1000),
                wins = me.int("wins"),
                losses = me.int("losses"),
                matchesPlayed = me.int("matchesPlayed"),
                avatarPath = me["avatarPath"] as? String
            ),
            friends = root.list("friends").map { parsePlayer(it) },
            friendRequests = root.list("friendRequests").map { item ->
                val map = item.asMap()
                FriendRequestItem(map.string("requestId"), parsePlayer(map["from"]))
            },
            gameInvites = root.list("gameInvites").map { item ->
                val map = item.asMap()
                GameInviteItem(map.string("inviteId"), map.string("roomCode"), parsePlayer(map["from"]))
            }
        )
    }

    private fun parseLeaderboard(data: Any?): LeaderboardPage {
        val root = data.asMap()
        val cursorMap = root["nextCursor"] as? Map<*, *>
        return LeaderboardPage(
            players = root.list("players").map { parsePlayer(it) },
            nextCursor = cursorMap?.let {
                LeaderboardCursor(
                    rating = (it["rating"] as? Number)?.toInt() ?: 0,
                    uid = it["uid"] as? String ?: ""
                )
            }
        )
    }

    private fun parsePlayer(value: Any?): CommunityPlayer {
        val map = value.asMap()
        return CommunityPlayer(
            uid = map.string("uid"),
            displayName = map.string("displayName", "Speler"),
            rating = map.int("rating", 1000),
            wins = map.int("wins"),
            losses = map.int("losses"),
            matchesPlayed = map.int("matchesPlayed"),
            provisional = map["provisional"] as? Boolean ?: false
        )
    }

    private fun Any?.asMap(): Map<*, *> = this as? Map<*, *>
        ?: throw IllegalStateException("Ongeldig antwoord van de communityserver.")

    private fun Map<*, *>.string(key: String, fallback: String = ""): String =
        this[key] as? String ?: fallback

    private fun Map<*, *>.int(key: String, fallback: Int = 0): Int =
        (this[key] as? Number)?.toInt() ?: fallback

    private fun Map<*, *>.list(key: String): List<Any?> =
        this[key] as? List<Any?> ?: emptyList()
}
