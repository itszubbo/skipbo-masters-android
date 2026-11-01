const test = require("node:test");
const assert = require("node:assert/strict");
const { drawToFive, setAsideCompletedBuild } = require("../lib/gameRules.js");

test("completed building piles stay set aside while draw pile still has cards", () => {
  const hand = [7];
  const draw = [1, 2, 3, 4, 5];
  const completed = Array.from({ length: 12 }, (_, i) => i);
  drawToFive(hand, draw, completed);
  assert.equal(hand.length, 5);
  assert.equal(draw.length, 1);
  assert.equal(completed.length, 12);
});

test("completed pile is recycled only when the ordinary draw pile is empty", () => {
  const hand = [99, 98, 97, 96];
  const draw = [];
  const completed = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11];
  drawToFive(hand, draw, completed);
  assert.equal(hand.length, 5);
  assert.equal(completed.length, 0);
  assert.equal(draw.length, 11);
  assert.equal(hand[4] >= 0 && hand[4] <= 11, true);
  assert.deepEqual([...hand.slice(4), ...draw].sort((a, b) => a - b), [0,1,2,3,4,5,6,7,8,9,10,11]);
});

test("completed pile preserves a Skip-Bo joker and clears the building pile", () => {
  const building = [1, 2, 0, 4, 5, 6, 7, 8, 9, 10, 11, 12];
  const completed = [];
  setAsideCompletedBuild(building, completed);
  assert.deepEqual(completed, [1, 2, 0, 4, 5, 6, 7, 8, 9, 10, 11, 12]);
  assert.deepEqual(building, []);
  assert.throws(() => setAsideCompletedBuild([1, 2], completed));
});

test("empty draw and completed piles do not loop or invent cards", () => {
  const hand = [3];
  const draw = [];
  const completed = [];
  drawToFive(hand, draw, completed);
  assert.deepEqual(hand, [3]);
});
