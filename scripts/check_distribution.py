#!/usr/bin/env python3
"""Validate the plugin archive before publishing it; uses only Python's standard library."""
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
        raise ValueError(f"Expected exactly one plugin ZIP in {directory}, found {len(archives)}")
    archive = archives[0]
    descriptors: list[ET.Element] = []
    classes = 0
    with ZipFile(archive) as outer:
        if outer.testzip() is not None:
            raise ValueError("Plugin ZIP is corrupt")
        for name in outer.namelist():
            path = PurePosixPath(name)
            if path.is_absolute() or ".." in path.parts or not path.parts or path.parts[0] != "ChangeLines":
                raise ValueError(f"Unexpected archive path: {name}")
            if not name.endswith(".jar"):
                continue
            if len(path.parts) < 3 or path.parts[1] != "lib":
                raise ValueError(f"JAR is not in the plugin lib directory: {name}")
            with ZipFile(io.BytesIO(outer.read(name))) as jar:
                if jar.testzip() is not None:
                    raise ValueError(f"Corrupt plugin JAR: {name}")
                if "META-INF/plugin.xml" in jar.namelist():
                    descriptors.append(ET.fromstring(jar.read("META-INF/plugin.xml")))
                for entry in jar.namelist():
                    if entry.startswith("dev/subtlespark/changelines/") and entry.endswith(".class"):
                        data = jar.read(entry)
                        if data[:4] != b"\xca\xfe\xba\xbe" or int.from_bytes(data[6:8], "big") > 65:
                            raise ValueError(f"Not Java 21 compatible: {entry}")
                        if "Test" in PurePosixPath(entry).name:
                            raise ValueError(f"Test class must not be packaged: {entry}")
                        classes += 1
    if len(descriptors) != 1 or classes == 0:
        raise ValueError("Expected one plugin descriptor and compiled production classes")
    descriptor = descriptors[0]
    if descriptor.findtext("id") != "dev.subtlespark.changelines":
        raise ValueError("Unexpected plugin ID")
    version = descriptor.findtext("version", "")
    if not re.fullmatch(r"\d+\.\d+\.\d+", version):
        raise ValueError(f"Unexpected release version: {version}")
    if archive.name != f"ChangeLines-{version}.zip":
        raise ValueError("Archive filename and plugin version differ")
    idea = descriptor.find("idea-version")
    if idea is None or idea.get("since-build") != "261":
        raise ValueError("Minimum IDEA build must be 261 (2026.1)")
    dependencies = {node.get("name") for node in descriptor.findall("dependencies/module")}
    required = {"intellij.platform.lang.impl", "intellij.platform.vcs.impl"}
    if not required.issubset(dependencies):
        raise ValueError(f"Missing runtime module dependencies: {sorted(required - dependencies)}")
    print(f"Validated {archive.name}: version {version}, IDEA 261+, {classes} production classes")
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
