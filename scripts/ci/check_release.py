"""Read-only GitHub checks: distinguish a missing release from auth/network failures."""
import json
import os
import re
import urllib.error
import urllib.request
from pathlib import Path


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
    versions = re.findall(r'^version = "([0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?)"$', text, re.M)
    if len(versions) != 1:
        raise ValueError("Invalid release version")
    version = versions[0]
    tag = "v" + version
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
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as out:
        out.write(f"version={version}\nexists={'true' if release else 'false'}\n")


if __name__ == "__main__":
    main()
