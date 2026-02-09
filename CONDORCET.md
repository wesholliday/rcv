# Condorcet Winner Election Mode

This adds a new "Condorcet" option to Winner Election Mode that determines
the winner using head-to-head comparisons between all pairs of candidates,
rather than the round-based elimination process used by Instant Runoff Voting.

## Algorithm

The winner is determined by a 4-step cascade:

1. **Condorcet winner**: If one candidate beats every other candidate
   head-to-head, they win immediately.

2. **Most head-to-head wins**: If no Condorcet winner exists, the candidate
   with the most pairwise wins is elected. A pairwise tie counts as half a
   win for each candidate. (Internally, wins are tracked as doubled integers
   — win=2, tie=1, loss=0 — to avoid floating-point comparison.)

3. **Smallest loss margin**: If multiple candidates tie for most wins, the
   one whose smallest loss is smallest wins.

4. **Configured tiebreak**: If still tied, falls back to the configured
   tiebreak mode (restricted to "Random" for Condorcet).

This is equivalent to the
[Most Wins, Smallest Loss (MWSL)](https://pref-voting.readthedocs.io/en/latest/margin_based_methods.html#most-wins-smallest-loss)
method from the voting theory literature.

## Ballot interpretation

For each pair of candidates (A, B) on a ballot:
- If both are ranked, the one with the lower rank number is preferred.
- If both are ranked at the same position (overvote), neither is preferred
  (counted as a tie for that pair).
- If neither is ranked, no preference is recorded.
- If one is ranked and the other is not, the ranked candidate is preferred
  **unless** there is a skipped rank number that is lower than the ranked
  candidate's rank number (e.g., if a voter ranks A in 1st, skips the 2nd
  rank, ranks C in 3rd, and ranks no one else, this does **not** count as
  a vote for C over B in the head-to-head comparison between B and C; but
  it does count as a vote for A over C in the head-to-head comparison
  between A and C).

The "Condorcet: Count All Ranked Candidates Above Unranked" config option
(`condorcetCountAllRankedOverUnranked`) overrides the skip-rank rule: when
enabled, all ranked candidates are treated as preferred above all unranked
candidates regardless of skipped ranks.

## Output format

Condorcet produces its own report format (not the round-based RCV format):

**CSV**: Contest Information, Contest Summary, Head-to-Head Results table
(one row per pair showing support counts, margin, tied/neither-ranked
counts), Candidate Summary (wins/losses/ties), and Best Head-to-Head
Record (candidates with the most wins and their smallest loss margin).

**JSON**: Equivalent structured data with config, summary, pairwise array,
candidateSummary array, bestHeadToHeadRecord array, and winner.

## Configuration

- `winnerElectionMode`: `"condorcet"`
- `condorcetCountAllRankedOverUnranked`: `true`/`false` (default `false`)
- `numberOfWinners`: must be `1`
- `tiebreakMode`: must be `"random"` (with a `randomSeed`)
- `batchElimination`, `continueUntilTwoCandidatesRemain`,
  `stopTabulationEarlyAfterRound`: must not be set/enabled
- `tabulateByPrecinct`, `tabulateByBatch`: must not be enabled

Voter error rules (overvote rule, max skipped ranks) are not applicable
and hence are disabled in the GUI when Condorcet is selected.

## Files

New:
- `CondorcetTabulator.java` — all Condorcet pairwise logic and result record

Modified:
- `Tabulator.java` — CONDORCET enum value, tabulateCondorcet() entry point
- `ContestConfig.java` — isCondorcetEnabled(), 7 validation rules
- `RawContestConfig.java` — condorcetCountAllRankedOverUnranked field
- `OutputWriter.java` — Condorcet-specific CSV/JSON report generation
- `GuiConfigController.java` — Condorcet GUI behavior (disables
  inapplicable options, restricts tiebreak to Random)
- `GuiConfigLayout.fxml` — checkbox for Count All Ranked Over Unranked
- `hints_winning_rules.txt` — Condorcet description and tiebreak note
- `config_file_documentation.txt` — condorcet and new config field docs
- `TabulatorTests.java` — 8 Condorcet test methods

Test data (8 new directories under test_data/):
- condorcet_test — basic Condorcet winner
- condorcet_cycle_test — cycle resolved by smallest loss margin
- condorcet_tiebreak_test — symmetric cycle falls back to random tiebreak
- condorcet_skip_test — skipped ranks affect pairwise preferences
- condorcet_overvote_test — overvotes treated as tied
- condorcet_two_candidate_test — minimal 2-candidate case
- condorcet_count_all_ranked_test — override skip rule changes outcome
- condorcet_invalid_params_test — validation rejects invalid combinations
