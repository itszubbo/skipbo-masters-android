const test = require("node:test");
const assert = require("node:assert/strict");
const { eloWinnerDelta, isValidFriendCode, preserveStoredDisplayName } = require("../lib/communityRules.js");

test("Elo starts with a symmetric 16-point change for equal ratings", () => {
  assert.equal(eloWinnerDelta(1000, 1000), 16);
});

test("upset win awards more points than a favorite win", () => {
  assert.ok(eloWinnerDelta(800, 1200) > eloWinnerDelta(1200, 800));
});

test("friend codes require the SKB prefix and six unambiguous characters", () => {
  assert.equal(isValidFriendCode("SKB-82K9QM"), true);
  assert.equal(isValidFriendCode("SKB-00K9QM"), false);
  assert.equal(isValidFriendCode("SKB-8K9QM"), false);
  assert.equal(isValidFriendCode("ABC-82K9QM"), false);
});

test("invalid Elo values are rejected", () => {
  assert.throws(() => eloWinnerDelta(-1, 1000));
  assert.throws(() => eloWinnerDelta(1000.5, 1000));
});

test("restoring an account preserves its server-side player name", () => {
  assert.equal(preserveStoredDisplayName("Original Name", "This device nickname"), "Original Name");
  assert.equal(preserveStoredDisplayName(null, " New   Player "), "New Player");
});

test("match history is scoped to the requested player and signs Elo changes", () => {
  const { buildPlayerMatchHistory } = require("../lib/communityRules.js");
  const rows = buildPlayerMatchHistory("player-a", [
    {
      matchId: "older-win", winnerUid: "player-a", loserUid: "player-b",
      winnerName: "A", loserName: "B", ratingChange: 18, playedAtMs: 100
    },
    {
      matchId: "newer-loss", winnerUid: "player-c", loserUid: "player-a",
      winnerName: "C", loserName: "A", ratingChange: 12, playedAtMs: 200
    },
    {
      matchId: "not-mine", winnerUid: "player-c", loserUid: "player-d",
      winnerName: "C", loserName: "D", ratingChange: 20, playedAtMs: 300
    }
  ]);

  assert.deepEqual(rows.map(row => row.matchId), ["newer-loss", "older-win"]);
  assert.equal(rows[0].opponentUid, "player-c");
  assert.equal(rows[0].opponentName, "C");
  assert.equal(rows[0].won, false);
  assert.equal(rows[0].ratingChange, -12);
  assert.equal(rows[1].opponentUid, "player-b");
  assert.equal(rows[1].opponentName, "B");
  assert.equal(rows[1].won, true);
  assert.equal(rows[1].ratingChange, 18);
});

test("match history rejects malformed rows and requires a player uid", () => {
  const { buildPlayerMatchHistory } = require("../lib/communityRules.js");
  assert.throws(() => buildPlayerMatchHistory("", []));
  const rows = buildPlayerMatchHistory("player-a", [
    {
      matchId: "bad-rating", winnerUid: "player-a", loserUid: "player-b",
      winnerName: "A", loserName: "B", ratingChange: -2, playedAtMs: 100
    },
    {
      matchId: "same-player", winnerUid: "player-a", loserUid: "player-a",
      winnerName: "A", loserName: "A", ratingChange: 2, playedAtMs: 100
    }
  ]);
  assert.deepEqual(rows, []);
});

test("achievement catalogue unlocks only when server statistics reach targets", () => {
  const { getAchievementProgress } = require("../lib/communityRules.js");
  const progress = getAchievementProgress({
    wins: 1,
    matchesPlayed: 12,
    winStreak: 3,
    rating: 1499
  });

  const byId = Object.fromEntries(progress.map(item => [item.id, item]));
  assert.equal(byId.first_win.unlocked, true);
  assert.equal(byId.ten_wins.unlocked, false);
  assert.equal(byId.five_win_streak.unlocked, false);
  assert.equal(byId.fifty_matches.unlocked, false);
  assert.equal(byId.hundred_matches.unlocked, false);
  assert.equal(byId.master_rating.unlocked, false);
  assert.equal(byId.master_rating.progress, 1499);
});

test("achievement progress is capped and previously unlocked trophies stay unlocked", () => {
  const { getAchievementProgress } = require("../lib/communityRules.js");
  const progress = getAchievementProgress({
    wins: 20,
    matchesPlayed: 120,
    winStreak: 8,
    rating: 1600
  }, new Set(["master_rating"]));

  const byId = Object.fromEntries(progress.map(item => [item.id, item]));
  assert.equal(byId.ten_wins.progress, 10);
  assert.equal(byId.hundred_matches.progress, 100);
  assert.equal(byId.five_win_streak.progress, 5);
  assert.equal(byId.master_rating.unlocked, true);
  assert.equal(byId.master_rating.progress, 1500);
});

