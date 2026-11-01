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
