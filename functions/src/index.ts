import { initializeApp } from "firebase-admin/app";
import { getStorage } from "firebase-admin/storage";
import { createHash, randomBytes, randomInt } from "node:crypto";
import { FieldValue, getFirestore, type DocumentData, type DocumentReference, type DocumentSnapshot } from "firebase-admin/firestore";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { setGlobalOptions } from "firebase-functions/v2";
import { drawToFive, setAsideCompletedBuild, shuffleCards } from "./gameRules";
import { buildPlayerMatchHistory, buildTournamentBracket, getAchievementProgress, eloWinnerDelta, preserveStoredDisplayName, type MatchHistoryRecord } from "./communityRules";

initializeApp();
setGlobalOptions({ region: "europe-west1", maxInstances: 20 });

const db = getFirestore();
const ROOMS = "rooms";
const CODE_RE = /^[A-HJ-NP-Z2-9]{6}$/;

type PublicPlayer = {
  name: string;
  stockCount: number;
  stockTop: number | null;
  discards: number[][];
};
type PublicState = {
  drawCount: number;
  builds: number[][];
  players: [PublicPlayer, PublicPlayer];
  current: number;
  message: string;
  finished: boolean;
  winner: string;
  needsTurnDraw: false;
};
type PrivatePlayer = { seat: number; stock: number[]; hand: number[] };

function requireUid(request: { auth?: { uid: string } | undefined }): string {
  if (!request.auth?.uid) throw new HttpsError("unauthenticated", "Meld je aan om online te spelen.");
  return request.auth.uid;
}

function roomCode(value: unknown): string {
  const code = String(value ?? "").trim().toUpperCase();
  if (!CODE_RE.test(code)) throw new HttpsError("invalid-argument", "Ongeldige kamercode.");
  return code;
}

function nameForRoom(value: unknown, fallback: string): string {
  const name = String(value ?? fallback).trim().slice(0, 24);
  return name || fallback;
}

function shuffledDeck(): number[] {
  const cards: number[] = [];
  for (let value = 1; value <= 12; value += 1) {
    for (let copy = 0; copy < 12; copy += 1) cards.push(value);
  }
  for (let joker = 0; joker < 18; joker += 1) cards.push(0);
  return shuffleCards(cards);
}

function publicJson(state: PublicState): string {
  return JSON.stringify(state);
}

function privatePlayerData(snapshot: DocumentSnapshot, seat: number): PrivatePlayer {
  if (!snapshot.exists) throw new HttpsError("failed-precondition", "Privékaartgegevens ontbreken. Start het spel opnieuw.");
  const data = snapshot.data() ?? {};
  const stock = data.stock;
  const hand = data.hand;
  if (data.seat !== seat || !Array.isArray(stock) || stock.length > 30 || !Array.isArray(hand) || hand.length > 5
      || stock.some((v) => !Number.isInteger(v) || v < 0 || v > 12)
      || hand.some((v) => !Number.isInteger(v) || v < 0 || v > 12)) {
    throw new HttpsError("internal", "Ongeldige privékaartgegevens op de server.");
  }
  return { seat, stock: [...stock] as number[], hand: [...hand] as number[] };
}

function updatePublicStock(state: PublicState, privatePlayers: [PrivatePlayer, PrivatePlayer]): void {
  for (let seat = 0; seat < 2; seat += 1) {
    const stock = privatePlayers[seat].stock;
    state.players[seat].stockCount = stock.length;
    state.players[seat].stockTop = stock.length ? stock[stock.length - 1] : null;
  }
}

function parsePublicState(raw: unknown): PublicState {
  if (typeof raw !== "string") throw new HttpsError("internal", "De online spelstatus ontbreekt.");
  let value: unknown;
  try { value = JSON.parse(raw); } catch { throw new HttpsError("internal", "De online spelstatus is beschadigd."); }
  const state = value as PublicState;
  if (!state || !Number.isInteger(state.drawCount) || state.drawCount < 0 || state.drawCount > 162 || !Array.isArray(state.builds) || state.builds.length !== 4 || !Array.isArray(state.players) || state.players.length !== 2 || !Number.isInteger(state.current) || state.current < 0 || state.current > 1) {
    throw new HttpsError("internal", "De online spelstatus heeft een ongeldig formaat.");
  }
  for (const pile of state.builds) {
    if (!Array.isArray(pile) || pile.length > 12 || pile.some((v) => !Number.isInteger(v) || v < 0 || v > 12)) {
      throw new HttpsError("internal", "Een centrale bouwstapel is ongeldig.");
    }
  }
  for (const player of state.players) {
    if (!player || !Number.isInteger(player.stockCount) || player.stockCount < 0 || player.stockCount > 30
        || (player.stockTop !== null && (!Number.isInteger(player.stockTop) || player.stockTop < 0 || player.stockTop > 12))
        || !Array.isArray(player.discards) || player.discards.length !== 4 || player.discards.some((pile) => !Array.isArray(pile) || pile.some((v) => !Number.isInteger(v) || v < 0 || v > 12))) {
      throw new HttpsError("internal", "Een persoonlijke aflegstapel is ongeldig.");
    }
  }
  return state;
}

function playerSeat(room: DocumentData, uid: string): number {
  if (room.hostUid === uid) return 0;
  if (room.guestUid === uid) return 1;
  throw new HttpsError("permission-denied", "Je bent geen deelnemer van deze kamer.");
}

function stateRefs(code: string) {
  const roomRef = db.collection(ROOMS).doc(code);
  return {
    roomRef,
    stateRef: roomRef.collection("state").doc("current"),
    deckRef: roomRef.collection("server").doc("deck"),
  };
}

const FRIEND_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
const FRIEND_CODE_RE = /^SKB-[A-HJ-NP-Z2-9]{6}$/;
const friendCodeCandidate = () => `SKB-${Array.from({ length: 6 }, () => FRIEND_ALPHABET[randomInt(FRIEND_ALPHABET.length)]).join("")}`;
const cleanDisplayName = (value: unknown, fallback = "Skip-Bo speler") => {
  const name = String(value ?? fallback).trim().replace(/\s+/g, " ").slice(0, 24);
  return name || fallback;
};
const friendRequestId = (a: string, b: string) =>
  createHash("sha256").update([a, b].sort().join(":")).digest("hex");
const initialProfile = (uid: string, displayName: string) => ({
  uid, displayName, rating: 1000, wins: 0, losses: 0, matchesPlayed: 0, winStreak: 0,
  createdAt: FieldValue.serverTimestamp(), updatedAt: FieldValue.serverTimestamp(),
});

// TODO before public release: register Play Integrity/App Check, then set enforceAppCheck: true.
export const startOnlineMatch = onCall(async (request) => {
  const uid = requireUid(request);
  const code = roomCode(request.data?.code);
  const refs = stateRefs(code);
  return db.runTransaction(async (transaction) => {
    const [roomSnap, stateSnap] = await Promise.all([transaction.get(refs.roomRef), transaction.get(refs.stateRef)]);
    if (!roomSnap.exists) throw new HttpsError("not-found", "Deze kamer bestaat niet meer.");
    const room = roomSnap.data() ?? {};
    if (room.hostUid !== uid) throw new HttpsError("permission-denied", "Alleen de host kan het spel starten.");
    if (!room.guestUid || room.status !== "READY") throw new HttpsError("failed-precondition", "Wacht tot een tweede speler de kamer heeft gevuld.");
    if (stateSnap.exists) throw new HttpsError("already-exists", "Dit spel is al gestart.");
    const hostUid = String(room.hostUid);
    const guestUid = String(room.guestUid);
    const privateRefs: [DocumentReference, DocumentReference] = [
      refs.roomRef.collection("privatePlayers").doc(hostUid),
      refs.roomRef.collection("privatePlayers").doc(guestUid),
    ];
    const serverPlayerRefs: [DocumentReference, DocumentReference] = [
      refs.roomRef.collection("serverPlayers").doc(hostUid),
      refs.roomRef.collection("serverPlayers").doc(guestUid),
    ];

    const deck = shuffledDeck();
    const hostStock = deck.splice(0, 30);
    const guestStock = deck.splice(0, 30);
    const hostHand: number[] = [];
    const guestHand: number[] = [];
    const completed: number[] = [];
    drawToFive(hostHand, deck, completed);
    const privatePlayers: [PrivatePlayer, PrivatePlayer] = [
      { seat: 0, stock: hostStock, hand: hostHand },
      { seat: 1, stock: guestStock, hand: guestHand },
    ];
    const state: PublicState = {
      drawCount: deck.length,
      builds: [[], [], [], []],
      players: [
        { name: nameForRoom(room.hostName, "Speler 1"), stockCount: hostStock.length, stockTop: hostStock[hostStock.length - 1] ?? null, discards: [[], [], [], []] },
        { name: nameForRoom(room.guestName, "Speler 2"), stockCount: guestStock.length, stockTop: guestStock[guestStock.length - 1] ?? null, discards: [[], [], [], []] },
      ],
      current: 0,
      message: `${nameForRoom(room.hostName, "Speler 1")} is aan de beurt. Speel een kaart of leg af.`,
      finished: false,
      winner: "",
      needsTurnDraw: false,
    };
    const revision = 1;
    transaction.create(refs.stateRef, { payload: publicJson(state), revision, updatedBy: uid, updatedAt: FieldValue.serverTimestamp() });
    transaction.create(refs.deckRef, {
      cards: deck, completed, updatedAt: FieldValue.serverTimestamp(),
    });
    transaction.create(serverPlayerRefs[0], { ...privatePlayers[0], updatedAt: FieldValue.serverTimestamp() });
    transaction.create(serverPlayerRefs[1], { ...privatePlayers[1], updatedAt: FieldValue.serverTimestamp() });
    transaction.create(privateRefs[0], { seat: 0, hand: hostHand, revision, updatedAt: FieldValue.serverTimestamp() });
    transaction.create(privateRefs[1], { seat: 1, hand: guestHand, revision, updatedAt: FieldValue.serverTimestamp() });
    transaction.update(refs.roomRef, { status: "PLAYING", updatedAt: FieldValue.serverTimestamp() });
    return { revision, payload: publicJson(state), privatePlayer: { hand: hostHand } };
  });
});

