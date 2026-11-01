/** Elo change for the winner in a two-player match. The loser loses the same amount. */
export function eloWinnerDelta(winnerRating: number, loserRating: number): number {
  if (!Number.isSafeInteger(winnerRating) || !Number.isSafeInteger(loserRating)
      || winnerRating < 0 || loserRating < 0) {
    throw new Error("Ratings must be non-negative safe integers.");
  }
  const expected = 1 / (1 + Math.pow(10, (loserRating - winnerRating) / 400));
  return Math.round(32 * (1 - expected));
}

export function isValidFriendCode(value: string): boolean {
  return /^SKB-[A-HJ-NP-Z2-9]{6}$/.test(value);
}

/** Keep an existing server-side player name when a device signs back in. */
export function preserveStoredDisplayName(storedName: unknown, incomingName: string): string {
  if (typeof storedName === "string" && storedName.trim().length > 0) {
    return storedName.trim().replace(/\s+/g, " ").slice(0, 24);
  }
  return incomingName.trim().replace(/\s+/g, " ").slice(0, 24) || "Skip-Bo speler";
}

export type MatchHistoryRecord = {
  matchId: string;
  winnerUid: string;
  loserUid: string;
  winnerName: string;
  loserName: string;
  ratingChange: number;
  playedAtMs: number;
};

export type PlayerMatchHistoryItem = {
  matchId: string;
  opponentName: string;
  won: boolean;
  ratingChange: number;
  playedAtMs: number;
};

/** Convert private match-result records to the safe view for one participant. */
export function buildPlayerMatchHistory(
  uid: string,
  records: readonly MatchHistoryRecord[]
): PlayerMatchHistoryItem[] {
  if (!uid.trim()) throw new Error("A player uid is required.");

  return records
    .filter((record) =>
      record.matchId.length > 0 &&
      record.winnerUid !== record.loserUid &&
      (record.winnerUid === uid || record.loserUid === uid) &&
      Number.isSafeInteger(record.ratingChange) &&
      record.ratingChange >= 0 &&
      Number.isFinite(record.playedAtMs)
    )
    .map((record) => {
      const won = record.winnerUid === uid;
      return {
        matchId: record.matchId,
        opponentName: won ? record.loserName : record.winnerName,
        won,
        ratingChange: won ? record.ratingChange : -record.ratingChange,
        playedAtMs: record.playedAtMs,
      };
    })
    .sort((a, b) =>
      b.playedAtMs - a.playedAtMs || a.matchId.localeCompare(b.matchId)
    );
}

