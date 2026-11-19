"""Offline C6 experiment: compile current app sources, replay matching and compare settlement."""
from __future__ import annotations

import argparse
import csv
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import random
import shutil
import subprocess
import zipfile

from fares import analyze, quantile

HERE = Path(__file__).resolve().parent
REPO = HERE.parent
CACHE = HERE / ".cache"


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def json_write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def synthetic(path, seed, trials):
    """Deterministic illustrative requests; these are not observed student journeys."""
    rng = random.Random(seed)
    lines = []
    for trial in range(trials):
        n = (8, 16, 24)[trial % 3]
        lines.append(f"CASE\tSYNTHETIC\tseed{seed}_t{trial}\tsingle\tFROM_HUB\t{trial}\t37.874\t127.205\t1.3\t24\tMODEL\t4800\t770\t0\t100\t4800\t-\tKRW")
        for index in range(n):
            # Three destination corridors allow both compatible and incompatible combinations.
            angle = (index % 3) * 2 * math.pi / 3 + rng.uniform(-.06, .06)
            distance = rng.uniform(.025, .09)
            lat, lon = 37.874 + distance * math.cos(angle), 127.205 + distance * math.sin(angle)
            # Same haversine radius as GeoPoint.distanceKm, to keep model/solo inputs consistent.
            dlat, dlon = math.radians(lat - 37.874), math.radians(lon - 127.205)
            a = math.sin(dlat / 2)**2 + math.cos(math.radians(lat)) * math.cos(math.radians(37.874)) * math.sin(dlon / 2)**2
            km = 6371 * 2 * math.atan2(math.sqrt(a), math.sqrt(1-a)) * 1.3
            sec, fare = round(km / 24 * 3600), math.ceil((4800 + 770 * km) / 100) * 100
            lines.append(f"R\t{index+1}\t{index+1}\t{lat:.12f}\t{lon:.12f}\t{km:.9f}\t{sec}\t{fare}\t-600\t0")
        lines.append("END")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def import_legacy(source, limit, experiment="single"):
    """Reuse only input/cached model, never legacy copied engine or DP implementation."""
    source = Path(source)
    cases = source / "data" / "korea_cases.txt"
    matrix = source / "data" / "kakao_matrix.tsv"
    if not cases.is_file() or not matrix.is_file():
        raise ValueError("Expected realdata/data/korea_cases.txt and kakao_matrix.tsv")
    compatible = []; current = []
    for line in cases.read_text(encoding="utf-8-sig").splitlines():
        current.append(line)
        if line == "END":
            header = current[0].split("\t")
            if header[3] == experiment and header[4] == "FROM_HUB":
                # Preserve requests and time inputs. Label cached multi-stop prices explicitly as modeled.
                header[1] = "KOREA_CACHED_PROXY"
                header[16] = "CACHE_MATRIX"
                current[0] = "\t".join(header)
                compatible.append(current)
            current = []
    if not compatible:
        raise ValueError("No compatible cases in legacy input")
    count = min(limit, len(compatible))
    # Spread across original input ordering (request-size strata), instead of only first n=4 cases.
    indices = [index * len(compatible) // count for index in range(count)]
    selected_blocks = [compatible[index] for index in indices]
    selected = [line for block in selected_blocks for line in block]
    sizes = {}
    for block in selected_blocks:
        size = str(sum(line.startswith("R\t") for line in block))
        sizes[size] = sizes.get(size, 0) + 1
    local = CACHE / "legacy"
    local.mkdir(parents=True, exist_ok=True)
    selected_path = local / "cases.tsv"
    selected_path.write_text("\n".join(selected) + "\n", encoding="utf-8")
    shutil.copyfile(matrix, local / "matrix.tsv")
    provenance = {"source_project": "taximate-matching-sim/realdata", "selected_cases": count,
                  "selection": "evenly spaced indices across compatible FROM_HUB cases in source order",
                  "experiment": experiment,
                  "compatible_cases": len(compatible), "selected_request_count_histogram": sizes,
                  "source_cases_sha256": sha(cases), "source_matrix_sha256": sha(matrix),
                  "selected_cases_sha256": sha(selected_path), "historical_api_calls": 1490,
                  "new_api_calls": 0,
                  "limitations": ["TIMS aggregate demand proxy; not observed student OD",
                                  "request times may be synthetic", "51.5% matrix cells composed from observed legs",
                                  "multi-stop fare modeled from cached solo quotes; not actual meter payment",
                                  "legacy report MAPE 14.35% compares model and Kakao, not actual fare accuracy"]}
    return selected_path, local / "matrix.tsv", provenance


def jdk_home(explicit=None):
    candidates = [Path(explicit)] if explicit else []
    if os.environ.get("JAVA_HOME"):
        candidates.append(Path(os.environ["JAVA_HOME"]))
    if shutil.which("java"):
        candidates.append(Path(shutil.which("java")).resolve().parent.parent)
    candidates += sorted((Path.home() / ".jdks").glob("*"))
    for home in candidates:
        release = home / "release"
        if release.is_file() and 'JAVA_VERSION="21.' in release.read_text(encoding="utf-8"):
            return home
    raise ValueError("JDK 21 required; pass --java-home or set JAVA_HOME")


def compile_current(java_home, build=False, maven="mvn"):
    env = dict(os.environ, JAVA_HOME=str(java_home))
    env["PATH"] = str(java_home / "bin") + os.pathsep + env.get("PATH", "")
    if build:
        subprocess.run([maven, "-pl", "server", "-am", "package", "-DskipTests"], cwd=REPO, env=env, check=True)
    boot = REPO / "server" / "target" / "server-0.1.0.jar"
    if not boot.is_file():
        raise ValueError("Build Maven app first, or pass --build --maven <mvn command>")
    deps = CACHE / "deps"
    deps.mkdir(parents=True, exist_ok=True)
    # These are the exact Maven-managed runtime dependencies in the built app, not substitute stubs.
    with zipfile.ZipFile(boot) as archive:
        names = [name for name in archive.namelist() if name.startswith("BOOT-INF/lib/") and name.endswith(".jar")]
        if not names:
            raise ValueError("Expected Spring Boot Maven package with BOOT-INF/lib dependencies")
        for name in names:
            (deps / Path(name).name).write_bytes(archive.read(name))
    # Lombok is compile-only and intentionally absent from the app runtime jar.
    lombok_candidates = sorted((Path.home() / ".m2" / "repository" / "org" / "projectlombok" / "lombok").glob("*/*.jar"))
    lombok_candidates = [p for p in lombok_candidates if not p.name.endswith(("-sources.jar", "-javadoc.jar"))]
    if not lombok_candidates:
        raise ValueError("Maven compile-only Lombok dependency missing; build app first")
    lombok = lombok_candidates[-1]
    runtime = [deps / Path(name).name for name in names]
    classpath = os.pathsep.join(map(str, runtime + [lombok]))
    classes = CACHE / "classes"
    # Drop only this task's private classes directory; no repo sources/build output are touched.
    if not CACHE.resolve().is_relative_to(HERE) or classes.is_symlink() or not classes.resolve().is_relative_to(CACHE.resolve()):
        raise ValueError("Unsafe experiment cache path")
    if classes.exists():
        shutil.rmtree(classes)
    classes.mkdir(parents=True)
    matching = sorted((REPO / "matching-engine" / "src" / "main" / "java").rglob("*.java"))
    service = REPO / "server" / "src" / "main" / "java" / "com" / "campustaximate" / "server"
    production = matching + [service / "route" / "RouteClient.java"] + [service / "service" / name for name in
                  ("RouteGroupVerifier.java", "RoutePathStore.java", "MatchingDiagnostics.java")]
    adapter = HERE / "java" / "com" / "campustaximate" / "server" / "service" / "ExperimentRunner.java"
    sourcepath = os.pathsep.join((str(REPO / "server" / "src" / "main" / "java"), str(REPO / "matching-engine" / "src" / "main" / "java")))
    compiled_hashes = {str(path.relative_to(REPO)).replace("\\", "/"): sha(path) for path in production + [adapter]}
    args = ["--release", "21", "-encoding", "UTF-8", "-cp", classpath, "-processorpath", str(lombok), "-sourcepath", sourcepath,
            "-d", str(classes), *map(str, production), str(adapter)]
    # Windows command line limit: Java argument file, with slashes rather than backslash escapes.
    argfile = CACHE / "javac.args"
    argfile.write_text("\n".join('"' + value.replace("\\", "/") + '"' for value in args), encoding="utf-8")
    subprocess.run([str(java_home / "bin" / "javac"), "@" + str(argfile)], cwd=REPO, check=True)
    if any(sha(REPO / name) != digest for name, digest in compiled_hashes.items()):
        raise ValueError("Production source changed during compilation; rerun after edits settle")
    # Hash all app Java sources too, covering any production dependencies javac resolves implicitly.
    all_sources = matching + sorted((REPO / "server" / "src" / "main" / "java").rglob("*.java"))
    hashes = {str(path.relative_to(REPO)).replace("\\", "/"): sha(path) for path in all_sources}
    hashes[str(adapter.relative_to(REPO)).replace("\\", "/")] = sha(adapter)
    return str(classes) + os.pathsep + classpath, {"source_sha256": hashes, "compiled_source_sha256": compiled_hashes, "maven_package_sha256": sha(boot),
            "maven_pom_sha256": {str(path.relative_to(REPO)).replace("\\", "/"): sha(path) for path in
                                  (REPO / "pom.xml", REPO / "server" / "pom.xml", REPO / "matching-engine" / "pom.xml")},
            "dependency_sha256": {path.name: sha(path) for path in runtime}, "lombok_sha256": sha(lombok),
            "compiled_app_sources_directly": True, "jdk_major": 21}


def summarize(path, observations=None):
    with path.open(encoding="utf-8") as stream:
        rows = list(csv.DictReader(stream))
    total = lambda key: sum(int(row[key]) for row in rows)
    requests, matched = total("requests"), total("matched")
    weighted = lambda key, denominator: None if not denominator else sum(float(row[key]) * int(row["matched"]) for row in rows) / denominator
    summary = {"cases": len(rows), "requests": requests, "matched": matched, "expired": total("expired"),
            "expired_rate": None if requests == 0 else total("expired") / requests,
            "matching_rate": None if requests == 0 else matched / requests,
            "mean_ratio_saving_matched": weighted("mean_ratio_saving_matched", matched),
            "mean_equal_saving_matched": weighted("mean_equal_saving_matched", matched),
            "mean_ratio_saving_all": weighted("mean_ratio_saving_matched", requests),
            "mean_equal_saving_all": weighted("mean_equal_saving_matched", requests),
            "mean_delay_minutes_matched": weighted("mean_delay_minutes_matched", matched),
            "max_delay_minutes": max((float(row["max_delay_minutes"]) for row in rows), default=None),
            "saved_krw": total("saved_krw"), "equal_saving_below_10pct_count": total("equal_saving_below_10pct_count"),
            "conservation_errors": total("conservation_errors"), "offline_route_calls": total("offline_route_calls"),
            "engine_and_offline_route_ms": sum(float(row["engine_and_offline_route_ms"]) for row in rows),
            "network_ms": 0, "new_api_calls": 0}
    for key in ("verification_passes", "verification_fails", "verification_unavailable"):
        summary[key] = sum(int(row.get(key, 0)) for row in rows)
    if observations is not None:
        with observations.open(encoding="utf-8") as stream:
            riders = list(csv.DictReader(stream))
        if len(riders) != matched:
            raise ValueError("Individual observation count differs from matched count")
        summary["distributions_matched"] = {}
        for key in ("ratio_saving", "equal_saving", "delay_minutes"):
            values = [float(row[key]) for row in riders]
            summary["distributions_matched"][key] = {"min": min(values) if values else None,
                    "median": quantile(values, .5), "p90": quantile(values, .9), "p95": quantile(values, .95),
                    "max": max(values) if values else None}
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--legacy-realdata", type=Path, help="Optional historical realdata directory; copies input/cache locally only")
    parser.add_argument("--limit", type=int, default=60)
    parser.add_argument("--legacy-experiment", choices=("single", "timeline"), default="single")
    parser.add_argument("--seed", type=int, default=20261119)
    parser.add_argument("--trials", type=int, default=12)
    parser.add_argument("--java-home")
    parser.add_argument("--build", action="store_true")
    parser.add_argument("--maven", default="mvn")
    parser.add_argument("--actual-fares", type=Path, default=HERE / "templates" / "actual_fares.csv")
    parser.add_argument("--output-prefix", default="synthetic")
    args = parser.parse_args()
    if not 1 <= args.limit <= 1000 or not 1 <= args.trials <= 1000:
        parser.error("limit and trials must be 1..1000")
    if Path(args.output_prefix).name != args.output_prefix:
        parser.error("output-prefix must be a filename prefix")
    CACHE.mkdir(parents=True, exist_ok=True)
    if args.legacy_realdata:
        cases, matrix, provenance = import_legacy(args.legacy_realdata, args.limit, args.legacy_experiment)
    else:
        cases, matrix = CACHE / "synthetic.tsv", None
        synthetic(cases, args.seed, args.trials)
        provenance = {"cohort": "SYNTHETIC", "observed_student_trips": False,
                      "limitations": ["Synthetic corridor destinations and request times", "Distance/time/fare model; not observed meter payments"]}
    home = jdk_home(args.java_home)
    classpath, source_manifest = compile_current(home, args.build, args.maven)
    reports = HERE / "reports"
    reports.mkdir(exist_ok=True)
    csv_output = reports / (args.output_prefix + ".csv")
    individuals = CACHE / (args.output_prefix + "-riders.csv")
    settings_path = CACHE / (args.output_prefix + "-settings.properties")
    java = [str(home / "bin" / "java"), "-cp", classpath, "com.campustaximate.server.service.ExperimentRunner", str(cases)]
    subprocess.run(java + [str(csv_output), str(matrix) if matrix else "-", "1", str(individuals), str(settings_path)], cwd=REPO, check=True,
                   stdout=subprocess.DEVNULL)
    runtime_settings = {}
    for line in settings_path.read_text(encoding="utf-8").splitlines():
        if line and not line.startswith("#"):
            key, value = line.split("=", 1)
            runtime_settings[key] = float(value) if "." in value else int(value)
    report = {"schema_version": 1, "generated_at": datetime.now(timezone.utc).isoformat(), "seed": args.seed,
              "source": source_manifest, "data": provenance,
              "input_sha256": sha(cases), "matrix_sha256": sha(matrix) if matrix else None,
              "experiment_tool_sha256": {path.name: sha(path) for path in (HERE / "run.py", HERE / "fares.py")},
              "settings": {"engine": runtime_settings, "scope": "reservation departure windows, single common hub, walking 0, single gender pool",
                           "fare_scope": "as imported; paired actual fare validation separate",
                           "comparison": "same matched groups, ratio vs equal 1/N settlement; not an alternate matching algorithm"},
              "baseline": summarize(csv_output, individuals), "sensitivity": {}}
    actual, details = analyze(args.actual_fares)
    signed = [row["signed_relative"] for row in details if row.get("cohort") == "prospective"]
    if signed:
        for label, value in (("observed_p90", quantile(signed, .90)), ("observed_p95", quantile(signed, .95))):
            sensitivity_path = reports / (args.output_prefix + "-" + label + ".csv")
            sensitivity_individuals = CACHE / (args.output_prefix + "-" + label + "-riders.csv")
            factor = 1 + value
            subprocess.run(java + [str(sensitivity_path), str(matrix) if matrix else "-", str(factor), str(sensitivity_individuals)], cwd=REPO,
                           check=True, stdout=subprocess.DEVNULL)
            report["sensitivity"][label] = {"shared_fare_factor": factor, "prospective_sample_n": len(signed), **summarize(sensitivity_path, sensitivity_individuals)}
    else:
        report["sensitivity"] = {"status": "BLOCKED_NO_PROSPECTIVE_FARES", "observed_n": 0}
    try:
        report["git_head"] = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=REPO, text=True).strip()
        report["git_dirty"] = bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=REPO, text=True).strip())
    except (subprocess.CalledProcessError, FileNotFoundError):
        report["git_head"] = None
    json_write(reports / (args.output_prefix + ".json"), report)
    result = report["baseline"]
    percent = lambda value: "미측정" if value is None else f"{value:.2%}"
    text = ["# 매칭·정산 실험", "",
            f"자료: {'기존 한국 캐시 기반 대리 자료' if matrix else '가상 요청'}. 실제 학생 탑승 효과를 의미하지 않는다.", "",
            f"요청 {result['requests']}건, 매칭 {result['matched']}건, 만료 {result['expired']}건, 매칭률 {percent(result['matching_rate'])}.",
            f"매칭된 사람의 평균 절감률: 비율 정산 {percent(result['mean_ratio_saving_matched'])}, 1/N {percent(result['mean_equal_saving_matched'])}.",
            f"전체 요청 기준 평균 절감률(미매칭은 0): 비율 {percent(result['mean_ratio_saving_all'])}, 1/N {percent(result['mean_equal_saving_all'])}.",
            f"금액 보존 오류 {result['conservation_errors']}건. 1/N에서 10% 절감 미달 {result['equal_saving_below_10pct_count']}명.", "",
            "두 정산은 같은 매칭 결과와 결제액을 사용한다. 별도 매칭 알고리즘의 우열 비교가 아니다.",
            "현재 앱의 MatchingRound, RouteGroupVerifier, SettlementCalculator를 직접 컴파일했다.",
            "시간은 엔진과 로컬 경로 모델을 포함한다. 네트워크 호출은 0회이며 실제 API 지연을 측정하지 않았다.",
            "실측 요금 오차 민감도는 전향적 실측 자료가 있어야 실행한다. 자료가 없으면 미검증으로 남긴다.", "",
            *["- " + item for item in provenance["limitations"]], ""]
    (reports / (args.output_prefix + ".md")).write_text("\n".join(text), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
