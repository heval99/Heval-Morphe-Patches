"""Tests for .github/scripts/generate_release_notes.py (offline; the git tests use a temp repo).

Run with: python -m unittest discover -s .github/scripts/tests -v
Snapshot files live in tests/snapshots/; rewrite them with UPDATE_SNAPSHOTS=1 after an
intended layout change, then review the diff.
"""

import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[3]
SPEC = importlib.util.spec_from_file_location(
    "generate_release_notes", ROOT / ".github/scripts/generate_release_notes.py"
)
notes = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(notes)

SNAPSHOTS = Path(__file__).resolve().parent / "snapshots"
REPO = "owner/repo"
DATE = "2026-11-03"
# commit-analyzer's breaking-note pattern, widened: no rendered line may match it.
BREAKING_NOTE = re.compile(r"^[\s|*]*(?:\*\s+)?breaking[ -]changes?[:\s]", re.IGNORECASE)


def p(package, name, version, patch, codes=None, default=True, description=""):
    """One (package, patch) row of a fixture patch list."""
    return {"package": package, "app": name, "version": version, "patch": patch,
            "codes": codes, "default": default, "description": description}


def app(package, name, version, *patches, codes=None):
    return [p(package, name, version, patch, codes) for patch in patches]


def patch_list(version, *groups, universal=()):
    """A patches-list.json dict from p() rows (or lists of them); `version=None` = any version."""
    by_patch = {}
    for row in [r for g in groups for r in (g if isinstance(g, list) else [g])]:
        entry = by_patch.setdefault(row["patch"], {
            "name": row["patch"], "description": row["description"], "default": row["default"],
            "compatiblePackages": [], "options": [],
        })
        target = {"version": row["version"], "versionCodes": row["codes"], "isExperimental": False}
        entry["compatiblePackages"].append({
            "packageName": row["package"], "name": row["app"],
            "targets": [target] if row["version"] is not None else [],
        })
    patches = [by_patch[name] for name in sorted(by_patch)]
    patches += [{"name": name, "description": f"{name} for any app.", "default": False,
                 "compatiblePackages": None, "options": []} for name in universal]
    return {"version": version, "patches": patches}


OLD = patch_list(
    "1.0.0",
    app("com.truecaller", "Truecaller", "26.41.5", "Disable ads", "Hide Premium tab", "Old patch"),
    app("com.scores365", "365Scores", "14.9.8", "Disable ads"),
    app("com.fotmob", "FotMob", "239.1", "Disable ads"),
    app("dev.librepods", "LibrePods", "1.0", "Enable Premium"),
)
NEW = patch_list(
    "1.1.0",
    app("com.truecaller", "Truecaller", "26.42.5", "Disable ads", "Hide Premium tab"),
    p("com.truecaller", "Truecaller", "26.42.5", "Hide Messages tab",
      description="Hides the Messages tab."),
    p("com.truecaller", "Truecaller", "26.42.5", "Swap call answer and decline buttons"),
    app("com.scores365", "365Scores", "15.0.0", "Disable ads"),
    app("com.fotmob", "FotMob", "239.1", "Disable ads"),
    app("com.gpsmap.camera", "GPS Map Camera Editor", "1.0.31",
        "Disable ads", "Enable Premium", "Spoof signature"),
)


def render(subjects, old=OLD, new=NEW, dir_index=None, version="1.1.0", **kwargs):
    kwargs.setdefault("previous_tag", "v1.0.0")
    return notes.render_notes(old, new, subjects, REPO, version, DATE, dir_index or {}, **kwargs)


def commit(message, sha=""):
    return notes.parse_commit(sha, message)


def manager_matches_scope(scope, app_names):
    """Morphe Manager's ChangelogParser.SubjectMatcher.matchesScope, app names only: the scope, or
    its part before " - ", compared on letters and digits."""
    def compact(value):
        return "".join(ch for ch in value if ch.isalnum()).lower()
    keys = {compact(scope), compact(scope.split(" - ", 1)[0])}
    return any(compact(name) in keys for name in app_names if compact(name))


def block(text, title):
    """The ### section whose heading starts with `title`, without the heading."""
    for part in text.split("\n### "):
        if part.startswith(title) or part.startswith("### " + title):
            return part.split("\n", 1)[1].strip("\n") if "\n" in part else ""
    raise AssertionError(f"no section {title!r} in:\n{text}")


class LayoutTest(unittest.TestCase):
    def test_heading_footer_and_compare_link(self):
        text = render([])
        self.assertTrue(text.startswith("## 1.1.0 (2026-11-03)\n"))
        footer = block(text, "Download and verify")
        self.assertIn("[patches-1.1.0.mpp](https://github.com/owner/repo/releases/download/v1.1.0/"
                      "patches-1.1.0.mpp)", footer)
        self.assertIn("[SHA256SUMS.txt](https://github.com/owner/repo/releases/download/v1.1.0/"
                      "SHA256SUMS.txt)", footer)
        self.assertIn("(https://github.com/owner/repo/compare/v1.0.0...v1.1.0)", footer)
        self.assertIn("`gh attestation verify patches-1.1.0.mpp -R owner/repo`", footer)

    def test_dev_notice_only_on_the_dev_channel(self):
        dev = render([], version="1.1.0-dev.2", previous_tag="v1.1.0-dev.1")
        self.assertIn("> Pre-release on the dev channel. Changes since v1.1.0-dev.1.", dev)
        self.assertNotIn("Pre-release", render([]))

    def test_no_emoji_tables_or_html(self):
        text = render(["fix(fotmob): use the device font"])
        self.assertNotRegex(text, r"[\U0001F300-\U0001FAFF☀-➿]")
        self.assertNotIn("|---", text)
        self.assertNotRegex(text, r"<[a-z]+[ >]")
        for line in text.splitlines():
            if line.startswith("#"):
                self.assertNotIn("`", line)  # Manager shows heading text literally

    def test_nothing_user_facing(self):
        text = render([], old=NEW, new=NEW)
        self.assertIn("No user-facing changes.", text)


