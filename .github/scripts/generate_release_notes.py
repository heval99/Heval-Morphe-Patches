#!/usr/bin/env python3
"""Write the release notes for one release of this patch bundle (Markdown on stdout).

semantic-release runs this as @semantic-release/exec's generateNotesCmd (see .releaserc); its
stdout becomes nextRelease.notes, which goes into CHANGELOG.md (what Morphe Manager shows),
patches-bundle.json "description", the release commit message and the GitHub release body.
docs/release-notes.md describes the layout, the commit trailers and the local preview.

What the notes are built from, all deterministic:
  * patches-list.json at both ends of the range: new, removed and changed apps, added and
    removed patches, default changes and every compatibility change (per patch, with build
    numbers when only those changed);
  * the user-facing commits of the range (feat/fix/perf), grouped by the app their scope names,
    with optional trailers (Release-note, Release-category, Release-highlight, Action-required)
    and "BREAKING CHANGE:" footers;
  * .github/release-notes.json: known issues (shown until resolved) and per-commit overrides for
    commits that are already pushed.

    generate_release_notes.py --repo OWNER/REPO --version 2.2.0 --previous-tag v2.1.0 --build
    generate_release_notes.py --preview --from v3.3.0-dev.2 --to v3.3.0-dev.3 --output notes.md

Range: CI passes semantic-release's lastRelease, which is already channel-aware (main: the
previous stable release; dev: the previous release of either channel, as stable releases are
back-merged into dev). Without --previous-tag the script picks the same tag itself.

With --build, the new patches-list.json comes from `./gradlew generatePatchesList` run for the
next version (Gradle's stdout goes to stderr); the tracked patches-list.json is put back as it
was, so semantic-release's prepare step still writes the real one. When the working tree already
holds the list for the next version (semantic-release runs generateNotes again after prepare),
that list is used and nothing is built. --preview never builds and never writes tracked files.

The script never fails the release: an error in the full notes falls back to a plain list of the
user-facing commits, and an error there to a heading and the download links. It always exits 0.
Python stdlib only.
"""

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import unicodedata

UNIVERSAL = "*"
PATCHES_DIR = Path("patches/src/main/kotlin/app/template/patches")
CONSTANTS = PATCHES_DIR / "shared" / "Constants.kt"
CONFIG_FILE = ".github/release-notes.json"

# Categories of a change line, in the order an app block lists them.
CATEGORIES = ["Added", "Improved", "Fixed", "Changed", "Performance", "Deprecated", "Removed"]
COMPATIBILITY = "Compatibility"
INTERNAL = "Internal"
TYPE_CATEGORY = {"feat": "Improved", "fix": "Fixed", "perf": "Performance"}
CATEGORY_NAMES = {c.lower(): c for c in CATEGORIES + [COMPATIBILITY, INTERNAL]}
CATEGORY_NAMES.update({"new": "Added", "faster": "Performance", "perf": "Performance",
                       "compat": COMPATIBILITY, "fix": "Fixed", "feature": "Added"})
SKIP_VALUES = {"none", "skip", "no", "-"}

# Commit scopes for code that is not one app's. Shared patch code and the shared extensions go
# under "Shared patches"; repository work under "Internal improvements".
SHARED_SCOPES = {
    "shared", "universal", "all", "gms", "gmscore", "signature", "spoofsignature", "screenshot",
    "controls", "hevalcontrols", "patchsettings", "ads", "webviewads", "dns", "telemetry",
    "theme", "packagename", "clone", "clones", "danishnews",
}
# Words that add nothing to a feat subject that announces patches ("add a switch to ...").
FILLER_TEXT = (
    "a an the and or to for of in on at by it its them this that with from into above below "
    "under over when so all every add adds added new switch switches option options toggle "
    "setting settings patch patches opt-in support"
)
# An unscoped commit that adds or drops whole apps: the New/Removed apps lists say it.
APP_LIST_VERB_RE = re.compile(r"^(?:add|adds|drop|drops|remove|removes|support)\b", re.IGNORECASE)
INTERNAL_SCOPES = {
    "ci", "release", "releases", "build", "deps", "depsdev", "docs", "doc", "test", "tests",
    "readme", "tools", "tooling", "workflow", "workflows", "scripts", "repo", "github", "agents",
    "claude", "templates", "issues", "triage", "lint",
}

SUBJECT_RE = re.compile(
    r"^(?P<type>[A-Za-z]+)(?:\((?P<scope>[^)]*)\))?(?P<bang>!)?:\s*(?P<subject>.+)$"
)
RELEASE_COMMIT_RE = re.compile(r"^chore(?:\([^)]*\))?:\s*release\s+v?\d", re.IGNORECASE)
REVERT_SUBJECT_RE = re.compile(r'^Revert\s+"(?P<subject>.+)"\s*$')
REVERTS_SHA_RE = re.compile(r"\bThis reverts commit ([0-9a-f]{7,40})\b")
TRAILER_RE = re.compile(
    r"^(?P<key>Release-note|Release-category|Release-highlight|Action-required|Ported-from)"
    r"\s*:\s*(?P<value>.*\S)\s*$",
    re.IGNORECASE,
)
# Only what commit-analyzer's angular preset (no preset is set in .releaserc) counts as breaking,
# so the notes never say "Breaking" for a release that is not major. It ignores "feat!:" and
# "BREAKING-CHANGE:"; those are logged instead.
BREAKING_FOOTER_RE = re.compile(r"^BREAKING CHANGE:\s*(.*)$", re.IGNORECASE)
NEAR_BREAKING_RE = re.compile(r"^BREAKING[ -]CHANGES?\b", re.IGNORECASE)
# git's own subject for reverting a revert: newer git writes Reapply "X", older Revert "Revert "X"".
REAPPLY_SUBJECT_RE = re.compile(r'^(?:Reapply\s+"(?P<a>.+)"|Revert\s+"Revert\s+"(?P<b>.+)"")\s*$')
# "@name" or "@org/team" that GitHub would turn into a notification; not part of an e-mail or path.
MENTION_RE = re.compile(r"(?<![\w.`/@])@[A-Za-z0-9][A-Za-z0-9-]*(?:/[A-Za-z0-9][\w.-]*)?")
TRAILER_LINE_RE = re.compile(r"^[A-Za-z][A-Za-z-]*(?::\s|\s#)")
# "#123" that is not part of a word, a URL path or an existing Markdown link text.
ISSUE_RE = re.compile(r"(?<![\w/&\[])#(\d+)\b")
UPDATED_TO_VERSION_RE = re.compile(r"^updated? to version\b", re.IGNORECASE)
UPDATED_TO_VERSION_PART_RE = re.compile(
    r"\s*[,;]?\s*(?:and\s+)?updated?\s+to\s+version\s+\S+(?:\s+\(\d+\))?", re.IGNORECASE
)
# A trailing housekeeping clause that says nothing to users, such as ", refresh stale notes" or
# "; record policy notes". Only at the very end, so ", add notes export" stays.
HOUSEKEEPING_RE = re.compile(
    r"\s*[,;]\s*(?:and\s+)?(?:(?:record|refresh|tidy)\s+(?:the\s+)?(?:\w+\s+)?(?:notes|docs|comments)"
    r"|(?:add|update)\s+(?:the\s+)?(?:smoke\s+|unit\s+)?tests?)\s*$",
    re.IGNORECASE,
)
DEFAULT_WORDS_RE = re.compile(r"\b(?:default|opt-in|opt in|opt-out|opt out)\b", re.IGNORECASE)
COMPAT_VAL_RE = re.compile(r"\bval\s+(COMPATIBILITY_\w+)\s*=\s*Compatibility\(")
PACKAGE_NAME_RE = re.compile(r'\bpackageName\s*=\s*"([^"]+)"')
COMPAT_REF_RE = re.compile(r"\bCOMPATIBILITY_[A-Z0-9_]+\b")
SEMVER_RE = re.compile(r"^v?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?$")
# Lines commit-analyzer could read as a breaking-change note if they echo back in a release
# commit body (its pattern is case-insensitive and tolerates a leading "* ").
UNSAFE_NOTE_LINE_RE = re.compile(r"^[\s|*]*breaking[ -]changes?\b", re.IGNORECASE)
# The notes show this many apps or more with a one-line summary of the counts.
SUMMARY_MIN_APPS = 8


