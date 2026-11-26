#!/usr/bin/env python3
"""Check that main's patches-bundle.json advertises a real, stable, published bundle.

Read-only. The metadata's download_url must equal exactly
https://github.com/<repo>/releases/download/v<version>/patches-<version>.mpp, and the GitHub release
v<version> must exist, be a published non-prerelease and carry that asset with the same
browser_download_url. Usage: verify_stable_metadata.py <repo>   (needs GH_TOKEN for `gh api`).
"""

import json
import re
import subprocess
import sys

STABLE_VERSION_RE = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+$")
DIGEST_RE = re.compile(r"^sha256:[0-9a-f]{64}$")


def check(metadata, release, repo):
    """Return (errors, notes). `release` is the GitHub API release object, or None if absent."""
    errors, notes = [], []
    version = metadata.get("version") or ""
    download = metadata.get("download_url") or ""
    if not version:
        return ["main's patches-bundle.json has no version"], notes
    if not STABLE_VERSION_RE.match(version):
        return [f"main's patches-bundle.json advertises '{version}', not a stable X.Y.Z version"], notes

    asset_name = f"patches-{version}.mpp"
    expected = f"https://github.com/{repo}/releases/download/v{version}/{asset_name}"
    if download != expected:
        errors.append(f"main's download_url '{download}' is not exactly {expected}")

    if release is None:
        errors.append(f"GitHub release v{version} does not exist")
        return errors, notes
    if release.get("tag_name") != f"v{version}":
        errors.append(f"GitHub release tag is '{release.get('tag_name')}', not v{version}")
    if release.get("prerelease") is not False:
        errors.append(f"GitHub release v{version} is marked as a prerelease")
    if release.get("draft"):
        errors.append(f"GitHub release v{version} is still a draft")

    matches = [a for a in release.get("assets") or [] if a.get("name") == asset_name]
    if len(matches) != 1:
        errors.append(f"GitHub release v{version} has {len(matches)} assets named {asset_name}, expected 1")
    else:
        asset = matches[0]
        if asset.get("browser_download_url") != expected:
            errors.append(
                f"asset {asset_name} has browser_download_url '{asset.get('browser_download_url')}', "
                f"not {expected}"
            )
        digest = asset.get("digest")
        if digest:
            if DIGEST_RE.match(digest):
                notes.append(f"asset digest {digest}")
            else:
                errors.append(f"asset {asset_name} has a malformed digest '{digest}'")
        else:
            notes.append("the API reports no digest for the asset")
    return errors, notes


def gh_json(*args):
    result = subprocess.run(["gh", *args], capture_output=True, text=True)
    if result.returncode != 0:
        return None, result.stderr.strip()
    return json.loads(result.stdout), ""


def main(argv):
    repo = argv[1]
    # Read through the API, not raw.githubusercontent.com: the raw CDN caches per path and ignores
    # cache-busting query strings, so it can serve a stale file right after a release.
    raw = subprocess.run(
        ["gh", "api", f"repos/{repo}/contents/patches-bundle.json?ref=main",
         "-H", "Accept: application/vnd.github.raw"],
        capture_output=True, text=True, check=True,
    ).stdout
    metadata = json.loads(raw)
    print(f"main channel: {metadata.get('version')} -> {metadata.get('download_url')}")
    release = None
    version = metadata.get("version") or ""
    if STABLE_VERSION_RE.match(version):
        release, _ = gh_json("api", f"repos/{repo}/releases/tags/v{version}")
    errors, notes = check(metadata, release, repo)
    for error in errors:
        print(f"::error::{error}")
    if errors:
        return 1
    print(f"OK: main advertises stable {version}; release v{version} is published, not a prerelease "
          f"and carries the exact asset ({'; '.join(notes)})")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