test("invalid or negative achievement counters never create progress", () => {
  const { getAchievementProgress } = require("../lib/communityRules.js");
  const progress = getAchievementProgress({
    wins: -1,
    matchesPlayed: Number.NaN,
    winStreak: -5,
    rating: 999
  });
  assert.ok(progress.every(item => item.progress >= 0));
  assert.equal(progress.find(item => item.id === "first_win").unlocked, false);
});

test("four-player tournament creates two semifinals and one final", () => {
  const { buildTournamentBracket } = require("../lib/communityRules.js");
  const bracket = buildTournamentBracket(["a", "b", "c", "d"]);
  assert.deepEqual(
    bracket.map(({ round, slot, playerUids }) => ({ round, slot, playerUids })),
    [
      { round: 1, slot: 0, playerUids: ["a", "b"] },
      { round: 1, slot: 1, playerUids: ["c", "d"] },
      { round: 2, slot: 0, playerUids: [] }
    ]
  );
});

test("eight-player tournament creates a complete seven-match bracket", () => {
  const { buildTournamentBracket } = require("../lib/communityRules.js");
  const bracket = buildTournamentBracket(["a", "b", "c", "d", "e", "f", "g", "h"]);
  assert.equal(bracket.length, 7);
  assert.equal(bracket.filter(match => match.round === 1).length, 4);
  assert.equal(bracket.filter(match => match.round === 2).length, 2);
  assert.equal(bracket.filter(match => match.round === 3).length, 1);
  assert.deepEqual(bracket.slice(0, 4).map(match => match.playerUids), [
    ["a", "b"], ["c", "d"], ["e", "f"], ["g", "h"]
  ]);
});

test("tournament brackets reject invalid sizes and duplicate identities", () => {
  const { buildTournamentBracket } = require("../lib/communityRules.js");
  assert.throws(() => buildTournamentBracket(["a", "b", "c"]));
  assert.throws(() => buildTournamentBracket(["a", "b", "c", "a"]));
  assert.throws(() => buildTournamentBracket(["a", "b", "c", ""]));
});

test("daily missions advance only from confirmed match counters and award XP once", () => {
  const { updateDailyMissionProgress, getDailyMissionProgress } = require("../lib/communityRules.js");
  const first = updateDailyMissionProgress({}, true);
  assert.equal(first.matchesPlayed, 1);
  assert.equal(first.wins, 1);
  assert.deepEqual(new Set(first.newlyCompleted), new Set(["play_one_match", "win_one_match"]));
  assert.equal(first.xpAwarded, 80);

  const second = updateDailyMissionProgress({
    matchesPlayed: first.matchesPlayed,
    wins: first.wins,
    completedMissionIds: first.completedMissionIds
  }, false);
  assert.equal(second.matchesPlayed, 2);
  assert.equal(second.xpAwarded, 0);

  const third = updateDailyMissionProgress({
    matchesPlayed: second.matchesPlayed,
    wins: second.wins,
    completedMissionIds: second.completedMissionIds
  }, false);
  assert.equal(third.matchesPlayed, 3);
  assert.equal(third.xpAwarded, 70);
  assert.ok(getDailyMissionProgress(
    third.matchesPlayed,
    third.wins,
    new Set(third.completedMissionIds)
  ).every(mission => mission.completed || mission.id === "win_one_match"));
});

test("daily mission progress caps at targets and never creates duplicate completion rewards", () => {
  const { updateDailyMissionProgress } = require("../lib/communityRules.js");
  const existing = {
    matchesPlayed: 8,
    wins: 5,
    completedMissionIds: ["play_one_match", "win_one_match", "play_three_matches"]
  };
  const next = updateDailyMissionProgress(existing, true);
  assert.equal(next.xpAwarded, 0);
  assert.deepEqual(next.newlyCompleted, []);
  assert.equal(next.matchesPlayed, 9);
});

test("XP levels advance every 500 points", () => {
  const { getLevelProgress } = require("../lib/communityRules.js");
  assert.deepEqual(getLevelProgress(0), {
    level: 1, xp: 0, xpIntoLevel: 0, xpForNextLevel: 500
  });
  assert.deepEqual(getLevelProgress(1249), {
    level: 3, xp: 1249, xpIntoLevel: 249, xpForNextLevel: 500
  });
  assert.equal(getLevelProgress(-40).level, 1);
});

test("daily login streak increments only on consecutive UTC dates", () => {
  const { nextDailyStreak } = require("../lib/communityRules.js");
  assert.equal(nextDailyStreak(null, "2026-11-01", 0), 1);
  assert.equal(nextDailyStreak("2026-10-31", "2026-11-01", 6), 7);
  assert.equal(nextDailyStreak("2026-10-30", "2026-11-01", 4), 1);
  assert.equal(nextDailyStreak("2026-11-01", "2026-11-01", 7), 0);
  assert.equal(nextDailyStreak("invalid-old-date", "2026-11-01", 4), 1);
  assert.throws(() => nextDailyStreak("2026-11-01", "today", 1));
});