class AppTest(unittest.TestCase):
    def test_new_app_line_lists_version_and_patches(self):
        self.assertIn(
            "### New apps\n- **GPS Map Camera Editor:** `1.0.31` · Disable ads, Enable Premium, "
            "Spoof signature", render([]))

    def test_new_and_removed_app_commits_are_not_repeated(self):
        text = render(["feat(gpsmapcamera): add GPS Map Camera Editor",
                       "fix(librepods): remove LibrePods support",
                       "fix: drop the LibrePods patches"])
        self.assertNotIn("Add GPS Map Camera Editor", text)
        self.assertNotIn("LibrePods support", text)
        self.assertNotIn("Drop the LibrePods", text)
        self.assertIn("### Removed apps\n- LibrePods", text)

    def test_app_block_with_compat_change_is_not_repeated_in_the_list(self):
        text = render([])
        self.assertIn("### Truecaller · 26.42.5\n\n**Compatibility:** `26.41.5` → `26.42.5`\n\n"
                      "- **Truecaller - Added:** Hide Messages tab — Hides the Messages tab\n"
                      "- **Truecaller - Added:** Swap call answer and decline buttons\n"
                      "- **Truecaller - Removed:** Old patch", text)
        support = block(text, "Compatibility updates")
        self.assertEqual(support, "- **365Scores:** `14.9.8` → `15.0.0`")

    def test_version_bump_commit_gives_no_line(self):
        text = render(["fix(365scores): updated to version 15.0.0"])
        self.assertNotIn("### 365Scores", text)
        self.assertNotIn("Updated to version", text)

    def test_categories_from_commit_types(self):
        text = render(["feat(fotmob): patch settings switches for the font.",
                       "fix(fotmob): call notification buttons cut off (#116)",
                       "perf(fotmob): resolve the device font once"])
        self.assertIn("### FotMob · 239.1\n\n- **FotMob - Improved:** Patch settings switches for the font\n"
                      "- **FotMob - Fixed:** Call notification buttons cut off "
                      "([#116](https://github.com/owner/repo/issues/116))\n"
                      "- **FotMob - Performance:** Resolve the device font once", text)

    def test_commits_restating_added_patches_are_dropped(self):
        old = patch_list("1", app("t", "Truecaller", "1", "Disable ads"))
        new = patch_list("2", app("t", "Truecaller", "1", "Disable ads", "Tap call history to call",
                                  "Hide inbox cards"))
        text = render([
            "feat(truecaller): add a switch to call a number by tapping it in the call history",
            "feat(truecaller): add a switch to hide the cards above the Messages inbox",
            "fix(truecaller): keep Hide inbox cards from hiding the search bar",
            "fix(truecaller): keep Disable ads and Hide inbox cards apart",
            "feat(truecaller): add a switch for the share cards",
        ], old=old, new=new)
        lines = block(text, "Truecaller").splitlines()
        self.assertEqual([l for l in lines if l.startswith("- ")], [
            "- **Truecaller - Added:** Hide inbox cards",
            "- **Truecaller - Added:** Tap call history to call",
            "- **Truecaller - Improved:** Add a switch for the share cards",
            "- **Truecaller - Fixed:** Keep Disable ads and Hide inbox cards apart",
        ])

    def test_compound_feat_that_says_more_than_the_patch_names_stays(self):
        old = patch_list("1", app("t", "Truecaller", "1", "Disable ads"))
        new = patch_list("2", app("t", "Truecaller", "1", "Disable ads", "Hide inbox cards"))
        text = render(["feat(truecaller): add Hide inbox cards and a faster contact search"], old=old, new=new)
        self.assertIn("- **Truecaller - Added:** Hide inbox cards\n"
                      "- **Truecaller - Improved:** Add Hide inbox cards and a faster contact search", text)

    def test_feat_that_only_lists_added_patches_is_dropped(self):
        text = render(["feat(truecaller): add Swap call answer and decline buttons, updated to version 26.42.5",
                       "feat(truecaller): add a switch for the Messages tab and a new settings row"])
        self.assertNotIn("Add Swap call answer", text)
        self.assertIn("- **Truecaller - Improved:** Add a switch for the Messages tab and a new settings row", text)

    def test_default_flip_from_metadata(self):
        old = patch_list("1", p("k", "Keyboard", "5", "Stop remote updates"),
                         p("k", "Keyboard", "5", "Always use GIPHY"), p("k", "Keyboard", "5", "Spoof signature", default=False))
        new = patch_list("2", p("k", "Keyboard", "5", "Stop remote updates", default=False),
                         p("k", "Keyboard", "5", "Always use GIPHY", default=False),
                         p("k", "Keyboard", "5", "Spoof signature"))
        text = render(["fix(keyboard): make Stop remote updates and Always use GIPHY opt-in"], old=old, new=new)
        self.assertEqual([l for l in block(text, "Keyboard").splitlines() if l.startswith("- ")], [
            "- **Keyboard - Changed:** Spoof signature is now on by default",
            "- **Keyboard - Changed:** Always use GIPHY and Stop remote updates are now off by default",
        ])

    def test_lines_about_removed_or_transient_patches_are_dropped(self):
        old = patch_list("1", app("f", "FotMob", "1", "Disable ads", "Old patch"))
        new = patch_list("2", app("f", "FotMob", "1", "Disable ads"))
        between = {"f": {"name": "FotMob", "patches": {"Disable ads", "Old patch", "Short lived"}},
                   "x.gone": {"name": "Gone App", "patches": {"Disable ads"}}}
        text = render(["fix(fotmob): keep Old patch working", "feat(fotmob): tune Short lived",
                       "fix(fotmob): keep the feed scrolling", "fix(goneapp): crash on start"],
                      old=old, new=new, between=between)
        self.assertEqual([l for l in block(text, "FotMob").splitlines() if l.startswith("- ")], [
            "- **FotMob - Fixed:** Keep the feed scrolling", "- **FotMob - Removed:** Old patch"])
        self.assertNotIn("Crash on start", text)

    def test_scope_mapping_by_directory_and_multiple_scopes(self):
        dir_index = {"scores365": {"com.scores365"}, "tc": {"com.truecaller"}}
        text = render(["fix(scores365, fotmob): handle the new consent screen",
                       "fix(tc): keep controls on root installs"], dir_index=dir_index)
        self.assertIn("**Compatibility:** `14.9.8` → `15.0.0`\n\n- **365Scores - Fixed:** Handle the new consent screen", text)
        self.assertIn("### FotMob · 239.1\n\n- **FotMob - Fixed:** Handle the new consent screen", text)
        self.assertIn("- **Truecaller - Fixed:** Keep controls on root installs", text)

    def test_scope_prefix_must_be_unique(self):
        names = {"a.b": "Brave Browser", "a.c": "Brave Browser (Beta)", "x.y": "GPS Map Camera Editor"}
        self.assertEqual(notes.resolve_scope("gpsmapcamera", names, {}), {"x.y"})
        self.assertEqual(notes.resolve_scope("brave", names, {}), set())
        self.assertEqual(notes.resolve_scope("BraveBrowser", names, {}), {"a.b"})

    def test_same_display_name_for_two_packages(self):
        old = patch_list("1", app("a.one", "Brave", "1", "X"), app("a.two", "Brave", "1", "X"))
        new = patch_list("2", app("a.one", "Brave", "2", "X"), app("a.two", "Brave", "1", "X", "Y"))
        text = render([], old=old, new=new)
        self.assertIn("- **Brave - a.one:** `1` → `2`", text)
        self.assertIn("### Brave (a.two) · 1\n\n- **Brave - Added:** Y", text)
        for scope in ("Brave - a.one", "Brave - Added"):
            self.assertTrue(manager_matches_scope(scope, ["Brave"]), scope)

    def test_every_app_label_is_one_manager_files_under_that_app(self):
        old = patch_list("1", app("com.sofascore", "Sofascore", "1", "A"), app("com.x", "L'Équipe", "1", "A"),
                         app("c.f", "FotMob", "1", "A"))
        new = patch_list("2", app("com.sofascore", "Sofascore", "2", "A", "B"), app("com.x", "L'Équipe", "1", "A", "B"),
                         app("c.f", "FotMob", "2", "A"), app("n.tom", "My Talking Tom 2", "1", "A"))
        text = render(["fix(sofascore): keep scores hidden"], old=old, new=new)
        names = ["Sofascore", "L'Équipe", "FotMob", "My Talking Tom 2"]
        current, checked = None, 0
        for line in text.splitlines():
            if line.startswith("### "):
                current = next((n for n in names if line[4:].startswith(n + " ·")), None)
            match = re.match(r"^- \*\*(.+?):\*\*", line)
            if match and current:
                checked += 1
                self.assertTrue(manager_matches_scope(match.group(1), [current]), line)
                self.assertFalse(manager_matches_scope(match.group(1), ["Other App"]), line)
        self.assertEqual(checked, 3)  # Sofascore Added + Fixed, L'Équipe Added
        for name in ("FotMob", "My Talking Tom 2"):
            scopes = re.findall(rf"^- \*\*({re.escape(name)}):\*\*", text, re.M)
            self.assertTrue(scopes and manager_matches_scope(scopes[0], [name]), name)


