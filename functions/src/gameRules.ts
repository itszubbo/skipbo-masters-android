import { randomInt } from "node:crypto";

/** Return a shuffled copy; the source array is left untouched. */
export function shuffleCards(cards: number[]): number[] {
  const result = [...cards];
  for (let i = result.length - 1; i > 0; i -= 1) {
    const j = randomInt(i + 1);
    [result[i], result[j]] = [result[j], result[i]];
  }
  return result;
}

/** Recycle completed piles only when the ordinary draw pile becomes empty. */
export function drawToFive(hand: number[], drawPile: number[], completed: number[]): void {
  while (hand.length < 5) {
    if (drawPile.length === 0 && completed.length > 0) {
      drawPile.push(...shuffleCards(completed));
      completed.length = 0;
    }
    if (drawPile.length === 0) break;
    const card = drawPile.pop();
    if (card !== undefined) hand.push(card);
  }
}

/** Move an exactly twelve-card building pile to the completed pile. */
export function setAsideCompletedBuild(buildingPile: number[], completed: number[]): void {
  if (buildingPile.length !== 12) {
    throw new Error("Only a twelve-card building pile can be completed.");
  }
  completed.push(...buildingPile);
  buildingPile.length = 0;
}
