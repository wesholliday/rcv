/*
 * RCTab
 * Copyright (c) 2017-2023 Bright Spots Developers.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * Purpose: Condorcet voting method implementation.
 * If a Condorcet winner exists (beats all others head-to-head), they are elected.
 * Otherwise, the candidate with the most head-to-head wins is elected (a tie counts as
 * half a win). If multiple candidates tie for the most head-to-head wins, the one whose
 * smallest loss margin is the smallest is elected. If still tied, fall back to the
 * configured tiebreak mode.
 *
 * Ballot interpretation rule for skipped ranks: A voter is considered to have ranked
 * candidate A above candidate B if (i) A has a lower numerical rank than B, or (ii) A is
 * ranked and B is unranked, provided the voter has not skipped any rank with a lower
 * numerical value than the rank assigned to A.
 *
 * Design: Static utility class called from Tabulator.tabulateCondorcet().
 * Conditions: During tabulation when winnerElectionMode is "condorcet".
 * Version history: see https://github.com/BrightSpots/rcv.
 */

package network.brightspots.rcv;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javafx.util.Pair;
import network.brightspots.rcv.Tabulator.TabulationAbortedException;

final class CondorcetTabulator {

  private CondorcetTabulator() {}

  /**
   * Holds all pairwise comparison data and the winner for Condorcet tabulation.
   *
   * @param winner the name of the winning candidate
   * @param candidates sorted list of candidate names
   * @param pref pref[i][j] = voters ranking candidate i above candidate j
   * @param tied tied[i][j] = voters with both candidates ranked at the same position
   * @param oneRankedNoPreference one candidate ranked but no preference counted (skip rules)
   * @param neitherRanked neither candidate was ranked by the voter
   * @param margins margins[i][j] = pref[i][j] - pref[j][i]
   * @param wins wins[i] = doubled head-to-head win score (win=2, tie=1, loss=0)
   * @param wasDecidedViaTieBreak true if the winner was determined by the configured tiebreak
   */
  record CondorcetResult(
      String winner,
      List<String> candidates,
      int[][] pref,
      int[][] tied,
      int[][] oneRankedNoPreference,
      int[][] neitherRanked,
      int[][] margins,
      int[] wins,
      boolean wasDecidedViaTieBreak) {}

  /**
   * Determine the winner using the Condorcet method.
   *
   * @param castVoteRecords all ballots
   * @param candidateNames all candidate names (including excluded, UWI, overvote labels)
   * @param config contest configuration
   * @return a CondorcetResult containing all pairwise data and the winner
   * @throws TabulationAbortedException if tabulation cannot proceed
   */
  static CondorcetResult determineWinner(
      List<CastVoteRecord> castVoteRecords,
      Set<String> candidateNames,
      ContestConfig config)
      throws TabulationAbortedException {

    // Build the list of active candidates (excluding special entries and excluded candidates)
    List<String> candidates = new ArrayList<>();
    for (String name : candidateNames) {
      if (!config.candidateIsExcluded(name)
          && !name.equals(Tabulator.EXPLICIT_OVERVOTE_LABEL)
          && !name.equals(Tabulator.UNDECLARED_WRITE_IN_OUTPUT_LABEL)) {
        candidates.add(name);
      }
    }
    candidates.sort(String::compareTo);
    int n = candidates.size();

    // Build index lookup
    Map<String, Integer> candidateIndex = new HashMap<>();
    for (int i = 0; i < n; i++) {
      candidateIndex.put(candidates.get(i), i);
    }

    // Compute pairwise preference counts and category breakdowns
    // pref[i][j] = number of voters who rank candidate i above candidate j
    int[][] pref = new int[n][n];
    int[][] tied = new int[n][n];
    int[][] oneRankedNoPreference = new int[n][n];
    int[][] neitherRanked = new int[n][n];

    for (CastVoteRecord cvr : castVoteRecords) {
      processBallotPairwise(
          cvr, candidates, candidateIndex, pref, tied, oneRankedNoPreference, neitherRanked,
          config);
    }

    // Compute margins: margin[i][j] = pref[i][j] - pref[j][i]
    int[][] margins = new int[n][n];
    for (int i = 0; i < n; i++) {
      for (int j = 0; j < n; j++) {
        margins[i][j] = pref[i][j] - pref[j][i];
      }
    }

    logPairwiseResults(candidates, pref, margins);

    // Step 1: Check for a Condorcet winner (beats all others head-to-head)
    for (int i = 0; i < n; i++) {
      boolean isCondorcetWinner = true;
      for (int j = 0; j < n; j++) {
        if (i != j && margins[i][j] <= 0) {
          isCondorcetWinner = false;
          break;
        }
      }
      if (isCondorcetWinner) {
        Logger.info(
            "Condorcet winner: \"%s\" beats all other candidates head-to-head.",
            candidates.get(i));
        int[] wins = computeWins(n, margins);
        return new CondorcetResult(
            candidates.get(i), candidates, pref, tied, oneRankedNoPreference, neitherRanked,
            margins, wins, false);
      }
    }

    Logger.info("No Condorcet winner exists. Using head-to-head win count.");

    // Step 2: Count head-to-head wins using doubled scores (win=2, tie=1, loss=0)
    int[] wins = computeWins(n, margins);

    for (int i = 0; i < n; i++) {
      Logger.info(
          "Candidate \"%s\" has %.1f head-to-head win(s).",
          candidates.get(i), wins[i] / 2.0);
    }

    // Find candidates with the most wins
    int maxWins = 0;
    for (int w : wins) {
      if (w > maxWins) {
        maxWins = w;
      }
    }

    List<Integer> topCandidates = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      if (wins[i] == maxWins) {
        topCandidates.add(i);
      }
    }