class CompatibilityTest(unittest.TestCase):
    def test_build_only_update(self):
        old = patch_list("1", app("t", "Taskito", "1.3.4", "A", "B", codes={"ARM64_V8A": 1340, "X86": 1340}))
        new = patch_list("2", app("t", "Taskito", "1.3.4", "A", "B", codes={"ARM64_V8A": 1341, "X86": 1341}))
        self.assertIn("- **Taskito:** `1.3.4 (build 1340)` → `1.3.4 (build 1341)`", render([], old=old, new=new))

    def test_per_abi_codes_prefer_arm64(self):
        old = patch_list("1", app("f", "Firefox", "160", "A", codes={"ARM64_V8A": 15, "X86_64": 14}))
        new = patch_list("2", app("f", "Firefox", "160", "A", codes={"ARM64_V8A": 17, "X86_64": 16}))
        self.assertIn("`160 (build 15)` → `160 (build 17)`", render([], old=old, new=new))

    def test_only_some_patches_move_to_the_new_version(self):
        old = patch_list("1", app("s", "Sofascore", "9", "A", "B", "C"))
        new = patch_list("2", app("s", "Sofascore", "16", "A", "B"), p("s", "Sofascore", "9", "C"))
        text = render([], old=old, new=new)
        self.assertIn("- **Sofascore:** `9` → `16` (other versions: C: `9`)", text)
        text = render(["fix(sofascore): keep A working"], old=old, new=new)
        self.assertIn("### Sofascore · 16\n\n**Compatibility:** `9` → `16`\n\n**Other versions:** C: `9`", text)

    def test_one_patch_moves_while_the_main_version_stays(self):
        old = patch_list("1", app("s", "Sofascore", "9", "A", "B", "C"))
        new = patch_list("2", app("s", "Sofascore", "9", "A", "B"), p("s", "Sofascore", "10", "C"))
        self.assertIn("- **Sofascore:** `9` (other versions: none) → `9` (other versions: C: `10`)",
                      render([], old=old, new=new))

    def test_any_version_and_multiple_versions(self):
        old = patch_list("1", app("v", "Via", None, "A"), app("w", "W", "1", "A"))
        new = patch_list("2", app("v", "Via", None, "A"), app("w", "W", "1", "A"),
                         app("n", "New", None, "A"), p("w", "W", "2", "A"))
        text = render([], old=old, new=new)
        self.assertIn("- **New:** any version · A", text)
        self.assertNotIn("**Via:**", text)
        multi = notes.load_apps(patch_list("x", p("m", "M", "1", "A"))); multi["m"]["patches"]["A"] = frozenset(
            {("1", ()), ("2", ())})
        self.assertEqual(notes.compat_summary(multi["m"]), ("`1`, `2`", []))

    def test_new_app_with_mixed_versions(self):
        new = patch_list("2", app("t", "Truecaller", "26.41.5", "Disable ads"),
                         app("g", "Gmail", "2", "A", "B"), p("g", "Gmail", "1", "C"))
        old = patch_list("1", app("t", "Truecaller", "26.41.5", "Disable ads"))
        self.assertIn("- **Gmail:** `2` · A, B, C (other versions: C: `1`)", render([], old=old, new=new))

    def test_universal_patches_go_to_shared(self):
        old = patch_list("1", app("p", "P", "1", "A"))
        new = patch_list("2", app("p", "P", "1", "A"), universal=["Remove screenshot restriction"])
        text = render([], old=old, new=new)
        self.assertIn("### Shared patches\n- **Added:** Remove screenshot restriction — "
                      "Remove screenshot restriction for any app", text)
        self.assertNotIn("All apps", text)
        removed = render([], old=new, new=old)
        self.assertIn("### Shared patches\n- **Removed:** Remove screenshot restriction", removed)

    def test_missing_metadata_fields(self):
        apps = notes.load_apps({"patches": [{"name": "A", "compatiblePackages": [{"packageName": "x"}]},
                                            {"compatiblePackages": None}, {"name": "B"}]})
        self.assertEqual(apps["x"]["name"], "x")
        self.assertEqual(notes.compat_summary(apps["x"]), ("any version", []))
        self.assertIn("B", apps[notes.UNIVERSAL]["patches"])
        self.assertEqual(notes.load_apps(None), {})


