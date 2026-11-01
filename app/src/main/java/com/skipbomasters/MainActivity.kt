package com.skipbomasters

import android.os.Bundle
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.shadow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

internal data class Card(val value: Int) { val joker get() = value == 0 }
internal data class Player(
    var name: String,
    val stock: MutableList<Card>,
    val hand: MutableList<Card> = mutableListOf(),
    val discards: List<MutableList<Card>> = List(4) { mutableListOf() },
    var publicStockCount: Int? = null,
    var publicStockTop: Card? = null
) {
    val stockCount: Int get() = publicStockCount ?: stock.size
    val stockTop: Card? get() = publicStockTop ?: stock.lastOrNull()
}
internal class Game(val vsComputer: Boolean) {
    val draw = mutableListOf<Card>()
    // Completed building piles are set aside until the draw pile is empty.
    val completed = mutableListOf<Card>()
    val builds = List(4) { mutableListOf<Card>() }
    val players: List<Player>
    var current = 0
    var selectedHand = -1
    var selectedDiscard = -1
    var message = "Jouw beurt. Speel een kaart of leg af."
    var finished = false
    var winner = ""
    var needsTurnDraw = false
    var publicDrawCount: Int? = null

    init {
        val deck = buildList {
            repeat(12) { n -> repeat(12) { add(Card(n + 1)) } }
            repeat(18) { add(Card(0)) }
        }.shuffled().toMutableList()
        val count = 30
        val p1 = Player("Jij", MutableList(count) { deck.removeAt(0) })
        val p2 = Player(if (vsComputer) "Computer" else "Speler 2", MutableList(count) { deck.removeAt(0) })
        players = listOf(p1, p2)
        draw.addAll(deck)
        refillHand(p1)
    }
    fun player() = players[current]

    /** Public snapshot deliberately excludes the draw deck and both private stock piles. */
    fun toPublicSnapshot(): String {
        val root = JSONObject()
        fun cardsJson(cards: List<Card>) = JSONArray().apply { cards.forEach { put(it.value) } }
        root.put("drawCount", publicDrawCount ?: draw.size)
        root.put("builds", JSONArray().apply { builds.forEach { put(cardsJson(it)) } })
        root.put("players", JSONArray().apply {
            players.forEach { p ->
                put(JSONObject().apply {
                    put("name", p.name)
                    put("stockCount", p.stockCount)
                    put("stockTop", p.stockTop?.value ?: JSONObject.NULL)
                    put("discards", JSONArray().apply { p.discards.forEach { put(cardsJson(it)) } })
                })
            }
        })
        root.put("current", current)
        root.put("message", message)
        root.put("finished", finished)
        root.put("winner", winner)
        root.put("needsTurnDraw", false)
        return root.toString()
    }

    fun privateHandJson(playerIndex: Int): String = JSONArray().apply {
        players[playerIndex].hand.forEach { put(it.value) }
    }.toString()

    fun privateHandValues(playerIndex: Int): List<Int> = players[playerIndex].hand.map { it.value }

    fun setPrivateHand(playerIndex: Int, handValues: List<Int>) {
        require(playerIndex in players.indices) { "Ongeldige spelerindex." }
        require(handValues.size <= 5) { "Een hand mag maximaal vijf kaarten bevatten." }
        require(handValues.all { it in 0..12 }) { "Ongeldige kaartwaarde in privéhand." }
        val hand = players[playerIndex].hand
        hand.clear(); handValues.forEach { hand.add(Card(it)) }
    }