    if (topCandidates.size() == 1) {
      String winner = candidates.get(topCandidates.get(0));
      Logger.info(
          "Candidate \"%s\" wins with the most head-to-head wins (%.1f).",
          winner, maxWins / 2.0);
      return new CondorcetResult(
          winner, candidates, pref, tied, oneRankedNoPreference, neitherRanked, margins,
          wins, false);
    }

    // Step 3: Tie for most wins — use smallest loss margin tiebreaker
    Logger.info(
        "Tie for most head-to-head wins (%.1f). Applying smallest loss margin tiebreaker.",
        maxWins / 2.0);

    // For each tied candidate, find their smallest loss margin (across all opponents)
    int bestSmallestLoss = Integer.MAX_VALUE;
    List<Integer> smallestLossWinners = new ArrayList<>();

    for (int idx : topCandidates) {
      int smallestLoss = Integer.MAX_VALUE;
      for (int j = 0; j < n; j++) {
        if (idx != j && margins[idx][j] < 0) {
          int lossMargin = -margins[idx][j];
          if (lossMargin < smallestLoss) {
            smallestLoss = lossMargin;
          }
        }
      }
      Logger.info(
          "Candidate \"%s\" smallest loss margin: %s.",
          candidates.get(idx),
          smallestLoss == Integer.MAX_VALUE ? "none (no losses)" : smallestLoss);

      if (smallestLoss < bestSmallestLoss) {
        bestSmallestLoss = smallestLoss;
        smallestLossWinners.clear();
        smallestLossWinners.add(idx);
      } else if (smallestLoss == bestSmallestLoss) {
        smallestLossWinners.add(idx);
      }
    }

    if (smallestLossWinners.size() == 1) {
      String winner = candidates.get(smallestLossWinners.get(0));
      Logger.info(
          "Candidate \"%s\" wins by smallest loss margin tiebreaker (margin: %d).",
          winner, bestSmallestLoss);
      return new CondorcetResult(
          winner, candidates, pref, tied, oneRankedNoPreference, neitherRanked, margins,
          wins, false);
    }

    // Step 4: Still tied — fall back to configured tiebreak mode
    Logger.info(
        "Smallest loss margin tiebreaker resulted in a tie. "
            + "Falling back to configured tiebreak mode.");

    LinkedList<String> tiedCandidateNames = new LinkedList<>();
    for (int idx : smallestLossWinners) {
      tiedCandidateNames.add(candidates.get(idx));
    }

    Tiebreak tiebreak =
        new Tiebreak(
            true,
            tiedCandidateNames,
            config.getTiebreakMode(),
            1, // round number (Condorcet uses a single round)
            BigDecimal.ZERO, // vote tally (not meaningful for Condorcet tiebreak)
            new Tabulator.RoundTallies(),
            config.getCandidatePermutation());

