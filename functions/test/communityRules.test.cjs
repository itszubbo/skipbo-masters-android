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