def log(message):
    print(f"[release-notes] {message}", file=sys.stderr)


# ---------------------------------------------------------------------------------------------
# Small helpers
# ---------------------------------------------------------------------------------------------

def normalize(text):
    return re.sub(r"[^a-z0-9]", "", (text or "").lower())


def stem(word):
    """A light stemmer so "tapping" meets "Tap" and "cards" meets "card"."""
    for suffix, minimum in (("ing", 5), ("ed", 4), ("es", 4), ("s", 3)):
        if len(word) > minimum and word.endswith(suffix) and not word.endswith("ss"):
            word = word[: -len(suffix)]
            if len(word) > 2 and word[-1] == word[-2] and word[-1] not in "aeiouls":
                word = word[:-1]
            break
    return word


def words(text):
    return {stem(w) for w in re.findall(r"[a-z0-9]+", (text or "").lower())}


def semver_key(version):
    """A sort key for vX.Y.Z[-pre]; None for anything else. A release sorts after its pre-releases."""
    match = SEMVER_RE.match((version or "").strip())
    if not match:
        return None
    pre = match.group(4)
    pre_key = tuple((0, int(p), "") if p.isdigit() else (1, 0, p) for p in pre.split(".")) if pre else ()
    return (int(match.group(1)), int(match.group(2)), int(match.group(3)), 0 if pre else 1, pre_key)


def is_prerelease(version):
    match = SEMVER_RE.match((version or "").strip())
    return bool(match and match.group(4))


def link_issues(text, repo):
    if not repo:
        return text
    return ISSUE_RE.sub(
        lambda m: f"[#{m.group(1)}](https://github.com/{repo}/issues/{m.group(1)})", text
    )


def clean_text(text, repo):
    """A commit subject or note as one sentence: capitalised, no trailing dot, issues linked."""
    text = " ".join((text or "").split()).strip().rstrip(".").strip()
    if text:
        text = text[0].upper() + text[1:]
    return link_issues(neutralise_mentions(text), repo)


def tidy_subject(subject):
    """Drops the parts of a commit subject that other sections already say or that are noise."""
    text = UPDATED_TO_VERSION_PART_RE.sub("", subject)
    text = HOUSEKEEPING_RE.sub("", text)
    return text.strip() or subject


def join_names(names):
    names = list(names)
    if len(names) <= 1:
        return "".join(names)
    return ", ".join(names[:-1]) + " and " + names[-1]


# ---------------------------------------------------------------------------------------------
# Commits
# ---------------------------------------------------------------------------------------------

def git(repo_root, *args):
    return subprocess.run(
        ["git", *args], cwd=repo_root, check=True, capture_output=True, text=True,
        encoding="utf-8", errors="replace",
    ).stdout


def breaking_from_messages(messages, repo=""):
    """Breaking-change texts from full commit messages, in order, without repeats.

    The source is a "BREAKING CHANGE:" footer in the body; its text runs on until a blank line or
    the next trailer. A "!" after the type alone does not count (see BREAKING_FOOTER_RE).
    """
    found, seen = [], set()
    for message in messages:
        for text in breaking_of_message(message):
            text = clean_text(text, repo)
            if text and text.lower() not in seen:
                seen.add(text.lower())
                found.append(text)
    return found


def breaking_of_message(message):
    lines = (message or "").splitlines()
    found = []
    i = 1
    while i < len(lines):
        match = BREAKING_FOOTER_RE.match(lines[i].strip())
        i += 1
        if not match:
            continue
        parts = [match.group(1)]
        while i < len(lines) and lines[i].strip() and not TRAILER_LINE_RE.match(lines[i].strip()):
            parts.append(lines[i].strip())
            i += 1
        found.append(" ".join(parts))
    return [" ".join(text.split()) for text in found if text.strip()]


def breaking_ignored(message):
    """True for a "!" subject or a "BREAKING-CHANGE:"-style footer without a real footer: semantic-
    release will not make the release major, so the notes do not call it breaking."""
    lines = (message or "").splitlines()
    if not lines or breaking_of_message(message):
        return False
    subject = SUBJECT_RE.match(lines[0].strip())
    return bool(subject and subject.group("bang")) or any(
        NEAR_BREAKING_RE.match(line.strip()) for line in lines[1:])


def neutralise_mentions(text):
    """Puts "@name" in backticks outside code spans, so GitHub does not notify anyone."""
    parts = (text or "").split("`")
    for i in range(0, len(parts), 2):
        parts[i] = MENTION_RE.sub(lambda m: f"`{m.group(0)}`", parts[i])
    return "`".join(parts)


