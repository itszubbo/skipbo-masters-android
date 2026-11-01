package com.skipbomasters

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.auth.FirebaseAuth
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

private val CommunityGold = Color(0xFFFFD34E)
private val CommunityMuted = Color(0xFFB8CFC3)
private val CommunityPanel = Color(0xFF15382F)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CommunityScreen(
    onBack: () -> Unit,
    onOpenRoom: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rememberedName = FirebaseRooms.lastRoomSession(context)?.playerName ?: "Skip-Bo speler"
    var displayName by remember { mutableStateOf(rememberedName) }
    var googleLinked by remember { mutableStateOf(false) }
    var googleEmail by remember { mutableStateOf("") }
    var accountBusy by remember { mutableStateOf(false) }
    var accountMessage by remember { mutableStateOf("") }
    var accountError by remember { mutableStateOf("") }
    var showRestoreConfirm by remember { mutableStateOf(false) }
    var overview by remember { mutableStateOf<CommunityOverview?>(null) }
    var leaderboard by remember { mutableStateOf<List<CommunityPlayer>>(emptyList()) }
    var leaderboardCursor by remember { mutableStateOf<LeaderboardCursor?>(null) }
    var matchHistory by remember { mutableStateOf<List<MatchHistoryItem>>(emptyList()) }
    var historyLoaded by remember { mutableStateOf(false) }
    var achievements by remember { mutableStateOf<List<AchievementItem>>(emptyList()) }
    var achievementsLoaded by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var friendCodeInput by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }

    fun loadOverview() {
        loading = true
        error = ""
        FirebaseCommunity.loadOverview(context, displayName) { result ->
            loading = false
            result.onSuccess {
                overview = it
                displayName = it.profile.displayName
                val user = runCatching { FirebaseAuth.getInstance().currentUser }.getOrNull()
                googleLinked = user?.providerData?.any { provider -> provider.providerId == "google.com" } == true
                googleEmail = user?.email.orEmpty()
                error = ""
            }.onFailure {
                error = it.localizedMessage ?: "Communitygegevens konden niet worden geladen."
            }
        }
    }

    fun loadLeaderboard(reset: Boolean) {
        if (loadingMore) return
        loadingMore = true
        val cursor = if (reset) null else leaderboardCursor
        FirebaseCommunity.loadLeaderboard(context, cursor) { result ->
            loadingMore = false
            result.onSuccess { page ->
                leaderboard = if (reset) page.players else leaderboard + page.players
                leaderboardCursor = page.nextCursor
            }.onFailure {
                error = it.localizedMessage ?: "Ranglijst kon niet worden geladen."
            }
        }
    }
 
    fun loadMatchHistory() {
        loading = true
        error = ""
        FirebaseCommunity.loadMatchHistory(context) { result ->
            loading = false
            result.onSuccess {
                matchHistory = it
                historyLoaded = true
            }.onFailure {
                historyLoaded = false
                error = it.localizedMessage ?: "Wedstrijdgeschiedenis kon niet worden geladen."
            }
        }
    }

    fun loadAchievements() {
        loading = true
        error = ""
        FirebaseCommunity.loadAchievements(context) { result ->
            loading = false
            result.onSuccess {
                achievements = it
                achievementsLoaded = true
            }.onFailure {
                achievementsLoaded = false
                error = it.localizedMessage ?: "Prestaties konden niet worden geladen."
            }
        }
    }

    LaunchedEffect(Unit) {
        // Load the authenticated profile first; only then request the leaderboard.
        // This avoids racing two initial anonymous Firebase sign-in attempts.
        loadOverview()
    }
    LaunchedEffect(overview) {
        if (overview != null && leaderboard.isEmpty() && !loadingMore) {
            loadLeaderboard(reset = true)
        }
    }

    fun openAcceptedInvite(invite: GameInviteItem) {
        loading = true
        error = ""
        FirebaseCommunity.respondToGameInvite(context, invite.inviteId, true) { result ->
            loading = false
            result.onSuccess { code ->
                if (code.isNullOrBlank()) {
                    notice = "Uitnodiging geaccepteerd, maar de kamer is niet meer beschikbaar."
                } else {
                    FirebaseRooms.saveLastRoomSession(context, code, rememberedName)
                    onOpenRoom(code)
                }
            }.onFailure {
                error = it.localizedMessage ?: "Speluitnodiging kon niet worden geaccepteerd."
            }
        }
    }

    Column(
        Modifier.fillMaxSize()
            .background(Color(0xFF071D19))
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Terug", color = CommunityGold) }
            Spacer(Modifier.width(8.dp))
            Text("COMMUNITY", color = Color.White, fontWeight = FontWeight.Black, fontSize = 20.sp)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                loadOverview()
                if (selectedTab == 2) loadLeaderboard(reset = true)
                if (selectedTab == 3) loadMatchHistory()
                if (selectedTab == 4) loadAchievements()
            }) { Text("Vernieuwen", color = CommunityMuted) }
        }

        overview?.let { data ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CommunityPanel),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(data.profile.displayName, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = displayName,
                        onValueChange = { displayName = it.take(24) },
                        label = { Text("Spelersnaam") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(
                        enabled = !loading && displayName.isNotBlank() && displayName.trim() != data.profile.displayName,
                        onClick = {
                            loading = true
                            error = ""
                            FirebaseCommunity.updateDisplayName(context, displayName) { result ->
                                loading = false
                                result.onSuccess {
                                    notice = "Spelersnaam bijgewerkt."
                                    loadOverview()
                                }.onFailure {
                                    error = it.localizedMessage ?: "Spelersnaam kon niet worden opgeslagen."
                                }
                            }
                        }
                    ) { Text("Naam opslaan") }
                    Text("JOUW VRIENDENCODE", color = CommunityMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(data.profile.friendCode, color = CommunityGold, fontSize = 23.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = {
                            val share = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, "Voeg mij toe in Skip-Bo Masters: ${data.profile.friendCode}")
                            }
                            context.startActivity(Intent.createChooser(share, "Deel je vriendencode"))
                        }) { Text("Delen") }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        StatCell("${data.profile.rating}", "Punten")
                        StatCell("${data.profile.wins}", "Gewonnen")
                        StatCell("${data.profile.losses}", "Verloren")
                        StatCell("${data.profile.matchesPlayed}", "Wedstrijden")
                    }
                    val winRate = if (data.profile.matchesPlayed > 0) {
                        data.profile.wins * 100 / data.profile.matchesPlayed
                    } else 0
                    Text("Winstpercentage: $winRate%", color = CommunityMuted, fontSize = 11.sp)
                    if (data.profile.matchesPlayed < 10) {
                        Text("Voorlopig gerangschikt • ${10 - data.profile.matchesPlayed} wedstrijden tot officiële plaatsing", color = CommunityMuted, fontSize = 10.sp)
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CommunityPanel),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("ACCOUNT & VOORTGANG", color = CommunityGold, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.2.sp)
                if (googleLinked) {
                    Text("Google-account gekoppeld", color = Color(0xFF9EE6B4), fontWeight = FontWeight.SemiBold)
                    if (googleEmail.isNotBlank()) Text(googleEmail, color = CommunityMuted, fontSize = 11.sp)
                    Text("Je Firebase-speler-ID blijft behouden. Je vrienden, statistieken en wedstrijden blijven aan dit account gekoppeld.", color = CommunityMuted, fontSize = 11.sp)
                } else {
                    Text("Je speelt momenteel met een tijdelijk gastaccount.", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text("Koppel Google om deze speler-ID en de bijbehorende voortgang op andere toestellen te kunnen herstellen.", color = CommunityMuted, fontSize = 11.sp)
                    Button(
                        enabled = !accountBusy && FirebaseRooms.isConfigured(context),
                        onClick = {
                            accountBusy = true
                            accountMessage = ""
                            accountError = ""
                            scope.launch {
                                try {
                                    val user = GoogleAccountSignIn.linkCurrentAccount(context)
                                    googleLinked = user.providerData.any { it.providerId == "google.com" }
                                    googleEmail = user.email.orEmpty()
                                    accountMessage = "Google is gekoppeld. Je bestaande speler-ID is behouden."
                                    loadOverview()
                                } catch (failure: Exception) {
                                    accountError = failure.localizedMessage ?: "Google-account koppelen is mislukt."
                                } finally {
                                    accountBusy = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (accountBusy) "Bezig met koppelen…" else "Google-account koppelen")
                    }
                }
                OutlinedButton(
                    enabled = !accountBusy && FirebaseRooms.isConfigured(context),
                    onClick = { showRestoreConfirm = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Bestaand Google-account herstellen") }
                if (accountBusy) CircularProgressIndicator(color = CommunityGold)
                if (accountMessage.isNotBlank()) Text(accountMessage, color = Color(0xFF9EE6B4), fontSize = 11.sp)
                if (accountError.isNotBlank()) Text(accountError, color = Color(0xFFFFA8A8), fontSize = 11.sp)
            }
        }

        if (showRestoreConfirm) {
            AlertDialog(
                onDismissRequest = { showRestoreConfirm = false },
                title = { Text("Google-account herstellen") },
                text = {
                    Text(
                        "Kies het Google-account dat eerder aan Skip-Bo Masters is gekoppeld. Als het account bestaat, opent de app dat spelersprofiel. Als het Google-account nog niet eerder is gebruikt, maakt Firebase een nieuw account aan.\n\nEen tijdelijk gastaccount op dit toestel wordt niet automatisch met een ander account samengevoegd. Kies ‘Google-account koppelen’ om juist je huidige gastaccount te behouden."
                    )
                },
                confirmButton = {
                    Button(
                        enabled = !accountBusy,
                        onClick = {
                            showRestoreConfirm = false
                            accountBusy = true
                            accountMessage = ""
                            accountError = ""
                            scope.launch {
                                try {
                                    val result = GoogleAccountSignIn.signInOrRestore(context)
                                    googleLinked = result.user.providerData.any { it.providerId == "google.com" }
                                    googleEmail = result.user.email.orEmpty()
                                    accountMessage = if (result.createdNewAccount) {
                                        "Dit Google-account was nog niet aan Skip-Bo gekoppeld. Er is een nieuw spelersprofiel aangemaakt."
                                    } else {
                                        "Je bestaande Google-spelersaccount is hersteld."
                                    }
                                    loadOverview()
                                } catch (failure: Exception) {
                                    accountError = failure.localizedMessage ?: "Aanmelden met Google is mislukt."
                                } finally {
                                    accountBusy = false
                                }
                            }
                        }
                    ) { Text("Doorgaan") }
                },
                dismissButton = {
                    TextButton(onClick = { showRestoreConfirm = false }) { Text("Annuleren") }
                }
            )
        }

        TabRow(selectedTabIndex = selectedTab, containerColor = Color(0xFF0B2821), contentColor = CommunityGold) {
            listOf("Vrienden", "Uitnodigingen", "Ranglijst", "Geschiedenis", "Prestaties").forEachIndexed { index, label ->
                Tab(selected = selectedTab == index, onClick = {
                    selectedTab = index
                    if (index == 3 && !historyLoaded) loadMatchHistory()
                    if (index == 4 && !achievementsLoaded) loadAchievements()
                }, text = {
                    Text(label, fontSize = 9.sp, fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal)
                })
            }
        }

        if (loading || loadingMore) {
            CircularProgressIndicator(color = CommunityGold)
        }
        if (error.isNotBlank()) Text(error, color = Color(0xFFFFA8A8), fontSize = 12.sp)
        if (notice.isNotBlank()) Text(notice, color = CommunityGold, fontSize = 12.sp)

        when (selectedTab) {
            0 -> {
                Card(colors = CardDefaults.cardColors(containerColor = CommunityPanel), shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("VRIEND TOEVOEGEN", color = CommunityGold, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        OutlinedTextField(
                            value = friendCodeInput,
                            onValueChange = { raw -> friendCodeInput = raw.uppercase().filter { it.isLetterOrDigit() || it == '-' }.take(10) },
                            label = { Text("Vriendencode, bijvoorbeeld SKB-82K9Q") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            enabled = !loading && friendCodeInput.startsWith("SKB-") && friendCodeInput.length == 10,
                            onClick = {
                                loading = true
                                error = ""
                                FirebaseCommunity.requestFriend(context, friendCodeInput) { result ->
                                    loading = false
                                    result.onSuccess {
                                        friendCodeInput = ""
                                        notice = "Vriendschapsverzoek verzonden."
                                        loadOverview()
                                    }.onFailure { error = it.localizedMessage ?: "Verzoek kon niet worden verstuurd." }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Vriendschapsverzoek sturen") }
                    }
                }
                Text("MIJN VRIENDEN", color = CommunityMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                val friends = overview?.friends.orEmpty()
                if (friends.isEmpty()) Text("Je vriendenlijst is nog leeg. Voeg iemand toe met een vriendencode.", color = CommunityMuted, fontSize = 12.sp)
                friends.forEach { friend ->
                    Card(colors = CardDefaults.cardColors(containerColor = CommunityPanel), shape = RoundedCornerShape(12.dp)) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(friend.displayName, color = Color.White, fontWeight = FontWeight.SemiBold)
                                Text("${friend.rating} punten • ${friend.wins} overwinningen", color = CommunityMuted, fontSize = 10.sp)
                            }
                            OutlinedButton(enabled = !loading, onClick = {
                                loading = true
                                error = ""
                                FirebaseCommunity.createGameInvite(context, friend.uid) { result ->
                                    loading = false
                                    result.onSuccess { code ->
                                        FirebaseRooms.saveLastRoomSession(context, code, rememberedName)
                                        notice = "Speluitnodiging verstuurd naar ${friend.displayName}."
                                        onOpenRoom(code)
                                    }.onFailure { error = it.localizedMessage ?: "Uitnodiging kon niet worden aangemaakt." }
                                }
                            }) { Text("Daag uit", fontSize = 11.sp) }
                        }
                    }
                }
            }
            1 -> {
                Text("VRIENDSCHAPSVERZOEKEN", color = CommunityMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                val requests = overview?.friendRequests.orEmpty()
                if (requests.isEmpty()) Text("Je hebt geen openstaande vriendschapsverzoeken.", color = CommunityMuted, fontSize = 12.sp)
                requests.forEach { item ->
                    Card(colors = CardDefaults.cardColors(containerColor = CommunityPanel), shape = RoundedCornerShape(12.dp)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(item.from.displayName, color = Color.White, fontWeight = FontWeight.SemiBold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(enabled = !loading, onClick = {
                                    loading = true
                                    FirebaseCommunity.respondToFriend(context, item.requestId, true) { result ->
                                        loading = false
                                        result.onSuccess { notice = "Jullie zijn nu vrienden."; loadOverview() }
                                            .onFailure { error = it.localizedMessage ?: "Verzoek accepteren is mislukt." }
                                    }
                                }, modifier = Modifier.weight(1f)) { Text("Accepteren") }
                                OutlinedButton(enabled = !loading, onClick = {
                                    loading = true
                                    FirebaseCommunity.respondToFriend(context, item.requestId, false) { result ->
                                        loading = false
                                        result.onSuccess { notice = "Verzoek afgewezen."; loadOverview() }
                                            .onFailure { error = it.localizedMessage ?: "Verzoek afwijzen is mislukt." }
                                    }
                                }, modifier = Modifier.weight(1f)) { Text("Weigeren") }
                            }
                        }
                    }
                }
                Spacer(Modifier.width(1.dp))
                Text("SPELUITNODIGINGEN", color = CommunityMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                val invites = overview?.gameInvites.orEmpty()
                if (invites.isEmpty()) Text("Je hebt geen speluitnodigingen.", color = CommunityMuted, fontSize = 12.sp)
                invites.forEach { invite ->
                    Card(colors = CardDefaults.cardColors(containerColor = CommunityPanel), shape = RoundedCornerShape(12.dp)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${invite.from.displayName} daagt je uit voor een potje.", color = Color.White, fontWeight = FontWeight.SemiBold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(enabled = !loading, onClick = { openAcceptedInvite(invite) }, modifier = Modifier.weight(1f)) { Text("Accepteren") }
                                OutlinedButton(enabled = !loading, onClick = {
                                    loading = true
                                    FirebaseCommunity.respondToGameInvite(context, invite.inviteId, false) { result ->
                                        loading = false
                                        result.onSuccess { notice = "Uitnodiging afgewezen."; loadOverview() }
                                            .onFailure { error = it.localizedMessage ?: "Uitnodiging afwijzen is mislukt." }
                                    }
                                }, modifier = Modifier.weight(1f)) { Text("Weigeren") }
                            }
                        }
                    }
                }
            }
            2 -> {
                Text("WERELDWIJDE RANGLIJST", color = CommunityMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                if (leaderboard.isEmpty()) Text("Er zijn nog geen gerangschikte spelers.", color = CommunityMuted, fontSize = 12.sp)
                leaderboard.forEachIndexed { index, player ->
                    Card(colors = CardDefaults.cardColors(containerColor = CommunityPanel), shape = RoundedCornerShape(12.dp)) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${index + 1}.", color = CommunityGold, fontSize = 18.sp, fontWeight = FontWeight.Black)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(player.displayName, color = Color.White, fontWeight = FontWeight.SemiBold)
                                Text(if (player.provisional) "Voorlopig • ${player.matchesPlayed} wedstrijden" else "${player.matchesPlayed} wedstrijden", color = CommunityMuted, fontSize = 10.sp)
                            }
                            Text("${player.rating}", color = CommunityGold, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                if (leaderboardCursor != null) {
                    OutlinedButton(onClick = { loadLeaderboard(reset = false) }, enabled = !loadingMore, modifier = Modifier.fillMaxWidth()) {
                        Text("Meer spelers laden")
                    }
                }
            }
            3 -> {
                Text("RECENTE WEDSTRIJDEN", color = CommunityMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Text("Uitslagen van online wedstrijden die door de server zijn bevestigd.", color = CommunityMuted, fontSize = 11.sp)
                OutlinedButton(
                    onClick = { loadMatchHistory() },
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Geschiedenis vernieuwen") }

                if (!historyLoaded && !loading) {
                    Text("Open dit tabblad om je wedstrijden op te halen.", color = CommunityMuted, fontSize = 12.sp)
                } else if (historyLoaded && matchHistory.isEmpty()) {
                    Text("Je hebt nog geen afgeronde online wedstrijden.", color = CommunityMuted, fontSize = 12.sp)
                }

                matchHistory.forEach { match ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = CommunityPanel),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Text(match.opponentName, color = Color.White, fontWeight = FontWeight.SemiBold)
                                Text(formatMatchDate(match.playedAtMs), color = CommunityMuted, fontSize = 10.sp)
                                Text(
                                    if (match.won) "Gewonnen" else "Verloren",
                                    color = if (match.won) Color(0xFF9EE6B4) else Color(0xFFFFA8A8),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            val pointLabel = when {
                                match.ratingChange > 0 -> "+${match.ratingChange}"
                                match.ratingChange < 0 -> "−${kotlin.math.abs(match.ratingChange)}"
                                else -> "0"
                            }
                            Text(
                                "$pointLabel punten",
                                color = if (match.ratingChange >= 0) CommunityGold else Color(0xFFFFA8A8),
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
            else -> {
                val unlockedCount = achievements.count { it.unlocked }
                Text("PRESTATIES & TROFEEËN", color = CommunityMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Text(
                    "$unlockedCount van ${achievements.size} trofeeën vrijgespeeld",
                    color = CommunityGold,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black
                )
                Text(
                    "Je prestaties worden automatisch toegekend na door de server bevestigde online wedstrijden.",
                    color = CommunityMuted,
                    fontSize = 11.sp
                )
                OutlinedButton(
                    onClick = { loadAchievements() },
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Prestaties vernieuwen") }

                if (!achievementsLoaded && !loading) {
                    Text("Open dit tabblad om je prestaties op te halen.", color = CommunityMuted, fontSize = 12.sp)
                } else if (achievementsLoaded && achievements.isEmpty()) {
                    Text("Er zijn nog geen prestaties beschikbaar.", color = CommunityMuted, fontSize = 12.sp)
                }

                achievements.forEach { achievement ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (achievement.unlocked) Color(0xFF1B4837) else CommunityPanel
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (achievement.unlocked) "🏆" else "🔒",
                                fontSize = 25.sp
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(
                                Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(achievement.title, color = Color.White, fontWeight = FontWeight.Bold)
                                Text(achievement.description, color = CommunityMuted, fontSize = 10.sp)
                                Text(
                                    "${achievement.progress} / ${achievement.target}",
                                    color = CommunityGold,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    if (achievement.unlocked) "VRIJGESPEELD" else "NOG TE HALEN",
                                    color = if (achievement.unlocked) Color(0xFF9EE6B4) else CommunityMuted,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.8.sp
                                )
                                if (achievement.unlocked && achievement.unlockedAtMs != null) {
                                    Text(
                                        "Behaald op ${formatMatchDate(achievement.unlockedAtMs)}",
                                        color = CommunityMuted,
                                        fontSize = 9.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        Text(
            "Wedstrijdscores worden alleen na een door de server bevestigde overwinning aangepast.",
            color = Color(0xFF829D90), fontSize = 10.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )
    }
}

@Composable
private fun StatCell(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, color = CommunityGold, fontWeight = FontWeight.Black, fontSize = 17.sp)
        Text(label, color = CommunityMuted, fontSize = 9.sp)
    }
}

private fun formatMatchDate(timestampMs: Long): String {
    if (timestampMs <= 0L) return "Datum onbekend"
    return DateFormat.getDateTimeInstance(
        DateFormat.MEDIUM,
        DateFormat.SHORT,
        Locale.getDefault()
    ).format(Date(timestampMs))
}
