#!/usr/bin/env python3
"""Check installable ZIP metadata and the real Changes Between entry points."""
from __future__ import annotations
import io
import os
from pathlib import Path, PurePosixPath
import re
import sys
import xml.etree.ElementTree as ET
from zipfile import ZipFile


def check(directory: Path) -> str:
    archives = list(directory.glob("*.zip"))
    if len(archives) != 1:
        raise ValueError(f"Expected one plugin ZIP in {directory}; got {len(archives)}")
    archive = archives[0]
    descriptors = []
    classes = set()
    with ZipFile(archive) as outer:
        if outer.testzip() is not None:
            raise ValueError("Corrupt plugin ZIP")
        for name in outer.namelist():
            path = PurePosixPath(name)
            if path.is_absolute() or ".." in path.parts or not path.parts or path.parts[0] != "ChangeLines":
                raise ValueError(f"Unexpected archive path: {name}")
            if not name.endswith(".jar"):
                continue
            if len(path.parts) < 3 or path.parts[1] != "lib":
                raise ValueError(f"Unexpected JAR path: {name}")
            with ZipFile(io.BytesIO(outer.read(name))) as jar:
                if jar.testzip() is not None:
                    raise ValueError(f"Corrupt JAR: {name}")
                if "META-INF/plugin.xml" in jar.namelist():
                    descriptors.append(ET.fromstring(jar.read("META-INF/plugin.xml")))
                for entry in jar.namelist():
                    if not entry.startswith("dev/subtlespark/changelines/") or not entry.endswith(".class"):
                        continue
                    data = jar.read(entry)
                    if data[:4] != b"\xca\xfe\xba\xbe" or int.from_bytes(data[6:8], "big") > 65:
                        raise ValueError(f"Not Java 21 compatible: {entry}")
                    if "Test" in PurePosixPath(entry).name:
                        raise ValueError(f"Packaged test class: {entry}")
                    classes.add(entry[:-6].replace("/", "."))
    if len(descriptors) != 1:
        raise ValueError("Expected one plugin descriptor")
    descriptor = descriptors[0]
    if descriptor.findtext("id") != "dev.subtlespark.changelines":
        raise ValueError("Unexpected plugin ID")
    version = descriptor.findtext("version", "")
    if not re.fullmatch(r"\d+\.\d+\.\d+", version) or archive.name != f"ChangeLines-{version}.zip":
        raise ValueError("Invalid version or ZIP filename")
    idea = descriptor.find("idea-version")
    if idea is None or idea.get("since-build") != "261":
        raise ValueError("Minimum IDEA build must be 261")
    required_modules = {"intellij.platform.vcs.impl.shared", "intellij.platform.vcs.impl"}
    actual_modules = {node.get("name") for node in descriptor.findall("dependencies/module")}
    if not required_modules.issubset(actual_modules):
        raise ValueError("Missing Changes tree or VCS Diff runtime module")
    prefix = "dev.subtlespark.changelines."
    for name in ("ChangeLinesStartup", "ChangeLinesInstaller", "ChangeLinesRenderer", "ReviewSession", "LineStatsService", "ReviewDiffExtension"):
        if prefix + name not in classes:
            raise ValueError(f"Missing comparison integration class: {name}")
    for point, implementation in (("postStartupActivity", "ChangeLinesStartup"), ("diff.DiffExtension", "ReviewDiffExtension")):
        node = descriptor.find(f"extensions/{point}")
        if node is None or node.get("implementation") != prefix + implementation:
            raise ValueError(f"Missing actual Changes integration extension: {point}")
    for action in descriptor.findall(".//actions//action"):
        implementation = action.get("class", "")
        if implementation.startswith(prefix) and implementation not in classes:
            raise ValueError(f"Missing action implementation: {implementation}")
    print(f"Validated {archive.name}: IDEA 261+, {len(classes)} production classes, Changes Between entry points present")
    if output := os.environ.get("GITHUB_OUTPUT"):
        with open(output, "a", encoding="utf-8") as stream:
            stream.write(f"version={version}\ntag=v{version}\n")
    return version


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: check_distribution.py <directory-containing-plugin-zip>")
    try:
        check(Path(sys.argv[1]))
    except (ValueError, OSError, ET.ParseError) as error:
        raise SystemExit(str(error)) from error