export const submitOnlineMove = onCall(async (request) => {
  const uid = requireUid(request);
  const data = request.data as Record<string, unknown>;
  const code = roomCode(data.code);
  const expectedRevision = Number(data.expectedRevision);
  if (!Number.isSafeInteger(expectedRevision) || expectedRevision < 1) throw new HttpsError("invalid-argument", "Ongeldige spelrevisie.");
  const refs = stateRefs(code);
  return db.runTransaction(async (transaction) => {
    const [roomSnap, stateSnap, deckSnap] = await Promise.all([
      transaction.get(refs.roomRef), transaction.get(refs.stateRef), transaction.get(refs.deckRef),
    ]);
    if (!roomSnap.exists || !stateSnap.exists || !deckSnap.exists) throw new HttpsError("not-found", "Het online spel is niet beschikbaar.");
    const room = roomSnap.data() ?? {};
    const seat = playerSeat(room, uid);
    const hostUid = String(room.hostUid ?? "");
    const guestUid = String(room.guestUid ?? "");
    if (!hostUid || !guestUid) throw new HttpsError("failed-precondition", "Deze kamer heeft geen twee spelers.");
    const privateRefs: [DocumentReference, DocumentReference] = [
      refs.roomRef.collection("privatePlayers").doc(hostUid),
      refs.roomRef.collection("privatePlayers").doc(guestUid),
    ];
    const serverPlayerRefs: [DocumentReference, DocumentReference] = [
      refs.roomRef.collection("serverPlayers").doc(hostUid),
      refs.roomRef.collection("serverPlayers").doc(guestUid),
    ];
    const [seat0Snap, seat1Snap] = await Promise.all([
      transaction.get(serverPlayerRefs[0]), transaction.get(serverPlayerRefs[1]),
    ]);
    if (room.status !== "PLAYING") throw new HttpsError("failed-precondition", "Dit spel is niet actief.");
    const actualRevision = Number(stateSnap.get("revision"));
    if (actualRevision !== expectedRevision) throw new HttpsError("aborted", "De spelstatus is gewijzigd. De nieuwste stand wordt opgehaald.");
    const state = parsePublicState(stateSnap.get("payload"));
    if (state.finished) throw new HttpsError("failed-precondition", "Dit spel is afgelopen.");
    if (state.current !== seat) throw new HttpsError("permission-denied", "Het is niet jouw beurt.");

    const privatePlayers: [PrivatePlayer, PrivatePlayer] = [
      privatePlayerData(seat0Snap, 0), privatePlayerData(seat1Snap, 1),
    ];
    const deckRaw = deckSnap.get("cards");
    const completedRaw = deckSnap.get("completed") ?? [];
    if (!Array.isArray(deckRaw) || deckRaw.some((v) => !Number.isInteger(v) || v < 0 || v > 12)) {
      throw new HttpsError("internal", "De server-trekstapel is ongeldig.");
    }
    if (!Array.isArray(completedRaw) || completedRaw.length > 162
        || completedRaw.some((v) => !Number.isInteger(v) || v < 0 || v > 12)) {
      throw new HttpsError("internal", "De server-opzijstapel met voltooide bouwstapels is ongeldig.");
    }
    const deck = [...deckRaw] as number[];
    const completed = [...completedRaw] as number[];
    const active = privatePlayers[seat];
    const otherSeat = (seat + 1) as 0 | 1;
    const actionType = String(data.actionType ?? "");

    if (actionType === "play") {
      const source = String(data.source ?? "");
      let card: number | undefined;
      if (source === "stock") {
        card = active.stock.pop();
      } else if (source === "hand") {
        const index = Number(data.index);
        if (!Number.isInteger(index) || index < 0 || index >= active.hand.length) throw new HttpsError("invalid-argument", "Ongeldige handkaart.");
        card = active.hand.splice(index, 1)[0];
      } else if (source === "discard") {
        const discardIndex = Number(data.index);
        if (!Number.isInteger(discardIndex) || discardIndex < 0 || discardIndex > 3) throw new HttpsError("invalid-argument", "Ongeldige aflegstapel.");
        card = state.players[seat].discards[discardIndex].pop();
      } else {
        throw new HttpsError("invalid-argument", "Ongeldige kaartbron.");
      }
      if (card === undefined) throw new HttpsError("failed-precondition", "Er ligt geen kaart op deze plek.");
      if (!Number.isInteger(card) || card < 0 || card > 12) throw new HttpsError("internal", "Ongeldige kaart op de server.");

      const buildIndex = state.builds.findIndex((pile) => {
        if (pile.length >= 12) return false;
        const needed = pile.length + 1;
        return pile.length === 0 ? card === 0 || card === 1 : card === 0 || card === needed;
      });
      if (buildIndex < 0) throw new HttpsError("failed-precondition", "Deze kaart past op dit moment niet op een bouwstapel.");
      const build = state.builds[buildIndex];
      build.push(card); // A physical Skip-Bo wild card remains a joker (0) when recycled.
      if (build.length === 12) {
        setAsideCompletedBuild(build, completed);
        state.message = "Bouwstapel compleet en apart gelegd. Deze kaarten worden pas gebruikt als de trekstapel op is.";
      } else {
        state.message = `${state.players[seat].name} speelde een kaart op bouwstapel ${buildIndex + 1}.`;
      }
      if (active.stock.length === 0) {
        state.finished = true;
        state.winner = state.players[seat].name;
        state.message = `${state.winner} wint! De voorraadstapel is leeg.`;
      } else if (source === "hand" && active.hand.length === 0) {
        drawToFive(active.hand, deck, completed);
        state.message = "Je hand is direct opnieuw aangevuld. Je mag doorspelen.";
      }
    } else if (actionType === "discard") {
      const handIndex = Number(data.handIndex);
      const discardPileIndex = Number(data.discardPileIndex);
      if (!Number.isInteger(handIndex) || handIndex < 0 || handIndex >= active.hand.length) throw new HttpsError("invalid-argument", "Kies een kaart uit je hand.");
      if (!Number.isInteger(discardPileIndex) || discardPileIndex < 0 || discardPileIndex > 3) throw new HttpsError("invalid-argument", "Kies een van de vier aflegstapels.");
      const discarded = active.hand.splice(handIndex, 1)[0];
      state.players[seat].discards[discardPileIndex].push(discarded);
      state.current = otherSeat;
      drawToFive(privatePlayers[otherSeat].hand, deck, completed);
      state.message = `${state.players[otherSeat].name} is aan de beurt. De hand is aangevuld tot vijf kaarten.`;
    } else {
      throw new HttpsError("invalid-argument", "Onbekende spelactie.");
    }

    state.drawCount = deck.length;
    updatePublicStock(state, privatePlayers);

    // New trophies returned only to the player who made the winning move.
    let unlockedAchievements: string[] = [];

    // Record the final result and update ratings exactly once, inside the same
    // server transaction that commits the winning move.
    if (state.finished) {
      const winnerUid = seat === 0 ? hostUid : guestUid;
      const loserUid = seat === 0 ? guestUid : hostUid;
      const resultRef = db.collection("matchResults").doc(code);
      const winnerProfileRef = db.doc(`profiles/${winnerUid}`);
      const loserProfileRef = db.doc(`profiles/${loserUid}`);
      const winnerPublicRef = db.doc(`publicProfiles/${winnerUid}`);
      const loserPublicRef = db.doc(`publicProfiles/${loserUid}`);
      const winnerBoardRef = db.doc(`leaderboard/${winnerUid}`);
      const loserBoardRef = db.doc(`leaderboard/${loserUid}`);
      const [resultSnap, winnerProfile, loserProfile, winnerPublic, loserPublic] = await Promise.all([
        transaction.get(resultRef), transaction.get(winnerProfileRef), transaction.get(loserProfileRef),
        transaction.get(winnerPublicRef), transaction.get(loserPublicRef),
      ]);

      if (!resultSnap.exists) {
        const winnerName = cleanDisplayName(
          winnerPublic.get("displayName") ?? (seat === 0 ? state.players[0].name : state.players[1].name)
        );
        const loserName = cleanDisplayName(
          loserPublic.get("displayName") ?? (seat === 0 ? state.players[1].name : state.players[0].name)
        );
        const winnerData = winnerProfile.data() ?? {};
        const loserData = loserProfile.data() ?? {};
        const winnerRating = Number(winnerData.rating ?? 1000);
        const loserRating = Number(loserData.rating ?? 1000);
        if (!Number.isSafeInteger(winnerRating) || !Number.isSafeInteger(loserRating)
            || winnerRating < 0 || loserRating < 0 || winnerRating > 100000 || loserRating > 100000) {
          throw new HttpsError("failed-precondition", "Ongeldige ranglijstscore; uitslag niet verwerkt.");
        }
        const winnerDelta = eloWinnerDelta(winnerRating, loserRating);
        const newWinnerRating = winnerRating + winnerDelta;
        const newLoserRating = Math.max(0, loserRating - winnerDelta);
        const winnerWins = Number(winnerData.wins ?? 0) + 1;
        const winnerMatches = Number(winnerData.matchesPlayed ?? 0) + 1;
        const loserLosses = Number(loserData.losses ?? 0) + 1;
        const loserMatches = Number(loserData.matchesPlayed ?? 0) + 1;
        const previousWinnerStreak = Number(winnerData.winStreak ?? 0);
        const winnerWinStreak = Number.isSafeInteger(previousWinnerStreak) && previousWinnerStreak >= 0
          ? previousWinnerStreak + 1
          : 1;
        const loserWinStreak = 0;

        // Determine eligible trophies from the post-match server statistics.
        const winnerEligible = getAchievementProgress({
          wins: winnerWins,
          matchesPlayed: winnerMatches,
          winStreak: winnerWinStreak,
          rating: newWinnerRating,
        }).filter((achievement) => achievement.unlocked);
        const loserEligible = getAchievementProgress({
          wins: Number(loserData.wins ?? 0),
          matchesPlayed: loserMatches,
          winStreak: loserWinStreak,
          rating: newLoserRating,
        }).filter((achievement) => achievement.unlocked);

        const winnerAchievementRefs = winnerEligible.map((achievement) => ({
          achievement,
          ref: winnerProfileRef.collection("achievements").doc(achievement.id),
        }));
        const loserAchievementRefs = loserEligible.map((achievement) => ({
          achievement,
          ref: loserProfileRef.collection("achievements").doc(achievement.id),
        }));

        // Read every trophy document before the first write in this transaction.
        const achievementEntries = [...winnerAchievementRefs, ...loserAchievementRefs];
        const achievementSnapshots = await Promise.all(
          achievementEntries.map((entry) => transaction.get(entry.ref))
        );

        let tournamentRefForResult: DocumentReference | null = null;
        let tournamentMatchRefForResult: DocumentReference | null = null;
        let tournamentNextMatchRef: DocumentReference | null = null;
        let tournamentNextMatchData: DocumentData | null = null;
        let tournamentCurrentRound = 0;
        let tournamentCurrentSlot = 0;
        let tournamentIsFinal = false;

        const hasTournamentMarker = typeof room.tournamentId === "string"
          || typeof room.tournamentMatchId === "string";
        if (hasTournamentMarker) {
          if (typeof room.tournamentId !== "string" || typeof room.tournamentMatchId !== "string") {
            throw new HttpsError("failed-precondition", "De toernooiwedstrijd is onvolledig gekoppeld.");
          }
          tournamentRefForResult = db.collection("tournaments").doc(room.tournamentId);
          tournamentMatchRefForResult = db.collection("tournamentMatches").doc(room.tournamentMatchId);
          const [tournamentSnap, tournamentMatchSnap] = await Promise.all([
            transaction.get(tournamentRefForResult),
            transaction.get(tournamentMatchRefForResult),
          ]);
          if (!tournamentSnap.exists || !tournamentMatchSnap.exists) {
            throw new HttpsError("failed-precondition", "De toernooigegevens ontbreken; uitslag niet verwerkt.");
          }
          const tournamentData = tournamentSnap.data() ?? {};
          const tournamentMatchData = tournamentMatchSnap.data() ?? {};
          const matchPlayers = Array.isArray(tournamentMatchData.playerUids)
            ? tournamentMatchData.playerUids
            : [];
          if (tournamentData.status !== "RUNNING"
              || tournamentMatchData.tournamentId !== room.tournamentId
              || tournamentMatchData.status !== "PLAYING"
              || tournamentMatchData.roomCode !== code
              || matchPlayers.length !== 2
              || !matchPlayers.includes(winnerUid)
              || !matchPlayers.includes(loserUid)) {
            throw new HttpsError("failed-precondition", "De toernooiwedstrijd komt niet overeen met deze online kamer.");
          }

          tournamentCurrentRound = Number(tournamentMatchData.round);
          tournamentCurrentSlot = Number(tournamentMatchData.slot);
          const roundCount = Number(tournamentData.roundCount);
          if (!Number.isSafeInteger(tournamentCurrentRound) || tournamentCurrentRound < 1
              || !Number.isSafeInteger(tournamentCurrentSlot) || tournamentCurrentSlot < 0
              || !Number.isSafeInteger(roundCount) || roundCount < 1) {
            throw new HttpsError("failed-precondition", "De toernooibracket is ongeldig.");
          }
          tournamentIsFinal = tournamentCurrentRound === roundCount;
          if (tournamentCurrentRound < roundCount) {
            const rounds = Array.isArray(tournamentData.rounds) ? tournamentData.rounds : [];
            const nextRound = rounds.find((item: { round?: number }) => Number(item.round) === tournamentCurrentRound + 1);
            const nextSlot = Math.floor(tournamentCurrentSlot / 2);
            const nextMatchId = Array.isArray(nextRound?.matchIds) ? nextRound.matchIds[nextSlot] : null;
            if (typeof nextMatchId !== "string" || !/^[A-Za-z0-9]{15,40}$/.test(nextMatchId)) {
              throw new HttpsError("failed-precondition", "De volgende toernooiwedstrijd ontbreekt.");
            }
            tournamentNextMatchRef = db.collection("tournamentMatches").doc(nextMatchId);
            const nextMatchSnap = await transaction.get(tournamentNextMatchRef);
            if (!nextMatchSnap.exists) {
              throw new HttpsError("failed-precondition", "De volgende toernooiwedstrijd bestaat niet.");
            }
            tournamentNextMatchData = nextMatchSnap.data() ?? {};
            if (tournamentNextMatchData.status !== "WAITING_FOR_PLAYERS") {
              throw new HttpsError("aborted", "De volgende ronde is al bijgewerkt.");
            }
          }
        }

        transaction.create(resultRef, {
          matchId: code, winnerUid, loserUid, winnerName, loserName,
          ratingChange: winnerDelta, createdAt: FieldValue.serverTimestamp(),
        });
        transaction.set(winnerProfileRef, {
          ...initialProfile(winnerUid, winnerName), ...winnerData,
          uid: winnerUid, displayName: winnerName, rating: newWinnerRating,
          wins: winnerWins, matchesPlayed: winnerMatches, winStreak: winnerWinStreak,
          updatedAt: FieldValue.serverTimestamp(),
        }, { merge: true });
        transaction.set(loserProfileRef, {
          ...initialProfile(loserUid, loserName), ...loserData,
          uid: loserUid, displayName: loserName, rating: newLoserRating,
          losses: loserLosses, matchesPlayed: loserMatches, winStreak: loserWinStreak,
          updatedAt: FieldValue.serverTimestamp(),
        }, { merge: true });
        transaction.set(winnerPublicRef, {
          uid: winnerUid, displayName: winnerName, rating: newWinnerRating,
          wins: winnerWins, losses: Number(winnerData.losses ?? 0), matchesPlayed: winnerMatches,
          updatedAt: FieldValue.serverTimestamp(),
        }, { merge: true });
        transaction.set(loserPublicRef, {
          uid: loserUid, displayName: loserName, rating: newLoserRating,
          wins: Number(loserData.wins ?? 0), losses: loserLosses, matchesPlayed: loserMatches,
          updatedAt: FieldValue.serverTimestamp(),
        }, { merge: true });
        transaction.set(winnerBoardRef, {
          uid: winnerUid, displayName: winnerName, rating: newWinnerRating,
          matchesPlayed: winnerMatches, provisional: winnerMatches < 10,
          updatedAt: FieldValue.serverTimestamp(),
        });
        transaction.set(loserBoardRef, {
          uid: loserUid, displayName: loserName, rating: newLoserRating,
          matchesPlayed: loserMatches, provisional: loserMatches < 10,
          updatedAt: FieldValue.serverTimestamp(),
        });

        achievementEntries.forEach((entry, index) => {
          if (achievementSnapshots[index].exists) return;
          transaction.create(entry.ref, {
            id: entry.achievement.id,
            title: entry.achievement.title,
            description: entry.achievement.description,
            category: entry.achievement.category,
            unlockedAt: FieldValue.serverTimestamp(),
          });
          const ownerUid = winnerAchievementRefs.includes(entry) ? winnerUid : loserUid;
          if (ownerUid === uid) unlockedAchievements.push(entry.achievement.id);
        });

        if (tournamentMatchRefForResult && tournamentRefForResult) {
          transaction.update(tournamentMatchRefForResult, {
            status: "COMPLETED",
            winnerUid,
            completedAt: FieldValue.serverTimestamp(),
            updatedAt: FieldValue.serverTimestamp(),
          });

          if (tournamentIsFinal) {
            transaction.update(tournamentRefForResult, {
              status: "COMPLETED",
              winnerUid,
              completedAt: FieldValue.serverTimestamp(),
              updatedAt: FieldValue.serverTimestamp(),
            });
          } else if (tournamentNextMatchRef && tournamentNextMatchData) {
            const nextPlayer1Uid = tournamentCurrentSlot % 2 === 0
              ? winnerUid
              : (typeof tournamentNextMatchData.player1Uid === "string" ? tournamentNextMatchData.player1Uid : null);
            const nextPlayer2Uid = tournamentCurrentSlot % 2 === 1
              ? winnerUid
              : (typeof tournamentNextMatchData.player2Uid === "string" ? tournamentNextMatchData.player2Uid : null);
            const nextPlayers = [nextPlayer1Uid, nextPlayer2Uid].filter((value): value is string => typeof value === "string");
            transaction.update(tournamentNextMatchRef, {
              player1Uid: nextPlayer1Uid,
              player2Uid: nextPlayer2Uid,
              playerUids: nextPlayers,
              status: nextPlayers.length === 2 ? "WAITING" : "WAITING_FOR_PLAYERS",
              updatedAt: FieldValue.serverTimestamp(),
            });
          }
        }
      }
    }

    const nextRevision = actualRevision + 1;
    const payload = publicJson(state);
    transaction.update(refs.stateRef, { payload, revision: nextRevision, updatedBy: uid, updatedAt: FieldValue.serverTimestamp() });
    transaction.set(refs.deckRef, {
      cards: deck, completed, updatedAt: FieldValue.serverTimestamp(),
    });
    for (let playerSeatIndex = 0; playerSeatIndex < 2; playerSeatIndex += 1) {
      transaction.set(serverPlayerRefs[playerSeatIndex], {
        ...privatePlayers[playerSeatIndex], updatedAt: FieldValue.serverTimestamp(),
      });
      transaction.set(privateRefs[playerSeatIndex], {
        seat: playerSeatIndex, hand: privatePlayers[playerSeatIndex].hand,
        revision: nextRevision, updatedAt: FieldValue.serverTimestamp(),
      });
    }
    if (state.finished) transaction.update(refs.roomRef, { status: "FINISHED", updatedAt: FieldValue.serverTimestamp() });
    return { revision: nextRevision, payload, privatePlayer: { hand: active.hand }, unlockedAchievements };
  });
});


