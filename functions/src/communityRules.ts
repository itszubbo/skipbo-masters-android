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
  opponentUid: string;
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
        opponentUid: won ? record.loserUid : record.winnerUid,
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

export type TournamentBracketMatch = {
  round: number;
  slot: number;
  playerUids: string[];
};

/**
 * Create a single-elimination bracket for four or eight distinct players.
 * The caller chooses/shuffles the seed order before calling this helper.
 */
export function buildTournamentBracket(
  playerUids: readonly string[]
): TournamentBracketMatch[] {
  if (
    (playerUids.length !== 4 && playerUids.length !== 8) ||
    playerUids.some((uid) => typeof uid !== "string" || uid.trim().length === 0) ||
    new Set(playerUids).size !== playerUids.length
  ) {
    throw new Error("A tournament requires four or eight distinct player UIDs.");
  }

  const matches: TournamentBracketMatch[] = [];
  let matchCount = playerUids.length / 2;
  let round = 1;

  for (let slot = 0; slot < matchCount; slot += 1) {
    matches.push({
      round,
      slot,
      playerUids: [playerUids[slot * 2], playerUids[slot * 2 + 1]],
    });
  }

  while (matchCount > 1) {
    matchCount /= 2;
    round += 1;
    for (let slot = 0; slot < matchCount; slot += 1) {
      matches.push({ round, slot, playerUids: [] });
    }
  }

  return matches;
}

export type DailyMissionDefinition = {
  id: string;
  title: string;
  description: string;
  metric: "matches" | "wins";
  target: number;
  xpReward: number;
};

export type DailyMissionProgress = DailyMissionDefinition & {
  progress: number;
  completed: boolean;
};

export type DailyMissionUpdate = {
  matchesPlayed: number;
  wins: number;
  completedMissionIds: string[];
  newlyCompleted: string[];
  xpAwarded: number;
};

const DAILY_MISSIONS: readonly DailyMissionDefinition[] = [
  {
    id: "play_one_match",
    title: "Even opwarmen",
    description: "Speel één online wedstrijd.",
    metric: "matches",
    target: 1,
    xpReward: 30,
  },
  {
    id: "win_one_match",
    title: "Pak de winst",
    description: "Win één online wedstrijd.",
    metric: "wins",
    target: 1,
    xpReward: 50,
  },
  {
    id: "play_three_matches",
    title: "Fanatieke speler",
    description: "Speel drie online wedstrijden.",
    metric: "matches",
    target: 3,
    xpReward: 70,
  },
];

export function getDailyMissionProgress(
  matchesPlayed: number,
  wins: number,
  completedIds: ReadonlySet<string> = new Set<string>()
): DailyMissionProgress[] {
  const safeMatches = Number.isSafeInteger(matchesPlayed) && matchesPlayed > 0 ? matchesPlayed : 0;
  const safeWins = Number.isSafeInteger(wins) && wins > 0 ? wins : 0;
  return DAILY_MISSIONS.map((mission) => {
    const raw = mission.metric === "matches" ? safeMatches : safeWins;
    const progress = Math.min(mission.target, raw);
    return {
      ...mission,
      progress,
      completed: completedIds.has(mission.id) || raw >= mission.target,
    };
  });
}

/** Apply one server-confirmed online result to today's missions. */
export function updateDailyMissionProgress(
  existing: { matchesPlayed?: unknown; wins?: unknown; completedMissionIds?: unknown },
  playerWon: boolean
): DailyMissionUpdate {
  const previousMatches = Number(existing.matchesPlayed ?? 0);
  const previousWins = Number(existing.wins ?? 0);
  const matchesPlayed = Number.isSafeInteger(previousMatches) && previousMatches >= 0
    ? previousMatches + 1
    : 1;
  const wins = (Number.isSafeInteger(previousWins) && previousWins >= 0 ? previousWins : 0)
    + (playerWon ? 1 : 0);
  const priorIds = Array.isArray(existing.completedMissionIds)
    ? new Set(existing.completedMissionIds.filter((id): id is string => typeof id === "string"))
    : new Set<string>();
  const progress = getDailyMissionProgress(matchesPlayed, wins, priorIds);
  const newlyCompleted = progress
    .filter((mission) => mission.completed && !priorIds.has(mission.id))
    .map((mission) => mission.id);
  const completedMissionIds = [...new Set([...priorIds, ...newlyCompleted])];
  const rewardById = new Map(DAILY_MISSIONS.map((mission) => [mission.id, mission.xpReward]));
  const xpAwarded = newlyCompleted.reduce((total, id) => total + (rewardById.get(id) ?? 0), 0);

  return { matchesPlayed, wins, completedMissionIds, newlyCompleted, xpAwarded };
}