def parse_commit(sha, message):
    """One commit as a dict; see the keys below. Trailers are read from the whole body, so a
    Release-note above a separate attribution paragraph still counts."""
    lines = (message or "").strip("\n").splitlines()
    subject = lines[0].strip() if lines else ""
    commit = {
        "sha": sha, "subject": subject, "type": None, "scopes": [], "text": "", "note": None,
        "category": None, "highlights": [], "actions": [], "credits": [], "skip": False,
        "breaking": breaking_of_message(message), "reverts": None, "reverted_subject": None,
        "release": bool(RELEASE_COMMIT_RE.match(subject)),
    }
    for line in lines[1:]:
        match = TRAILER_RE.match(line.strip())
        if not match:
            continue
        key, value = match.group("key").lower(), match.group("value").strip()
        if key == "release-note":
            if value.lower() in SKIP_VALUES:
                commit["skip"] = True
            elif commit["note"] is None:
                commit["note"] = value
        elif key == "release-category":
            category = CATEGORY_NAMES.get(value.lower())
            if category:
                commit["category"] = category
            elif value.lower() in SKIP_VALUES:
                commit["skip"] = True
        elif key == "release-highlight":
            commit["highlights"].append(value)
        elif key == "action-required":
            commit["actions"].append(value)
        elif key == "ported-from":
            commit["credits"].append(value)

    commit["breaking_ignored"] = breaking_ignored(message)
    reapply = REAPPLY_SUBJECT_RE.match(subject)
    if reapply:
        # Re-lands the original change: read it as that commit (its revert pair cancels out).
        subject = reapply.group("a") or reapply.group("b")
        revert = None
    else:
        revert = REVERT_SUBJECT_RE.match(subject)
    if revert:
        commit["reverted_subject"] = revert.group("subject")
        sha_match = REVERTS_SHA_RE.search(message or "")
        commit["reverts"] = sha_match.group(1) if sha_match else None
        return commit
    match = SUBJECT_RE.match(subject)
    if match:
        commit["type"] = match.group("type").lower()
        commit["scopes"] = [s.strip() for s in (match.group("scope") or "").split(",") if s.strip()]
        commit["text"] = match.group("subject").strip()
    return commit


def apply_override(commit, override):
    """Applies one entry of the config file's "commits" map (for commits already pushed)."""
    note = override.get("note")
    if isinstance(note, str):
        if note.strip().lower() in SKIP_VALUES:
            commit["skip"] = True
        else:
            commit["note"] = note.strip()
    category = override.get("category")
    if isinstance(category, str):
        if category.lower() in SKIP_VALUES:
            commit["skip"] = True
        elif category.lower() in CATEGORY_NAMES:
            commit["category"] = CATEGORY_NAMES[category.lower()]
    for key, target in (("highlight", "highlights"), ("action", "actions")):
        value = override.get(key)
        if isinstance(value, str) and value.strip():
            commit[target].append(value.strip())
    if isinstance(override.get("scope"), str):
        commit["scopes"] = [s.strip() for s in override["scope"].split(",") if s.strip()]
    if isinstance(override.get("credits"), list):
        commit["credits"] = [str(c).strip() for c in override["credits"] if str(c).strip()]


def override_for(sha, overrides):
    for key, value in (overrides or {}).items():
        key = str(key).strip().lower()
        if len(key) >= 7 and sha.lower().startswith(key) and isinstance(value, dict):
            return value
    return None


def git_commits(repo_root, previous_ref, to_ref, overrides=None):
    """Commits in previous_ref..to_ref (merges left out), oldest first, parsed."""
    revision = f"{previous_ref}..{to_ref}" if previous_ref else to_ref
    out = git(repo_root, "log", "--no-merges", "--reverse", "--format=%H%x1f%B%x1e", revision)
    commits = []
    for record in out.split("\x1e"):
        record = record.strip("\n")
        if not record.strip():
            continue
        sha, _, message = record.partition("\x1f")
        commit = parse_commit(sha.strip(), message)
        override = override_for(commit["sha"], overrides)
        if override:
            apply_override(commit, override)
        if commit["breaking_ignored"] and not commit["release"]:
            log(f"{commit['sha'][:8]} has '!' or BREAKING-CHANGE but no 'BREAKING CHANGE:' footer; "
                "semantic-release will not make this major, so the notes do not list it as breaking")
        commits.append(commit)
    return commits


def commits_from_subjects(subjects):
    """Commits from bare subject lines (the tests and the plain fallback use these)."""
    return [parse_commit("", subject) for subject in subjects if (subject or "").strip()]


def drop_reverted(commits):
    """Cancels revert pairs inside the range. A revert of an earlier release's commit stays, as a
    "Changed: Reverted ..." line under the reverted commit's scope."""
    shas = {c["sha"] for c in commits if c["sha"]}
    cancelled = set()
    for commit in commits:
        target = commit["reverts"]
        if not target:
            continue
        match = next((s for s in shas if s.startswith(target)), None)
        if match and match not in cancelled:
            cancelled.update({match, commit["sha"]})
    kept = []
    for commit in commits:
        if commit["sha"] and commit["sha"] in cancelled:
            continue
        if commit["reverted_subject"] is not None:
            original = SUBJECT_RE.match(commit["reverted_subject"])
            if not original or original.group("type").lower() not in TYPE_CATEGORY:
                continue
            commit = dict(commit, type=original.group("type").lower(),
                          scopes=[s.strip() for s in (original.group("scope") or "").split(",") if s.strip()],
                          text="Reverted: " + original.group("subject").strip(),
                          category=commit["category"] or "Changed")
        kept.append(commit)
    return kept


def user_facing(commits):
    """Commits that may give a line: feat/fix/perf (or any commit with an explicit note), not
    release commits, not skipped; deduplicated by type, scopes and text."""
    result, seen = [], set()
    for commit in commits:
        if commit["release"] or commit["skip"]:
            continue
        explicit = commit["note"] is not None or commit["category"] is not None
        if commit["type"] not in TYPE_CATEGORY and not explicit:
            continue
        if not commit["text"] and not commit["note"]:
            continue
        if UPDATED_TO_VERSION_RE.match(commit["text"]) and not explicit:
            continue
        text = commit["note"] or commit["text"]
        key = (tuple(normalize(s) for s in commit["scopes"]), normalize(text))
        if key in seen:
            continue
        seen.add(key)
        result.append(commit)
    return result


def line_text(commit, repo):
    if commit["note"]:
        return clean_text(commit["note"], repo)
    return clean_text(tidy_subject(commit["text"]), repo)


def line_category(commit):
    return commit["category"] or TYPE_CATEGORY.get(commit["type"], "Changed")


# ---------------------------------------------------------------------------------------------
# patches-list.json
# ---------------------------------------------------------------------------------------------

def target_key(target):
    """(version or None, sorted (abi, code) pairs) for one compatibility target."""
    codes = target.get("versionCodes") or {}
    if isinstance(codes, dict):
        pairs = tuple(sorted((str(k), int(v)) for k, v in codes.items() if isinstance(v, int)))
    else:
        pairs = ()
    return (target.get("version") or None, pairs)


def load_apps(patch_list):
    """{package: {"name", "patches": {patch: frozenset(target keys)}, "defaults": {patch: bool},
    "descriptions": {patch: str}}} from a parsed patches-list.json.

    Patches without compatiblePackages apply to every app and are grouped under UNIVERSAL.
    A package without targets supports any version.
    """
    apps = {}
    for patch in (patch_list or {}).get("patches") or []:
        name = patch.get("name")
        if not name:
            continue
        packages = patch.get("compatiblePackages") or [{"packageName": UNIVERSAL, "name": "All apps"}]
        for package in packages:
            package_name = package.get("packageName")
            if not package_name:
                continue
            entry = apps.setdefault(package_name, {
                "name": package_name, "patches": {}, "defaults": {}, "descriptions": {},
            })
            if package.get("name"):
                entry["name"] = package["name"]
            targets = package.get("targets") or [{}]
            if package_name == UNIVERSAL:
                targets = []
            entry["patches"][name] = frozenset(target_key(t) for t in targets)
            entry["defaults"][name] = patch.get("default", patch.get("use", True)) is not False
            entry["descriptions"][name] = " ".join((patch.get("description") or "").split())
    return apps


