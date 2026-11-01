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
