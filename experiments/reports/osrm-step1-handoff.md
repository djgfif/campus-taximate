# C7 grid / OSRM Step 1 — completed 2026-11-20

No commit. All worker edits are inside experiments. No remaining blocker. The coordinator-provided sgis_mois_crosswalk.py and CSV were read and hashed; the generator was not rerun or edited by this worker.

## Actual execution

The complete crosswalk resolves all 250 living-move codes (0% unresolved flow). Seoul dong codes aggregate to first5+000. Every non-empty group is one region on both sides: union of old SGIS polygons and union of new MOIS codes. INCHEON_2026 and HWASEONG_2026 therefore produce 244 merged regions, without duplicating polygon trips. A dedicated fixture verifies grouped flows, unique grid denominators and Seoul aggregation.

The full pipeline completed in this order:

```powershell
Set-Location 'C:/work/01_Projects/Team/campus-taximate'
$c7python = './experiments/.cache/venv/python.exe'
& $c7python experiments/osrm_matrix.py --crosswalk experiments/.cache/kr-public/livingmove-sgis-crosswalk.csv
& $c7python experiments/kakao_calibration.py sample --n 400
& $c7python experiments/kakao_calibration.py query
& $c7python experiments/kakao_calibration.py fit
& $c7python experiments/detailed.py --days 30 --verify-c6
& $c7python -m pytest experiments/tests -q
& $c7python experiments/compare_c7.py
```

Each final command exited 0. The full suite passed 61 tests and 9 subtests. Final diff hygiene was checked separately. Earlier, two owned matrix processes were stopped to fix localhost latency and add bounded parallel requests; complete chunks were reused. An initial calibration sample invocation failed because this Python environment omits the script directory from sys.path; the CLI was fixed and isolated-interpreter entrypoint tests were added before a successful sample. No Kakao calls were made by that failed sample command.

400 authenticated future-directions requests succeeded, with no failed calibration calls observed. The key was never printed. Sample consists of 200 hub→grid and 200 local directed grid→grid OD pairs, with living-move-weighted service hours and 321 training / 79 held-out OD pairs. Query resumes only missing successful rows. Do not rerun sample into this existing output directory. Use a fresh --output for a new experiment version, then detailed.py --routing-cache.

## Coverage and routing

- All 209,563 positive, uniquely SGIS-assigned TIMS grids are considered before service and tail selection.
- Selected destinations: 4,311 TIMS 500m cell centers, no jitter.
- Hub included: 4,312 nodes and 18,593,344 ordered matrix entries, including returns and diagonals.
- Fully out-of-range regions: 195 of 244, representing 3.279225% of total home flow.
- Total service exclusion, including out-of-range cells inside partially served regions: 5.065198%.
- Tail excluded after range screening: 0.949307% of total home flow.
- Final represented national home flow: 93.985495%; retained eligible flow: 99.000043%.
- Snap >300m: 82 selected grids; maximum snap: 1,538.362499m. The flags were reported without silently discarding their flow.

60km of hub road distance defines the regional campus day-trip service assumption. WGS84 geodesic lower bounds >60km allow pre-screening without routing distant cells. The smallest descending original-weight prefix covers at least 99% of SERVICE-ELIGIBLE CELL flow. This does not mean 99% of national flow or 99% of whole partially served regions. All regional losses and excluded names/codes are in reports/c7-osrm-region-coverage.csv; snap flags are in reports/c7-osrm-snaps-over300.csv.

The same local server is reached through explicit IPv4: localhost took 2.096s for a trivial request versus 0.003s using 127.0.0.1. Four bounded requests were used for table chunks. C7 alone uses C7RoadMatrix primitive dense storage (~223MB for distance and time), streaming TSV loading and lookup; C6 retains its original reader. The Java self-test checks directed lookup and rejects incomplete matrices.

## Held-out calibration

- Training median Kakao/OSRM duration multiplier: 1.5670221552.
- Held-out n=79: raw duration MAPE 32.706888% → corrected 22.686892%.
- Held-out median Kakao/OSRM time ratio: 1.4174918938.
- Held-out OSRM distance MAPE: 11.932230%.
- Held-out tariff-proxy fare MAPE: 18.522733%.
- The >=30km stratum worsens: 19.314387% → 27.198203% (n=33). This was reported without tuning on held-out observations.

The model is a single preselected robust global ratio, not an hour-specific traffic simulation. Hour/band breakdowns are in reports/c7-osrm-calibration.json. FROM_HUB solo fares use the Pocheon daytime formula on corrected duration and unchanged road distance. Calibration grid-origin fare checks use Pocheon for Pocheon and the existing Seoul proxy elsewhere; they do not implement all regional tariffs. Kakao future estimates are not observed meter receipts.

## C7 results

24 conditions × 30 independent simulated days = 105,000 requests. Conservation errors 0, unavailable routes 0, engine-budget warning rounds 0; maximum round 58.721ms. Base matching 61.00%, day-cluster 95% CI 59.17–62.83%; ratio saving 28.71%, wait 8.54min. C6 regression is UNCHANGED: 60 cases / 768 requests / 661 matched, with identical 1,000-replicate error sensitivity. C6 published files were not changed.

# C7 grid vs historical 118-point results

Destination universe, routes, duration correction and solo tariffs change together; differences are not isolated causal effects. Both studies are simulated proxy demand, not measured student outcomes.