class CommitTest(unittest.TestCase):
    def test_trailers_anywhere_in_the_body(self):
        c = commit("fix(sofascore): turn x on\n\nBody.\n\nRelease-note: Spoof signature is now on.\n"
                   "Release-category: changed\n\nCo-Authored-By: someone <a@b>")
        self.assertEqual((c["note"], c["category"]), ("Spoof signature is now on.", "Changed"))

    def test_release_commits_are_flagged_and_ignored(self):
        c = commit("chore: Release v3.3.0-dev.2 [skip ci]\n\n## 3.3.0-dev.2\n\n- **Breaking:** x\n\n"
                   "BREAKING CHANGE: echoed\nRelease-highlight: echoed")
        self.assertTrue(c["release"])
        text = render([c])
        self.assertNotIn("echoed", text)

    def test_overrides_note_category_skip_and_internal(self):
        text = render([
            commit("fix(fotmob): raw subject\n\nRelease-note: Scores load faster on slow networks"),
            commit("fix(fotmob): second\n\nRelease-category: Performance"),
            commit("fix(fotmob): third\n\nRelease-note: none"),
            commit("fix(fotmob): fourth\n\nRelease-category: Internal"),
            commit("docs(fotmob): explained\n\nRelease-note: The font switch now names its default"),
        ])
        lines = [l for l in block(text, "FotMob").splitlines() if l.startswith("- ")]
        self.assertEqual(lines, ["- **FotMob - Fixed:** Scores load faster on slow networks",
                                 "- **FotMob - Changed:** The font switch now names its default",
                                 "- **FotMob - Performance:** Second"])
        self.assertIn("### Internal improvements\n- **Fixed:** Fourth", text)
        self.assertNotIn("Third", text)

    def test_config_override_by_sha(self):
        c = commit("fix(fotmob): raw subject", sha="abcdef1234567890")
        notes.apply_override(c, notes.override_for(c["sha"], {"abcdef12": {"note": "Better text",
                                                                              "category": "Changed"}}))
        self.assertIn("- **FotMob - Changed:** Better text", render([c]))
        self.assertIsNone(notes.override_for("abcdef1234", {"abc": {"note": "too short"}}))
        credited = commit("ci: triage\n\nPorted-from: Workflow from someone/template (GPL-3.0)", sha="1234567aa")
        self.assertIn("### Credits", render([dict(credited)]))
        notes.apply_override(credited, {"credits": []})
        self.assertNotIn("Credits", render([credited]))

    def test_highlights_actions_and_breaking(self):
        text = render([
            commit("feat(fotmob)!: rename the font switch\n\nRelease-highlight: FotMob uses your font"),
            commit("fix(fotmob): new package\n\nAction-required: Repatch FotMob and reinstall it"),
            commit("feat: upgrade\n\nBREAKING CHANGE: needs Manager 2.5 or newer"),
        ])
        self.assertIn("### Important\n- **Breaking:** Needs Manager 2.5 or newer\n"
                      "- **Action required:** Repatch FotMob and reinstall it\n\n"
                      "### Highlights\n- FotMob uses your font", text)
        # "!" alone does not make the release major (angular preset), so it is a normal line.
        self.assertIn("- **FotMob - Improved:** Rename the font switch", block(text, "FotMob"))
        self.assertNotIn("Upgrade", text)
        skipped = render([commit("feat: quiet\n\nRelease-note: none\n\nBREAKING CHANGE: needs Manager 3")])
        self.assertIn("### Important\n- **Breaking:** Needs Manager 3", skipped)

    def test_breaking_changes_from_messages(self):
        found = notes.breaking_from_messages([
            "feat: upgrade\n\nBody text.\n\nBREAKING CHANGE: needs Manager 2.5\nor newer. Older ones fail.\n"
            "\nSigned-off-by: x",
            "fix(a)!: drop the old switch",
            "feat!: needs Manager 2.5 or newer",
            "fix: other\n\nBREAKING-CHANGE: needs manager 2.5 or newer. older ones fail.\nRefs: #3",
            "fix: nothing here\n\nPlain body",
        ])
        self.assertEqual(found, ["Needs Manager 2.5 or newer. Older ones fail"])
        self.assertTrue(notes.breaking_ignored("fix(a)!: drop the old switch"))
        self.assertTrue(notes.breaking_ignored("fix: x\n\nBREAKING-CHANGE: y"))
        self.assertFalse(notes.breaking_ignored("feat!: x\n\nBREAKING CHANGE: y"))
        self.assertFalse(notes.breaking_ignored("fix: plain"))

    def test_no_line_reads_as_a_breaking_change_note(self):
        texts = [render([commit("feat!: x\n\nBREAKING CHANGE: breaking change: y")]),
                 notes.render_fallback([commit("fix!: breaking change: z")], REPO, "1", DATE)]
        for text in texts:
            for line in text.splitlines():
                self.assertIsNone(BREAKING_NOTE.match(line), line)

    def test_reverts(self):
        original = commit("feat(fotmob): add a font switch", sha="1111111aaaa")
        revert = commit('Revert "feat(fotmob): add a font switch"\n\nThis reverts commit 1111111aaaa.',
                        sha="2222222bbbb")
        old_revert = commit('Revert "fix(fotmob): keep the feed"\n\nThis reverts commit 9999999.',
                            sha="3333333cccc")
        text = render([original, revert, old_revert, commit("fix(fotmob): other", sha="4444444dddd")])
        lines = [l for l in block(text, "FotMob").splitlines() if l.startswith("- ")]
        self.assertEqual(lines, ["- **FotMob - Fixed:** Other", "- **FotMob - Changed:** Reverted: keep the feed"])

    def test_reapplied_change_is_shown_once(self):
        a = commit("feat(fotmob): add a font switch", sha="aaaaaaa1111")
        r1 = commit('Revert "feat(fotmob): add a font switch"\n\nThis reverts commit aaaaaaa1111.', sha="bbbbbbb2222")
        for subject in ('Revert "Revert "feat(fotmob): add a font switch""', 'Reapply "feat(fotmob): add a font switch"'):
            r2 = commit(f"{subject}\n\nThis reverts commit bbbbbbb2222.", sha="ccccccc3333")
            text = render([dict(a), dict(r1), r2])
            self.assertEqual(text.count("Add a font switch"), 1, text)
            self.assertNotIn("Reverted", text)
            alone = render([r2])  # the revert pair was in an earlier release
            self.assertIn("- **FotMob - Improved:** Add a font switch", alone)

    def test_mentions_do_not_notify(self):
        old = patch_list("1", app("f", "FotMob", "1", "A"))
        new = patch_list("2", app("f", "FotMob", "1", "A"),
                         p("f", "FotMob", "1", "B", description="Asked for by @someone and @org/team."))
        text = render([commit("fix(fotmob): thanks @helper, mail me@example.com, keep `@code`\n\n"
                              "Ported-from: Base from @author's repo (MIT)")], old=old, new=new,
                      known_issues=[{"text": "Ping @nobody", "app": "@FotMob"}])
        self.assertIn("Thanks `@helper`, mail me@example.com, keep `@code`", text)
        self.assertIn("Asked for by `@someone` and `@org/team`", text)
        self.assertIn("Base from `@author`'s repo (MIT)", text)
        self.assertIn("**`@FotMob`:** Ping `@nobody`", text)
        self.assertNotRegex(text.replace("me@example.com", ""), r"(?<![`\w])@\w")

    def test_skipped_types_and_dedup(self):
        text = render([
            "docs(truecaller): record the emulator run", "chore: Release v1.0.0 [skip ci]",
            "test(fotmob): cover the font hook", "ci: pin actions", "build(deps): bump gradle",
            "refactor(fotmob): split the patch", "Merge pull request #12 from owner/dev",
            "fix(fotmob): use the device font", "fix(fotmob): use the device font.",
        ])
        self.assertNotIn("record the emulator run", text.lower())
        self.assertEqual(text.count("Use the device font"), 1)
        self.assertNotIn("#12", text)

    def test_subject_tidying(self):
        text = render(["fix(fotmob): collect classes first, refresh stale notes",
                       "fix(fotmob): keep the font; record policy notes",
                       "fix(fotmob): tidy the hooks and updated to version 239.1"])
        lines = [l for l in block(text, "FotMob").splitlines() if l.startswith("- ")]
        self.assertEqual(lines, ["- **FotMob - Fixed:** Collect classes first", "- **FotMob - Fixed:** Keep the font",
                                 "- **FotMob - Fixed:** Tidy the hooks"])
        kept = render(["fix(fotmob): keep the list, add notes export",
                       "fix(fotmob): sync folders; update notes on open"])
        self.assertIn("- **FotMob - Fixed:** Keep the list, add notes export", kept)
        self.assertIn("- **FotMob - Fixed:** Sync folders; update notes on open", kept)

    def test_shared_internal_and_other(self):
        text = render(["fix(gms): rename permission groups in clones", "fix(release): verify the asset",
                       "fix: keep the bundle loadable", "feat(deps): bump the patcher"])
        self.assertIn("### Shared patches\n- **Fixed:** Rename permission groups in clones", text)
        self.assertIn("### Other changes\n- **Fixed:** Keep the bundle loadable", text)
        self.assertIn("### Internal improvements\n- **Fixed:** Verify the asset\n"
                      "- **Improved:** Bump the patcher", text)

    def test_summary_line_only_for_big_releases(self):
        self.assertNotIn("In this release", render([]))
        old = patch_list("1", *[app(f"a{i}", f"App {i}", "1", "A") for i in range(9)])
        new = patch_list("2", *[app(f"a{i}", f"App {i}", "2", "A") for i in range(8)],
                         app("a8", "App 8", "1", "A", "B"), app("n", "New", "1", "A"))
        self.assertIn("In this release: 1 new app, 1 new patch for an existing app, "
                      "1 app with patch changes, 8 compatibility updates.", render([], old=old, new=new))

    def test_summary_counts_each_app_once_and_leaves_out_universal_patches(self):
        old = patch_list("1", *[app(f"a{i}", f"App {i}", "1", "A") for i in range(8)])
        new = patch_list("2", *[app(f"a{i}", f"App {i}", "2", "A", "B") for i in range(7)],
                         app("a7", "App 7", "1", "A"), universal=["U1", "U2"])
        stats = notes.build_model(old, new, [], REPO)["stats"]
        self.assertEqual((stats["changed_apps"], stats["compat"], stats["new_patches"]), (7, 0, 7))
        # 7 apps changed: below the threshold of 8, as nothing else was touched.
        self.assertNotIn("In this release", render([], old=old, new=new))


