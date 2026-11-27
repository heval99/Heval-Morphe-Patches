# Release notes

Every release gets its notes from `.github/scripts/generate_release_notes.py`. semantic-release runs it
(the `generateNotesCmd` in `.releaserc`), and the same Markdown goes to four places:

- `CHANGELOG.md`, which is what Morphe Manager reads and shows for the bundle,
- the `description` field of `patches-bundle.json`,
- the release commit message,
- the GitHub release.

No AI or online service is involved. The same commits and patch lists always give the same notes.

## Where the content comes from

| Source | Gives |
|---|---|
| `patches-list.json` at the previous release and at this one | new and removed apps, added and removed patches (with their descriptions), "now on/off by default", and every compatibility change, per patch. A build-only update shows build numbers. When only some patches of an app support a version, the notes say so. |
| Commits in the range (`feat`, `fix`, `perf`) | one line each under the app named by the scope. `feat` becomes **Improved**, `fix` **Fixed**, `perf` **Performance**. `fix(<app>): updated to version X` gives no line, because the compatibility list already shows it. |
| Commit trailers (below) | a reworded line, another category, highlights, required actions |
| `BREAKING CHANGE:` footer | **Important** at the top (see Trailers for why `!` alone is not enough) |
| `.github/release-notes.json` | known issues, plus corrections to commits that are already pushed |

**Range.** A stable release covers everything since the previous stable release, so the notes of all
its dev pre-releases are merged. A dev pre-release covers everything since the previous release on
either channel (stable releases are merged back into dev). CI passes semantic-release's
`lastRelease`, which follows these rules. The preview picks the same tag itself.

**Merged (stable) notes.** Only the two ends of the range count for apps, patches and versions, so
in-between version bumps don't show. Some commit lines are dropped because another line already says
the same thing:

- a commit together with the revert of it in the same range. A re-landed change (`Reapply "X"`,
  or `Revert "Revert "X""`) counts as X, so X is shown once;
- lines about a patch or app that was added and removed again within the range;
- a `feat` that only names patches added in this release and says nothing their **Added** lines
  (patch name and description) don't already say. One extra word and filler such as "add a switch
  to" are allowed. A `feat` that also says something else, such as "add Hide inbox cards and a
  faster contact search", stays;
- fixes to a patch that is new in this release: for users the patch is new, so a fix made to it
  before the release is part of it;
- a commit that only turns patches on or off by default, which the **Changed** line already says.

A commit with a `Release-note:` trailer (or a `note` in `.github/release-notes.json`) is never
dropped this way. Use one when a dropped line carried something users need to know.

## Layout

Sections only appear when they have content, in this order:

1. Version heading, and for dev a one-line pre-release notice
2. A one-line count, only when the release touches 8 or more apps
3. **Important**: breaking changes and required actions
4. **Highlights**
5. **New apps**: supported version and patches
6. One block per changed app: `### App · version`, a **Compatibility:** line if its version
   changed, then lines like `- **Sofascore - Added:** ...`, in the order Added, Improved, Fixed,
   Changed, Performance, Deprecated, Removed
7. **Compatibility updates**: apps whose only change is a new version, one line each
8. **Shared patches**: universal patches and shared code (`gms`, `controls`, `signature`, ...)
9. **Removed apps**, **Other changes** (no or unknown scope), **Known issues**
10. **Internal improvements**: `feat`/`fix` with a scope like `ci`, `release`, `build` or `docs`
11. **Credits** (only when old `Ported-from:` trailers are in the range)
12. **Download and verify**: bundle, `SHA256SUMS.txt`, compare link, attestation command

The Markdown stays inside what Morphe Manager renders. Its parser shows headings as plain text, and
bullets with `**bold**`, `` `code` `` and links. It does not render tables, HTML such as `<details>`, or
nested lists. Each `###` heading becomes a card.

On an app's own page, and for its update badge, Manager ignores the heading a bullet sits under. It
only counts bullets whose bold label is the app's name, or whose label part before ` - ` is (letters
and digits are compared, so case and punctuation don't matter). Bullets without a bold label also
count when they name the app in their text. That is why every app line carries its app:

- in an app block: `- **Sofascore - Added:** Hide gambling warning pop-up — ...`,
- in New apps and Compatibility updates: `- **Sofascore:** ...`.

The name is the app's display name from `patches-list.json` (the bundle's `Compatibility` name),
which is the name Manager matches. Two limitations:

- If two packages share a display name, the headings add the package (`### Brave (com.x) · 1.0`) and
  the list bullets read `- **Brave - com.x:** ...`. Manager still files those under the name. App
  block lines use `**Brave - Added:**`, so Manager shows them for both packages: it tells apps
  apart only by name here.
- A display name that itself contains ` - ` would break the match. No app's name does today. The
  test `test_every_app_label_is_one_manager_files_under_that_app` checks the rule with a copy of
  Manager's matching.

