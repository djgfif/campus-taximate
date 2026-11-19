"""Create a reviewable source handoff without keys, uploads, or raw trip data."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ROOT_FILES = {"README.md", "pom.xml", "mvnw", "mvnw.cmd", ".gitignore", ".gitattributes", ".dockerignore", ".nvmrc", ".env.example", "compose.yml", "docker-compose.yml", "Dockerfile"}
SOURCE_DIRS = {"matching-engine", "server", "frontend", "scripts", "docs", ".github", ".mvn"}
EXPERIMENT_DIRS = {"java", "templates", "tests", "reports", "baseline"}
EXTENSIONS = {".java", ".xml", ".yml", ".yaml", ".js", ".jsx", ".mjs", ".css", ".json", ".md", ".txt", ".csv", ".ps1", ".py", ".svg", ".html", ".properties", ".sql", ".sh", ".cmd", ".bat", ".jar"}
EXCLUDED = {"target", "node_modules", ".git", ".cache", "__pycache__", "uploads", "test-results", "playwright-report", "private", "raw", "data", "cache", ".venv", "venv"}


def permitted(path: Path) -> bool:
    parts = path.parts
    if any(p in EXCLUDED for p in parts):
        return False
    if path.as_posix().startswith("server/src/main/resources/static/"):
        return False
    if len(parts) == 1:
        return path.name in ROOT_FILES
    if parts == ("experiments", ".gitignore"):
        return True
    if path.suffix.lower() not in EXTENSIONS:
        return False
    if path.suffix == ".jar" and parts[0] != ".mvn":
        return False
    if parts[0] == "experiments":
        return (len(parts) == 2 and path.suffix in {".py", ".ps1", ".md"}) or parts[1] in EXPERIMENT_DIRS
    return parts[0] in SOURCE_DIRS


def git(*args: str) -> str:
    return subprocess.check_output(["git", "-C", str(ROOT), *args], text=True, encoding="utf-8").strip()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / ".cache/release/campus-taximate-source.zip")
    args = parser.parse_args()
    paths = [Path(p) for p in git("ls-files", "-z", "--cached", "--others", "--exclude-standard").split("\0") if p]
    included = []
    for relative in sorted(set(paths)):
        absolute = ROOT / relative
        if not permitted(relative) or not absolute.is_file() or absolute.is_symlink():
            continue
        if not absolute.resolve().is_relative_to(ROOT.resolve()):
            raise ValueError(f"Source escapes workspace: {relative}")
        included.append((relative.as_posix(), absolute.read_bytes()))
    manifest = {
        "schema": 1,
        "git_head": git("rev-parse", "HEAD"),
        "working_tree_dirty": bool(git("status", "--porcelain")),
        "notice": "Source snapshot. Uncommitted files are represented by individual SHA-256 hashes. No executed-test or empirical-accuracy claim is implied.",
        "files": [{"path": p, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()} for p, data in included],
    }
    output = args.output.resolve()
    if not output.is_relative_to((ROOT / ".cache/release").resolve()):
        raise ValueError("Output must be inside this workspace's .cache/release directory")
    output.parent.mkdir(parents=True, exist_ok=True)
    modes = {}
    for entry in git("ls-files", "--stage", "-z").split("\0"):
        if entry:
            metadata, name = entry.split("\t", 1)
            modes[name] = int(metadata.split()[0], 8)
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for p, data in included:
            info = zipfile.ZipInfo(p, date_time=(1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.external_attr = modes.get(p, 0o100644) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, data)
        info = zipfile.ZipInfo("RELEASE_MANIFEST.json", date_time=(1980, 1, 1, 0, 0, 0))
        info.external_attr = 0o100644 << 16
        info.compress_type = zipfile.ZIP_DEFLATED
        archive.writestr(info, json.dumps(manifest, ensure_ascii=False, indent=2))
    digest = hashlib.sha256(output.read_bytes()).hexdigest()
    output.with_suffix(".zip.sha256").write_text(f"{digest}  {output.name}\n", encoding="utf-8")
    print(json.dumps({"archive": str(output), "files": len(included), "sha256": digest, "dirty": manifest["working_tree_dirty"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