/**
 * Reconnect/resume endpoint. Authentication and room membership are checked
 * on the server, and the returned private hand belongs only to the caller.
 */
export const resumeOnlineMatch = onCall(async (request) => {
  const uid = requireUid(request);
  const code = roomCode(request.data?.code);
  const refs = stateRefs(code);
  return db.runTransaction(async (transaction) => {
    const privateRef = refs.roomRef.collection("privatePlayers").doc(uid);
    const [roomSnap, stateSnap, privateSnap] = await Promise.all([
      transaction.get(refs.roomRef),
      transaction.get(refs.stateRef),
      transaction.get(privateRef),
    ]);

    if (!roomSnap.exists) throw new HttpsError("not-found", "Deze kamer bestaat niet meer.");
    const room = roomSnap.data() ?? {};
    playerSeat(room, uid);
    if (!stateSnap.exists || !privateSnap.exists) {
      throw new HttpsError("failed-precondition", "Deze kamer heeft nog geen actief spel.");
    }
    if (room.status !== "PLAYING" && room.status !== "FINISHED") {
      throw new HttpsError("failed-precondition", "Er is geen lopend spel om te hervatten.");
    }

    const revision = Number(stateSnap.get("revision"));
    if (!Number.isSafeInteger(revision) || revision < 1) {
      throw new HttpsError("internal", "De spelrevisie is ongeldig.");
    }
    const privateData = privateSnap.data() ?? {};
    const seat = playerSeat(room, uid);
    const hand = privateData.hand;
    const privateRevision = Number(privateData.revision);
    if (privateData.seat !== seat || privateRevision !== revision
        || !Array.isArray(hand) || hand.length > 5
        || hand.some((card) => !Number.isInteger(card) || card < 0 || card > 12)) {
      throw new HttpsError("aborted", "Je privékaarten worden nog gesynchroniseerd. Probeer opnieuw te verbinden.");
    }

    const payload = stateSnap.get("payload");
    parsePublicState(payload);
    return {
      revision,
      payload,
      privatePlayer: { hand: [...hand] as number[] },
    };
  });
});