def disambiguate_names(*app_maps):
    """Gives two packages that share a display name their package name in brackets.

    Each app also gets "scope", the bold label its list bullets start with. Morphe Manager files a
    `- **Scope:** ...` bullet under an app when the scope, or its part before " - ", is the app's
    name (letters and digits only). So the scope is the bundle's own display name, and for a shared
    name it is "Name - package": still that name before " - ", and the package tells them apart.
    """
    owners = {}
    for apps in app_maps:
        for package, app in apps.items():
            owners.setdefault(app["name"], set()).add(package)
    for apps in app_maps:
        for package, app in apps.items():
            app["base_name"] = app["name"]
            app["scope"] = app["name"]
            if package != UNIVERSAL and len(owners.get(app["name"], ())) > 1:
                app["scope"] = f"{app['name']} - {package}"
                app["name"] = f"{app['name']} ({package})"


def version_label(keys, with_build=False):
    """`1.2`, `1.2, 1.3`, "any version" or, with with_build, `1.3.4 (build 1341)`."""
    if not keys:
        return "any version"
    labels = []
    for version, codes in sorted(keys, key=lambda k: (k[0] is None, k[0] or "", k[1])):
        if version is None:
            labels.append("any version")
            continue
        label = f"`{version}`"
        if with_build and codes:
            unique = sorted({code for _, code in codes})
            primary = dict(codes).get("ARM64_V8A")
            build = unique[0] if len(unique) == 1 else primary or unique[-1]
            label = f"`{version} (build {build})`"
        labels.append(label)
    return ", ".join(dict.fromkeys(labels))


def plain_versions(keys):
    return {k[0] for k in keys}


def compat_groups(app):
    """[(target keys, [patch names])], the most-supported target set first."""
    groups = {}
    for patch, keys in app["patches"].items():
        groups.setdefault(keys, []).append(patch)
    return sorted(
        ((keys, sorted(names, key=str.lower)) for keys, names in groups.items()),
        key=lambda item: (-len(item[1]), sorted(str(v) for v in plain_versions(item[0]))),
    )


def compat_summary(app, with_build=False):
    """(main version label, [exception texts]) - an exception names patches on other versions."""
    groups = compat_groups(app)
    if not groups:
        return "any version", []
    main = version_label(groups[0][0], with_build)
    exceptions = [
        f"{join_names(names)}: {version_label(keys, with_build)}" for keys, names in groups[1:]
    ]
    return main, exceptions


def compat_change(old, new):
    """None when no patch kept by the app changed its targets, else (from, to, [exceptions]).

    The texts are the main version of each side; build numbers are added when the versions read
    the same, and when even those match (only some patches moved) the other versions are added.
    """
    shared = set(old["patches"]) & set(new["patches"])
    if all(old["patches"][p] == new["patches"][p] for p in shared):
        return None
    old_main, old_exceptions = compat_summary(old)
    new_main, new_exceptions = compat_summary(new)
    if old_main == new_main:
        old_main, old_exceptions = compat_summary(old, with_build=True)
        new_main, new_exceptions = compat_summary(new, with_build=True)
    if old_main == new_main:
        old_main = f"{old_main} (other versions: {'; '.join(old_exceptions) or 'none'})"
        new_main = f"{new_main} (other versions: {'; '.join(new_exceptions) or 'none'})"
        new_exceptions = []
    return old_main, new_main, new_exceptions


def sort_key(apps, package):
    """Apps by name, accents folded so "Århus" sorts with the A's."""
    folded = unicodedata.normalize("NFKD", apps[package]["name"]).encode("ascii", "ignore").decode()
    return (package == UNIVERSAL, folded.lower(), package)


# ---------------------------------------------------------------------------------------------
# Scope -> app
# ---------------------------------------------------------------------------------------------

def scan_patch_dirs(repo_root):
    """{normalized patch directory name: {package names}} from the patch sources.

    Each directory under PATCHES_DIR is mapped through the COMPATIBILITY_* constants its Kotlin
    files use, and Constants.kt maps each constant to its packageName. Missing sources give {}.
    """
    root = Path(repo_root)
    constants_file = root / CONSTANTS
    if not constants_file.is_file():
        return {}
    text = constants_file.read_text(encoding="utf-8", errors="replace")
    constant_packages = {}
    starts = list(COMPAT_VAL_RE.finditer(text))
    for i, match in enumerate(starts):
        end = starts[i + 1].start() if i + 1 < len(starts) else len(text)
        package = PACKAGE_NAME_RE.search(text, match.end(), end)
        if package:
            constant_packages[match.group(1)] = package.group(1)

    index = {}
    for directory in sorted((root / PATCHES_DIR).iterdir()):
        if not directory.is_dir() or directory.name == "shared":
            continue
        packages = set()
        for source in directory.rglob("*.kt"):
            for ref in COMPAT_REF_RE.findall(source.read_text(encoding="utf-8", errors="replace")):
                if ref in constant_packages:
                    packages.add(constant_packages[ref])
        if packages:
            index.setdefault(normalize(directory.name), set()).update(packages)
    return index


def resolve_scope(scope, names, dir_index):
    """Packages a commit scope names, or an empty set.

    Order: a patch directory name; an app name, package name or last package segment; then the
    one app whose name starts with the scope (at least 4 characters, so "gpsmapcamera" finds
    "GPS Map Camera Editor").
    """
    key = normalize(scope)
    if not key:
        return set()
    by_dir = {p for p in dir_index.get(key, set()) if p in names}
    if by_dir:
        return by_dir
    exact = {
        package for package, name in names.items()
        if package != UNIVERSAL
        and key in (normalize(name), normalize(package), normalize(package.rsplit(".", 1)[-1]))
    }
    if exact:
        return exact
    if len(key) >= 4:
        prefixed = {
            package for package, name in names.items()
            if package != UNIVERSAL and normalize(name).startswith(key)
        }
        if len(prefixed) == 1:
            return prefixed
    return set()


def classify(commit, names, dir_index):
    """("app", {packages}) | ("shared", None) | ("internal", None) | ("other", None)."""
    if commit["category"] == INTERNAL:
        return "internal", None
    packages = set()
    for scope in commit["scopes"]:
        packages |= resolve_scope(scope, names, dir_index)
    if packages:
        return "app", packages
    keys = {normalize(s) for s in commit["scopes"]}
    if keys & INTERNAL_SCOPES:
        return "internal", None
    if keys & SHARED_SCOPES:
        return "shared", None
    return "other", None


def mentioned_patches(text, patch_names):
    """Patch names whose every word appears (stemmed) in the text."""
    text_words = words(text)
    return {name for name in patch_names if words(name) and words(name) <= text_words}


