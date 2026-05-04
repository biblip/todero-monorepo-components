#!/usr/bin/env python3
from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from pathlib import Path


NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def child_text(parent: ET.Element, path: str) -> str | None:
    node = parent.find(path, NS)
    if node is None or node.text is None:
        return None
    value = node.text.strip()
    return value or None


def root_dir() -> Path:
    return Path(__file__).resolve().parent


def root_metadata(repo_root: Path) -> tuple[str, str]:
    root_pom = ET.parse(repo_root / "pom.xml").getroot()
    group_id = child_text(root_pom, "m:groupId")
    version = child_text(root_pom, "m:version")
    if group_id is None or version is None:
      raise SystemExit("root pom.xml is missing groupId or version")
    return group_id, version


def installable_coords(repo_root: Path) -> list[str]:
    group_id, version = root_metadata(repo_root)
    coords: list[str] = []
    for pom_path in sorted(repo_root.rglob("pom.xml")):
        if pom_path == repo_root / "pom.xml":
            continue
        root = ET.parse(pom_path).getroot()
        packaging = child_text(root, "m:packaging") or "jar"
        if packaging != "jar":
            continue
        runtime_kind = child_text(root, "m:properties/m:todero.runtime.kind")
        if runtime_kind == "library":
            continue
        artifact_id = child_text(root, "m:artifactId")
        if artifact_id is None:
            raise SystemExit(f"missing artifactId in {pom_path}")
        coords.append(f"{group_id}:{artifact_id}:{version}")
    return coords


def main() -> int:
    repo_root = root_dir()
    for coord in installable_coords(repo_root):
        print(coord)
    return 0


if __name__ == "__main__":
    sys.exit(main())
