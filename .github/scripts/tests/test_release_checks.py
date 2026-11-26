"""Run the existing release-check shell blocks against offline GitHub/Git fixtures.

Run with: python -m unittest discover -s .github/scripts/tests -v
Requires Bash and its standard utilities; no GitHub credentials or network are used.
"""

import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import verify_stable_metadata  # noqa: E402


ROOT = Path(__file__).resolve().parents[3]
BASH = next(
    (str(p) for p in (Path(r"C:\Program Files\Git\bin\bash.exe"),)
     if p.is_file()),
    shutil.which("bash"),
)


def shell_step(filename, step_name):
    """Extract one literal run block, keeping the workflow as the tested source."""
    lines = (ROOT / ".github/workflows" / filename).read_text(encoding="utf-8").splitlines()
    start = lines.index("      - name: " + step_name)
    run = next(i for i in range(start + 1, len(lines)) if lines[i] == "        run: |")
    body = []
    for line in lines[run + 1:]:
        if line.strip() and not line.startswith("          "):
            break
        body.append(line[10:] if line.strip() else "")
    if not body:
        raise AssertionError("Missing run block: " + step_name)
    return "\n".join(body) + "\n"


PUBLISHED_MOCKS = r'''
git() {
  case "$1" in
    ls-remote) printf '%s\trefs/tags/v1.2.3\n' "$AUDIT_TAG_COMMIT" ;;
    cat-file) return 0 ;;
    rev-parse) printf '%s\n' "$AUDIT_PARENT" ;;
    *) echo "unexpected git call: $*" >&2; return 99 ;;
  esac
}
gh() {
  if [ "$1 $2" != "release view" ]; then
    echo "unexpected gh call: $*" >&2; return 99
  fi
  printf '%s\n' "$AUDIT_REMOTE_DIGEST"
}
'''

@unittest.skipUnless(BASH, "Bash is required for offline workflow checks")
class ReleaseChecksTest(unittest.TestCase):
    def run_block(self, filename, step_name, mocks, env, setup=None):
        with tempfile.TemporaryDirectory(prefix="heval-release-check-") as tmp:
            directory = Path(tmp)
            if setup:
                setup(directory)
            output = directory / "github-output.txt"
            script = directory / "check.sh"
            script.write_text(mocks + shell_step(filename, step_name), encoding="utf-8")
            process_env = os.environ.copy()
            # All GitHub/Git calls in these blocks resolve to the functions above.
            process_env.update(env)
            process_env.update(GITHUB_OUTPUT=output.as_posix(), GITHUB_REPOSITORY="audit/repo",
                               REPO="audit/repo", AUDIT_PYTHON=sys.executable)
            result = subprocess.run([BASH, str(script)], cwd=directory, env=process_env,
                                    capture_output=True, text=True, timeout=30)
            return result, output.read_text() if output.exists() else "", (directory / "curl-called.txt").exists()

    def published(self, count=1, tag=None, parent=None, digest=None):
        content = b"offline test bundle"
        local_digest = "sha256:" + hashlib.sha256(content).hexdigest()

        def setup(directory):
            libs = directory / "patches/build/libs"
            libs.mkdir(parents=True)
            for index in range(count):
                (libs / ("patches-1.2.%d.mpp" % (3 + index))).write_bytes(content)

        return self.run_block(
            "release.yml", "Check for a release published by this run", PUBLISHED_MOCKS,
            {"RUN_SHA": "a" * 40, "AUDIT_TAG_COMMIT": tag or "a" * 40,
             "AUDIT_PARENT": parent or "b" * 40,
             "AUDIT_REMOTE_DIGEST": local_digest if digest is None else digest}, setup,
        )

    def test_no_bundle_is_not_attested(self):
        result, output, _ = self.published(count=0)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("published=false", output)

    def test_ambiguous_bundles_are_not_attested(self):
        result, output, _ = self.published(count=2)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("published=false", output)

    def test_current_run_and_matching_digest_are_attested(self):
        result, output, _ = self.published()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("published=true", output)
        self.assertIn("tag=v1.2.3", output)

    def test_release_commit_directly_after_run_is_accepted(self):
        result, output, _ = self.published(tag="c" * 40, parent="a" * 40)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("published=true", output)

    def test_another_run_cannot_attest_an_older_tag(self):
        result, output, _ = self.published(tag="c" * 40)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("published=false", output)

    def test_digest_mismatch_fails_without_attesting(self):
        result, output, _ = self.published(digest="sha256:" + "0" * 64)
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertIn("published=false", output)
        self.assertNotIn("published=true", output)
        self.assertIn("::error::", result.stdout)

    def test_missing_asset_on_this_runs_release_fails(self):
        result, output, _ = self.published(digest="")
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertNotIn("published=true", output)
        self.assertIn("::error::", result.stdout)


class StableMetadataTest(unittest.TestCase):
    REPO = "audit/repo"
    URL = "https://github.com/audit/repo/releases/download/v1.2.3/patches-1.2.3.mpp"

    def release(self, **overrides):
        release = {"tag_name": "v1.2.3", "prerelease": False, "draft": False,
                   "assets": [{"name": "patches-1.2.3.mpp", "browser_download_url": self.URL,
                               "digest": "sha256:" + "a" * 64}]}
        release.update(overrides)
        return release

    def run_check(self, version="1.2.3", download=None, release="default"):
        metadata = {"version": version, "download_url": download or self.URL}
        rel = self.release() if release == "default" else release
        return verify_stable_metadata.check(metadata, rel, self.REPO)

    def test_exact_url_passes(self):
        errors, notes = self.run_check()
        self.assertEqual(errors, [])
        self.assertTrue(any("digest" in n for n in notes))

    def test_prerelease_version_is_rejected(self):
        self.assertTrue(self.run_check(version="1.2.3-dev.1")[0])

    def test_missing_version_is_rejected(self):
        self.assertTrue(self.run_check(version="")[0])

    def test_unrelated_origin_is_rejected(self):
        url = "https://example.invalid/audit/repo/releases/download/v1.2.3/patches-1.2.3.mpp"
        self.assertTrue(self.run_check(download=url)[0])

    def test_other_repository_is_rejected(self):
        url = "https://github.com/evil/repo/releases/download/v1.2.3/patches-1.2.3.mpp"
        self.assertTrue(self.run_check(download=url)[0])

    def test_filename_suffix_or_substring_variants_are_rejected(self):
        for url in (self.URL + ".evil", self.URL + "?x=1", "https://evil.test/#" + self.URL,
                    self.URL.replace("patches-1.2.3.mpp", "x-patches-1.2.3.mpp")):
            with self.subTest(url=url):
                self.assertTrue(self.run_check(download=url)[0])

    def test_github_prerelease_flag_is_rejected(self):
        self.assertTrue(self.run_check(release=self.release(prerelease=True))[0])

    def test_missing_release_or_asset_is_rejected(self):
        self.assertTrue(self.run_check(release=None)[0])
        self.assertTrue(self.run_check(release=self.release(assets=[]))[0])

    def test_asset_with_a_different_download_url_is_rejected(self):
        asset = {"name": "patches-1.2.3.mpp", "browser_download_url": self.URL + "x"}
        self.assertTrue(self.run_check(release=self.release(assets=[asset]))[0])

    def test_workflow_runs_the_script(self):
        text = (ROOT / ".github/workflows/verify-stable-metadata.yml").read_text(encoding="utf-8")
        self.assertIn("verify_stable_metadata.py", text)


if __name__ == "__main__":
    unittest.main()