def covered_by_added_patches(subject, added):
    """True for an "add X, Y and Z" commit whose every item is a patch added in this release."""
    match = re.match(r"^adds?\s+(.+)$", subject.strip().rstrip("."), re.IGNORECASE)
    if not match or not added:
        return False
    rest = UPDATED_TO_VERSION_PART_RE.sub(" ", match.group(1)).lower()
    found = False
    # Longest names first, so "Hide Premium tab" is not eaten by a shorter "Hide Premium".
    for name in sorted(added, key=len, reverse=True):
        if name.lower() in rest:
            rest = rest.replace(name.lower(), " ")
            found = True
    return found and not re.sub(r"[\s,&]+|\band\b|\bthe\b|\bpatch(?:es)?\b", "", rest)


def redundant(commit, app_patches, added, flipped, gone, added_text=""):
    """True when the patch-list lines of the app already say what this commit says.

    * a feat that names only patches added in this range and says nothing the Added lines (patch
      names and descriptions, `added_text`) don't, give or take one word and filler. With more to
      say, it stays;
    * a fix that names only patches added in this range: for users the patch is new, so a fix
      made to it before the release is part of it;
    * it only turns patches on or off by default and the Changed line says so;
    * every patch it names is gone again (removed, or added and removed within the range).
    A commit with an explicit Release-note is never dropped.
    """
    if commit["note"]:
        return False
    text = tidy_subject(commit["text"])
    if commit["type"] == "feat" and covered_by_added_patches(text, added):
        return True
    mentioned = mentioned_patches(text, app_patches | gone)
    if not mentioned:
        return False
    if mentioned <= set(added):
        if commit["type"] != "feat":
            return True
        said = words(" ".join(added) + " " + added_text) | words(FILLER_TEXT)
        return len(words(ISSUE_RE.sub(" ", text)) - said) <= 1
    if flipped and mentioned <= set(flipped) and DEFAULT_WORDS_RE.search(text):
        return True
    return mentioned <= gone


# ---------------------------------------------------------------------------------------------
# Config file (known issues, overrides)
# ---------------------------------------------------------------------------------------------

def load_config(repo_root, to_ref):
    """The release-notes config at to_ref (the working tree for HEAD), or {}."""
    root = Path(repo_root)
    text = None
    try:
        if to_ref in ("HEAD", "", None):
            path = root / CONFIG_FILE
            if path.is_file():
                text = path.read_text(encoding="utf-8")
        else:
            text = git(root, "show", f"{to_ref}:{CONFIG_FILE}")
    except (subprocess.CalledProcessError, OSError):
        return {}
    if not text:
        return {}
    try:
        config = json.loads(text)
    except ValueError as error:
        log(f"{CONFIG_FILE} is not valid JSON ({error}); ignoring it")
        return {}
    return clean_config(config)


def clean_config(config):
    """Keeps the well-formed parts of the config; each bad part is logged and left out, so one
    typo never costs the rest of the notes."""
    if not isinstance(config, dict):
        log(f"{CONFIG_FILE} is not a JSON object; ignoring it")
        return {}
    issues = config.get("known_issues", [])
    if not isinstance(issues, list):
        log(f"{CONFIG_FILE}: known_issues is not a list; ignoring it")
        issues = []
    good_issues = []
    for issue in issues:
        if isinstance(issue, dict) and isinstance(issue.get("text"), str) and issue["text"].strip():
            good_issues.append(issue)
        else:
            log(f"{CONFIG_FILE}: ignoring known issue {json.dumps(issue)[:80]} (needs a text)")
    commits = config.get("commits", {})
    if not isinstance(commits, dict):
        log(f"{CONFIG_FILE}: commits is not an object; ignoring it")
        commits = {}
    good_commits = {}
    for key, value in commits.items():
        if isinstance(value, dict) and len(str(key).strip()) >= 7:
            good_commits[str(key).strip()] = value
        else:
            log(f"{CONFIG_FILE}: ignoring commits entry {str(key)[:40]!r} (needs a SHA of 7+ "
                "characters and an object)")
    return {"known_issues": good_issues, "commits": good_commits}


def open_known_issues(config, version):
    """Known issues to list for `version`: since <= version < resolved (either may be absent)."""
    current = semver_key(version)
    issues = []
    for issue in config.get("known_issues") or []:
        if not isinstance(issue, dict) or not str(issue.get("text") or "").strip():
            continue
        since, resolved = semver_key(issue.get("since")), semver_key(issue.get("resolved"))
        if current is not None:
            if since is not None and current < since:
                continue
            if resolved is not None and current >= resolved:
                continue
        elif issue.get("resolved"):
            continue
        issues.append(issue)
    return issues


# ---------------------------------------------------------------------------------------------
# Rendering
# ---------------------------------------------------------------------------------------------

def heading(version, date):
    return f"## {version} ({date})"


def footer(repo, version, previous_tag=None):
    """Download links and how to verify them. Checksums and the attestation are made after the
    notes, so the notes point at them instead of quoting them."""
    base = f"https://github.com/{repo}/releases/download/v{version}"
    links = [
        f"[patches-{version}.mpp]({base}/patches-{version}.mpp)",
        f"[SHA256SUMS.txt]({base}/SHA256SUMS.txt)",
    ]
    if previous_tag:
        links.append(f"[All changes since {previous_tag}](https://github.com/{repo}/compare/"
                     f"{previous_tag}...v{version})")
    return "\n\n".join([
        "### Download and verify",
        " · ".join(links),
        f"Build provenance: `gh attestation verify patches-{version}.mpp -R {repo}`",
    ])


def download_line(repo, version):
    return (
        f"**Download:** [patches-{version}.mpp](https://github.com/{repo}/releases/download/"
        f"v{version}/patches-{version}.mpp)"
    )


def bullet(label, text):
    return f"- **{label}:** {text}"


def section(title, lines):
    return "\n".join([f"### {title}", *lines]) if lines else None