    companion object {
        fun fromPublicSnapshot(payload: String): Game {
            val root = JSONObject(payload)
            val game = Game(false)
            fun readCards(array: JSONArray): List<Card> = buildList {
                for (i in 0 until array.length()) {
                    val value = array.getInt(i)
                    require(value in 0..12) { "Ongeldige kaartwaarde in online spel." }
                    add(Card(value))
                }
            }
            game.draw.clear()
            game.publicDrawCount = root.optInt("drawCount", 0)
            val buildArray = root.getJSONArray("builds")
            require(buildArray.length() == 4) { "Ongeldige bouwstapels in online spel." }
            game.builds.forEachIndexed { index, pile ->
                pile.clear(); pile.addAll(readCards(buildArray.getJSONArray(index)))
                require(pile.size <= 12) { "Een centrale stapel kan maximaal twaalf kaarten bevatten." }
            }
            val playerArray = root.getJSONArray("players")
            require(playerArray.length() == 2) { "Online Skip-Bo vereist twee spelers." }
            game.players.forEachIndexed { index, player ->
                val data = playerArray.getJSONObject(index)
                player.name = data.optString("name", if (index == 0) "Speler 1" else "Speler 2")
                player.stock.clear(); player.hand.clear()
                player.publicStockCount = data.optInt("stockCount", 0).also { require(it in 0..30) }
                player.publicStockTop = if (data.isNull("stockTop")) null else Card(data.getInt("stockTop").also { require(it in 0..12) })
                val discardArray = data.getJSONArray("discards")
                require(discardArray.length() == 4) { "Ongeldige aflegstapels in online spel." }
                player.discards.forEachIndexed { pileIndex, pile ->
                    pile.clear(); pile.addAll(readCards(discardArray.getJSONArray(pileIndex)))
                }
            }
            game.current = root.getInt("current").also { require(it in 0..1) }
            game.message = root.optString("message", "Online beurt")
            game.finished = root.optBoolean("finished", false)
            game.winner = root.optString("winner", "")
            game.needsTurnDraw = false
            game.selectedHand = -1; game.selectedDiscard = -1
            return game
        }
    }

    fun top(pile: List<Card>) = pile.lastOrNull()
    fun topStock(p: Player) = top(p.stock)
    fun topDiscard(p: Player, i: Int) = top(p.discards[i])
    fun refillHand(p: Player) {
        while (p.hand.size < 5) {
            if (draw.isEmpty() && completed.isNotEmpty()) {
                draw.addAll(completed.shuffled())
                completed.clear()
            }
            if (draw.isEmpty()) break
            p.hand.add(draw.removeAt(draw.lastIndex))
        }
    }
    fun nextTurn(refillNextPlayer: Boolean = true) {
        selectedHand = -1; selectedDiscard = -1
        current = (current + 1) % 2
        if (refillNextPlayer) {
            refillHand(player())
            needsTurnDraw = false
        } else {
            needsTurnDraw = true
        }
        message = if (refillNextPlayer) {
            "${player().name} is aan de beurt. De hand is aangevuld tot vijf kaarten."
        } else {
            "${player().name} is aan de beurt. De hand wordt op diens eigen toestel aangevuld."
        }
    }
    fun canPlay(card: Card, pile: List<Card>): Boolean {
        val needed = (pile.size + 1)
        return if (pile.isEmpty()) card.joker || card.value == 1
        else card.joker || card.value == needed
    }
    fun play(card: Card, source: String, index: Int = -1): Boolean {
        val pileIndex = builds.indexOfFirst { it.size < 12 && canPlay(card, it) }
        if (pileIndex < 0) { message = "Die kaart past nu niet op een bouwstapel."; return false }
        when (source) {
            "stock" -> player().stock.removeAt(player().stock.lastIndex)
            "hand" -> player().hand.removeAt(index)
            "discard" -> player().discards[index].removeAt(player().discards[index].lastIndex)
        }
        // Preserve the physical card; a Skip-Bo wild remains a joker (0) after recycling.
        builds[pileIndex].add(card)
        if (builds[pileIndex].size == 12) {
            completed.addAll(builds[pileIndex])
            builds[pileIndex].clear()
            message = "Bouwstapel compleet! De kaarten worden apart gelegd tot de trekstapel op is."
        } else message = "Kaart gespeeld op bouwstapel ${pileIndex + 1}."
        if (player().stock.isEmpty()) { finished = true; winner = player().name; message = "$winner wint! De voorraadstapel is leeg."; return true }
        // If a player empties their hand while playing, they immediately draw five
        // and may continue the same turn.
        if (source == "hand" && player().hand.isEmpty()) {
            refillHand(player())
            message = "Je hand is leeggespeeld en direct aangevuld. Speel verder!"
        }
        if (current == 1 && vsComputer) { /* handled by computer loop */ }
        return true
    }
    fun discard(handIndex: Int, pileIndex: Int, refillNextPlayer: Boolean = true) {
        val c = player().hand.removeAt(handIndex)
        player().discards[pileIndex].add(c)
        message = "${player().name} heeft ${label(c)} op aflegstapel ${pileIndex + 1} gelegd."
        nextTurn(refillNextPlayer)
    }
    fun label(c: Card) = if (c.joker) "Skip-Bo" else c.value.toString()
    fun computerTurn() {
        val p = players[1]
        refillHand(p)
        var safety = 0
        while (!finished && safety++ < 100) {
            var moved = false
            val stock = topStock(p)
            if (stock != null && play(stock, "stock")) { moved = true; continue }
            for (i in p.hand.indices.toList()) {
                if (play(p.hand[i], "hand", i)) { moved = true; break }
            }
            if (moved) continue
            for (d in 0..3) {
                val c = topDiscard(p, d)
                if (c != null && play(c, "discard", d)) { moved = true; break }
            }
            if (!moved) break
        }
        if (!finished) {
            if (p.hand.isNotEmpty()) {
                val idx = p.hand.indices.maxByOrNull { p.hand[it].value } ?: 0
                p.discards.first().add(p.hand.removeAt(idx))
            }
            nextTurn()
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SkipBoApp() }
    }
}