test("card themes unlock only at their required server-side level", () => {
  const { isCardThemeUnlocked } = require("../lib/communityRules.js");
  assert.equal(isCardThemeUnlocked("classic", 1), true);
  assert.equal(isCardThemeUnlocked("emerald", 1), false);
  assert.equal(isCardThemeUnlocked("emerald", 2), true);
  assert.equal(isCardThemeUnlocked("royal_gold", 3), false);
  assert.equal(isCardThemeUnlocked("royal_gold", 4), true);
  assert.equal(isCardThemeUnlocked("midnight", 7), true);
  assert.equal(isCardThemeUnlocked("unknown", 99), false);
  assert.equal(isCardThemeUnlocked("midnight", -1), false);
});


test("season progress advances for wins and losses and awards the reward only once", () => {
  const { updateSeasonProgress } = require("../lib/communityRules.js");
  let progress = {};
  let totalXp = 0;
  for (let i = 0; i < 9; i += 1) {
    const next = updateSeasonProgress(progress, i % 2 === 0);
    assert.equal(next.matchesPlayed, i + 1);
    assert.equal(next.rewardUnlocked, false);
    assert.equal(next.xpAwarded, 0);
    progress = next;
  }
  const tenth = updateSeasonProgress(progress, true);
  assert.equal(tenth.matchesPlayed, 10);
  assert.equal(tenth.rewardUnlocked, true);
  assert.equal(tenth.rewardClaimed, true);
  assert.equal(tenth.xpAwarded, 150);
  totalXp += tenth.xpAwarded;
  const eleventh = updateSeasonProgress(tenth, false);
  assert.equal(eleventh.rewardUnlocked, false);
  assert.equal(eleventh.xpAwarded, 0);
  assert.equal(totalXp, 150);
});

test("season progress sanitizes invalid counters and validates reward configuration", () => {
  const { updateSeasonProgress } = require("../lib/communityRules.js");
  const clean = updateSeasonProgress({ matchesPlayed: -8, wins: Number.NaN }, false);
  assert.equal(clean.matchesPlayed, 1);
  assert.equal(clean.wins, 0);
  assert.throws(() => updateSeasonProgress({}, true, 0, 150));
  assert.throws(() => updateSeasonProgress({}, true, 10, -1));
});

test("only safe achievement ids are accepted for profile badge selection", () => {
  const { isValidAchievementBadgeId } = require("../lib/communityRules.js");
  assert.equal(isValidAchievementBadgeId("first_win"), true);
  assert.equal(isValidAchievementBadgeId("master_rating"), true);
  assert.equal(isValidAchievementBadgeId("../first_win"), false);
  assert.equal(isValidAchievementBadgeId(""), false);
  assert.equal(isValidAchievementBadgeId("A".repeat(41)), false);
});


test("monthly season rank score prioritizes wins, then matches played", () => {
  const { seasonRankScore } = require("../lib/communityRules.js");
  assert.ok(seasonRankScore(2, 2) > seasonRankScore(1, 999));
  assert.ok(seasonRankScore(2, 9) > seasonRankScore(2, 4));
  assert.equal(seasonRankScore(0, 999999), 999999);
  assert.equal(seasonRankScore(0, 1000000), 999999);
});

test("monthly season rank score rejects invalid counters and unsafe values", () => {
  const { seasonRankScore } = require("../lib/communityRules.js");
  assert.throws(() => seasonRankScore(-1, 0));
  assert.throws(() => seasonRankScore(3, 2));
  assert.throws(() => seasonRankScore(1, 1.5));
  assert.throws(() => seasonRankScore(Number.MAX_SAFE_INTEGER, Number.MAX_SAFE_INTEGER));
});


test("personal-best win streak never decreases and follows the new current streak", () => {
  const { updateBestWinStreak } = require("../lib/communityRules.js");
  assert.equal(updateBestWinStreak(4, 5), 5);
  assert.equal(updateBestWinStreak(7, 2), 7);
  assert.equal(updateBestWinStreak(null, 3), 3);
  assert.equal(updateBestWinStreak(6, 0), 6);
});

test("personal-best win streak sanitizes malformed counters", () => {
  const { updateBestWinStreak } = require("../lib/communityRules.js");
  assert.equal(updateBestWinStreak(-4, 2), 2);
  assert.equal(updateBestWinStreak(3.5, 2), 2);
  assert.equal(updateBestWinStreak(Number.NaN, Number.NaN), 0);
  assert.equal(updateBestWinStreak(Number.MAX_SAFE_INTEGER + 1, 1), 1);
});