    String winner = tiebreak.selectCandidate();
    Logger.info(
        "Candidate \"%s\" won the final tiebreak. %s", winner, tiebreak.getExplanation());
    return new CondorcetResult(
        winner, candidates, pref, tied, oneRankedNoPreference, neitherRanked, margins,
        wins, true);
  }

  /**
   * Compute doubled win scores: win=2, tie=1, loss=0. Using integers avoids
   * floating-point equality comparisons while still handling half-point ties.
   */
  private static int[] computeWins(int n, int[][] margins) {
    int[] wins = new int[n];
    for (int i = 0; i < n; i++) {
      for (int j = i + 1; j < n; j++) {
        if (margins[i][j] > 0) {
          wins[i] += 2;
        } else if (margins[i][j] == 0) {
          wins[i] += 1;
          wins[j] += 1;
        } else {
          wins[j] += 2;
        }
      }
    }
    return wins;
  }

  /**
   * Process a single ballot to update the pairwise preference matrix and category arrays.
   *
   * <p>For each pair of candidates (A, B), determines whether the voter ranks A above B, B above
   * A, or neither, according to the Condorcet ballot interpretation rules. Also tracks:
   * tied (both ranked at same position), oneRankedNoPreference (one ranked but skip rules
   * prevent counting), and neitherRanked (neither candidate ranked).
   */
  private static void processBallotPairwise(
      CastVoteRecord cvr,
      List<String> candidates,
      Map<String, Integer> candidateIndex,
      int[][] pref,
      int[][] tied,
      int[][] oneRankedNoPreference,
      int[][] neitherRanked,
      ContestConfig config) {

    // Build a map from candidate name to their rank on this ballot.
    // For candidates ranked at the same rank (overvote), both get that rank.
    // If a candidate appears at multiple ranks (duplicate), keep the first (lowest) rank.
    Map<String, Integer> candidateToRank = new HashMap<>();
    Set<Integer> usedRanks = new HashSet<>();

    for (Pair<Integer, CandidatesAtRanking> rankPair : cvr.candidateRankings) {
      int rank = rankPair.getKey();
      CandidatesAtRanking candidatesAtRank = rankPair.getValue();
      usedRanks.add(rank);

      for (String rawCandidate : candidatesAtRank) {
        // Skip the explicit overvote label
        if (rawCandidate.equals(Tabulator.EXPLICIT_OVERVOTE_LABEL)) {
          continue;
        }

        // Resolve the candidate name (handles aliases)
        String candidateName = config.getNameForCandidate(rawCandidate);
        if (candidateName == null || !candidateIndex.containsKey(candidateName)) {
          continue;
        }

        // Keep first (lowest/best) rank if candidate appears multiple times
        if (!candidateToRank.containsKey(candidateName)) {
          candidateToRank.put(candidateName, rank);
        }
      }
    }

    // For each ranked candidate, determine if any rank below it was skipped.
    // "Skipped any rank with a lower numerical value than the rank assigned to the candidate."
    Map<String, Boolean> hasSkipBelow = new HashMap<>();
    for (var entry : candidateToRank.entrySet()) {
      int rank = entry.getValue();
      boolean skipped = false;
      for (int r = 1; r < rank; r++) {
        if (!usedRanks.contains(r)) {
          skipped = true;
          break;
        }
      }
      hasSkipBelow.put(entry.getKey(), skipped);
    }

    // Determine pairwise preferences and track categories
    int n = candidates.size();
    for (int i = 0; i < n; i++) {
      String candA = candidates.get(i);
      Integer rankA = candidateToRank.get(candA);

      for (int j = i + 1; j < n; j++) {
        String candB = candidates.get(j);
        Integer rankB = candidateToRank.get(candB);

        if (rankA != null && rankB != null) {
          // Condition (i): both ranked — lower numerical rank is preferred
          if (rankA < rankB) {
            pref[i][j]++;
          } else if (rankB < rankA) {
            pref[j][i]++;
          } else {
            // Same rank (overvote at same rank): no preference, treat as tied
            tied[i][j]++;
            tied[j][i]++;
          }
        } else if (rankA != null && rankB == null) {
          // Condition (ii): A ranked, B not ranked — A preferred if no skip below A's rank
          // (or always preferred if condorcetCountAllRankedOverUnranked is enabled)
          if (config.isCondorcetCountAllRankedOverUnrankedEnabled()
              || !hasSkipBelow.get(candA)) {
            pref[i][j]++;
          } else {
            oneRankedNoPreference[i][j]++;
            oneRankedNoPreference[j][i]++;
          }
        } else if (rankA == null && rankB != null) {
          // Condition (ii): B ranked, A not ranked — B preferred if no skip below B's rank
          // (or always preferred if condorcetCountAllRankedOverUnranked is enabled)
          if (config.isCondorcetCountAllRankedOverUnrankedEnabled()
              || !hasSkipBelow.get(candB)) {
            pref[j][i]++;
          } else {
            oneRankedNoPreference[i][j]++;
            oneRankedNoPreference[j][i]++;
          }
        } else {
          // Both unranked: no preference
          neitherRanked[i][j]++;
          neitherRanked[j][i]++;
        }
      }
    }
  }

  private static void logPairwiseResults(List<String> candidates, int[][] pref, int[][] margins) {
    Logger.info("Pairwise comparison results:");
    for (int i = 0; i < candidates.size(); i++) {
      for (int j = i + 1; j < candidates.size(); j++) {
        String result;
        if (margins[i][j] > 0) {
          result =
              String.format(
                  "\"%s\" defeats \"%s\" %d to %d (margin: %d)",
                  candidates.get(i), candidates.get(j), pref[i][j], pref[j][i], margins[i][j]);
        } else if (margins[i][j] < 0) {
          result =
              String.format(
                  "\"%s\" defeats \"%s\" %d to %d (margin: %d)",
                  candidates.get(j), candidates.get(i), pref[j][i], pref[i][j], -margins[i][j]);
        } else {
          result =
              String.format(
                  "\"%s\" ties \"%s\" %d to %d",
                  candidates.get(i), candidates.get(j), pref[i][j], pref[j][i]);
        }
        Logger.info(result);
      }
    }
  }
}