@Composable
private fun SkipBoApp() {
    var game by remember { mutableStateOf<Game?>(null) }
    var mode by remember { mutableStateOf(true) }
    var showOnline by remember { mutableStateOf(false) }
    var showCommunity by remember { mutableStateOf(false) }
    var pendingRoomCode by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    val g = game
    MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFFB51F2A), secondary = Color(0xFF174C35))) {
        Surface(
            Modifier.fillMaxSize(),
            color = Color(0xFF071D19)
        ) {
            if (g == null && showCommunity) {
                CommunityScreen(
                    onBack = { showCommunity = false },
                    onOpenRoom = { code ->
                        pendingRoomCode = code
                        showCommunity = false
                        showOnline = true
                    }
                )
            } else if (g == null && showOnline) {
                OnlineLobbyScreen(
                    onBack = { showOnline = false; pendingRoomCode = null },
                    initialRoomCode = pendingRoomCode,
                    onInitialRoomHandled = { pendingRoomCode = null }
                )
            } else if (g == null) {
                Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("SKIP-BO", fontSize = 42.sp, fontWeight = FontWeight.Black, color = Color.White)
                    Text("MASTERS", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFD34E))
                    Spacer(Modifier.height(28.dp))
                    Text("Kies je spelmodus", color = Color.White, fontSize = 20.sp)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { mode = true; game = Game(true) }, modifier = Modifier.fillMaxWidth()) { Text("Tegen de computer") }
                    Button(onClick = { mode = false; game = Game(false) }, modifier = Modifier.fillMaxWidth()) { Text("Twee spelers op één telefoon") }
                    OutlinedButton(onClick = { showOnline = true }, modifier = Modifier.fillMaxWidth()) { Text("Online spelen", color = Color(0xFFFFD34E)) }
                    OutlinedButton(onClick = { showCommunity = true }, modifier = Modifier.fillMaxWidth()) { Text("Vrienden & ranglijst", color = Color(0xFFFFD34E)) }
                    Spacer(Modifier.height(18.dp))
                    Text("Basisregels • 1 t/m 12 • Skip-Bo-jokers", color = Color.White)
                    Spacer(Modifier.height(8.dp))
                    Text("Online spelen, vrienden en ranglijst • Firebase-project vereist.", color = Color(0xFFB8CFC3), fontSize = 11.sp)
                }
            } else {
                GameScreen(g, onChanged = { revision++ }, onHome = { game = null }, gameRevision = revision)
            }
        }
    }
}