class KnownIssuesTest(unittest.TestCase):
    CONFIG = {"known_issues": [
        {"text": "Spoof signature breaks GmsCore clones", "app": "Sofascore", "since": "1.1.0-dev.1"},
        {"text": "Fixed long ago", "since": "0.1.0", "resolved": "1.0.0"},
        {"text": "Later", "since": "2.0.0"},
        {"text": ""},
    ]}

    def test_open_issues_for_a_version(self):
        self.assertEqual([i["text"] for i in notes.open_known_issues(self.CONFIG, "1.1.0")],
                         ["Spoof signature breaks GmsCore clones"])
        self.assertEqual(notes.open_known_issues(self.CONFIG, "1.0.5"), [])
        self.assertEqual(len(notes.open_known_issues(self.CONFIG, "2.0.0")), 2)

    def test_rendered_after_the_changes(self):
        text = render(["fix(fotmob): x"], known_issues=notes.open_known_issues(self.CONFIG, "1.1.0"))
        # Plain lines, not bullets, so Manager does not badge Sofascore on every release.
        self.assertIn("### Known issues\n\n**Sofascore:** Spoof signature breaks GmsCore clones", text)
        self.assertNotIn("- **Sofascore:**", text)
        self.assertLess(text.index("### FotMob"), text.index("### Known issues"))

    def test_malformed_config_parts_are_dropped_one_by_one(self):
        with contextlib.redirect_stderr(io.StringIO()) as err:
            config = notes.clean_config({
                "known_issues": [{"text": "Good"}, "bad", {"text": ""}],
                "commits": {"abcdef12": {"note": "ok"}, "abc": {"note": "short"}, "1234567x": "text"},
            })
            self.assertEqual(config, {"known_issues": [{"text": "Good"}],
                                      "commits": {"abcdef12": {"note": "ok"}}})
            self.assertEqual(notes.clean_config({"commits": ["x"], "known_issues": {}}),
                             {"known_issues": [], "commits": {}})
            self.assertEqual(notes.clean_config([]), {})
        self.assertIn("ignoring commits entry 'abc'", err.getvalue())

    def test_semver_order(self):
        keys = [notes.semver_key(v) for v in ["1.0.0-dev.2", "1.0.0-dev.10", "1.0.0", "1.0.1-dev.1"]]
        self.assertEqual(keys, sorted(keys))
        self.assertIsNone(notes.semver_key("latest"))