// A player may edit only their own display name; rating and match counters stay server-owned.
export const updatePlayerDisplayName = onCall(async (request) => {
  const uid = requireUid(request);
  const rawName = String(request.data?.displayName ?? "").trim().replace(/\s+/g, " ");
  if (!rawName || rawName.length > 24) {
    throw new HttpsError("invalid-argument", "Je spelersnaam moet 1 tot 24 tekens bevatten.");
  }
  const displayName = cleanDisplayName(rawName);
  const profileRef = db.doc(`profiles/${uid}`);
  const publicRef = db.doc(`publicProfiles/${uid}`);
  await db.runTransaction(async (transaction) => {
    const [profileSnap, publicSnap] = await Promise.all([
      transaction.get(profileRef), transaction.get(publicRef),
    ]);
    if (!profileSnap.exists || !publicSnap.exists) {
      throw new HttpsError("failed-precondition", "Je spelersprofiel is nog niet aangemaakt.");
    }
    transaction.update(profileRef, {
      displayName, updatedAt: FieldValue.serverTimestamp(),
    });
    transaction.update(publicRef, {
      displayName, updatedAt: FieldValue.serverTimestamp(),
    });
  });
  return { displayName };
});

// Creates or retrieves a stable friend code and ensures profile documents exist.
export const ensureFriendCode = onCall(async (request) => {
  const uid = requireUid(request);
  const displayName = cleanDisplayName(request.data?.displayName, "Skip-Bo speler");
  const profileRef = db.doc(`profiles/${uid}`);
  const publicRef = db.doc(`publicProfiles/${uid}`);

  for (let attempt = 0; attempt < 12; attempt += 1) {
    const candidate = friendCodeCandidate();
    const result = await db.runTransaction(async (transaction) => {
      const profileSnap = await transaction.get(profileRef);
      const existingCode = profileSnap.get("friendCode");
      let selectedCode: string | null = null;
      let selectedCodeRef = db.doc(`friendCodes/${candidate}`);
      let selectedCodeSnap = await transaction.get(selectedCodeRef);

      if (typeof existingCode === "string" && FRIEND_CODE_RE.test(existingCode)) {
        const oldRef = db.doc(`friendCodes/${existingCode}`);
        const oldSnap = await transaction.get(oldRef);
        if (oldSnap.exists && oldSnap.get("uid") === uid) {
          selectedCode = existingCode;
          selectedCodeRef = oldRef;
          selectedCodeSnap = oldSnap;
        } else if (!oldSnap.exists && !selectedCodeSnap.exists) {
          selectedCode = existingCode;
          selectedCodeRef = oldRef;
          selectedCodeSnap = oldSnap;
        }
      }

      if (!selectedCode) {
        if (selectedCodeSnap.exists) return null;
        selectedCode = candidate;
      }

      const oldData = profileSnap.data() ?? {};
      // Profile creation is idempotent. A device-local nickname must never
      // overwrite an existing account's server-stored display name on restore.
      const effectiveName = preserveStoredDisplayName(
        profileSnap.get("displayName"), displayName
      );
      const profile = {
        ...initialProfile(uid, effectiveName),
        ...oldData,
        uid,
        displayName: effectiveName,
        friendCode: selectedCode,
        updatedAt: FieldValue.serverTimestamp(),
      };
      transaction.set(profileRef, profile, { merge: true });
      transaction.set(publicRef, {
        uid, displayName: effectiveName,
        rating: Number(profile.rating ?? 1000),
        wins: Number(profile.wins ?? 0),
        losses: Number(profile.losses ?? 0),
        matchesPlayed: Number(profile.matchesPlayed ?? 0),
        updatedAt: FieldValue.serverTimestamp(),
      }, { merge: true });
      if (!selectedCodeSnap.exists) {
        transaction.create(selectedCodeRef, {
          uid, createdAt: FieldValue.serverTimestamp(),
        });
      }
      return selectedCode;
    });

    if (result) return { friendCode: result };
  }
  throw new HttpsError("resource-exhausted", "Er kon geen unieke vriendencode worden gemaakt.");
});

