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

export type AchievementStats = {
  wins: number;
  matchesPlayed: number;
  winStreak: number;
  rating: number;
};

export type AchievementProgress = {
  id: string;
  title: string;
  description: string;
  category: "wins" | "streak" | "matches" | "rating";
  target: number;
  progress: number;
  unlocked: boolean;
};

const ACHIEVEMENTS: ReadonlyArray<Omit<AchievementProgress, "progress" | "unlocked"> & {
  metric: keyof AchievementStats;
}> = [
  {
    id: "first_win",
    title: "Eerste overwinning",
    description: "Win je eerste online wedstrijd.",
    category: "wins",
    metric: "wins",
    target: 1,
  },
  {
    id: "ten_wins",
    title: "Tien overwinningen",
    description: "Win tien online wedstrijden.",
    category: "wins",
    metric: "wins",
    target: 10,
  },
  {
    id: "five_win_streak",
    title: "Vijf op rij",
    description: "Win vijf online wedstrijden achter elkaar.",
    category: "streak",
    metric: "winStreak",
    target: 5,
  },
  {
    id: "fifty_matches",
    title: "Vaste speler",
    description: "Speel vijftig online wedstrijden.",
    category: "matches",
    metric: "matchesPlayed",
    target: 50,
  },
  {
    id: "hundred_matches",
    title: "Skip-Bo legende",
    description: "Speel honderd online wedstrijden.",
    category: "matches",
    metric: "matchesPlayed",
    target: 100,
  },
  {
    id: "master_rating",
    title: "Meester van Skip-Bo",
    description: "Bereik 1.500 Elo-punten.",
    category: "rating",
    metric: "rating",
    target: 1500,
  },
];

/** Calculate server-owned achievement progress from committed player statistics. */
export function getAchievementProgress(
  stats: AchievementStats,
  unlockedIds: ReadonlySet<string> = new Set<string>()
): AchievementProgress[] {
  return ACHIEVEMENTS.map((achievement) => {
    const raw = stats[achievement.metric];
    const value = Number.isSafeInteger(raw) && raw > 0 ? raw : 0;
    const progress = Math.min(achievement.target, value);
    return {
      id: achievement.id,
      title: achievement.title,
      description: achievement.description,
      category: achievement.category,
      target: achievement.target,
      progress,
      unlocked: unlockedIds.has(achievement.id) || value >= achievement.target,
    };
  });
}

