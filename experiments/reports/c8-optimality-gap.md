# C8 production optimality gap

30 seeded C7 days per scenario. Values: daily mean [day-bootstrap 95% CI]. Saving is residual-adjusted ratio settlement. Money is KRW. Delay is minutes.

| Scenario | Policy | Match % | Matched/day | Mean saving % | Total saving/day KRW | Delay min |
|---|---|---:|---:|---:|---:|---:|
| base | PROD | 61.00 [59.17, 62.83] | 61.00 [59.17, 62.83] | 28.71 [27.19, 30.24] | 490737.40 [459102.37, 522361.87] | 3.79 [3.64, 3.94] |
| base | ROUND-OPT | 61.03 [59.20, 62.87] | 61.03 [59.20, 62.87] | 28.70 [27.19, 30.21] | 490777.47 [459324.82, 522323.25] | 3.81 [3.65, 3.96] |
| base | ROUND-OPT-SAVING | 60.00 [58.33, 61.77] | 60.00 [58.33, 61.77] | 29.53 [28.00, 31.09] | 493883.67 [463317.69, 525447.72] | 3.72 [3.56, 3.90] |
| base | HINDSIGHT | 81.70 [80.47, 82.97] | 81.70 [80.47, 82.97] | 31.04 [29.87, 32.18] | 748599.33 [710285.65, 784324.23] | 3.74 [3.60, 3.88] |
| n50_d15 | PROD | 47.73 [45.60, 49.80] | 23.87 [22.80, 24.90] | 27.26 [25.41, 29.13] | 179621.77 [162678.09, 197148.28] | 3.65 [3.43, 3.88] |
| n50_d15 | ROUND-OPT | 47.73 [45.60, 49.80] | 23.87 [22.80, 24.90] | 27.26 [25.41, 29.13] | 179621.77 [162678.09, 197148.28] | 3.65 [3.43, 3.88] |
| n50_d15 | ROUND-OPT-SAVING | 47.27 [45.07, 49.40] | 23.63 [22.53, 24.70] | 27.96 [26.16, 29.80] | 184243.90 [166646.10, 202161.03] | 3.72 [3.46, 3.99] |
| n50_d15 | HINDSIGHT | 63.53 [61.33, 65.80] | 31.77 [30.67, 32.90] | 29.00 [27.56, 30.38] | 270538.40 [255833.35, 284101.28] | 3.63 [3.43, 3.84] |
| participation150 | PROD | 68.60 [67.33, 69.91] | 102.90 [101.00, 104.87] | 30.41 [29.38, 31.45] | 907216.57 [861685.81, 953366.70] | 3.92 [3.79, 4.06] |
| participation150 | ROUND-OPT | 68.58 [67.31, 69.89] | 102.87 [100.97, 104.83] | 30.35 [29.33, 31.40] | 905509.40 [860685.19, 951257.73] | 3.92 [3.79, 4.06] |
| participation150 | ROUND-OPT-SAVING | 68.31 [66.91, 69.80] | 102.47 [100.37, 104.70] | 32.12 [31.20, 33.00] | 945561.47 [902185.85, 988598.05] | 3.91 [3.77, 4.06] |
| participation150 | HINDSIGHT | 89.80 [89.04, 90.56] | 134.70 [133.57, 135.83] | 33.65 [32.41, 34.89] | 1371392.73 [1318367.41, 1425437.81] | 3.76 [3.65, 3.88] |

## Paired gaps

Positive means the second policy has the larger metric; online queue feedback can make algorithm gaps negative.

| Scenario | Gap | Match pp | Matched/day | Total saving KRW/day |
|---|---|---:|---:|---:|
| base | algorithm_loss | 0.03 [0.00, 0.10] | 0.03 [0.00, 0.10] | 40.07 [-478.70, 598.90] |
| base | batching_online_loss | 20.67 [19.23, 22.07] | 20.67 [19.23, 22.07] | 257821.87 [235164.34, 283171.98] |
| n50_d15 | algorithm_loss | 0.00 [0.00, 0.00] | 0.00 [0.00, 0.00] | 0.00 [0.00, 0.00] |
| n50_d15 | batching_online_loss | 15.80 [14.00, 17.53] | 7.90 [7.00, 8.77] | 90916.63 [76270.11, 105435.80] |
| participation150 | algorithm_loss | -0.02 [-0.07, 0.00] | -0.03 [-0.10, 0.00] | -1707.17 [-4272.80, 306.03] |
| participation150 | batching_online_loss | 21.22 [20.04, 22.38] | 31.83 [30.07, 33.57] | 465883.33 [433300.45, 497373.29] |