class SnapshotTest(unittest.TestCase):
    """Whole notes for two small fixtures; the files show the layout as GitHub and Manager get it."""

    def check(self, name, text):
        path = SNAPSHOTS / f"{name}.md"
        if os.environ.get("UPDATE_SNAPSHOTS"):
            path.parent.mkdir(exist_ok=True)
            path.write_text(text, encoding="utf-8", newline="\n")
        expected = path.read_text(encoding="utf-8").replace("\r\n", "\n")
        self.assertEqual(text.replace("\r\n", "\n"), expected)

    def test_dev_release(self):
        text = notes.render_notes(OLD, NEW, [
            "feat(truecaller): add a switch for the Messages tab and a new settings row",
            "fix(fotmob): keep the font steady (#9)",
            "fix(gms): rename permission groups in clones",
        ], REPO, "1.1.0-dev.1", DATE, {}, "v1.0.0")
        self.check("dev-release", text)

    def test_stable_release(self):
        old = patch_list("1.0.0", app("com.sofascore", "Sofascore", "26.11.09", "Disable ads", "Spoof signature"),
                         app("com.taskito", "Taskito", "1.3.4", "Enable Premium", codes={"ARM64_V8A": 1340}),
                         p("com.keyboard", "Samsung Keyboard", "5.9", "Stop remote updates"))
        new = patch_list("1.1.0", app("com.sofascore", "Sofascore", "26.11.16", "Disable ads", "Spoof signature"),
                         p("com.sofascore", "Sofascore", "26.11.16", "Hide gambling warning pop-up",
                           description="Skips the responsible-gambling pop-up."),
                         app("com.taskito", "Taskito", "1.3.4", "Enable Premium", codes={"ARM64_V8A": 1341}),
                         p("com.keyboard", "Samsung Keyboard", "5.9", "Stop remote updates", default=False),
                         app("com.tom", "My Talking Tom", "26.5", "Disable ads", "Free purchases"),
                         universal=["Remove screenshot restriction"])
        commits = [
            commit("feat(sofascore): add Hide gambling warning pop-up"),
            commit("fix(sofascore): stop QuickHide leaking league activities\n\n"
                   "Release-note: Unwanted league content no longer appears when using QuickHide"),
            commit("fix(release): verify the exact stable asset"),
            commit("feat: upgrade the patcher\n\nBREAKING CHANGE: needs Morphe Manager 2.5 or newer"),
            commit("fix(keyboard): ported base\n\nPorted-from: Keyboard base from someone/patches (GPL-3.0)"),
        ]
        known = [{"text": "Spoof signature needs a fresh install", "app": "Sofascore"}]
        text = notes.render_notes(old, new, commits, REPO, "1.1.0", DATE, {}, "v1.0.0", None, known)
        self.check("stable-release", text)


class FallbackTest(unittest.TestCase):
    def test_plain_commit_list(self):
        text = notes.render_fallback(
            ["feat(truecaller): add Heval Controls", "fix: crash on start (#9)", "docs: notes",
             "fix(brave): updated to version 1.96.2"], REPO, "1.0.0", DATE)
        self.assertIn(
            "## 1.0.0 (2026-11-03)\n\n### Changes\n"
            "- **truecaller:** Improved: Add Heval Controls\n"
            "- Fixed: Crash on start ([#9](https://github.com/owner/repo/issues/9))\n"
            "- **brave:** Fixed: Updated to version 1.96.2\n\n### Download and verify", text)

    def test_no_credits_section_without_credits(self):
        self.assertNotIn("Credits", render([]))
        self.assertNotIn("Credits", notes.render_fallback([], REPO, "1.1.0", DATE))