| Scenario | Requests/day | Matching old → grid | Ratio saving old → grid | Wait min old → grid | Delay min old → grid | Additional-payment rate old → grid |
|---|---:|---:|---:|---:|---:|---:|
| base | 100 | 61.60% → 61.00% | 49.55% → 28.71% | 8.40 → 8.54 | 3.87 → 3.79 | 23.62% → 23.20% |
| n50_d15 | 50 | 45.47% → 47.73% | 49.04% → 27.26% | 8.71 → 8.64 | 3.76 → 3.65 | 23.55% → 22.43% |
| participation150 | 150 | 68.22% → 68.60% | 52.42% → 30.41% | 7.83 → 8.10 | 3.75 → 3.92 | 24.82% → 22.91% |
| error_x1_5 | 100 | 61.60% → 61.00% | 47.09% → 25.75% | 8.40 → 8.54 | 3.87 → 3.79 | 31.55% → 31.91% |
| error_x2 | 100 | 61.60% → 61.00% | 47.73% → 23.80% | 8.40 → 8.54 | 3.87 → 3.79 | 33.08% → 37.59% |
| exclude_intra_pocheon | 100 | 51.60% → 49.27% | 54.26% → 35.93% | 8.61 → 8.74 | 3.82 → 4.38 | 23.89% → 26.36% |

Full results and confidence intervals are in reports/c7-detailed-sim.json/.md/.csv and c7-detailed-sim-days.csv. Historical headline metrics and original report SHA256 were preserved in reports/c7-old118-headlines.json; full old reports also remain in .cache/osrm/old118. The comparison helper now reads the tracked historical headline artifact by default.

## Provenance and practical limits

- Crosswalk SHA256: 9fa4f7d453b1e5253c218ae5e0c172caac7471edd8fc3cde623cb4579a910931
- Crosswalk generator SHA256: f1be4a1a79824add32f72523693c0f87615f5cf42fac738138448740e5fa1a5b
- OSRM raw matrix SHA256: 3eaabb0dbec380b20e9762622fa9819b2dd6eb24dc9164c086f4bd49cdcfb044
- Corrected matrix SHA256: 3eaabb0dbec380b20e9762622fa9819b2dd6eb24dc9164c086f4bd49cdcfb044
- Exact inputs, sizes and output hashes: .cache/osrm/MANIFEST.json and reports/c7-input-MANIFEST.json.
- Image/extract identifiers were supplied by the coordinator (OSRM v5.27.1 MLD/car; Geofabrik South Korea 2026-11-20). This worker verified live API responses but did not independently inspect/hash the running container extract.

Grid-center assignment, representative-point crosswalks, reorganized-region unions and large road snaps have spatial limits. Home flow is proxied by taxi pickup density within each region. Destination and hour remain independently drawn. TIMS residual injection and 1.5x/2x cases remain unchanged; aggregate residuals can understate individual-trip variation. Shared-route pricing retains the C6 regression while solo tariffs changed, so this model mismatch remains. Changes in destination universe, routing, duration and solo tariff happen together; comparison differences are not a single-change causal estimate. No measured student ridership or real financial transactions are claimed.

## Changed worker files (absolute paths)

- C:/work/01_Projects/Team/campus-taximate/experiments/EXECUTION_REPORT.md
- C:/work/01_Projects/Team/campus-taximate/experiments/README.md
- C:/work/01_Projects/Team/campus-taximate/experiments/baseline/PROVENANCE.md
- C:/work/01_Projects/Team/campus-taximate/experiments/detailed.py
- C:/work/01_Projects/Team/campus-taximate/experiments/java/com/campustaximate/server/service/DetailedSimulation.java
- C:/work/01_Projects/Team/campus-taximate/experiments/reports/c7-detailed-sim-days.csv
- C:/work/01_Projects/Team/campus-taximate/experiments/reports/c7-detailed-sim.csv
- C:/work/01_Projects/Team/campus-taximate/experiments/reports/c7-detailed-sim.json
- C:/work/01_Projects/Team/campus-taximate/experiments/reports/c7-detailed-sim.md
- C:/work/01_Projects/Team/campus-taximate/experiments/reports/c7-input-MANIFEST.json
- C:/work/01_Projects/Team/campus-taximate/experiments/tests/test_detailed.py
- C:/work/01_Projects/Team/campus-taximate/experiments/compare_c7.py
- C:/work/01_Projects/Team/campus-taximate/experiments/grid_demand.py
- C:/work/01_Projects/Team/campus-taximate/experiments/java/com/campustaximate/server/service/C7RoadMatrix.java
- C:/work/01_Projects/Team/campus-taximate/experiments/kakao_calibration.py
- C:/work/01_Projects/Team/campus-taximate/experiments/osrm_matrix.py
- C:/work/01_Projects/Team/campus-taximate/experiments/reports/c7-grid-comparison.json
- C:/work/01_Projects/Team/campus-taximate/experiments/reports/c7-grid-comparison.md
- C:/work/01_Projects/Team/campus-taximate/experiments/reports/c7-old118-headlines.json
- C:/work/01_Projects/Team/campus-taximate/experiments/reports/c7-osrm-calibration.json
- C:/work/01_Projects/Team/campus-taximate/experiments/reports/c7-osrm-coverage.json
- C:/work/01_Projects/Team/campus-taximate/experiments/reports/c7-osrm-region-coverage.csv
- C:/work/01_Projects/Team/campus-taximate/experiments/reports/c7-osrm-snaps-over300.csv
- C:/work/01_Projects/Team/campus-taximate/experiments/reports/osrm-crosswalk-audit.csv
- C:/work/01_Projects/Team/campus-taximate/experiments/reports/osrm-crosswalk-audit.json
- C:/work/01_Projects/Team/campus-taximate/experiments/reports/osrm-step1-handoff.md
- C:/work/01_Projects/Team/campus-taximate/experiments/tests/test_osrm.py

Coordinator-provided source, read and hashed without editing: C:/work/01_Projects/Team/campus-taximate/experiments/sgis_mois_crosswalk.py. Raw matrices, query CSV, logs, Java outputs and old full reports are inside the already-ignored experiments/.cache directory.