## Group-size distribution

Daily share of groups, with 95% CIs. Daily group counts also appear in JSON.

| Scenario | Policy | 2 riders % | 3 riders % | 4 riders % |
|---|---|---:|---:|---:|
| base | PROD | 84.40 [82.09, 86.65] | 13.48 [11.36, 15.75] | 2.12 [1.05, 3.43] |
| base | ROUND-OPT | 84.27 [81.97, 86.49] | 13.61 [11.46, 15.88] | 2.12 [1.05, 3.43] |
| base | ROUND-OPT-SAVING | 81.97 [79.25, 84.58] | 15.44 [13.08, 18.05] | 2.59 [1.45, 3.89] |
| base | HINDSIGHT | 80.66 [77.85, 83.36] | 16.02 [13.69, 18.43] | 3.32 [2.31, 4.40] |
| n50_d15 | PROD | 90.36 [87.51, 93.06] | 8.02 [5.51, 10.74] | 1.62 [0.48, 3.09] |
| n50_d15 | ROUND-OPT | 90.36 [87.51, 93.06] | 8.02 [5.51, 10.74] | 1.62 [0.48, 3.09] |
| n50_d15 | ROUND-OPT-SAVING | 87.21 [83.66, 90.56] | 11.11 [8.01, 14.39] | 1.68 [0.48, 3.20] |
| n50_d15 | HINDSIGHT | 87.60 [84.97, 90.08] | 11.05 [8.59, 13.50] | 1.35 [0.46, 2.41] |
| participation150 | PROD | 78.78 [76.57, 80.93] | 17.56 [15.65, 19.45] | 3.65 [2.79, 4.55] |
| participation150 | ROUND-OPT | 78.77 [76.56, 80.91] | 17.51 [15.59, 19.41] | 3.72 [2.87, 4.62] |
| participation150 | ROUND-OPT-SAVING | 74.78 [72.67, 76.84] | 20.58 [18.61, 22.46] | 4.65 [3.65, 5.67] |
| participation150 | HINDSIGHT | 75.49 [72.82, 77.98] | 18.75 [16.74, 20.79] | 5.76 [4.52, 7.15] |

## Expected monetary objective

These expected-quote savings are optimized before TIMS residual draws; the headline table reports residual-adjusted outcomes.

| Scenario | Policy | Expected saving/day KRW | Expected mean saving % |
|---|---|---:|---:|
| base | PROD | 450366.67 [425146.00, 476457.33] | 29.25 [28.07, 30.41] |
| base | ROUND-OPT | 450526.67 [425473.25, 476510.25] | 29.25 [28.08, 30.41] |
| base | ROUND-OPT-SAVING | 462073.33 [437265.92, 487851.00] | 30.62 [29.49, 31.78] |
| base | HINDSIGHT | 705933.33 [676349.67, 733526.67] | 32.05 [31.13, 32.93] |
| n50_d15 | PROD | 162440.00 [148496.42, 176631.33] | 27.64 [26.28, 29.08] |
| n50_d15 | ROUND-OPT | 162440.00 [148496.42, 176631.33] | 27.64 [26.28, 29.08] |
| n50_d15 | ROUND-OPT-SAVING | 168060.00 [154102.17, 181805.25] | 28.55 [27.23, 29.92] |
| n50_d15 | HINDSIGHT | 249670.00 [234522.67, 264506.83] | 29.41 [28.29, 30.53] |
| participation150 | PROD | 835750.00 [804495.50, 867815.42] | 31.01 [30.36, 31.62] |
| participation150 | ROUND-OPT | 835826.67 [804503.17, 867953.83] | 31.02 [30.37, 31.63] |
| participation150 | ROUND-OPT-SAVING | 872846.67 [842696.08, 904273.33] | 32.69 [32.19, 33.16] |
| participation150 | HINDSIGHT | 1275196.67 [1237505.75, 1313728.58] | 34.33 [33.44, 35.26] |

## Exact solver time

Includes all round calls, even empty feasible sets. Java enumeration/verification is reported separately.