@Composable
internal fun GameScreen(
    g: Game,
    onChanged: () -> Unit,
    onHome: () -> Unit,
    canPlayTurn: Boolean = (!g.vsComputer || g.current == 0),
    localPlayerIndex: Int? = null,
    syncMessage: String? = null,
    onOnlinePlay: ((String, Int) -> Unit)? = null,
    onOnlineDiscard: ((Int, Int) -> Unit)? = null,
    gameRevision: Int = 0
) {
    // Keep the mutable game engine observable without disposing this composition.
    gameRevision.hashCode()
    val displayPlayer = localPlayerIndex?.let { g.players[it] } ?: g.player()
    var help by remember { mutableStateOf(false) }
    var soundEnabled by remember { mutableStateOf(false) }
    val toneGenerator = remember { ToneGenerator(AudioManager.STREAM_MUSIC, 55) }
    DisposableEffect(toneGenerator) { onDispose { toneGenerator.release() } }
    fun playUiSound(durationMs: Int = 85) {
        if (soundEnabled) toneGenerator.startTone(ToneGenerator.TONE_PROP_ACK, durationMs)
    }
    LaunchedEffect(g.finished) {
        if (g.finished) playUiSound(220)
    }
    LaunchedEffect(g.current, g.message) {
        if (g.current == 1 && !g.finished && g.vsComputer) {
            delay(650)
            g.computerTurn()
            onChanged()
        }
    }
    Column(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0A2923), Color(0xFF061713), Color(0xFF102F28))))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("SKIP-BO", color = Color(0xFFFFE6A0), fontWeight = FontWeight.Black, fontSize = 23.sp, letterSpacing = 2.sp)
                Text("MASTERS  •  CARD TABLE", color = Color(0xFF9AB8AA), fontWeight = FontWeight.Bold, fontSize = 9.sp, letterSpacing = 2.sp)
            }
            TextButton(onClick = { soundEnabled = !soundEnabled; if (soundEnabled) playUiSound(65) }) {
                Text(if (soundEnabled) "Geluid aan" else "Geluid uit", color = if (soundEnabled) Color(0xFFFFD34E) else Color.White)
            }
            TextButton(onClick = { help = true }) { Text("Regels", color = Color.White) }
            TextButton(onClick = onHome) { Text(if (localPlayerIndex != null) "Lobby" else "Menu", color = Color.White) }
        }
        Surface(color = Color(0xFF173B31), shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Color(0xFF315A49))) {
            Text(g.message, Modifier.fillMaxWidth().padding(12.dp), color = Color(0xFFFFE9B2), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            InfoBox(g.players[0].name, "Voorraad: ${g.players[0].stockCount}", g.current == 0)
            InfoBox(g.players[1].name, "Voorraad: ${g.players[1].stockCount}", g.current == 1)
        }
        Text("CENTRALE BOUWSTAPELS", color = Color(0xFFC7D8CE), fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.5.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            g.builds.forEachIndexed { i, pile ->
                PileCard(if (pile.isEmpty()) "1" else "${pile.size + 1}", pile.lastOrNull()?.let { g.label(it) } ?: "Start", Color(0xFF174C35), Modifier.weight(1f))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("TREKSTAPEL", color = Color.White, fontSize = 10.sp)
                PileCard("🂠", "${g.publicDrawCount ?: g.draw.size} kaarten", Color(0xFFB51F2A), Modifier.width(76.dp))
                Text("Trek tot 5", color = Color.White, fontSize = 10.sp)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("VOORRAAD • ${g.player().name.uppercase()}", color = Color.White, fontSize = 10.sp)
                val stock = g.player().stockTop
                PileCard(stock?.let { g.label(it) } ?: "LEEG", "${g.player().stockCount} over", Color(0xFFFFD34E), Modifier.width(76.dp), Color.Black) {
                    if (canPlayTurn && stock != null) {
                        if (onOnlinePlay != null) { onOnlinePlay("stock", -1); playUiSound() }
                        else { if (g.play(stock, "stock")) playUiSound(); onChanged() }
                    }
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("BEURT", color = Color.White, fontSize = 10.sp)
                Text(g.player().name, color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
        Text("HAND • ${displayPlayer.name.uppercase()}  •  SPEEL EEN KAART", color = Color(0xFFC7D8CE), fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.sp)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(displayPlayer.hand) { i, c ->
                PlayingCard(c, selected = false, modifier = Modifier.width(55.dp).height(78.dp)) {
                    if (canPlayTurn && !g.finished) {
                        if (onOnlinePlay != null) { onOnlinePlay("hand", i); playUiSound() }
                        else { if (g.play(c, "hand", i)) playUiSound(); onChanged() }
                    }
                }
            }
        }
        Text("PERSOONLIJKE AFLEGSTAPELS", color = Color(0xFFC7D8CE), fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            displayPlayer.discards.forEachIndexed { i, pile ->
                val c = pile.lastOrNull()
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    PileCard(c?.let { g.label(it) } ?: "—", "Afleg ${i+1}", Color(0xFF174C35), Modifier.width(68.dp)) {
                        if (canPlayTurn && c != null && !g.finished) {
                            if (onOnlinePlay != null) { onOnlinePlay("discard", i); playUiSound() }
                            else { if (g.play(c, "discard", i)) playUiSound(); onChanged() }
                        }
                    }
                }
            }
        }
        if (canPlayTurn && !g.finished) {
            Text("${g.player().name}: beëindig je beurt door een handkaart op een aflegstapel te leggen.", color = Color.White, fontSize = 11.sp)
            var discardChoice by remember(g.current, g.message) { mutableIntStateOf(-1) }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
                displayPlayer.hand.forEachIndexed { i, c ->
                    OutlinedButton(
                        onClick = { discardChoice = i },
                        border = BorderStroke(1.dp, if (discardChoice == i) Color(0xFFFFD34E) else Color(0xFF8CA99B)),
                        contentPadding = PaddingValues(horizontal = 7.dp, vertical = 4.dp)
                    ) { Text("${if (discardChoice == i) "✓ " else ""}${g.label(c)}", fontSize = 10.sp, color = Color.White) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
                (0..3).forEach { pile ->
                    OutlinedButton(
                        enabled = discardChoice >= 0,
                        onClick = {
                            if (onOnlineDiscard != null) onOnlineDiscard(discardChoice, pile)
                            else { g.discard(discardChoice, pile, refillNextPlayer = localPlayerIndex == null); playUiSound(); onChanged() }
                            discardChoice = -1
                        },
                        contentPadding = PaddingValues(horizontal = 7.dp, vertical = 4.dp),
                        modifier = Modifier.weight(1f)
                    ) { Text("Afleg ${pile + 1}", fontSize = 10.sp) }
                }
            }
        }
        if (localPlayerIndex != null && !canPlayTurn && !g.finished) {
            Text("Wachten op ${g.player().name}…", color = Color(0xFFFFE9B2), fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        if (!syncMessage.isNullOrBlank()) {
            Text(syncMessage, color = Color(0xFFFFB6A8), fontSize = 10.sp)
        }
        AnimatedVisibility(
            visible = g.finished,
            enter = fadeIn(tween(320)) + scaleIn(initialScale = 0.78f, animationSpec = spring(dampingRatio = 0.62f)),
            exit = fadeOut(tween(140)) + scaleOut(targetScale = 0.92f)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF3B3218),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.5.dp, Color(0xFFFFD34E)),
                shadowElevation = 12.dp
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Text("🏆  OVERWINNING!  🏆", color = Color(0xFFFFD34E), fontSize = 20.sp, fontWeight = FontWeight.Black)
                    Text("${g.winner} heeft gewonnen!", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
                    Text("De voorraadstapel is leeg. Knap gespeeld!", color = Color(0xFFFFE9B2), fontSize = 12.sp)
                    Button(onClick = onHome) { Text("Nieuw spel") }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            if (localPlayerIndex != null) "ONLINE • Je hand blijft privé; beurten en bouwstapels worden gesynchroniseerd."
            else if (g.vsComputer) "Tip: gebruik eerst je voorraadstapel. De eerste speler die deze leegmaakt, wint."
            else "LOKAAL SPELEN: geef de telefoon door na je beurt. Dit is nog geen online multiplayer.",
            color = Color.White.copy(alpha = .8f), fontSize = 10.sp
        )
    }
    if (help) AlertDialog(onDismissRequest = { help = false }, confirmButton = { TextButton(onClick = { help = false }) { Text("Begrepen") } },
        title = { Text("Skip-Bo — basisregels") }, text = { Text("Iedere speler heeft een voorraadstapel. Maak die als eerste leeg. Bouw in het midden vier stapels op volgorde van 1 tot en met 12. Skip-Bo is een joker. Je hebt maximaal vijf handkaarten en vier persoonlijke aflegstapels. Als je je laatste handkaart speelt, trek je direct vijf nieuwe kaarten en speel je verder. Eindig je beurt door één handkaart op een zelfgekozen aflegstapel te leggen. Voltooide bouwstapels gaan onder de trekstapel terug.") })
}

@Composable
private fun InfoBox(name: String, detail: String, active: Boolean) {
    val activeColor by animateColorAsState(
        targetValue = if (active) Color(0xFFFFD98A) else Color(0xFF15382F),
        animationSpec = tween(260),
        label = "active-player-highlight"
    )
    Column(
        Modifier.widthIn(min = 132.dp)
            .animateContentSize(tween(220))
            .shadow(if (active) 8.dp else 2.dp, RoundedCornerShape(14.dp))
            .background(activeColor, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(name, color = if (active) Color(0xFF18251E) else Color.White, fontWeight = FontWeight.Bold)
        Text(detail, color = if (active) Color(0xFF425348) else Color(0xFFA9C2B5), fontSize = 11.sp)
    }
}
@Composable
private fun PileCard(main: String, sub: String, bg: Color, modifier: Modifier = Modifier, fg: Color = Color.White, onClick: (() -> Unit)? = null) {
    Column(
        modifier.animateContentSize(tween(210))
            .shadow(4.dp, RoundedCornerShape(11.dp))
            .background(bg, RoundedCornerShape(11.dp))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(5.dp).heightIn(min = 66.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        AnimatedContent(
            targetState = main,
            transitionSpec = {
                (fadeIn(tween(190)) + scaleIn(initialScale = 0.72f, animationSpec = tween(190))) togetherWith
                    (fadeOut(tween(100)) + scaleOut(targetScale = 0.9f, animationSpec = tween(100)))
            },
            label = "pile-top-card"
        ) { animatedMain ->
            Text(animatedMain, color = fg, fontSize = 19.sp, fontWeight = FontWeight.Black)
        }
        Text(sub, color = fg.copy(alpha = .82f), fontSize = 9.sp)
    }
}
@Composable
private fun PlayingCard(card: Card, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val bg = if (card.joker) Color(0xFFFFD34E) else Color.White
    val fg = if (card.joker) Color(0xFFB51F2A) else when (card.value % 3) { 0 -> Color(0xFF174C35); 1 -> Color(0xFFB51F2A); else -> Color(0xFF174C35) }
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val cardScale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.58f, stiffness = 520f),
        label = "playing-card-press"
    )
    Column(
        modifier.graphicsLayer { scaleX = cardScale; scaleY = cardScale }
            .shadow(5.dp, RoundedCornerShape(10.dp))
            .background(bg, RoundedCornerShape(10.dp))
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .padding(5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text(if (card.joker) "SKIP-BO" else "${card.value}", color = fg, fontWeight = FontWeight.Black, fontSize = 15.sp)
        Text(if (card.joker) "★" else "✦", color = fg, fontSize = 23.sp)
        Text(if (card.joker) "JOKER" else "MASTERS", color = fg, fontWeight = FontWeight.Bold, fontSize = 7.sp, letterSpacing = .5.sp)
    }
}