export const requestFriendByCode = onCall(async (request) => {
  const uid = requireUid(request);
  const code = String(request.data?.code ?? "").trim().toUpperCase();
  if (!FRIEND_CODE_RE.test(code)) throw new HttpsError("invalid-argument", "Ongeldige vriendencode.");
  const codeSnap = await db.doc(`friendCodes/${code}`).get();
  if (!codeSnap.exists) throw new HttpsError("not-found", "Geen speler gevonden met deze code.");
  const targetUid = codeSnap.get("uid");
  if (typeof targetUid !== "string" || targetUid === uid) {
    throw new HttpsError("invalid-argument", "Je kunt jezelf niet als vriend toevoegen.");
  }

  const requestRef = db.collection("friendRequests").doc(friendRequestId(uid, targetUid));
  const myFriendRef = db.doc(`profiles/${uid}/friends/${targetUid}`);
  const theirFriendRef = db.doc(`profiles/${targetUid}/friends/${uid}`);
  const [myProfile, theirProfile] = await Promise.all([
    db.doc(`profiles/${uid}`).get(), db.doc(`profiles/${targetUid}`).get(),
  ]);
  if (!myProfile.exists) throw new HttpsError("failed-precondition", "Maak eerst je spelersprofiel aan.");
  if (!theirProfile.exists) throw new HttpsError("not-found", "Spelersprofiel niet gevonden.");

  await db.runTransaction(async (transaction) => {
    const [existing, myFriend, theirFriend] = await Promise.all([
      transaction.get(requestRef), transaction.get(myFriendRef), transaction.get(theirFriendRef),
    ]);
    if (myFriend.exists || theirFriend.exists) {
      throw new HttpsError("already-exists", "Jullie zijn al vrienden.");
    }
    if (existing.exists && existing.get("status") === "pending") {
      if (existing.get("fromUid") === targetUid && existing.get("toUid") === uid) {
        transaction.set(myFriendRef, { uid: targetUid, createdAt: FieldValue.serverTimestamp() });
        transaction.set(theirFriendRef, { uid, createdAt: FieldValue.serverTimestamp() });
        transaction.update(requestRef, { status: "accepted", respondedAt: FieldValue.serverTimestamp() });
        return;
      }
      throw new HttpsError("already-exists", "Je hebt deze speler al uitgenodigd.");
    }
    transaction.set(requestRef, {
      fromUid: uid, toUid: targetUid, status: "pending",
      createdAt: FieldValue.serverTimestamp(), respondedAt: null,
    });
  });
  return { success: true };
});

export const respondToFriendRequest = onCall(async (request) => {
  const uid = requireUid(request);
  const requestId = String(request.data?.requestId ?? "");
  const action = request.data?.action;
  if (!/^[a-f0-9]{64}$/.test(requestId)) throw new HttpsError("invalid-argument", "Ongeldig vriendschapsverzoek.");
  if (action !== "accept" && action !== "decline") throw new HttpsError("invalid-argument", "Ongeldige keuze.");

  const requestRef = db.collection("friendRequests").doc(requestId);
  await db.runTransaction(async (transaction) => {
    const snapshot = await transaction.get(requestRef);
    if (!snapshot.exists) throw new HttpsError("not-found", "Verzoek niet gevonden.");
    const data = snapshot.data() ?? {};
    if (data.toUid !== uid) throw new HttpsError("permission-denied", "Alleen de ontvanger kan dit verzoek beantwoorden.");
    if (data.status !== "pending") throw new HttpsError("failed-precondition", "Dit verzoek is al afgehandeld.");
    if (action === "decline") {
      transaction.update(requestRef, { status: "declined", respondedAt: FieldValue.serverTimestamp() });
      return;
    }
    const fromUid = data.fromUid;
    if (typeof fromUid !== "string" || fromUid === uid) throw new HttpsError("failed-precondition", "Ongeldige afzender.");
    const myFriendRef = db.doc(`profiles/${uid}/friends/${fromUid}`);
    const theirFriendRef = db.doc(`profiles/${fromUid}/friends/${uid}`);
    const [myFriend, theirFriend] = await Promise.all([transaction.get(myFriendRef), transaction.get(theirFriendRef)]);
    transaction.set(myFriendRef, { uid: fromUid, createdAt: FieldValue.serverTimestamp() });
    transaction.set(theirFriendRef, { uid, createdAt: FieldValue.serverTimestamp() });
    transaction.update(requestRef, { status: "accepted", respondedAt: FieldValue.serverTimestamp() });
  });
  return { success: true, status: action };
});

export const createFriendGameInvite = onCall(async (request) => {
  const uid = requireUid(request);
  const targetUid = String(request.data?.friendUid ?? "");
  if (!targetUid || targetUid === uid || targetUid.length > 128) {
    throw new HttpsError("invalid-argument", "Ongeldige vriend geselecteerd.");
  }
  const friendRef = db.doc(`profiles/${uid}/friends/${targetUid}`);
  const reverseFriendRef = db.doc(`profiles/${targetUid}/friends/${uid}`);
  const [friend, reverseFriend, profileSnap, publicSnap] = await Promise.all([
    friendRef.get(), reverseFriendRef.get(), db.doc(`profiles/${uid}`).get(), db.doc(`publicProfiles/${uid}`).get(),
  ]);
  if (!friend.exists || !reverseFriend.exists) throw new HttpsError("permission-denied", "Je kunt alleen bevriende spelers uitnodigen.");
  const hostName = cleanDisplayName(publicSnap.get("displayName") ?? profileSnap.get("displayName"));

  for (let attempt = 0; attempt < 8; attempt += 1) {
    const code = Array.from({ length: 6 }, () => FRIEND_ALPHABET[randomInt(FRIEND_ALPHABET.length)]).join("");
    const inviteId = randomBytes(16).toString("hex");
    const roomRef = db.collection(ROOMS).doc(code);
    const inviteRef = db.collection("gameInvites").doc(inviteId);
    const created = await db.runTransaction(async (transaction) => {
      const roomSnap = await transaction.get(roomRef);
      if (roomSnap.exists) return false;
      transaction.create(roomRef, {
        hostUid: uid, hostName, guestUid: null, guestName: null, status: "WAITING",
        createdAt: FieldValue.serverTimestamp(), updatedAt: FieldValue.serverTimestamp(),
      });
      transaction.create(inviteRef, {
        fromUid: uid, toUid: targetUid, roomCode: code, status: "pending",
        createdAt: FieldValue.serverTimestamp(), respondedAt: null,
      });
      return true;
    });
    if (created) return { inviteId, roomCode: code };
  }
  throw new HttpsError("resource-exhausted", "Er kon geen vrije spelkamer worden gemaakt.");
});

