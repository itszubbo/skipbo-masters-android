package com.skipbomasters

import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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

private data class CardThemeOption(
    val id: String,
    val title: String,
    val description: String,
    val requiredLevel: Int,
    val background: Color,
    val foreground: Color,
    val symbol: String
)

private val CardThemeOptions = listOf(
    CardThemeOption("classic", "Klassiek", "De vertrouwde rode kaartachterkant.", 1, Color(0xFFB51F2A), Color.White, "♠"),
    CardThemeOption("emerald", "Smaragd", "Donkergroen met een gouden accent.", 2, Color(0xFF174C35), CommunityGold, "✦"),
    CardThemeOption("royal_gold", "Koninklijk goud", "Een luxe gouden kaartstijl.", 4, Color(0xFFFFD34E), Color(0xFF43320C), "♛"),
    CardThemeOption("midnight", "Middernacht", "Nachtblauw met een lichte glans.", 7, Color(0xFF282640), Color(0xFFE3D8FF), "✧")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CommunityScreen(
    onBack: () -> Unit,
    onOpenRoom: (String) -> Unit,
    onCardThemeChanged: (String) -> Unit = {}
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
    var tournaments by remember { mutableStateOf<List<TournamentSummary>>(emptyList()) }
    var tournamentsLoaded by remember { mutableStateOf(false) }
    var dailyProgress by remember { mutableStateOf<DailyProgress?>(null) }
    var dailyLoaded by remember { mutableStateOf(false) }
    var seasonProgress by remember { mutableStateOf<SeasonProgress?>(null) }
    var seasonLoaded by remember { mutableStateOf(false) }
    var tournamentTitle by remember { mutableStateOf("Mijn vriendentoernooi") }
    var tournamentCapacity by remember { mutableIntStateOf(4) }
    var tournamentCodeInput by remember { mutableStateOf("") }
    var tournamentDetails by remember { mutableStateOf<TournamentDetails?>(null) }
    var avatarBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var avatarBusy by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var friendCodeInput by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }

    fun loadAvatar(path: String?) {
        if (path.isNullOrBlank()) {
            avatarBitmap = null
            return
        }
        FirebaseCommunity.loadAvatar(path) { result ->
            result.onSuccess { avatarBitmap = it }
                .onFailure { avatarBitmap = null }
        }
    }

    fun loadOverview() {
        loading = true
        error = ""
        FirebaseCommunity.loadOverview(context, displayName) { result ->
            loading = false
            result.onSuccess {
                overview = it
                onCardThemeChanged(it.profile.activeCardTheme)
                displayName = it.profile.displayName
                loadAvatar(it.profile.avatarPath)
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
 
    fun loadDailyProgress() {
        loading = true
        error = ""
        FirebaseCommunity.loadDailyProgress(context) { result ->
            loading = false
            result.onSuccess {
                dailyProgress = it
                dailyLoaded = true
            }.onFailure {
                dailyLoaded = false
                error = it.localizedMessage ?: "Dagelijkse uitdagingen konden niet worden geladen."
            }
        }
    }

    fun loadSeasonProgress() {
        loading = true
        error = ""
        FirebaseCommunity.loadSeasonProgress(context) { result ->
            loading = false
            result.onSuccess {
                seasonProgress = it
                seasonLoaded = true
            }.onFailure {
                seasonLoaded = false
                error = it.localizedMessage ?: "Seizoensvoortgang kon niet worden geladen."
            }
        }
    }

    fun claimDailyRewardNow() {
        loading = true
        error = ""
        FirebaseCommunity.claimDailyReward(context) { result ->
            loading = false
            result.onSuccess { claim ->
                notice = when {
                    !claim.claimed -> "Je dagbeloning is vandaag al opgehaald."
                    claim.cosmeticUnlocked != null -> "+${claim.xpAwarded} XP • Niveau ${claim.level} • Smaragdgroene profielrand vrijgespeeld!"
                    else -> "+${claim.xpAwarded} XP toegevoegd • inlogreeks: ${claim.streak} dagen."
                }
                loadDailyProgress()
                loadOverview()
            }.onFailure {
                error = it.localizedMessage ?: "Dagbeloning ophalen is mislukt."
            }
        }
    }

    fun loadTournaments() {
        loading = true
        error = ""
        FirebaseCommunity.loadTournaments(context) { result ->
            loading = false
            result.onSuccess {
                tournaments = it
                tournamentsLoaded = true
            }.onFailure {
                tournamentsLoaded = false
                error = it.localizedMessage ?: "Toernooien konden niet worden geladen."
            }
        }
    }

    fun loadTournamentDetails(tournamentId: String) {
        loading = true
        error = ""
        FirebaseCommunity.loadTournamentDetails(context, tournamentId) { result ->
            loading = false
            result.onSuccess {
                tournamentDetails = it
                tournamentCodeInput = it.tournament.inviteCode
            }.onFailure {
                error = it.localizedMessage ?: "Toernooi kon niet worden geopend."
            }
        }
    }

    fun createTournamentNow() {
        loading = true
        error = ""
        FirebaseCommunity.createTournament(context, tournamentTitle, tournamentCapacity) { result ->
            loading = false
            result.onSuccess { created ->
                notice = "Toernooi aangemaakt. Deel de code met je Skip-Bo-vrienden."
                tournamentDetails = null
                loadTournaments()
                loadTournamentDetails(created.tournamentId)
            }.onFailure {
                error = it.localizedMessage ?: "Toernooi aanmaken is mislukt."
            }
        }
    }

    fun joinTournamentNow() {
        val code = tournamentCodeInput.trim()
        if (code.isBlank()) {
            error = "Vul eerst de toernooicode in."
            return
        }
        loading = true
        error = ""
        FirebaseCommunity.joinTournament(context, code) { result ->
            loading = false
            result.onSuccess { status ->
                notice = if (status == "RUNNING") {
                    "Je bent toegevoegd. Het toernooi is gestart!"
                } else {
                    "Je bent toegevoegd aan het toernooi."
                }
                loadTournaments()
                loadTournamentDetails(code)
            }.onFailure {
                error = it.localizedMessage ?: "Deelname aan het toernooi is mislukt."
            }
        }
    }

    fun openTournamentMatch(match: TournamentMatchItem) {
        val currentTournament = tournamentDetails ?: return
        loading = true
        error = ""
        FirebaseCommunity.startTournamentMatch(
            context,
            currentTournament.tournament.tournamentId,
            match.matchId
        ) { result ->
            loading = false
            result.onSuccess { roomCode ->
                FirebaseRooms.saveLastRoomSession(context, roomCode, rememberedName)
                onOpenRoom(roomCode)
            }.onFailure {
                error = it.localizedMessage ?: "Toernooiwedstrijd kon niet worden geopend."
            }
        }
    }

    val avatarPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            avatarBusy = true
            error = ""
            FirebaseCommunity.uploadAvatar(context, uri) { result ->
                avatarBusy = false
                result.onSuccess { path ->
                    loadAvatar(path)
                    notice = "Profielfoto bijgewerkt."
                    loadOverview()
                }.onFailure {
                    error = it.localizedMessage ?: "Profielfoto uploaden is mislukt."
                }
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
                if (selectedTab == 8) loadSeasonProgress()
            }) { Text("Vernieuwen", color = CommunityMuted) }
        }

        overview?.let { data ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CommunityPanel),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val currentAvatar = avatarBitmap
                        if (currentAvatar != null) {
                            Image(
                                bitmap = currentAvatar.asImageBitmap(),
                                contentDescription = "Profielfoto",
                                modifier = Modifier.size(68.dp).clip(CircleShape).border(
                                    width = if (data.profile.unlockedCosmetics.contains("emerald_frame")) 3.dp else 0.dp,
                                    color = if (data.profile.unlockedCosmetics.contains("emerald_frame")) CommunityGold else Color.Transparent,
                                    shape = CircleShape
                                ),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Card(
                                modifier = Modifier.size(68.dp).border(
                                    width = if (data.profile.unlockedCosmetics.contains("emerald_frame")) 3.dp else 0.dp,
                                    color = if (data.profile.unlockedCosmetics.contains("emerald_frame")) CommunityGold else Color.Transparent,
                                    shape = CircleShape
                                ),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF245A43)),
                                shape = CircleShape
                            ) {
                                Column(
                                    Modifier.fillMaxSize(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text("SM", color = CommunityGold, fontWeight = FontWeight.Black, fontSize = 20.sp)
                                }
                            }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(data.profile.displayName, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            OutlinedButton(
                                enabled = !avatarBusy,
                                onClick = {
                                    avatarPicker.launch(
                                        PickVisualMediaRequest(
                                            ActivityResultContracts.PickVisualMedia.ImageOnly
                                        )
                                    )
                                }
                            ) {
                                Text(if (avatarBusy) "Foto uploaden…" else "Profielfoto wijzigen")
                            }
                        }
                    }
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
                    Text("Niveau ${data.profile.level} • ${data.profile.xp} XP", color = CommunityGold, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    data.profile.activeBadgeId?.let { badgeId ->
                        Text("🏅 Badge: ${badgeName(badgeId)}", color = Color(0xFFFFE29A), fontWeight = FontWeight.Bold, fontSize = 10.sp)
                        TextButton(
                            onClick = {
                                loading = true
                                FirebaseCommunity.setActiveBadge(context, null) { result ->
                                    loading = false
                                    result.onSuccess {
                                        overview = overview?.copy(profile = data.profile.copy(activeBadgeId = null))
                                        notice = "Profielbadge verwijderd."
                                    }.onFailure {
                                        error = it.localizedMessage ?: "Badge kon niet worden verwijderd."
                                    }
                                }
                            },
                            contentPadding = PaddingValues(0.dp)
                        ) { Text("Badge verwijderen", color = CommunityMuted, fontSize = 10.sp) }
                    }
                    if (data.profile.unlockedCosmetics.contains("emerald_frame")) {
                        Text("✦ Smaragdgroene profielrand vrijgespeeld", color = Color(0xFF9EE6B4), fontSize = 10.sp)
                    }
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
            listOf("Vrienden", "Uitnodigingen", "Ranglijst", "Geschiedenis", "Prestaties", "Toernooien", "Dagelijks", "Kaartstijl", "Seizoen").forEachIndexed { index, label ->
                Tab(selected = selectedTab == index, onClick = {
                    selectedTab = index
                    if (index == 3 && !historyLoaded) loadMatchHistory()
                    if (index == 4 && !achievementsLoaded) loadAchievements()
                    if (index == 5 && !tournamentsLoaded) loadTournaments()
                    if (index == 6 && !dailyLoaded) loadDailyProgress()
                    if (index == 8 && !seasonLoaded) loadSeasonProgress()
                }, text = {
                    Text(label, fontSize = 7.sp, fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal)
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
                            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    "$pointLabel punten",
                                    color = if (match.ratingChange >= 0) CommunityGold else Color(0xFFFFA8A8),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                                val canRematch = overview?.friends?.any { it.uid == match.opponentUid } == true
                                if (canRematch) {
                                    OutlinedButton(
                                        enabled = !loading,
                                        onClick = {
                                            loading = true
                                            error = ""
                                            FirebaseCommunity.createGameInvite(context, match.opponentUid) { result ->
                                                loading = false
                                                result.onSuccess { code ->
                                                    FirebaseRooms.saveLastRoomSession(context, code, displayName)
                                                    notice = "Revanche-uitnodiging verstuurd naar ${match.opponentName}."
                                                    onOpenRoom(code)
                                                }.onFailure {
                                                    error = it.localizedMessage ?: "Revanche-uitnodiging kon niet worden verstuurd."
                                                }
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) { Text("Revanche", fontSize = 10.sp) }
                                }
                            }
                        }
                    }
                }
            }
            4 -> {
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
                                if (achievement.unlocked) {
                                    val badgeIsActive = overview?.profile?.activeBadgeId == achievement.id
                                    OutlinedButton(
                                        enabled = !loading && !badgeIsActive,
                                        onClick = {
                                            loading = true
                                            error = ""
                                            FirebaseCommunity.setActiveBadge(context, achievement.id) { result ->
                                                loading = false
                                                result.onSuccess { selected ->
                                                    overview = overview?.let { current ->
                                                        current.copy(profile = current.profile.copy(activeBadgeId = selected))
                                                    }
                                                    notice = "Trofeebadge ${achievement.title} is nu actief."
                                                }.onFailure {
                                                    error = it.localizedMessage ?: "Trofeebadge kon niet worden uitgerust."
                                                }
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) { Text(if (badgeIsActive) "Badge actief" else "Uitruisten als badge", fontSize = 10.sp) }
                                }
                            }
                        }
                    }
                }
            }
            5 -> {
                val details = tournamentDetails
                if (details == null) {
                    Text("VRIENDENTOERNOOIEN", color = CommunityMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                    Text("Organiseer een privétoernooi met vier of acht vrienden. Alleen bestaande vrienden kunnen met de code deelnemen.", color = CommunityMuted, fontSize = 11.sp)

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = CommunityPanel),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Text("NIEUW TOERNOOI", color = CommunityGold, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            OutlinedTextField(
                                value = tournamentTitle,
                                onValueChange = { tournamentTitle = it.take(32) },
                                label = { Text("Toernooinaam") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { tournamentCapacity = 4 },
                                    modifier = Modifier.weight(1f)
                                ) { Text(if (tournamentCapacity == 4) "✓ 4 spelers" else "4 spelers") }
                                OutlinedButton(
                                    onClick = { tournamentCapacity = 8 },
                                    modifier = Modifier.weight(1f)
                                ) { Text(if (tournamentCapacity == 8) "✓ 8 spelers" else "8 spelers") }
                            }
                            Button(
                                onClick = { createTournamentNow() },
                                enabled = !loading && tournamentTitle.trim().length >= 3,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Toernooi aanmaken") }
                        }
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = CommunityPanel),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("MEEDOEN MET EEN CODE", color = CommunityGold, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            OutlinedTextField(
                                value = tournamentCodeInput,
                                onValueChange = { tournamentCodeInput = it.trim().take(40) },
                                label = { Text("Toernooicode van een vriend") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Button(
                                onClick = { joinTournamentNow() },
                                enabled = !loading && tournamentCodeInput.trim().length >= 10,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Deelnemen") }
                        }
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("MIJN TOERNOOIEN", color = CommunityMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
                        TextButton(onClick = { loadTournaments() }, enabled = !loading) {
                            Text("Vernieuwen", color = CommunityGold)
                        }
                    }
                    if (tournamentsLoaded && tournaments.isEmpty()) {
                        Text("Je neemt nog niet deel aan toernooien.", color = CommunityMuted, fontSize = 12.sp)
                    }
                    tournaments.forEach { tournament ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = CommunityPanel),
                            shape = RoundedCornerShape(12.dp),
                            onClick = { loadTournamentDetails(tournament.tournamentId) }
                        ) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(tournament.title, color = Color.White, fontWeight = FontWeight.Bold)
                                Text(
                                    "${tournament.participantCount} / ${tournament.capacity} spelers • ${tournamentStatusLabel(tournament.status)}",
                                    color = CommunityMuted,
                                    fontSize = 10.sp
                                )
                                Text("Open toernooi en bracket bekijken ›", color = CommunityGold, fontSize = 11.sp)
                            }
                        }
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(onClick = { tournamentDetails = null; loadTournaments() }) {
                            Text("‹ Toernooien", color = CommunityGold)
                        }
                        TextButton(onClick = { loadTournamentDetails(details.tournament.tournamentId) }) {
                            Text("Vernieuwen", color = CommunityGold)
                        }
                    }
                    Text(details.tournament.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black)
                    Text(
                        "${details.tournament.participantCount} / ${details.tournament.capacity} spelers • ${tournamentStatusLabel(details.tournament.status)}",
                        color = CommunityMuted,
                        fontSize = 11.sp
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Toernooicode: ${details.tournament.inviteCode}", color = CommunityGold, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = {
                            val share = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, "Doe mee aan mijn Skip-Bo Masters-toernooi met code: ${details.tournament.inviteCode}")
                            }
                            context.startActivity(Intent.createChooser(share, "Deel toernooicode"))
                        }) { Text("Delen") }
                    }
                    Text("DEELNEMERS", color = CommunityMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
                    details.tournament.participants.forEach { participant ->
                        Text("• ${participant.displayName}", color = Color.White, fontSize = 12.sp)
                    }
                    Text("TOERNOOIBRACKET", color = CommunityMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
                    details.matches.forEach { match ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = CommunityPanel),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Text(
                                    "${tournamentRoundLabel(match.round, details.tournament.capacity)} • Wedstrijd ${match.slot + 1}",
                                    color = CommunityGold,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(match.player1Name, color = Color.White, fontWeight = FontWeight.SemiBold)
                                Text("tegen", color = CommunityMuted, fontSize = 9.sp)
                                Text(match.player2Name, color = Color.White, fontWeight = FontWeight.SemiBold)
                                if (!match.winnerName.isNullOrBlank()) {
                                    Text("Winnaar: ${match.winnerName}", color = Color(0xFF9EE6B4), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                } else {
                                    Text(tournamentMatchStatusLabel(match.status), color = CommunityMuted, fontSize = 10.sp)
                                }
                                if (match.canOpen) {
                                    Button(
                                        onClick = { openTournamentMatch(match) },
                                        enabled = !loading,
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text(if (match.status == "PLAYING") "Wedstrijd openen" else "Wedstrijd starten") }
                                }
                            }
                        }
                    }
                    if (details.tournament.status == "COMPLETED" && details.tournament.winnerName != null) {
                        Text("🏆 Toernooiwinnaar: ${details.tournament.winnerName}", color = CommunityGold, fontWeight = FontWeight.Black)
                    }
                }
            }
            7 -> {
                Text("VRIJSPEELBARE KAARTSTIJLEN", color = CommunityMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
                Text(
                    "Verdien XP met online uitdagingen en wedstrijden om nieuwe achterkanten voor je trekstapel vrij te spelen.",
                    color = CommunityMuted,
                    fontSize = 11.sp
                )
                val profile = overview?.profile
                if (profile == null) {
                    Text("Je profiel wordt geladen. Open dit tabblad zodra je profiel beschikbaar is.", color = CommunityMuted, fontSize = 12.sp)
                } else {
                    Text("HUIDIGE STIJL: ${CardThemeOptions.firstOrNull { it.id == profile.activeCardTheme }?.title ?: "Klassiek"}", color = CommunityGold, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    CardThemeOptions.forEach { theme ->
                        val unlocked = profile.level >= theme.requiredLevel
                        val active = profile.activeCardTheme == theme.id
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = if (active) Color(0xFF1B4837) else CommunityPanel
                            ),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Card(
                                    modifier = Modifier.size(width = 64.dp, height = 82.dp),
                                    colors = CardDefaults.cardColors(containerColor = theme.background),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Column(
                                        Modifier.fillMaxSize().padding(4.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Text(theme.symbol, color = theme.foreground, fontSize = 27.sp, fontWeight = FontWeight.Black)
                                        Text("SKIP-BO", color = theme.foreground, fontSize = 7.sp, fontWeight = FontWeight.Black)
                                        Text("MASTERS", color = theme.foreground, fontSize = 6.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(theme.title, color = Color.White, fontWeight = FontWeight.Bold)
                                    Text(theme.description, color = CommunityMuted, fontSize = 10.sp)
                                    Text(
                                        if (unlocked) "Vrijgespeeld" else "Vrij vanaf niveau ${theme.requiredLevel} • nog ${maxOf(0, (theme.requiredLevel - 1) * 500 - profile.xp)} XP",
                                        color = if (unlocked) Color(0xFF9EE6B4) else CommunityMuted,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    if (active) {
                                        Text("ACTIEF IN JE SPEL", color = CommunityGold, fontSize = 9.sp, fontWeight = FontWeight.Black)
                                    } else if (unlocked) {
                                        OutlinedButton(
                                            enabled = !loading,
                                            onClick = {
                                                loading = true
                                                error = ""
                                                FirebaseCommunity.setActiveCardTheme(context, theme.id) { result ->
                                                    loading = false
                                                    result.onSuccess { selected ->
                                                        overview = overview?.copy(
                                                            profile = profile.copy(activeCardTheme = selected)
                                                        )
                                                        onCardThemeChanged(selected)
                                                        notice = "${theme.title} is nu je actieve kaartstijl."
                                                    }.onFailure {
                                                        error = it.localizedMessage ?: "Kaartstijl kon niet worden ingesteld."
                                                    }
                                                }
                                            }
                                        ) { Text("Gebruiken") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            8 -> {
                val season = seasonProgress
                Text("MAANDELIJKS SEIZOEN", color = CommunityMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Text("Speel online wedstrijden om deze maand een exclusieve seizoensbadge en extra XP te verdienen.", color = CommunityMuted, fontSize = 11.sp)
                OutlinedButton(
                    onClick = { loadSeasonProgress() },
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Seizoensvoortgang vernieuwen") }

                if (season == null) {
                    if (!loading) Text("Open dit tabblad om je seizoensvoortgang op te halen.", color = CommunityMuted, fontSize = 12.sp)
                } else {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = CommunityPanel),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(9.dp)
                        ) {
                            Text(season.title.uppercase(), color = CommunityGold, fontSize = 18.sp, fontWeight = FontWeight.Black)
                            Text("Seizoen ${season.seasonKey}", color = CommunityMuted, fontSize = 11.sp)
                            Text(
                                "${season.matchesPlayed} / ${season.target} online wedstrijden",
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            LinearProgressIndicator(
                                progress = (season.matchesPlayed.toFloat() / season.target.coerceAtLeast(1)).coerceIn(0f, 1f),
                                modifier = Modifier.fillMaxWidth(),
                                color = CommunityGold,
                                trackColor = Color(0xFF315A49)
                            )
                            Text("${season.wins} overwinningen deze maand", color = CommunityMuted, fontSize = 11.sp)
                            if (season.rewardClaimed && season.rewardBadgeId != null) {
                                Text("🏅 SEIZOENSBADGE VRIJGESPEELD", color = CommunityGold, fontWeight = FontWeight.Black, fontSize = 12.sp)
                                Text("Je hebt ook ${season.xpReward} bonus-XP verdiend.", color = Color(0xFF9EE6B4), fontSize = 11.sp)
                                if (overview?.profile?.activeBadgeId == season.rewardBadgeId) {
                                    Text("Seizoensbadge is actief op je profiel.", color = CommunityGold, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                } else {
                                    OutlinedButton(
                                        enabled = !loading,
                                        onClick = {
                                            loading = true
                                            FirebaseCommunity.setActiveBadge(context, season.rewardBadgeId) { result ->
                                                loading = false
                                                result.onSuccess { selected ->
                                                    overview = overview?.let { current ->
                                                        current.copy(profile = current.profile.copy(activeBadgeId = selected))
                                                    }
                                                    notice = "Je seizoensbadge staat nu op je profiel."
                                                }.onFailure {
                                                    error = it.localizedMessage ?: "Seizoensbadge kon niet worden uitgerust."
                                                }
                                            }
                                        }
                                    ) { Text("Seizoensbadge uitrusten") }
                                }
                            } else {
                                val remaining = (season.target - season.matchesPlayed).coerceAtLeast(0)
                                Text("Nog ${remaining} wedstrijden tot je beloning.", color = CommunityMuted, fontSize = 11.sp)
                                Text("Beloning: +${season.xpReward} XP en een exclusieve seizoensbadge.", color = CommunityGold, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Text("Maandreset: 00:00 UTC op de eerste dag van de volgende maand.", color = CommunityMuted, fontSize = 9.sp)
                        }
                    }
                    Text("MAANDELIJKSE RANGLIJST", color = CommunityMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                    Text(
                        season.myRank?.let { "Jouw positie: #$it" } ?: "Speel een online wedstrijd om in de ranglijst te verschijnen.",
                        color = CommunityGold,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp
                    )
                    if (season.leaderboard.isEmpty()) {
                        Text("Er zijn deze maand nog geen gerangschikte spelers.", color = CommunityMuted, fontSize = 11.sp)
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            season.leaderboard.take(10).forEachIndexed { index, entry ->
                                val isCurrentPlayer = FirebaseAuth.getInstance().currentUser?.uid == entry.uid
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isCurrentPlayer) Color(0xFF285543) else CommunityPanel
                                    ),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("#${entry.rank}", color = CommunityGold, fontWeight = FontWeight.Black, fontSize = 13.sp)
                                        Spacer(Modifier.width(10.dp))
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(
                                                entry.displayName + if (isCurrentPlayer) " (jij)" else "",
                                                color = Color.White,
                                                fontWeight = if (isCurrentPlayer) FontWeight.Bold else FontWeight.SemiBold,
                                                fontSize = 12.sp
                                            )
                                            Text("${entry.wins} zeges • ${entry.matchesPlayed} wedstrijden", color = CommunityMuted, fontSize = 10.sp)
                                        }
                                        if (!entry.activeBadgeId.isNullOrBlank()) {
                                            Text("✦", color = CommunityGold, fontSize = 15.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Text(
                        "Iedere door de server bevestigde online wedstrijd telt mee, ook een verlies. Lokale potjes tegen de computer tellen niet mee. De beloning wordt automatisch en slechts één keer per maand toegekend.",
                        color = CommunityMuted,
                        fontSize = 10.sp
                    )
                }
            }
            else -> {
                val daily = dailyProgress
                Text("DAGELIJKSE UITDAGINGEN", color = CommunityMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Text("Voltooi online missies, verdien XP en bouw je dagelijkse inlogreeks op.", color = CommunityMuted, fontSize = 11.sp)

                OutlinedButton(
                    onClick = { loadDailyProgress() },
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Voortgang vernieuwen") }

                if (daily == null) {
                    if (!loading) Text("Open dit tabblad om je dagelijkse voortgang op te halen.", color = CommunityMuted, fontSize = 12.sp)
                } else {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = CommunityPanel),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("SPEELNIVEAU ${daily.level}", color = CommunityGold, fontWeight = FontWeight.Black, fontSize = 18.sp)
                            Text(
                                "${daily.xpIntoLevel} / ${daily.xpForNextLevel} XP naar niveau ${daily.level + 1}",
                                color = CommunityMuted,
                                fontSize = 11.sp
                            )
                            Text("Inlogreeks: ${daily.streak} dagen", color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text("Dagreset: 00:00 UTC", color = CommunityMuted, fontSize = 9.sp)
                            Button(
                                onClick = { claimDailyRewardNow() },
                                enabled = !loading && daily.canClaim,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (daily.canClaim) "Dagbeloning ophalen • +${daily.xpReward} XP" else "Dagbeloning vandaag al opgehaald")
                            }
                            if (daily.unlockedCosmetics.contains("emerald_frame")) {
                                Text("✦ Beloning vrijgespeeld: smaragdgroene profielrand", color = Color(0xFF9EE6B4), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            } else {
                                Text("Haal 7 dagen achter elkaar je dagbeloning op voor een smaragdgroene profielrand.", color = CommunityMuted, fontSize = 10.sp)
                            }
                        }
                    }

                    Text("MISSIES VAN VANDAAG", color = CommunityMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
                    daily.missions.forEach { mission ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = if (mission.completed) Color(0xFF1B4837) else CommunityPanel
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(13.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(if (mission.completed) "✓" else "◌", color = if (mission.completed) Color(0xFF9EE6B4) else CommunityGold, fontSize = 22.sp, fontWeight = FontWeight.Black)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(mission.title, color = Color.White, fontWeight = FontWeight.Bold)
                                    Text(mission.description, color = CommunityMuted, fontSize = 10.sp)
                                    Text(
                                        "${mission.progress} / ${mission.target} • +${mission.xpReward} XP",
                                        color = CommunityGold,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                if (mission.completed) {
                                    Text("VOLTOOID", color = Color(0xFF9EE6B4), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                    Text(
                        "Missie-XP wordt automatisch toegekend na een serverbevestigde online wedstrijd. Lokale potjes tegen de computer tellen niet mee.",
                        color = CommunityMuted,
                        fontSize = 10.sp
                    )
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

private fun tournamentStatusLabel(status: String): String = when (status) {
    "OPEN" -> "Open • wacht op spelers"
    "RUNNING" -> "Bezig"
    "COMPLETED" -> "Afgerond"
    else -> status
}

private fun tournamentRoundLabel(round: Int, capacity: Int): String = when {
    round == 1 && capacity == 8 -> "Kwartfinale"
    round == 1 -> "Halve finale"
    round == 2 && capacity == 8 -> "Halve finale"
    else -> "Finale"
}

private fun tournamentMatchStatusLabel(status: String): String = when (status) {
    "WAITING_FOR_PLAYERS" -> "Wacht op winnaars uit vorige ronde"
    "WAITING" -> "Klaar om te spelen"
    "PLAYING" -> "Wedstrijd bezig"
    "COMPLETED" -> "Afgerond"
    else -> status
}



private fun badgeName(id: String): String {
    if (id.startsWith("season_badge_")) return "Seizoenskampioen ${id.removePrefix("season_badge_")}"
    return when (id) {
    "first_win" -> "Eerste overwinning"
    "ten_wins" -> "Tien overwinningen"
    "five_win_streak" -> "Vijf op rij"
    "fifty_matches" -> "Vaste speler"
    "hundred_matches" -> "Skip-Bo legende"
    "master_rating" -> "Meester van Skip-Bo"
    else -> id.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}
