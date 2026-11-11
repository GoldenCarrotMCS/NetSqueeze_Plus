"""Release policy tests, executed by GitHub Actions rather than on developers' machines."""
import os
import runpy
import unittest
import urllib.error
from pathlib import Path
from unittest.mock import patch

MODULE = runpy.run_path(str(Path(__file__).with_name("check_release.py")))
coordinates = MODULE["release_coordinates"]
get = MODULE["get"]
SHA = "0123456789abcdef0123456789abcdef01234567"


class ReleasePolicyTest(unittest.TestCase):
    def test_master_is_versioned_stable(self):
        result = coordinates("1.2.0", "refs/heads/master", SHA)
        self.assertEqual(result["tag"], "v1.2.0")
        self.assertEqual(result["prerelease"], "false")

    def test_branch_is_commit_specific_prerelease(self):
        result = coordinates("1.2.0", "refs/heads/feat/velocity-tunnel-1.2.0", SHA)
        self.assertEqual(result["tag"], "preview-v1.2.0-0123456789ab")
        self.assertEqual(result["prerelease"], "true")
        next_commit = coordinates("1.2.0", "refs/heads/feat/velocity-tunnel-1.2.0", "f" * 40)
        self.assertNotEqual(result["tag"], next_commit["tag"])

    def test_pr_refs_tags_and_empty_branch_are_rejected(self):
        for ref in ("refs/pull/1/merge", "refs/tags/v1.2.0", "refs/heads/", "master"):
            with self.subTest(ref=ref), self.assertRaises(ValueError):
                coordinates("1.2.0", ref, SHA)

    def test_unsafe_version_and_commit_values_are_rejected(self):
        for version in ("1.2.0\nexists=false", '1.2.0"; echo bad', ""):
            with self.subTest(version=version), self.assertRaises(ValueError):
                coordinates(version, "refs/heads/master", SHA)
        with self.assertRaises(ValueError):
            coordinates("1.2.0", "refs/heads/master", "not-a-sha")

    def test_only_http_404_means_missing(self):
        with patch.dict(os.environ, {"GH_TOKEN": "test-only-placeholder"}):
            for code in (401, 403, 429, 500):
                error = urllib.error.HTTPError("https://api.github.com/test", code, "test", {}, None)
                with self.subTest(code=code), patch("urllib.request.urlopen", side_effect=error):
                    with self.assertRaises(urllib.error.HTTPError):
                        get("test")
            missing = urllib.error.HTTPError("https://api.github.com/test", 404, "test", {}, None)
            with patch("urllib.request.urlopen", side_effect=missing):
                self.assertIsNone(get("test"))
            with patch("urllib.request.urlopen", side_effect=urllib.error.URLError("test offline")):
                with self.assertRaises(urllib.error.URLError):
                    get("test")


if __name__ == "__main__":
    unittest.main()