| Policy | Solves | Total s | Mean ms | Max s | Max DP states |
|---|---:|---:|---:|---:|---:|
| ROUND-OPT | 44897 | 0.631 | 0.014 | 0.009 | 124 |
| ROUND-OPT-SAVING | 44897 | 0.617 | 0.014 | 0.058 | 124 |
| HINDSIGHT | 90 | 49.960 | 555.117 | 41.585 | 50361 |

Enumeration + production verification: 9.096s; production selection: 2.788s.

## Calibration decision

Kept the original global 1.567022x correction. Five-fold train CV chose distance bands (19.84% MAPE; global 25.15%, affine 20.66%). One held-out assessment improved overall MAPE 22.69% to 17.05% and >=30km 27.20% to 10.10%, but worsened 5-15km 19.20% to 23.63% (+4.43pp), exceeding the predeclared 2pp band regression tolerance. No fallback model was chosen using held-out data. Matrix unchanged; existing C7 results remain current. Full coefficients and band results: c8-calibration-selection.json.

## Interpretation and boundaries

- Simulated public-data proxy demand, not observed students or real payments.
- PROD calls unchanged MatchingRound: greedy initialization plus DFS, bounded by wall time and 100000 nodes; candidate cap 12 and secondary checks per anchor 3.
- ROUND-OPT sums (last-chance riders, matched riders, waited seconds, rounded saved meters), then smallest sorted matched IDs, matching the production Packer objective. Full feasible group set, no search/candidate/verification budgets.
- Each rolling policy owns its waiting pool. Day-level algorithm gap includes subsequent queue feedback and can be negative; it is not a sum of same-state round regret.
- ROUND-OPT-SAVING maximizes expected quote saving KRW each round, then deterministic ID tie. It does not know future residuals.
- HINDSIGHT maximizes matched riders then expected quote saving over a full day, with overlapping departure windows. Reservation windows unchanged; instant window is earliest through latest production-eligible minute confirmation plus 10 min preparation/dispatch. Online round confirmation/early-confirm restrictions are relaxed. Every selected group passes the production verifier with a fixed feasible departure.
- HINDSIGHT is an exact upper bound on matched riders within this day/window/route model. Its saving and delay are feasible-solution metrics, not independent upper bounds on those metrics.
- Reported ratio mean saving and total KRW use the identical C7 seeded TIMS group residual and rider solo residual, with production ratio settlement and integer conservation. Expected saving metrics additionally show the optimization objective.
- Metrics and group-size shares are means of daily metrics; 95% CIs use existing 10000-replicate day-cluster bootstrap. Gaps use paired daily differences; units are fractions, multiply by 100 for percentage points.
- All requests share the original C7 male gender pool and hub; the benchmark enforces pool parity but does not infer population gender effects.

## Same production-state round regret

{
  "nonempty_rounds": 2261,
  "objective_suboptimal_rounds": 3,
  "solve_seconds": 0.0663577985833399,
  "by_scenario": {
    "base": {
      "nonempty_rounds": 766,
      "objective_suboptimal_rounds": 1
    },
    "n50_d15": {
      "nonempty_rounds": 326,
      "objective_suboptimal_rounds": 0
    },
    "participation150": {
      "nonempty_rounds": 1169,
      "objective_suboptimal_rounds": 2
    }
  }
}

This compares exhaustive packing with PROD on PROD’s own waiting pool, before queue feedback. Objective suboptimality compares the first four production priorities; flattened-ID ties are excluded. Solve time is additional to the policy table.

## Verification

{
  "production_verifier_passes": 67624,
  "enumerated_groups_including_repeated_rounds": 584826,
  "conservation_errors": 0,
  "hindsight_contains_all_selected_online_groups": true,
  "all_solves_exact": true,
  "lp_relaxations": 0,
  "c7_prod_daily_matched_counts_identical": true,
  "selected_group_replay": {
    "status": "PASS",
    "groups": 10908,
    "riders": 24294,
    "conservation_errors": 0,
    "by_policy": {
      "HINDSIGHT": 3306,
      "PROD": 2557,
      "ROUND-OPT": 2556,
      "ROUND-OPT-SAVING": 2489
    },
    "selected_sha256": "23696bbc5e771ef72053d8b95038415eaf530c190e4663c429de393a80261645",
    "verifier_source_sha256": "a6729cba28a3e001cdcf014ad3bb7641100f26dd1317bdb6ee203a281fa96937"
  }
}

Exact input/source/dependency hashes are in JSON. Daily policy metrics, chosen groups, all solve times and Java export remain in experiments/.cache/c8. No production files changed; no commit.
