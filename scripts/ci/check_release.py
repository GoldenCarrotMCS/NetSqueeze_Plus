"""Read-only release checks. Branch builds are immutable prereleases, never latest stable."""
import json
import os
import re
import urllib.error
import urllib.request
from pathlib import Path

VERSION_PATTERN = r"[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?"


def release_coordinates(version, ref, sha):
    if not re.fullmatch(VERSION_PATTERN, version):
        raise ValueError("Invalid release version")
    if not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise ValueError("Invalid source commit")
    if not ref.startswith("refs/heads/") or ref == "refs/heads/":
        raise ValueError("Only branch builds can publish; PR merge refs and tags cannot")
    preview = ref != "refs/heads/master"
    return {
        "version": version,
        "tag": f"preview-v{version}-{sha[:12]}" if preview else f"v{version}",
        "prerelease": "true" if preview else "false",
        "title": f"NetSqueeze {version} preview ({sha[:12]})" if preview else f"NetSqueeze {version}",
    }


def get(path):
    request = urllib.request.Request(
        "https://api.github.com/" + path,
        headers={"Authorization": "Bearer " + os.environ["GH_TOKEN"],
                 "Accept": "application/vnd.github+json", "User-Agent": "NetSqueeze-CI"})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return None
        raise


def main():
    text = Path("build.gradle.kts").read_text(encoding="utf-8")
    versions = re.findall(r'^version = "(' + VERSION_PATTERN + r')"$', text, re.M)
    if len(versions) != 1:
        raise ValueError("Expected one root release version")
    output = release_coordinates(versions[0], os.environ["GITHUB_REF"], os.environ["GITHUB_SHA"])
    tag = output["tag"]
    repo = os.environ["GITHUB_REPOSITORY"]
    if get(f"repos/{repo}") is None:
        raise ValueError("Repository is inaccessible; do not treat an auth 404 as a missing release")
    release = get(f"repos/{repo}/releases/tags/{tag}")
    if release is None:
        ref = get(f"repos/{repo}/git/ref/tags/{tag}")
        if ref:
            obj = ref["object"]
            for _ in range(10):
                if obj["type"] != "tag":
                    break
                obj = get(f"repos/{repo}/git/tags/{obj['sha']}")["object"]
            if obj["type"] != "commit" or obj["sha"] != os.environ["GITHUB_SHA"]:
                raise ValueError("Existing tag points to another commit; refusing to overwrite")
    output["exists"] = "true" if release else "false"
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as out:
        for key, value in output.items():
            out.write(f"{key}={value}\n")
    print(f"Release plan: {tag} (prerelease={output['prerelease']}, exists={output['exists']})")


if __name__ == "__main__":
    main()