@unittest.skipUnless(shutil.which("git"), "git is required")
class GitIntegrationTest(unittest.TestCase):
    """End-to-end runs of main() against a temporary repository."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.git("init", "-q")
        self.write_list(OLD)
        self.commit("chore: Release v1.0.0 [skip ci]")
        self.first = self.git("rev-parse", "HEAD").strip()
        self.git("tag", "v1.0.0")
        self.commit("feat(truecaller): add Heval Controls (#7)")
        self.commit("docs: notes")

    def tearDown(self):
        self.tmp.cleanup()

    def git(self, *args):
        return subprocess.run(
            ["git", "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false",
             "-c", "tag.gpgsign=false", *args],
            cwd=self.root, check=True, capture_output=True, text=True,
        ).stdout

    def write_list(self, data):
        (self.root / "patches-list.json").write_text(json.dumps(data), encoding="utf-8")

    def commit(self, message):
        self.git("add", "-A")
        self.git("commit", "-q", "--allow-empty", "-m", message)
        return self.git("rev-parse", "HEAD").strip()

    def run_main(self, *args, version="1.1.0"):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = notes.main(["--repo", REPO, "--date", DATE, "--repo-root", str(self.root),
                               *(["--version", version] if version else []), *args])
        self.assertEqual(code, 0)
        self.stderr = err.getvalue()
        return out.getvalue()

    def new_list_file(self, data=NEW):
        path = self.root / "new.json"
        path.write_text(json.dumps(data), encoding="utf-8")
        return str(path)

    def test_previous_list_from_the_previous_tag(self):
        text = self.run_main("--previous-tag", "v1.0.0", "--new-list", self.new_list_file())
        self.assertIn("### Truecaller · 26.42.5", text)
        self.assertIn("- **Truecaller - Improved:** Add Heval Controls ([#7](https://github.com/owner/repo/issues/7))", text)
        self.assertIn("- **365Scores:** `14.9.8` → `15.0.0`", text)
        self.assertIn("### Removed apps\n- LibrePods", text)

    def release(self, tag, data):
        self.write_list(data)
        self.commit(f"chore: Release {tag} [skip ci]")
        self.git("tag", tag)

    def test_channel_aware_previous_tag(self):
        self.release("v1.1.0-dev.1", NEW)
        self.commit("fix(fotmob): one")
        self.release("v1.1.0", NEW)  # stable, back-merged into dev
        self.commit("fix(fotmob): two")
        self.release("v1.2.0-dev.1", NEW)
        self.commit("fix(fotmob): three")
        self.assertEqual(notes.previous_release_tag(self.root, "HEAD", "1.2.0", "stable"), "v1.1.0")
        self.assertEqual(notes.previous_release_tag(self.root, "HEAD", "1.2.0-dev.2", "dev"), "v1.2.0-dev.1")
        self.assertEqual(notes.previous_release_tag(self.root, "v1.2.0-dev.1", "1.2.0-dev.1", "dev"), "v1.1.0")
        self.assertEqual(notes.previous_release_tag(self.root, "v1.1.0", "1.1.0", "stable"), "v1.0.0")
        stable = self.run_main("--new-list", self.new_list_file(), version="1.2.0")
        self.assertIn("compare/v1.1.0...v1.2.0", stable)
        self.assertIn("Two", stable)
        self.assertNotIn("One", stable)

    def test_preview_of_a_tag_reads_its_list_and_writes_nothing_tracked(self):
        self.release("v1.1.0", NEW)
        before = (self.root / "patches-list.json").read_bytes()
        out = self.root / "preview.md"
        self.write_list({"version": "local edit", "patches": []})
        edited = (self.root / "patches-list.json").read_bytes()
        text = self.run_main("--preview", "--to", "v1.1.0", "--output", str(out), version=None)
        self.assertTrue(text.startswith("## 1.1.0 ("))
        self.assertIn("### Truecaller", text)
        self.assertEqual(out.read_text(encoding="utf-8"), text)
        self.assertEqual((self.root / "patches-list.json").read_bytes(), edited)
        self.assertNotEqual(before, edited)

    def test_second_run_after_the_release_commit_gives_the_same_notes(self):
        """semantic-release runs generateNotes again once @semantic-release/git has committed; that
        run's notes become the GitHub release body, so it must match CHANGELOG.md byte for byte."""
        self.commit("fix(fotmob): keep the font\n\nRelease-highlight: FotMob keeps its font\n\n"
                    "BREAKING CHANGE: needs Manager 2.5\nAction-required: Repatch FotMob")
        first = self.run_main("--previous-tag", "v1.0.0", "--new-list", self.new_list_file())
        self.write_list(NEW)  # what the prepare step writes
        self.commit("chore: Release v1.1.0 [skip ci]\n\n" + first)
        second = self.run_main("--previous-tag", "v1.0.0", "--build")
        self.assertEqual(second, first)
        self.git("tag", "v1.1.0")
        self.commit("fix(fotmob): later fix")
        later = self.run_main("--previous-tag", "v1.1.0", "--new-list", self.new_list_file(),
                              version="1.1.1")
        for echoed in ("FotMob keeps its font", "Needs Manager 2.5", "Repatch FotMob", "Keep the font"):
            self.assertNotIn(echoed, later)
        self.assertIn("- **FotMob - Fixed:** Later fix", later)

    def test_preview_of_untagged_work_uses_the_newest_tag(self):
        text = self.run_main("--preview", version=None)
        self.assertTrue(text.startswith("## unreleased ("))
        self.assertIn("range: v1.0.0..HEAD (dev)", self.stderr)
        self.assertIn("- **Truecaller - Improved:** Add Heval Controls", text)

    def test_preview_refuses_to_build(self):
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            notes.parse_args(["--preview", "--build"])

    def test_config_file_overrides_and_known_issues(self):
        sha = self.commit("fix(fotmob): raw subject")
        (self.root / ".github").mkdir()
        (self.root / ".github" / "release-notes.json").write_text(json.dumps({
            "known_issues": [{"text": "Needs a reinstall", "app": "FotMob"}],
            "commits": {sha[:10]: {"note": "Clear words", "category": "Changed"}},
        }), encoding="utf-8")
        text = self.run_main("--previous-tag", "v1.0.0", "--new-list", self.new_list_file())
        self.assertIn("- **FotMob - Changed:** Clear words", text)
        self.assertIn("### Known issues\n\n**FotMob:** Needs a reinstall", text)

    def test_ported_from_trailers_become_credits(self):
        self.commit("feat(samsungkeyboard): add Samsung Keyboard\n\n"
                    "Ported-from: Samsung Keyboard base from someone/patches (GPL-3.0)\n\nCo-Authored-By: x <y@z>")
        self.commit("fix(samsungkeyboard): tidy\n\nPorted-from: Samsung Keyboard base from someone/patches (GPL-3.0)")
        text = self.run_main("--previous-tag", "v1.0.0", "--new-list", self.new_list_file())
        self.assertIn("### Credits\n- Samsung Keyboard base from someone/patches (GPL-3.0)\n\n"
                      "### Download and verify", text)
        self.assertEqual(text.count("someone/patches"), 1)

    def test_first_release_falls_back_to_the_commit_list(self):
        self.git("tag", "-d", "v1.0.0")
        text = self.run_main()
        self.assertIn("### Changes\n- **truecaller:** Improved: Add Heval Controls", text)
        self.assertNotIn("Release v1.0.0", text)

    def test_unreadable_new_list_falls_back(self):
        broken = self.root / "broken.json"
        broken.write_text("{not json", encoding="utf-8")
        text = self.run_main("--previous-tag", "v1.0.0", "--new-list", str(broken))
        self.assertIn("### Changes\n- **truecaller:** Improved: Add Heval Controls", text)
        self.assertIn("full notes failed", self.stderr)

    def test_bad_previous_ref_still_writes_notes(self):
        text = self.run_main("--previous-tag", "v0-missing")
        self.assertTrue(text.startswith("## 1.1.0 (2026-11-03)\n"))
        self.assertIn("patches-1.1.0.mpp", text)

    def test_internal_error_gives_the_minimal_notes(self):
        original = notes.generate
        notes.generate = lambda args: (_ for _ in ()).throw(RuntimeError("boom"))
        try:
            text = self.run_main()
        finally:
            notes.generate = original
        self.assertEqual(text, "## 1.1.0 (2026-11-03)\n\n**Download:** [patches-1.1.0.mpp]"
                               "(https://github.com/owner/repo/releases/download/v1.1.0/patches-1.1.0.mpp)\n")
        self.assertIn("release notes failed: RuntimeError: boom", self.stderr)

    def test_build_uses_an_existing_list_for_the_version(self):
        self.write_list(NEW)
        calls = []
        result = notes.build_new_list(self.root, "1.1.0", runner=lambda *a, **k: calls.append(a))
        self.assertEqual(result, NEW)
        self.assertEqual(calls, [])

    def test_build_runs_gradle_restores_the_list_and_drops_other_bundles(self):
        libs = self.root / "patches" / "build" / "libs"
        original = (self.root / "patches-list.json").read_bytes()

        def fake_gradle(command, cwd, stdout, stderr):
            self.assertIn("generatePatchesList", command)
            self.assertIn("-Pversion=1.1.0", command)
            (Path(cwd) / "patches-list.json").write_text(json.dumps(NEW), encoding="utf-8")
            libs.mkdir(parents=True, exist_ok=True)
            (libs / "patches-1.1.0.mpp").write_bytes(b"new")
            (libs / "patches-1.0.0.mpp").write_bytes(b"stale")
            return subprocess.CompletedProcess(command, 0)

        result = notes.build_new_list(self.root, "1.1.0", runner=fake_gradle)
        self.assertEqual(result, NEW)
        self.assertEqual((self.root / "patches-list.json").read_bytes(), original)
        self.assertEqual(sorted(p.name for p in libs.glob("*.mpp")), ["patches-1.1.0.mpp"])

    def test_failed_build_gives_none(self):
        original = (self.root / "patches-list.json").read_bytes()
        result = notes.build_new_list(
            self.root, "1.1.0", runner=lambda command, **k: subprocess.CompletedProcess(command, 1))
        self.assertIsNone(result)
        self.assertEqual((self.root / "patches-list.json").read_bytes(), original)