export const respondToGameInvite = onCall(async (request) => {
  const uid = requireUid(request);
  const inviteId = String(request.data?.inviteId ?? "");
  const action = request.data?.action;
  if (!/^[a-f0-9]{32}$/.test(inviteId)) throw new HttpsError("invalid-argument", "Ongeldige speluitnodiging.");
  if (action !== "accept" && action !== "decline") throw new HttpsError("invalid-argument", "Ongeldige keuze.");
  const inviteRef = db.collection("gameInvites").doc(inviteId);

  const result = await db.runTransaction(async (transaction) => {
    const inviteSnap = await transaction.get(inviteRef);
    if (!inviteSnap.exists) throw new HttpsError("not-found", "Speluitnodiging niet gevonden.");
    const invite = inviteSnap.data() ?? {};
    if (invite.toUid !== uid) throw new HttpsError("permission-denied", "Deze uitnodiging is niet voor jou.");
    if (invite.status !== "pending") throw new HttpsError("failed-precondition", "Deze uitnodiging is al afgehandeld.");
    if (action === "decline") {
      transaction.update(inviteRef, { status: "declined", respondedAt: FieldValue.serverTimestamp() });
      return { roomCode: null };
    }
    const code = roomCode(invite.roomCode);
    const roomRef = db.collection(ROOMS).doc(code);
    const publicRef = db.doc(`publicProfiles/${uid}`);
    const [roomSnap, profileSnap] = await Promise.all([transaction.get(roomRef), transaction.get(publicRef)]);
    if (!roomSnap.exists) throw new HttpsError("not-found", "De uitgenodigde kamer bestaat niet meer.");
    const room = roomSnap.data() ?? {};
    if (room.hostUid !== invite.fromUid || room.guestUid != null || room.status !== "WAITING") {
      throw new HttpsError("failed-precondition", "Deze kamer is niet meer beschikbaar.");
    }
    const guestName = cleanDisplayName(profileSnap.get("displayName"), "Skip-Bo speler");
    transaction.update(roomRef, {
      guestUid: uid, guestName, status: "READY", updatedAt: FieldValue.serverTimestamp(),
    });
    transaction.update(inviteRef, { status: "accepted", respondedAt: FieldValue.serverTimestamp() });
    return { roomCode: code };
  });
  return { success: true, roomCode: result.roomCode };
});

export const getCommunityOverview = onCall(async (request) => {
  const uid = requireUid(request);
  const [profileSnap, friendsSnap, requestsSnap, invitesSnap] = await Promise.all([
    db.doc(`profiles/${uid}`).get(),
    db.collection(`profiles/${uid}/friends`).orderBy("createdAt", "desc").limit(100).get(),
    db.collection("friendRequests").where("toUid", "==", uid).where("status", "==", "pending").orderBy("createdAt", "desc").limit(30).get(),
    db.collection("gameInvites").where("toUid", "==", uid).where("status", "==", "pending").orderBy("createdAt", "desc").limit(30).get(),
  ]);
  if (!profileSnap.exists) throw new HttpsError("failed-precondition", "Spelersprofiel ontbreekt. Haal eerst je vriendencode op.");

  const friendUids = friendsSnap.docs.map(doc => String(doc.get("uid") ?? doc.id));
  const requestUids = requestsSnap.docs.map(doc => String(doc.get("fromUid") ?? "")).filter(Boolean);
  const inviteUids = invitesSnap.docs.map(doc => String(doc.get("fromUid") ?? "")).filter(Boolean);
  const allUids = [...new Set([...friendUids, ...requestUids, ...inviteUids])];
  const publicSnaps = await Promise.all(allUids.map(id => db.doc(`publicProfiles/${id}`).get()));
  const publicMap = new Map(publicSnaps.map(doc => [doc.id, doc.data() ?? {}]));
  const publicPlayer = (id: string) => {
    const data = publicMap.get(id) ?? {};
    return {
      uid: id,
      displayName: cleanDisplayName(data.displayName),
      rating: Number(data.rating ?? 1000),
      wins: Number(data.wins ?? 0),
      losses: Number(data.losses ?? 0),
      matchesPlayed: Number(data.matchesPlayed ?? 0),
    };
  };
  const me = profileSnap.data() ?? {};
  return {
    profile: {
      uid, displayName: cleanDisplayName(me.displayName), friendCode: String(me.friendCode ?? ""),
      rating: Number(me.rating ?? 1000), wins: Number(me.wins ?? 0), losses: Number(me.losses ?? 0),
      matchesPlayed: Number(me.matchesPlayed ?? 0),
      avatarPath: typeof me.avatarPath === "string" ? me.avatarPath : null,
    },
    friends: friendUids.map(publicPlayer),
    friendRequests: requestsSnap.docs.map(doc => ({
      requestId: doc.id, from: publicPlayer(String(doc.get("fromUid") ?? "")),
    })),
    gameInvites: invitesSnap.docs.map(doc => ({
      inviteId: doc.id,
      roomCode: String(doc.get("roomCode") ?? ""),
      from: publicPlayer(String(doc.get("fromUid") ?? "")),
    })),
  };
});

export const getLeaderboardPage = onCall(async (request) => {
  requireUid(request);
  const requestedSize = request.data?.pageSize;
  const pageSize = Number.isInteger(requestedSize) ? Math.min(50, Math.max(1, Number(requestedSize))) : 25;
  const cursor = request.data?.cursor;
  if (cursor != null && (
    !Number.isSafeInteger(cursor.rating) || cursor.rating < 0 || cursor.rating > 100000
    || typeof cursor.uid !== "string" || cursor.uid.length < 1 || cursor.uid.length > 128
  )) throw new HttpsError("invalid-argument", "Ongeldige ranglijstcursor.");

  let query = db.collection("leaderboard").orderBy("rating", "desc").orderBy("uid", "asc");
  if (cursor) query = query.startAfter(Number(cursor.rating), String(cursor.uid));
  const snapshot = await query.limit(pageSize + 1).get();
  const hasMore = snapshot.docs.length > pageSize;
  const docs = snapshot.docs.slice(0, pageSize);
  const players = docs.map(doc => ({
    uid: doc.id,
    displayName: cleanDisplayName(doc.get("displayName")),
    rating: Number(doc.get("rating") ?? 1000),
    matchesPlayed: Number(doc.get("matchesPlayed") ?? 0),
    provisional: Boolean(doc.get("provisional")),
  }));
  const last = docs.length > 0 ? docs[docs.length - 1] : undefined;
  return {
    players,
    nextCursor: hasMore && last ? { rating: Number(last.get("rating")), uid: String(last.get("uid")) } : null,
  };
});

/** Return only the signed-in player's recent, server-recorded online matches. */
export const getMyMatchHistory = onCall(async (request) => {
  const uid = requireUid(request);
  const requestedSize = request.data?.pageSize;
  const pageSize = Number.isInteger(requestedSize)
    ? Math.min(30, Math.max(1, Number(requestedSize)))
    : 20;

  // Keep the two indexed queries scoped to the authenticated player's UID.
  const [wonSnapshot, lostSnapshot] = await Promise.all([
    db.collection("matchResults")
      .where("winnerUid", "==", uid)
      .orderBy("createdAt", "desc")
      .limit(pageSize)
      .get(),
    db.collection("matchResults")
      .where("loserUid", "==", uid)
      .orderBy("createdAt", "desc")
      .limit(pageSize)
      .get(),
  ]);

  const records: MatchHistoryRecord[] = [...wonSnapshot.docs, ...lostSnapshot.docs]
    .map((doc) => {
      const data = doc.data();
      const createdAt = data.createdAt as { toMillis?: () => number } | undefined;
      const playedAtMs = createdAt && typeof createdAt.toMillis === "function"
        ? createdAt.toMillis()
        : 0;

      return {
        matchId: String(data.matchId ?? doc.id),
        winnerUid: String(data.winnerUid ?? ""),
        loserUid: String(data.loserUid ?? ""),
        winnerName: cleanDisplayName(data.winnerName),
        loserName: cleanDisplayName(data.loserName),
        ratingChange: Number(data.ratingChange),
        playedAtMs,
      };
    });

  const matches = buildPlayerMatchHistory(uid, records).slice(0, pageSize);
  return { matches };
});