def build_model(old_list, new_list, commits, repo, dir_index=None, between=None):
    """Everything the notes say, grouped, before any Markdown is written.

    `between` holds the apps of the releases inside the range (see intermediate_apps).
    """
    dir_index = dir_index or {}
    between = between or {}
    old_apps, new_apps = load_apps(old_list), load_apps(new_list)
    disambiguate_names(old_apps, new_apps)
    names = {p: a["name"] for p, a in between.items() if p != UNIVERSAL}
    names.update({p: a["name"] for p, a in old_apps.items()})
    names.update({p: a["name"] for p, a in new_apps.items()})
    # Apps the New/Removed lists name, or that came and went within the range.
    listed_apps = [names[p] for p in set(names) - (set(old_apps) & set(new_apps)) if p != UNIVERSAL]

    model = {
        "new_apps": [], "app_blocks": [], "compat": [], "shared": [], "removed_apps": [],
        "other": [], "internal": [], "highlights": [], "actions": [], "breaking": [], "credits": [],
    }
    stats = {"new_apps": 0, "new_patches": 0, "changed_apps": 0, "compat": 0,
             "removed_apps": 0, "removed_patches": 0}
    empty = {"name": "All apps", "patches": {}, "defaults": {}, "descriptions": {}}
    if UNIVERSAL in old_apps or UNIVERSAL in new_apps:
        old_apps.setdefault(UNIVERSAL, dict(empty))
        new_apps.setdefault(UNIVERSAL, dict(empty))
    added_apps = sorted((p for p in new_apps if p not in old_apps), key=lambda p: sort_key(new_apps, p))
    removed_apps = sorted((p for p in old_apps if p not in new_apps), key=lambda p: sort_key(old_apps, p))
    kept = sorted((p for p in new_apps if p in old_apps), key=lambda p: sort_key(new_apps, p))

    # Release-level items come from every commit, whatever its scope.
    seen = {"highlights": set(), "actions": set(), "breaking": set(), "credits": set()}
    for commit in commits:
        if commit["release"]:
            continue
        # A breaking change is shown even when the commit's own line is left out.
        items = [("breaking", commit["breaking"])]
        if not commit["skip"]:
            items += [("highlights", commit["highlights"]), ("actions", commit["actions"]),
                      ("credits", commit["credits"])]
        for key, values in items:
            for value in values:
                text = neutralise_mentions(value.strip()) if key == "credits" else clean_text(value, repo)
                if text and text.lower() not in seen[key]:
                    seen[key].add(text.lower())
                    model[key].append(text)

    per_app = {}
    for commit in user_facing(commits):
        if commit["breaking"] and not commit["note"]:
            continue  # Important already says it.
        kind, packages = classify(commit, names, dir_index)
        if kind == "app":
            for package in packages:
                # New and removed apps get one line each; their commits add nothing for users.
                if package in new_apps and package in old_apps:
                    per_app.setdefault(package, []).append(commit)
        elif not (commit["note"] is None and APP_LIST_VERB_RE.match(commit["text"])
                  and any(words(n) and words(n) <= words(commit["text"]) for n in listed_apps)):
            model[kind].append(commit)

    for package in added_apps:
        app = new_apps[package]
        main, exceptions = compat_summary(app)
        patches = ", ".join(sorted(app["patches"], key=str.lower))
        text = f"{main} · {patches}"
        if exceptions:
            text += f" (other versions: {'; '.join(exceptions)})"
        model["new_apps"].append(bullet(app["scope"], text))
    stats["new_apps"] = len(added_apps)
    stats["removed_apps"] = len(removed_apps)

    for package in kept:
        old, new = old_apps[package], new_apps[package]
        added = sorted(set(new["patches"]) - set(old["patches"]), key=str.lower)
        removed = sorted(set(old["patches"]) - set(new["patches"]), key=str.lower)
        flipped = sorted((p for p in set(old["patches"]) & set(new["patches"])
                          if old["defaults"].get(p) != new["defaults"].get(p)), key=str.lower)
        gone = (set(between.get(package, {}).get("patches", ())) | set(old["patches"])) - set(new["patches"])
        app_patches = set(old["patches"]) | set(new["patches"])

        if package != UNIVERSAL:  # universal patches are no app's; Shared patches lists them
            stats["new_patches"] += len(added)
            stats["removed_patches"] += len(removed)
        lines = {category: [] for category in CATEGORIES + [COMPATIBILITY]}
        for patch in added:
            description = neutralise_mentions((new["descriptions"].get(patch) or "").rstrip("."))
            lines["Added"].append(f"{patch} — {description}" if description else patch)
        for state in (True, False):
            names_now = [p for p in flipped if new["defaults"].get(p) is state]
            if names_now:
                verb = "is" if len(names_now) == 1 else "are"
                lines["Changed"].append(
                    f"{join_names(names_now)} {verb} now {'on' if state else 'off'} by default")
        for commit in per_app.get(package, []):
            if redundant(commit, app_patches, added, flipped, gone, " ".join(lines["Added"])):
                continue
            category = line_category(commit)
            if category == INTERNAL:
                continue
            lines.setdefault(category, []).append(line_text(commit, repo))
        if removed:
            lines["Removed"].append(join_names(removed))

        # "- **Sofascore - Added:** ...": Manager reads the part before " - " as the app, so the
        # line also shows on the app's own page and counts for its update badge. Two packages with
        # one display name both match it (Manager tells apps apart by name only here).
        label = new.get("base_name", new["name"])
        entries = [bullet(c if package == UNIVERSAL else f"{label} - {c}", t)
                   for c in CATEGORIES + [COMPATIBILITY] for t in dict.fromkeys(lines[c])]
        if package == UNIVERSAL:
            model["shared"].extend(entries)
            continue
        change = compat_change(old, new)
        if entries:
            stats["changed_apps"] += 1
            main, exceptions = compat_summary(new)
            notes = []
            if change:
                notes.append(f"**Compatibility:** {change[0]} → {change[1]}")
            if exceptions:
                notes.append(f"**Other versions:** {'; '.join(exceptions)}")
            model["app_blocks"].append("\n\n".join(
                [f"### {new['name']} · {main.replace('`', '')}", *notes, "\n".join(entries)]))
        elif change:
            # Counted once: an app with patch changes counts under changed_apps only.
            stats["compat"] += 1
            text = f"{change[0]} → {change[1]}"
            if change[2]:
                text += f" (other versions: {'; '.join(change[2])})"
            model["compat"].append(bullet(new["scope"], text))

    model["removed_apps"] = [f"- {old_apps[p]['name']}" for p in removed_apps]
    for kind in ("shared", "other", "internal"):
        model[kind] = [entry if isinstance(entry, str) else commit_bullet(entry, repo)
                       for entry in model[kind]]
    model["stats"] = stats
    return model


def commit_bullet(commit, repo):
    category = line_category(commit)
    if category == INTERNAL:
        category = TYPE_CATEGORY.get(commit["type"], "Changed")
    return bullet(category, line_text(commit, repo))


def summary_line(stats):
    parts = []
    for key, one, many in (
        ("new_apps", "new app", "new apps"),
        ("new_patches", "new patch for an existing app", "new patches for existing apps"),
        ("changed_apps", "app with patch changes", "apps with patch changes"),
        ("compat", "compatibility update", "compatibility updates"),
        ("removed_apps", "removed app", "removed apps"),
        ("removed_patches", "removed patch", "removed patches"),
    ):
        count = stats.get(key, 0)
        if count:
            parts.append(f"{count} {one if count == 1 else many}")
    return "In this release: " + ", ".join(parts) + "." if parts else None