class RealSourcesTest(unittest.TestCase):
    def test_patch_directories_map_to_packages(self):
        index = notes.scan_patch_dirs(ROOT)
        if not index:
            self.skipTest("patch sources not present")
        self.assertIn("com.truecaller", index.get("truecaller", set()))
        self.assertIn("scores365", index)

    def test_tracked_config_is_valid(self):
        path = ROOT / notes.CONFIG_FILE
        if not path.is_file():
            self.skipTest("no release-notes config")
        config = json.loads(path.read_text(encoding="utf-8"))
        self.assertIsInstance(config.get("known_issues", []), list)
        self.assertIsInstance(config.get("commits", {}), dict)


class ReleasercTest(unittest.TestCase):
    def test_exec_generates_the_notes(self):
        config = json.loads((ROOT / ".releaserc").read_text(encoding="utf-8"))
        names = [p if isinstance(p, str) else p[0] for p in config["plugins"]]
        self.assertNotIn("@semantic-release/release-notes-generator", names)
        exec_config = config["plugins"][names.index("@semantic-release/exec")][1]
        command = exec_config["generateNotesCmd"]
        self.assertIn(".github/scripts/generate_release_notes.py --build", command)
        self.assertIn("${nextRelease.version}", command)
        self.assertIn("${lastRelease.gitTag}", command)
        # The notes come from generateNotes, which runs before the changelog/git prepare steps.
        self.assertLess(names.index("@MorpheApp/changelog"), names.index("@semantic-release/git"))


if __name__ == "__main__":
    unittest.main()