/** Return the signed-in player's achievement catalogue and server-owned unlocks. */
export const getMyAchievements = onCall(async (request) => {
  const uid = requireUid(request);
  const profileRef = db.doc(`profiles/${uid}`);
  const [profileSnap, unlockSnapshot] = await Promise.all([
    profileRef.get(),
    profileRef.collection("achievements").get(),
  ]);

  if (!profileSnap.exists) {
    throw new HttpsError("failed-precondition", "Je spelersprofiel is nog niet aangemaakt.");
  }

  const profile = profileSnap.data() ?? {};
  const unlockedById = new Map(unlockSnapshot.docs.map((doc) => [doc.id, doc]));
  const unlockedIds = new Set(unlockedById.keys());
  const progress = getAchievementProgress({
    wins: Number(profile.wins ?? 0),
    matchesPlayed: Number(profile.matchesPlayed ?? 0),
    winStreak: Number(profile.winStreak ?? 0),
    rating: Number(profile.rating ?? 1000),
  }, unlockedIds);

  return {
    achievements: progress.map((achievement) => {
      const unlock = unlockedById.get(achievement.id);
      const unlockedAt = unlock?.get("unlockedAt") as { toMillis?: () => number } | undefined;
      return {
        ...achievement,
        unlockedAtMs: unlockedAt && typeof unlockedAt.toMillis === "function"
          ? unlockedAt.toMillis()
          : null,
      };
    }),
    stats: {
      wins: Number(profile.wins ?? 0),
      matchesPlayed: Number(profile.matchesPlayed ?? 0),
      winStreak: Number(profile.winStreak ?? 0),
      rating: Number(profile.rating ?? 1000),
    },
  };
});

/** Validate an uploaded avatar in the caller's own Storage folder, then publish its path. */
export const setAvatarPath = onCall(async (request) => {
  const uid = requireUid(request);
  const expectedPath = `profilePhotos/${uid}/avatar`;
  const suppliedPath = String(request.data?.storagePath ?? "");

  if (suppliedPath !== expectedPath) {
    throw new HttpsError("permission-denied", "Je kunt alleen je eigen profielfoto instellen.");
  }

  const file = getStorage().bucket().file(expectedPath);
  let metadata: { size?: string | number; contentType?: string };
  try {
    const [fileMetadata] = await file.getMetadata();
    metadata = fileMetadata;
  } catch {
    throw new HttpsError("failed-precondition", "Upload eerst een profielfoto.");
  }

  const contentType = String(metadata.contentType ?? "");
  const size = Number(metadata.size ?? 0);
  if (!/^image\/(jpeg|png|webp)$/.test(contentType) || !Number.isSafeInteger(size) || size <= 0 || size > 5 * 1024 * 1024) {
    throw new HttpsError("invalid-argument", "De profielfoto moet JPEG, PNG of WebP zijn en maximaal 5 MB groot zijn.");
  }

  const profileRef = db.doc(`profiles/${uid}`);
  const publicRef = db.doc(`publicProfiles/${uid}`);
  await db.runTransaction(async (transaction) => {
    const [profileSnap, publicSnap] = await Promise.all([
      transaction.get(profileRef),
      transaction.get(publicRef),
    ]);
    if (!profileSnap.exists || !publicSnap.exists) {
      throw new HttpsError("failed-precondition", "Je spelersprofiel is nog niet aangemaakt.");
    }
    transaction.update(profileRef, {
      avatarPath: expectedPath,
      updatedAt: FieldValue.serverTimestamp(),
    });
    transaction.update(publicRef, {
      avatarPath: expectedPath,
      updatedAt: FieldValue.serverTimestamp(),
    });
  });

  return { avatarPath: expectedPath };
});

const tournamentCode = (value: unknown): string => {
  const id = String(value ?? "").trim();
  if (!/^[A-Za-z0-9_-]{10,40}$/.test(id)) {
    throw new HttpsError("invalid-argument", "Ongeldige toernooicode.");
  }
  return id;
};

const tournamentRoomCode = () =>
  Array.from({ length: 6 }, () => FRIEND_ALPHABET[randomInt(FRIEND_ALPHABET.length)]).join("");

/** Create a private tournament; only existing friends can join via its invite ID. */
export const createTournament = onCall(async (request) => {
  const uid = requireUid(request);
  const title = cleanDisplayName(request.data?.title, "Vriendentoernooi").slice(0, 32);
  const capacity = Number(request.data?.capacity);
  if (capacity !== 4 && capacity !== 8) {
    throw new HttpsError("invalid-argument", "Kies een toernooi met vier of acht spelers.");
  }

  const [profileSnap, publicSnap] = await Promise.all([
    db.doc(`profiles/${uid}`).get(),
    db.doc(`publicProfiles/${uid}`).get(),
  ]);
  const profile = profileSnap.data() ?? {};
  const publicProfile = publicSnap.data() ?? {};
  const displayName = cleanDisplayName(profile.displayName ?? publicProfile.displayName);
  const tournamentRef = db.collection("tournaments").doc();
  const inviteCode = tournamentRef.id;

  await tournamentRef.create({
    title,
    ownerUid: uid,
    capacity,
    participantUids: [uid],
    participants: {
      [uid]: {
        uid,
        displayName,
        rating: Number(profile.rating ?? 1000),
      },
    },
    status: "OPEN",
    rounds: [],
    winnerUid: null,
    createdAt: FieldValue.serverTimestamp(),
    updatedAt: FieldValue.serverTimestamp(),
  });

  return { tournamentId: tournamentRef.id, inviteCode, title, capacity };
});

/** Join a friend's open tournament. The backend verifies the friendship in both directions. */
export const joinTournament = onCall(async (request) => {
  const uid = requireUid(request);
  const id = tournamentCode(request.data?.tournamentId);
  const tournamentRef = db.collection("tournaments").doc(id);
  const profileRef = db.doc(`profiles/${uid}`);
  const [profileSnap, publicSnap] = await Promise.all([
    profileRef.get(),
    db.doc(`publicProfiles/${uid}`).get(),
  ]);
  const profile = profileSnap.data() ?? {};
  const displayName = cleanDisplayName(profile.displayName ?? publicSnap.get("displayName"));

  return db.runTransaction(async (transaction) => {
    const tournamentSnap = await transaction.get(tournamentRef);
    if (!tournamentSnap.exists) throw new HttpsError("not-found", "Dit toernooi bestaat niet.");
    const tournament = tournamentSnap.data() ?? {};
    const ownerUid = String(tournament.ownerUid ?? "");
    const participantUids = Array.isArray(tournament.participantUids)
      ? tournament.participantUids.filter((item): item is string => typeof item === "string")
      : [];

    if (participantUids.includes(uid)) {
      return { tournamentId: id, status: String(tournament.status ?? "OPEN"), joined: false };
    }
    if (tournament.status !== "OPEN") {
      throw new HttpsError("failed-precondition", "Dit toernooi is al vol of gestart.");
    }
    const capacity = Number(tournament.capacity);
    if ((capacity !== 4 && capacity !== 8) || participantUids.length >= capacity) {
      throw new HttpsError("failed-precondition", "Dit toernooi heeft geen vrije plaatsen meer.");
    }

    const friendshipRefs = [
      db.doc(`profiles/${ownerUid}/friends/${uid}`),
      db.doc(`profiles/${uid}/friends/${ownerUid}`),
    ];
    const friendships = await Promise.all(friendshipRefs.map((ref) => transaction.get(ref)));
    if (!friendships.some((snapshot) => snapshot.exists)) {
      throw new HttpsError("permission-denied", "Alleen vrienden van de organisator kunnen deelnemen.");
    }

    const participants = {
      ...(tournament.participants as Record<string, { uid: string; displayName: string; rating: number }> ?? {}),
      [uid]: { uid, displayName, rating: Number(profile.rating ?? 1000) },
    };
    const updatedUids = [...participantUids, uid];
    if (updatedUids.length < capacity) {
      transaction.update(tournamentRef, {
        participantUids: updatedUids,
        participants,
        updatedAt: FieldValue.serverTimestamp(),
      });
      return { tournamentId: id, status: "OPEN", joined: true };
    }

    // Shuffle the final seed list server-side before building the bracket.
    const seeds = [...updatedUids];
    for (let index = seeds.length - 1; index > 0; index -= 1) {
      const swapIndex = randomInt(index + 1);
      [seeds[index], seeds[swapIndex]] = [seeds[swapIndex], seeds[index]];
    }
    const bracket = buildTournamentBracket(seeds);
    const matchRefs = bracket.map(() => db.collection("tournamentMatches").doc());
    const roundCount = Math.log2(capacity);
    const rounds = Array.from({ length: roundCount }, (_, index) => ({
      round: index + 1,
      matchIds: bracket
        .map((match, matchIndex) => ({ match, id: matchRefs[matchIndex].id }))
        .filter((entry) => entry.match.round === index + 1)
        .map((entry) => entry.id),
    }));

    bracket.forEach((match, index) => {
      const player1Uid = match.playerUids[0] ?? null;
      const player2Uid = match.playerUids[1] ?? null;
      transaction.create(matchRefs[index], {
        tournamentId: id,
        round: match.round,
        slot: match.slot,
        player1Uid,
        player2Uid,
        playerUids: match.playerUids,
        status: match.playerUids.length === 2 ? "WAITING" : "WAITING_FOR_PLAYERS",
        roomCode: null,
        winnerUid: null,
        createdAt: FieldValue.serverTimestamp(),
        updatedAt: FieldValue.serverTimestamp(),
      });
    });

    transaction.update(tournamentRef, {
      participantUids: updatedUids,
      participants,
      status: "RUNNING",
      rounds,
      roundCount,
      startedAt: FieldValue.serverTimestamp(),
      updatedAt: FieldValue.serverTimestamp(),
    });
    return { tournamentId: id, status: "RUNNING", joined: true };
  });
});