export type LevelProgress = {
  level: number;
  xp: number;
  xpIntoLevel: number;
  xpForNextLevel: number;
};

export function getLevelProgress(totalXp: number): LevelProgress {
  const xp = Number.isSafeInteger(totalXp) && totalXp > 0 ? totalXp : 0;
  const level = Math.floor(xp / 500) + 1;
  return {
    level,
    xp,
    xpIntoLevel: xp % 500,
    xpForNextLevel: 500,
  };
}

export function nextDailyStreak(
  lastClaimedDate: string | null,
  today: string,
  previousStreak: number
): number {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(today)) throw new Error("Today must be an ISO UTC date.");
  if (!lastClaimedDate || !/^\d{4}-\d{2}-\d{2}$/.test(lastClaimedDate)) return 1;
  if (lastClaimedDate === today) return 0;
  const last = Date.parse(`${lastClaimedDate}T00:00:00.000Z`);
  const current = Date.parse(`${today}T00:00:00.000Z`);
  if (!Number.isFinite(last) || !Number.isFinite(current)) return 1;
  const dayDifference = Math.round((current - last) / 86400000);
  const safeStreak = Number.isSafeInteger(previousStreak) && previousStreak > 0
    ? previousStreak
    : 0;
  return dayDifference === 1 ? safeStreak + 1 : 1;
}



export type CardThemeId = "classic" | "emerald" | "royal_gold" | "midnight";

const CARD_THEME_LEVELS: Readonly<Record<CardThemeId, number>> = {
  classic: 1,
  emerald: 2,
  royal_gold: 4,
  midnight: 7,
};

/** Check whether a player has reached the server-owned unlock level for a card back. */
export function isCardThemeUnlocked(themeId: string, level: number): boolean {
  if (!Number.isSafeInteger(level) || level < 1 || !(themeId in CARD_THEME_LEVELS)) {
    return false;
  }
  return level >= CARD_THEME_LEVELS[themeId as CardThemeId];
}


export type SeasonProgressUpdate = {
  matchesPlayed: number;
  wins: number;
  rewardClaimed: boolean;
  rewardUnlocked: boolean;
  xpAwarded: number;
};

/** Advance the current UTC season only from a server-confirmed match result. */
export function updateSeasonProgress(
  existing: { matchesPlayed?: unknown; wins?: unknown; rewardClaimed?: unknown },
  playerWon: boolean,
  rewardTarget = 10,
  rewardXp = 150
): SeasonProgressUpdate {
  if (!Number.isSafeInteger(rewardTarget) || rewardTarget < 1
      || !Number.isSafeInteger(rewardXp) || rewardXp < 0) {
    throw new Error("Invalid season reward configuration.");
  }
  const previousMatches = Number(existing.matchesPlayed ?? 0);
  const previousWins = Number(existing.wins ?? 0);
  const matchesPlayed = (Number.isSafeInteger(previousMatches) && previousMatches >= 0 ? previousMatches : 0) + 1;
  const wins = (Number.isSafeInteger(previousWins) && previousWins >= 0 ? previousWins : 0) + (playerWon ? 1 : 0);
  const alreadyClaimed = existing.rewardClaimed === true;
  const rewardUnlocked = !alreadyClaimed && matchesPlayed >= rewardTarget;
  return {
    matchesPlayed,
    wins,
    rewardClaimed: alreadyClaimed || rewardUnlocked,
    rewardUnlocked,
    xpAwarded: rewardUnlocked ? rewardXp : 0,
  };
}


/**
 * Build a sortable monthly-season score. Wins are the primary ranking
 * criterion; total matches break ties. The match component is capped so a
 * very active account can never overtake a player with one more win.
 */
export function seasonRankScore(wins: number, matchesPlayed: number): number {
  if (!Number.isSafeInteger(wins) || wins < 0 ||
      !Number.isSafeInteger(matchesPlayed) || matchesPlayed < wins) {
    throw new Error("Season counters must be non-negative safe integers and wins cannot exceed matches.");
  }
  const score = wins * 1_000_000 + Math.min(matchesPlayed, 999_999);
  if (!Number.isSafeInteger(score)) throw new Error("Season rank score is outside the safe integer range.");
  return score;
}

export function isValidAchievementBadgeId(value: string): boolean {
  return /^[a-z0-9_]{1,40}$/.test(value);
}