def render_notes(old_list, new_list, commits, repo, version, date, dir_index=None, previous_tag=None,
                 channel=None, known_issues=None, between=None):
    """Full notes from both patch lists and the parsed commits of the release range."""
    if commits and isinstance(commits[0], str):
        commits = commits_from_subjects(commits)
    commits = drop_reverted(list(commits or []))
    channel = channel or ("dev" if is_prerelease(version) else "stable")
    model = build_model(old_list, new_list, commits, repo, dir_index, between)

    parts = [heading(version, date)]
    if channel == "dev":
        since = f" Changes since {previous_tag}." if previous_tag else ""
        parts.append(f"> Pre-release on the dev channel.{since} Stable users get these changes "
                     "with the next stable release.")
    stats = model["stats"]
    if stats["new_apps"] + stats["changed_apps"] + stats["compat"] + stats["removed_apps"] >= SUMMARY_MIN_APPS:
        line = summary_line(stats)
        if line:
            parts.append(line)

    important = [bullet("Breaking", t) for t in model["breaking"]]
    important += [bullet("Action required", t) for t in model["actions"]]
    # Known issues are plain lines, not bullets: Manager counts only bullets for an app's update
    # badge, and an open issue repeats in every release, so as a bullet it would flag the app as
    # changed each time.
    issues = [
        f"**{neutralise_mentions(str(i['app']).strip())}:** {clean_text(str(i['text']), repo)}"
        if i.get("app") else clean_text(str(i["text"]), repo)
        for i in (known_issues or [])
    ]
    body = [
        section("Important", important),
        section("Highlights", [f"- {t}" for t in model["highlights"]]),
        section("New apps", model["new_apps"]),
        *model["app_blocks"],
        # Not "Updated app support": Manager sorts a card with that title above all others.
        section("Compatibility updates", model["compat"]),
        section("Shared patches", model["shared"]),
        section("Removed apps", model["removed_apps"]),
        section("Other changes", model["other"]),
        "\n\n".join(["### Known issues", *issues]) if issues else None,
        section("Internal improvements", model["internal"]),
        section("Credits", [f"- {c}" for c in model["credits"]]),
    ]
    body = [b for b in body if b]
    content = [b for b in body if not b.startswith(("### Important", "### Known issues", "### Credits"))]
    if not content:
        body.insert(1 if body and body[0].startswith("### Important") else 0, "No user-facing changes.")
    parts += body
    parts.append(footer(repo, version, previous_tag))
    return "\n\n".join(parts) + "\n"


def render_fallback(commits, repo, version, date, previous_tag=None):
    """Plain notes: every user-facing commit, used when a patch list is missing or the full notes
    failed. Version updates stay in, as there is no compatibility list here."""
    if commits and isinstance(commits[0], str):
        commits = commits_from_subjects(commits)
    commits = list(commits or [])
    lines, seen = [], set()
    for commit in commits:
        if commit["release"] or commit["skip"] or commit["type"] not in TYPE_CATEGORY:
            continue
        text = clean_text(commit["note"] or commit["text"], repo)
        scope = ", ".join(commit["scopes"])
        line = f"- **{scope}:** {line_category(commit)}: {text}" if scope else f"- {line_category(commit)}: {text}"
        if line.lower() not in seen:
            seen.add(line.lower())
            lines.append(line)
    commits = [c for c in commits if not c["release"]]
    breaking, seen_breaking = [], set()
    for commit in commits:
        for text in commit["breaking"]:
            text = clean_text(text, repo)
            if text and text.lower() not in seen_breaking:
                seen_breaking.add(text.lower())
                breaking.append(bullet("Breaking", text))
    credits = list(dict.fromkeys(neutralise_mentions(c) for commit in commits if not commit["skip"]
                                 for c in commit["credits"]))
    parts = [heading(version, date)]
    if breaking:
        parts.append(section("Important", breaking))
    parts.append(section("Changes", lines) if lines else "No user-facing changes.")
    if credits:
        parts.append(section("Credits", [f"- {c}" for c in credits]))
    parts.append(footer(repo, version, previous_tag))
    return "\n\n".join(parts) + "\n"


def render_minimal(repo, version, date):
    return "\n\n".join([heading(version, date), download_line(repo, version)]) + "\n"


# ---------------------------------------------------------------------------------------------
# Inputs
# ---------------------------------------------------------------------------------------------

def read_json(path):
    with open(path, encoding="utf-8") as handle:
        return json.load(handle)


def list_at(repo_root, ref):
    try:
        return json.loads(git(repo_root, "show", f"{ref}:patches-list.json"))
    except (subprocess.CalledProcessError, ValueError, OSError) as error:
        log(f"no patches-list.json at {ref}: {str(error).strip()[:200]}")
        return None


def build_new_list(repo_root, version, runner=subprocess.run):
    """patches-list.json for `version`, built with Gradle when the working tree does not have it.

    Returns the parsed list or None. patches-list.json is put back byte for byte afterwards and
    any bundle the build left for another version is deleted, so the prepare step starts clean.
    """
    root = Path(repo_root)
    list_file = root / "patches-list.json"
    original = None
    try:
        original = list_file.read_bytes()
        current = json.loads(original.decode("utf-8"))
        if current.get("version") == version:
            log(f"using the existing patches-list.json for {version}")
            return current
    except (OSError, ValueError):
        pass

    libs = root / "patches" / "build" / "libs"
    before = set(libs.glob("*.mpp")) if libs.is_dir() else set()
    gradlew = root / ("gradlew.bat" if os.name == "nt" else "gradlew")
    command = [str(gradlew), "generatePatchesList", f"-Pversion={version}"]
    log("running " + " ".join(command))
    try:
        result = runner(command, cwd=root, stdout=sys.stderr, stderr=sys.stderr)
        if result.returncode != 0:
            log(f"generatePatchesList failed with exit code {result.returncode}")
            return None
        built = read_json(list_file)
        if built.get("version") != version:
            log(f"generatePatchesList wrote version {built.get('version')}, expected {version}")
            return None
        return built
    except (OSError, ValueError) as error:
        log(f"generatePatchesList failed: {error}")
        return None
    finally:
        try:
            if original is not None:
                list_file.write_bytes(original)
            else:
                git(root, "checkout", "--", "patches-list.json")
        except (subprocess.CalledProcessError, OSError) as error:
            log(f"could not restore patches-list.json: {error}")
        if libs.is_dir():
            for bundle in set(libs.glob("*.mpp")) - before:
                if bundle.name != f"patches-{version}.mpp":
                    bundle.unlink(missing_ok=True)


def release_tags(repo_root, merged_into, not_merged_into=None):
    """Version tags (vX.Y.Z[-pre]) reachable from merged_into, optionally not from another ref."""
    args = ["tag", "--list", "v*", "--merged", merged_into]
    if not_merged_into:
        args += ["--no-merged", not_merged_into]
    return [t.strip() for t in git(repo_root, *args).splitlines() if semver_key(t.strip())]


