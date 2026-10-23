#!/usr/bin/env python3
"""Fetch the pinned, build-only Vulkan headers and GLSL compiler from Khronos."""

import argparse
import concurrent.futures
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import urllib.request
import zipfile


VULKAN_HEADERS_VERSION = "1.4.341"
VULKAN_HEADERS_COMMIT = "b5c8f996196ba4aa6d8f97e52b5d3b6e70f7e4e2"
VULKAN_HEADERS_SHA256 = "7937d8ec21785736436d9ff52564db7d55992070b5ac98324472cce6e425a7ba"
GLSLANG_VERSION = "16.6.0"
GLSLANG_SHA256 = {
    "linux-x86_64": "a3fc4f083b1793eb53e55fa3577ac9649ffbe0340715e30d116290fb5382393f",
    "windows-x86_64": "82bf434e69b9bb4829de7e2b4bc2c5e7a7861e53d66cf75e5cc70f5f694a8d9b",
}


def download(url, destination, expected_sha256):
    if not destination.is_file():
        request = urllib.request.Request(url, headers={"User-Agent": "obs-overlay-native-build"})
        with urllib.request.urlopen(request, timeout=60) as response:
            with destination.open("wb") as output:
                shutil.copyfileobj(response, output)
    actual = hashlib.sha256(destination.read_bytes()).hexdigest()
    if actual != expected_sha256:
        raise RuntimeError(f"SHA-256 mismatch for {destination.name}: {actual}")
    return destination


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--platform", choices=GLSLANG_SHA256, required=True)
    parser.add_argument("--directory", type=Path, default=Path("build/native-tools"))
    args = parser.parse_args()
    directory = args.directory.resolve()
    downloads = directory / "downloads"
    downloads.mkdir(parents=True, exist_ok=True)

    headers_archive = downloads / f"Vulkan-Headers-{VULKAN_HEADERS_COMMIT}.tar.gz"
    glslang_name = f"glslang-{GLSLANG_VERSION}-{args.platform}-release.zip"
    glslang_archive = downloads / glslang_name
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
        futures = [
            executor.submit(
                download,
                f"https://codeload.github.com/KhronosGroup/Vulkan-Headers/tar.gz/{VULKAN_HEADERS_COMMIT}",
                headers_archive,
                VULKAN_HEADERS_SHA256,
            ),
            executor.submit(
                download,
                f"https://github.com/KhronosGroup/glslang/releases/download/{GLSLANG_VERSION}/{glslang_name}",
                glslang_archive,
                GLSLANG_SHA256[args.platform],
            ),
        ]
        for future in futures:
            future.result()

    headers = directory / "Vulkan-Headers"
    with tempfile.TemporaryDirectory(dir=directory) as temporary:
        with tarfile.open(headers_archive, "r:gz") as archive:
            archive.extractall(temporary, filter="data")
        source = Path(temporary) / f"Vulkan-Headers-{VULKAN_HEADERS_COMMIT}"
        shutil.copytree(source, headers, dirs_exist_ok=True)

    glslang = directory / "glslang"
    with zipfile.ZipFile(glslang_archive) as archive:
        archive.extractall(glslang)
    executable = glslang / "bin" / ("glslang.exe" if args.platform.startswith("windows") else "glslang")
    if os.name != "nt":
        executable.chmod(executable.stat().st_mode | 0o111)
    subprocess.run([str(executable), "--version"], check=True)

    include = headers / "include"
    if not (include / "vulkan/vk_layer.h").is_file():
        raise RuntimeError("The Vulkan headers archive does not contain vk_layer.h")
    print(f"Vulkan-Headers {VULKAN_HEADERS_VERSION}: {include}")
    print(f"glslang {GLSLANG_VERSION}: {executable}")
    if "GITHUB_ENV" in os.environ:
        with open(os.environ["GITHUB_ENV"], "a", encoding="utf-8") as environment:
            environment.write(f"VULKAN_HEADERS_DIR={include.as_posix()}\n")
            environment.write(f"GLSLANG_VALIDATOR={executable.as_posix()}\n")


if __name__ == "__main__":
    main()
