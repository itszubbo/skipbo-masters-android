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
  assert.equal(rows[0].opponentName, "C");
  assert.equal(rows[0].won, false);
  assert.equal(rows[0].ratingChange, -12);
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

