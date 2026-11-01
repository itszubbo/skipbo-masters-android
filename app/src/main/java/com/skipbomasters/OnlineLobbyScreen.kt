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
import androidx.compose.material3.Button
import androidx.compose.material3.Card as MaterialCard
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.firestore.ListenerRegistration

@Composable
fun OnlineLobbyScreen(
    onBack: () -> Unit,
    initialRoomCode: String? = null,
    onInitialRoomHandled: () -> Unit = {}
) {
    val context = LocalContext.current
    var savedRoomSession by remember { mutableStateOf(FirebaseRooms.lastRoomSession(context)) }
    var playerName by remember { mutableStateOf(savedRoomSession?.playerName ?: "") }
    var inviteCode by remember { mutableStateOf("") }
    var activeCode by remember { mutableStateOf("") }
    var room by remember { mutableStateOf<OnlineRoom?>(null) }
    var myUid by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var savingState by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var syncError by remember { mutableStateOf("") }
    var stateLoaded by remember { mutableStateOf(false) }
    var privateStateLoaded by remember { mutableStateOf(false) }
    var publicRevision by remember { mutableLongStateOf(0L) }
    var privateRevision by remember { mutableLongStateOf(0L) }
    var publicPayload by remember { mutableStateOf<String?>(null) }
    var privateHandValues by remember { mutableStateOf<List<Int>>(emptyList()) }
    var showMatch by remember { mutableStateOf(false) }
    var rebuildToken by remember { mutableLongStateOf(0L) }

    val latestPublicRevision by rememberUpdatedState(publicRevision)
    val localPlayerIndex = room?.let { current ->
        when (myUid) {
            current.hostUid -> 0
            current.guestUid -> 1
            else -> null
        }
    }

    DisposableEffect(activeCode) {
        var registration: ListenerRegistration? = null
        if (activeCode.isNotBlank()) {
            registration = FirebaseRooms.listenRoom(context, activeCode) { result ->
                result.onSuccess {
                    room = it
                    myUid = FirebaseRooms.currentUid(context)
                    error = ""
                }.onFailure { error = it.localizedMessage ?: "De kamer kon niet worden bijgewerkt." }
            }
        }
        onDispose { registration?.remove() }
    }

    DisposableEffect(activeCode, room?.isReady) {
        var stateRegistration: ListenerRegistration? = null
        var privateRegistration: ListenerRegistration? = null
        if (activeCode.isNotBlank() && room?.isReady == true) {
            stateRegistration = FirebaseRooms.listenGameState(context, activeCode) { result ->
                result.onSuccess { state ->
                    stateLoaded = true
                    if (state != null && state.first >= latestPublicRevision) {
                        publicRevision = state.first
                        publicPayload = state.second
                        showMatch = true
                    }
                    if (state == null && publicPayload == null) notice = "Kamer is klaar. De host kan het spel starten."
                }.onFailure {
                    syncError = it.localizedMessage ?: "De online spelstatus kon niet worden geladen."
                    stateLoaded = true
                }
            }
            privateRegistration = FirebaseRooms.listenPrivatePlayer(context, activeCode) { result ->
                result.onSuccess { privateState ->
                    privateStateLoaded = privateState != null
                    if (privateState != null && privateState.revision >= privateRevision) {
                        privateRevision = privateState.revision
                        privateHandValues = privateState.hand
                    }
                }.onFailure {
                    syncError = it.localizedMessage ?: "Je privékaarten konden niet worden geladen."
                }
            }
        }
        onDispose {
            stateRegistration?.remove()
            privateRegistration?.remove()
        }
    }

    fun acceptCommit(result: Result<OnlineCommit>, openMatch: Boolean = false) {
        savingState = false
        result.onSuccess { commit ->
            publicRevision = commit.revision
            publicPayload = commit.payload
            privateRevision = commit.revision
            privateHandValues = commit.hand
            stateLoaded = true
            privateStateLoaded = true
            syncError = ""
            if (openMatch) showMatch = true
        }.onFailure {
            syncError = it.localizedMessage ?: "De spelactie kon niet worden opgeslagen."
            rebuildToken++
        }
    }

    fun refreshMatch(code: String) {
        if (savingState) return
        savingState = true
        syncError = ""
        FirebaseRooms.resumeOnlineMatch(context, code) { result ->
            acceptCommit(result, openMatch = true)
        }
    }

    fun submitPlay(source: String, index: Int) {
        val code = activeCode
        if (savingState || code.isBlank() || localPlayerIndex == null) return
        savingState = true
        syncError = ""
        FirebaseRooms.submitOnlinePlay(context, code, publicRevision, source, index) { acceptCommit(it) }
    }

    fun submitDiscard(handIndex: Int, pileIndex: Int) {
        val code = activeCode
        if (savingState || code.isBlank() || localPlayerIndex == null) return
        savingState = true
        syncError = ""
        FirebaseRooms.submitOnlineDiscard(context, code, publicRevision, handIndex, pileIndex) { acceptCommit(it) }
    }

    fun startMatch(code: String) {
        if (savingState) return
        savingState = true
        syncError = ""
        FirebaseRooms.startOnlineMatch(context, code) { acceptCommit(it, openMatch = true) }
    }

    val parsedGame = remember(publicPayload, privateHandValues, localPlayerIndex, rebuildToken) {
        val payload = publicPayload
        val seat = localPlayerIndex
        if (payload == null || seat == null) null
        else runCatching {
            Game.fromPublicSnapshot(payload).also { it.setPrivateHand(seat, privateHandValues) }
        }.getOrNull()
    }

    fun handleResult(result: Result<OnlineRoom>) {
        busy = false
        result.onSuccess {
            room = it
            activeCode = it.code
            inviteCode = it.code
            FirebaseRooms.saveLastRoomSession(context, it.code, playerName)
            savedRoomSession = SavedRoomSession(it.code, playerName.trim().take(24))
            myUid = FirebaseRooms.currentUid(context)
            error = ""
            notice = if (it.isReady) "Beide spelers zijn verbonden met de kamer." else "Kamer aangemaakt. Deel de code met je tegenstander."
            showMatch = false
            stateLoaded = false
            privateStateLoaded = false
            publicRevision = 0L
            privateRevision = 0L
            publicPayload = null
            privateHandValues = emptyList()
            syncError = ""
            if (it.status == "PLAYING" || it.status == "FINISHED") {
                refreshMatch(it.code)
            }
        }.onFailure { error = it.localizedMessage ?: "Er ging iets mis bij het openen van de kamer." }
    }

    LaunchedEffect(initialRoomCode) {
        val code = initialRoomCode?.trim()?.uppercase().orEmpty()
        if (code.length == 6 && activeCode.isBlank() && !busy) {
            if (playerName.isBlank()) playerName = savedRoomSession?.playerName ?: "Skip-Bo speler"
            busy = true
            error = ""
            FirebaseRooms.joinRoom(context, playerName, code) { result ->
                handleResult(result)
                onInitialRoomHandled()
            }
        }
    }

    if (showMatch && parsedGame != null && room != null && localPlayerIndex != null && privateStateLoaded) {
        GameScreen(
            g = parsedGame,
            onChanged = {},
            onHome = { showMatch = false },
            canPlayTurn = parsedGame.current == localPlayerIndex && privateRevision == publicRevision && !savingState && !parsedGame.finished,
            localPlayerIndex = localPlayerIndex,
            syncMessage = when {
                savingState -> "Server controleert de zet…"
                syncError.isNotBlank() -> syncError
                else -> null
            },
            onOnlinePlay = { source, index -> submitPlay(source, index) },
            onOnlineDiscard = { handIndex, pileIndex -> submitDiscard(handIndex, pileIndex) }
        )
        return
    }

    Column(
        Modifier.fillMaxSize().background(Color(0xFF071D19)).verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Terug", color = Color(0xFFFFD34E)) }
            Spacer(Modifier.width(8.dp))
            Text("ONLINE SPELEN", color = Color.White, fontWeight = FontWeight.Black, fontSize = 20.sp)
        }
        Text("Maak een privékamer of doe mee met een uitnodigingscode.", color = Color(0xFFB8CFC3), textAlign = TextAlign.Center)

        if (!FirebaseRooms.isConfigured(context)) {
            MaterialCard(colors = CardDefaults.cardColors(containerColor = Color(0xFF4A3420)), shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Firebase-configuratie ontbreekt", color = Color(0xFFFFE3AC), fontWeight = FontWeight.Bold)
                    Text("Voeg app/google-services.json toe en activeer Anoniem aanmelden, Cloud Firestore en Cloud Functions.", color = Color.White, fontSize = 12.sp)
                }
            }
        }

        val savedSessionForDisplay = savedRoomSession
        if (activeCode.isBlank() && room == null && savedSessionForDisplay != null) {
            MaterialCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF15382F)),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("VORIGE KAMER HERVATTEN", color = Color(0xFFFFD34E), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    Text("Code ${savedSessionForDisplay.code} • ${savedSessionForDisplay.playerName}", color = Color.White)
                    Text("Je kunt na een app-herstart opnieuw verbinden met dezelfde kamer zolang je Firebase-spelersessie nog geldig is.",
                        color = Color(0xFFB8CFC3), fontSize = 11.sp)
                    Button(
                        enabled = !busy && FirebaseRooms.isConfigured(context),
                        onClick = {
                            playerName = savedSessionForDisplay.playerName
                            inviteCode = savedSessionForDisplay.code
                            busy = true
                            error = ""
                            FirebaseRooms.joinRoom(context, savedSessionForDisplay.playerName, savedSessionForDisplay.code) { handleResult(it) }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Opnieuw verbinden") }
                    TextButton(onClick = {
                        FirebaseRooms.clearLastRoomSession(context)
                        savedRoomSession = null
                    }) {
                        Text("Vergeten op dit toestel", color = Color.White)
                    }
                }
            }
        }

        OutlinedTextField(value = playerName, onValueChange = { playerName = it.take(24) }, label = { Text("Jouw spelersnaam") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(
            onClick = { busy = true; error = ""; notice = ""; FirebaseRooms.createRoom(context, playerName) { handleResult(it) } },
            enabled = !busy && playerName.isNotBlank() && FirebaseRooms.isConfigured(context), modifier = Modifier.fillMaxWidth()
        ) { Text("Nieuwe privékamer maken") }
        Text("OF", color = Color(0xFF8EAA9C), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = inviteCode,
            onValueChange = { inviteCode = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(6) },
            label = { Text("Uitnodigingscode (6 tekens)") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = { busy = true; error = ""; notice = ""; FirebaseRooms.joinRoom(context, playerName, inviteCode) { handleResult(it) } },
            enabled = !busy && playerName.isNotBlank() && inviteCode.length == 6 && FirebaseRooms.isConfigured(context), modifier = Modifier.fillMaxWidth()
        ) { Text("Deelnemen aan kamer") }

        if (busy || savingState) CircularProgressIndicator(color = Color(0xFFFFD34E))
        if (error.isNotBlank()) Text(error, color = Color(0xFFFFA8A8), fontSize = 12.sp)
        if (syncError.isNotBlank()) Text(syncError, color = Color(0xFFFFA8A8), fontSize = 12.sp)
        if (notice.isNotBlank()) Text(notice, color = Color(0xFFFFE3AC), fontSize = 12.sp, textAlign = TextAlign.Center)

        room?.let { current ->
            MaterialCard(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF15382F)), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("JOUW KAMER", color = Color(0xFFB8CFC3), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Text(current.code, color = Color(0xFFFFD34E), fontSize = 34.sp, fontWeight = FontWeight.Black, letterSpacing = 5.sp)
                    Text("Speler 1: ${current.hostName}", color = Color.White)
                    Text("Speler 2: ${current.guestName ?: "Wacht op tegenstander…"}", color = if (current.isReady) Color(0xFF9DE3B2) else Color(0xFFFFE3AC))
                    Text(when (current.status) {
                        "PLAYING" -> "Spel bezig • serververbinding actief"
                        "FINISHED" -> "Spel afgelopen"
                        else -> if (current.isReady) "Kamer gevuld • klaar om te starten" else "Deel de code om iemand uit te nodigen"
                    }, color = Color(0xFFB8CFC3), fontSize = 11.sp, textAlign = TextAlign.Center)
                    Button(onClick = {
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, "Speel Skip-Bo Masters met mij! Gebruik kamercode ${current.code}.")
                        }
                        context.startActivity(Intent.createChooser(share, "Nodig een speler uit"))
                    }, modifier = Modifier.fillMaxWidth()) { Text("Uitnodiging delen") }

                    if (current.status == "PLAYING" || current.status == "FINISHED") {
                        Button(
                            onClick = { refreshMatch(current.code) },
                            enabled = !savingState,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (parsedGame == null) "Spelstatus opnieuw ophalen" else "Spelstatus synchroniseren") }
                    }
                    if (parsedGame != null) {
                        Button(onClick = { showMatch = true }, modifier = Modifier.fillMaxWidth()) { Text("Terug naar online spel") }
                    }
                    if (current.status == "READY" && localPlayerIndex == 0 && stateLoaded && parsedGame == null) {
                        Button(enabled = !savingState, onClick = { startMatch(current.code) }, modifier = Modifier.fillMaxWidth()) { Text("Online spel starten") }
                    } else if (current.status == "READY" && parsedGame == null) {
                        Text("Wachten tot de host het spel start…", color = Color(0xFFFFE3AC), fontSize = 12.sp)
                    }
                    TextButton(onClick = {
                        activeCode = ""; room = null; showMatch = false
                        notice = "Je hebt de kamerweergave verlaten. De online kamer blijft bestaan."
                    }) { Text("Kamerweergave verlaten", color = Color.White) }
                }
            }
        }

        Text("Kaartverdeling, spelregels en beurten worden door Cloud Functions op de server beheerd. Je privévoorraad en hand zijn alleen voor jouw account leesbaar.", color = Color(0xFF829D90), fontSize = 10.sp, textAlign = TextAlign.Center)
        Text("Voor online tests moeten Firebase-configuratie en de meegeleverde Firestore-regels en Cloud Functions zijn uitgerold.", color = Color(0xFF829D90), fontSize = 10.sp, textAlign = TextAlign.Center)
    }
}