def previous_release_tag(repo_root, to_ref, version, channel):
    """The tag semantic-release would use as lastRelease: on stable the newest stable tag before
    `version`, on dev the newest tag of either channel (stable releases are back-merged)."""
    try:
        tags = release_tags(repo_root, to_ref)
        to_commit = git(repo_root, "rev-parse", f"{to_ref}^{{commit}}").strip()
    except (subprocess.CalledProcessError, OSError) as error:
        log(f"could not list the tags: {error}")
        return None
    current = semver_key(version)
    candidates = []
    for tag in tags:
        key = semver_key(tag)
        if channel == "stable" and is_prerelease(tag):
            continue
        if current is not None:
            if key >= current:
                continue
        else:
            try:
                if git(repo_root, "rev-parse", f"{tag}^{{commit}}").strip() == to_commit:
                    continue
            except (subprocess.CalledProcessError, OSError):
                continue
        candidates.append((key, tag))
    return max(candidates)[1] if candidates else None


def intermediate_apps(repo_root, previous_ref, to_ref):
    """{package: {"name", "patches"}} from the patch lists of the releases inside the range (for a
    stable release: its dev pre-releases), so lines about apps and patches that came and went
    within the range drop out."""
    apps = {}
    if not previous_ref:
        return apps
    try:
        tags = release_tags(repo_root, to_ref, previous_ref)
    except (subprocess.CalledProcessError, OSError):
        return apps
    for tag in tags:
        if tag == to_ref:
            continue
        for package, app in load_apps(list_at(repo_root, tag)).items():
            entry = apps.setdefault(package, {"name": app["name"], "patches": set()})
            entry["patches"].update(app["patches"])
    return apps


def tag_date(repo_root, ref):
    try:
        return git(repo_root, "log", "-1", "--format=%cs", ref).strip() or None
    except (subprocess.CalledProcessError, OSError):
        return None


def generate(args):
    repo_root = Path(args.repo_root).resolve()
    version = args.version
    channel = args.channel or ("dev" if is_prerelease(version) else "stable")
    date = args.date or datetime.now(timezone.utc).date().isoformat()
    previous_ref = args.previous_tag or previous_release_tag(repo_root, args.to, version, channel)
    log(f"range: {previous_ref or '(first release)'}..{args.to} ({channel})")

    config = load_config(repo_root, args.to)
    commits = []
    try:
        commits = git_commits(repo_root, previous_ref, args.to, config.get("commits"))
    except (subprocess.CalledProcessError, OSError) as error:
        log(f"git log failed: {error}")

    try:
        if args.new_list:
            new_list = read_json(args.new_list)
        elif args.build:
            new_list = build_new_list(repo_root, version)
        elif args.preview and args.to != "HEAD":
            new_list = list_at(repo_root, args.to)
        else:
            new_list = read_json(repo_root / "patches-list.json")
            if args.preview and new_list.get("version") != version:
                log(f"patches-list.json is from {new_list.get('version')}: changes since then are "
                    "not visible; pass --new-list with a list built by ./gradlew generatePatchesList")
        if args.previous_list:
            old_list = read_json(args.previous_list)
        elif previous_ref:
            old_list = list_at(repo_root, previous_ref)
        else:
            old_list = None
        if new_list and old_list:
            dir_index = {}
            try:
                dir_index = scan_patch_dirs(repo_root)
            except OSError as error:
                log(f"could not scan the patch directories: {error}")
            return render_notes(
                old_list, new_list, commits, args.repo, version, date, dir_index, previous_ref,
                channel, open_known_issues(config, version),
                intermediate_apps(repo_root, previous_ref, args.to),
            )
        log("a patches list is missing; writing the plain commit list")
    except Exception as error:  # noqa: BLE001 - the release must never fail on its notes
        log(f"full notes failed ({type(error).__name__}: {error}); writing the plain commit list")
    return render_fallback(commits, args.repo, version, date, previous_ref)


def parse_args(argv):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY", ""),
                        help="OWNER/REPO (default: $GITHUB_REPOSITORY)")
    parser.add_argument("--version", help="the version being released, without v (default with "
                                          "--preview: the --to tag's version)")
    parser.add_argument("--previous-tag", "--from", dest="previous_tag", default="",
                        help="last release tag (semantic-release lastRelease.gitTag); empty = the "
                             "tag semantic-release would pick for the channel, or a first release")
    parser.add_argument("--to", default="HEAD", help="end of the commit range (default HEAD)")
    parser.add_argument("--channel", choices=["dev", "stable"],
                        help="default: dev for a pre-release version, else stable")
    parser.add_argument("--previous-list", help="previous patches-list.json (default: the one at --previous-tag)")
    parser.add_argument("--new-list", help="new patches-list.json (default: see --build and --preview)")
    parser.add_argument("--build", action="store_true",
                        help="build the new list with ./gradlew generatePatchesList unless the "
                             "working tree already has it for --version")
    parser.add_argument("--preview", action="store_true",
                        help="local preview: never builds or writes tracked files; the new list "
                             "is the one at --to")
    parser.add_argument("--output", help="also write the notes to this file")
    parser.add_argument("--repo-root", default=".", help="repository root (default: cwd)")
    parser.add_argument("--date", help="release date YYYY-MM-DD (default: today, UTC; with "
                                       "--preview of a tag: the tag's commit date)")
    args = parser.parse_args(argv)
    if args.preview and args.build:
        parser.error("--preview never builds; pass --new-list instead of --build")
    if not args.version:
        if not args.preview:
            parser.error("--version is required")
        # Not a version: the previous release is then the newest tag not on --to itself.
        args.version = args.to[1:] if semver_key(args.to) and args.to.startswith("v") else "unreleased"
        if args.version == "unreleased" and not args.channel:
            args.channel = "dev"
    if args.preview and not args.date and args.to != "HEAD":
        args.date = tag_date(Path(args.repo_root).resolve(), args.to)
    if not args.repo and args.preview:
        args.repo = origin_repo(Path(args.repo_root).resolve()) or "OWNER/REPO"
    return args


def origin_repo(repo_root):
    """OWNER/REPO of the origin remote, for previews run outside Actions."""
    try:
        url = git(repo_root, "remote", "get-url", "origin").strip()
    except (subprocess.CalledProcessError, OSError):
        return None
    match = re.search(r"github\.com[:/]([^/]+/[^/]+?)(?:\.git)?/?$", url)
    return match.group(1) if match else None


def main(argv=None):
    args = parse_args(argv)
    try:
        notes = generate(args)
    except Exception as error:  # noqa: BLE001 - never fail the release
        log(f"release notes failed: {type(error).__name__}: {error}")
        date = args.date or datetime.now(timezone.utc).date().isoformat()
        notes = render_minimal(args.repo, args.version, date)
    if any(UNSAFE_NOTE_LINE_RE.match(line) for line in notes.splitlines()):
        log("a line looked like a BREAKING CHANGE footer; indenting it out of commit-analyzer's reach")
        notes = "\n".join(("- " + line.lstrip(" *|")) if UNSAFE_NOTE_LINE_RE.match(line) else line
                          for line in notes.splitlines()) + "\n"
    if args.output:
        try:
            Path(args.output).write_text(notes, encoding="utf-8")
            log(f"wrote {args.output}")
        except OSError as error:
            log(f"could not write {args.output}: {error}")
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    sys.stdout.write(notes)
    return 0


if __name__ == "__main__":
    sys.exit(main())