/** List only tournaments in which the caller is a participant. */
export const getMyTournaments = onCall(async (request) => {
  const uid = requireUid(request);
  const snapshot = await db.collection("tournaments")
    .where("participantUids", "array-contains", uid)
    .limit(20)
    .get();

  return {
    tournaments: snapshot.docs.map((doc) => {
      const data = doc.data();
      const participants = data.participants as Record<string, { uid?: string; displayName?: string }> ?? {};
      return {
        tournamentId: doc.id,
        title: String(data.title ?? "Vriendentoernooi"),
        capacity: Number(data.capacity ?? 4),
        status: String(data.status ?? "OPEN"),
        participantCount: Array.isArray(data.participantUids) ? data.participantUids.length : 0,
        participants: Object.values(participants).map((player) => ({
          uid: String(player.uid ?? ""),
          displayName: cleanDisplayName(player.displayName),
        })),
        inviteCode: doc.id,
      };
    }),
  };
});

/** Load the bracket after verifying that the caller belongs to the tournament. */
export const getTournamentDetails = onCall(async (request) => {
  const uid = requireUid(request);
  const id = tournamentCode(request.data?.tournamentId);
  const tournamentRef = db.collection("tournaments").doc(id);
  const tournamentSnap = await tournamentRef.get();
  if (!tournamentSnap.exists) throw new HttpsError("not-found", "Dit toernooi bestaat niet.");
  const tournament = tournamentSnap.data() ?? {};
  const participantUids = Array.isArray(tournament.participantUids) ? tournament.participantUids : [];
  if (!participantUids.includes(uid)) {
    throw new HttpsError("permission-denied", "Je neemt niet deel aan dit toernooi.");
  }

  const participants = tournament.participants as Record<string, { uid?: string; displayName?: string }> ?? {};
  const rounds = Array.isArray(tournament.rounds) ? tournament.rounds as Array<{ round: number; matchIds: string[] }> : [];
  const matchIds = rounds.flatMap((round) => Array.isArray(round.matchIds) ? round.matchIds : []);
  const refs = matchIds.map((matchId) => db.collection("tournamentMatches").doc(matchId));
  const snapshots = refs.length ? await db.getAll(...refs) : [];
  const matches = snapshots.filter((snapshot) => snapshot.exists).map((snapshot) => {
    const data = snapshot.data() ?? {};
    const player1Uid = typeof data.player1Uid === "string" ? data.player1Uid : null;
    const player2Uid = typeof data.player2Uid === "string" ? data.player2Uid : null;
    const winnerUid = typeof data.winnerUid === "string" ? data.winnerUid : null;
    return {
      matchId: snapshot.id,
      round: Number(data.round ?? 1),
      slot: Number(data.slot ?? 0),
      player1Name: player1Uid ? cleanDisplayName(participants[player1Uid]?.displayName) : "Nog te bepalen",
      player2Name: player2Uid ? cleanDisplayName(participants[player2Uid]?.displayName) : "Nog te bepalen",
      status: String(data.status ?? "WAITING"),
      winnerName: winnerUid ? cleanDisplayName(participants[winnerUid]?.displayName) : null,
      roomCode: data.status === "PLAYING" ? String(data.roomCode ?? "") : null,
      canOpen: (data.status === "WAITING" || data.status === "PLAYING")
        && player1Uid !== null && player2Uid !== null
        && (player1Uid === uid || player2Uid === uid),
    };
  }).sort((a, b) => a.round - b.round || a.slot - b.slot);

  return {
    tournament: {
      tournamentId: id,
      inviteCode: id,
      title: String(tournament.title ?? "Vriendentoernooi"),
      capacity: Number(tournament.capacity ?? 4),
      status: String(tournament.status ?? "OPEN"),
      participantCount: participantUids.length,
      participants: Object.values(participants).map((player) => ({
        uid: String(player.uid ?? ""),
        displayName: cleanDisplayName(player.displayName),
      })),
      winnerName: tournament.winnerUid
        ? cleanDisplayName(participants[String(tournament.winnerUid)]?.displayName)
        : null,
    },
    matches,
  };
});

/** Create or reopen the online room for a specific tournament pairing. */
export const startTournamentMatch = onCall(async (request) => {
  const uid = requireUid(request);
  const id = tournamentCode(request.data?.tournamentId);
  const matchId = String(request.data?.matchId ?? "").trim();
  if (!/^[A-Za-z0-9]{15,40}$/.test(matchId)) {
    throw new HttpsError("invalid-argument", "Ongeldige toernoo wedstrijd.");
  }

  const tournamentRef = db.collection("tournaments").doc(id);
  const matchRef = db.collection("tournamentMatches").doc(matchId);
  const code = tournamentRoomCode();
  const roomRef = db.collection(ROOMS).doc(code);

  return db.runTransaction(async (transaction) => {
    const [tournamentSnap, matchSnap, roomSnap] = await Promise.all([
      transaction.get(tournamentRef), transaction.get(matchRef), transaction.get(roomRef),
    ]);
    if (!tournamentSnap.exists || !matchSnap.exists) {
      throw new HttpsError("not-found", "De toernooiwedstrijd bestaat niet.");
    }
    const tournament = tournamentSnap.data() ?? {};
    const match = matchSnap.data() ?? {};
    if (match.tournamentId !== id || tournament.status !== "RUNNING") {
      throw new HttpsError("failed-precondition", "Deze toernooiwedstrijd kan niet worden gestart.");
    }
    const players = Array.isArray(match.playerUids) ? match.playerUids : [];
    if (!players.includes(uid)) {
      throw new HttpsError("permission-denied", "Je bent geen speler in deze wedstrijd.");
    }
    if (match.status === "PLAYING" && typeof match.roomCode === "string") {
      return { roomCode: match.roomCode };
    }
    if (match.status !== "WAITING" || players.length !== 2) {
      throw new HttpsError("failed-precondition", "Deze wedstrijd wacht nog op eerdere rondes.");
    }
    if (roomSnap.exists) {
      throw new HttpsError("aborted", "Er is een kamercodebotsing opgetreden. Probeer opnieuw.");
    }

    const participantData = tournament.participants as Record<string, { displayName?: string }> ?? {};
    const hostUid = String(players[0]);
    const guestUid = String(players[1]);
    const hostName = cleanDisplayName(participantData[hostUid]?.displayName);
    const guestName = cleanDisplayName(participantData[guestUid]?.displayName);
    transaction.create(roomRef, {
      hostUid, hostName, guestUid, guestName,
      status: "READY",
      tournamentId: id,
      tournamentMatchId: matchId,
      createdAt: FieldValue.serverTimestamp(),
      updatedAt: FieldValue.serverTimestamp(),
    });
    transaction.update(matchRef, {
      status: "PLAYING",
      roomCode: code,
      updatedAt: FieldValue.serverTimestamp(),
    });
    return { roomCode: code };
  });
});