The section is not called "Updated app support": Manager sorts a card with that title above every
other card, including Important.

Known issues are plain lines (`**Sofascore:** ...`), not bullets. Manager only counts bullets
toward an app's update badge, and an open issue repeats in every release, so a bullet would mark the
app as changed each time.

`@name` and `@org/team` in commit text, trailers, known issues and patch descriptions are put in
backticks, so the GitHub release does not notify anyone. E-mail addresses are left alone.

## Trailers

Trailers are optional. Put them anywhere in the commit body as `Key: value` lines. Without them, the
notes use the commit subject.

| Trailer | Effect |
|---|---|
| `Release-note: <text>` | Use this text instead of the subject. Write it for users. |
| `Release-note: none` | Leave the commit out of the notes. |
| `Release-category: <name>` | One of `Added`, `Improved`, `Fixed`, `Changed`, `Performance`, `Deprecated`, `Removed`, `Compatibility`. `Internal` moves the line to Internal improvements. `none` drops it. |
| `Release-highlight: <text>` | Add a line under Highlights. The normal line still appears. |
| `Action-required: <text>` | Add a line under Important, for example "Repatch Sofascore from the original APK". |
| `BREAKING CHANGE: <text>` | Add a line under Important and give the release a major version. |

A breaking change needs that exact footer. semantic-release here uses its default (angular) commit
parser. That parser does not read `feat!:` as a feat, and it ignores `BREAKING-CHANGE:`, so neither
makes a major release. The notes follow semantic-release: `!` alone gives a normal line, not a
Breaking one. The Release step logs `... has '!' or BREAKING-CHANGE but no 'BREAKING CHANGE:' footer`
for such a commit.

Example:

```
fix(samsungkeyboard): make Stop remote updates and Always use GIPHY opt-in

Release-note: Stop remote updates and Always use GIPHY are now off by default.
Release-category: Changed
```

## `.github/release-notes.json`

`dev` can't be force-pushed, so a commit that is already pushed can't get a trailer later. Put the
correction here, keyed by at least 7 characters of the commit SHA. Known issues also live here: one
is listed in every release from `since` up to the one before `resolved`. Both fields are optional.

```json
{
  "known_issues": [
    {"app": "Sofascore", "text": "Spoof signature needs a fresh install.", "since": "3.3.0-dev.2"},
    {"text": "An old problem.", "since": "3.0.0", "resolved": "3.2.0"}
  ],
  "commits": {
    "b4aa1424": {"note": "Spoof signature is now on by default.", "category": "Changed"},
    "8c4b6ba9": {"note": "none"}
  }
}
```

A commit entry may set `note`, `category`, `highlight`, `action`, `scope` and `credits` (a list;
`[]` drops that commit's old `Ported-from:` credit). Only list known issues
that someone has confirmed, and set `resolved` to the release that fixes the issue.

If the file is not valid JSON, it is ignored and the Release step logs why. A bad entry (a known
issue without `text`, or a `commits` key shorter than 7 characters or with a value that is not an
object) is logged and skipped on its own, and the rest of the file still applies.

## Preview locally

The preview never builds, never pushes and never changes tracked files:

```
python -I .github/scripts/generate_release_notes.py --preview --to v3.3.0-dev.3
python -I .github/scripts/generate_release_notes.py --preview --from v3.1.0 --to v3.2.0 --channel stable --output ../notes.md
```

Write `--output` files outside the repository, so they can't be committed by mistake.

A preview of an old tag maps commit scopes to apps with today's patch folders, so the old notes can
differ slightly from the ones that were published. Everything else (lists, commits, config) comes
from that tag.

- `--to` is a tag or ref. For a tag, the version, date and new `patches-list.json` come from that tag.
- `--from` is optional. Without it, the previous release is picked by the channel rules above.
- `--channel` is optional. The default is `dev` for a pre-release version or a preview of untagged
  work, and `stable` otherwise.
- `--output` also writes the notes to a file.

Previewing unreleased work at `HEAD`: the tracked `patches-list.json` is still the one from the last
release. Build a list with `./gradlew generatePatchesList` (it rewrites the tracked file, so restore
it with `git checkout -- patches-list.json`) and pass a copy with `--new-list`. Pass `--version` to
name the release.

Tests: `python -m unittest discover -s .github/scripts/tests`. The snapshot files in
`.github/scripts/tests/snapshots/` show whole notes. After an intended layout change, rewrite them
with `UPDATE_SNAPSHOTS=1` and review the diff.

## When something goes wrong

The script never fails a release. If the full notes fail, it logs `[release-notes] full notes failed
(...)` in the Release step and writes a plain list of the commits instead. If that fails too, it
writes the heading and the download link. Every run logs the range it used, for example
`[release-notes] range: v3.2.0..HEAD (stable)`.
