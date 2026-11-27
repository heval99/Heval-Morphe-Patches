# AI issue triage

The "Issue triage" GitHub Action (`.github/workflows/issue_triage.yml`, script
`.github/scripts/issue-triage/triage.js`) checks new issues with a free OpenRouter model.

What it does on a new issue:

- Renames a title that does not match the body to `bug: ...`, `feat: ...` or `question: ...`.
- Closes an issue that has too little information to act on, with a comment that lists what to
  add. When the author edits the issue or replies, it is checked again and reopened if it is now
  clear enough. That happens at most twice.
- Never closes an issue that has logs, a stack trace, a screenshot or another attachment.
- Skips issues opened by the owner, members, collaborators and bots, and app removal requests
  (title starting with `remove:`), which a maintainer handles.

Issue events run workflows from the default branch, so the action only starts working once it
is on `main` (after the dev to main merge).

Comments are built only from fixed text in the script, never from the model's reply.

## Modes

It runs as a **dry run by default**: it only writes what it would do to the run summary
(Actions > Issue triage > a run > Summary). Nothing on the issue changes.

| Repository variable | Value | Effect |
|---|---|---|
| `ISSUE_TRIAGE_MODE` | `live` | Renames, comments, closes and reopens. Any other value or unset: dry run. |
| `OPENROUTER_MODEL` | `model-a,model-b` | Replaces the default free models (tried in order). |
| `ISSUE_TRIAGE_INCLUDE_MAINTAINERS` | `true` | Also triages issues of maintainers. |

## Setup

1. Create an OpenRouter account and an API key at https://openrouter.ai/settings/keys.
2. Add it as the repository secret `OPENROUTER_API_KEY` (Settings > Secrets and variables >
   Actions). Without it the action only logs that it is not set up.
3. In OpenRouter's privacy settings (https://openrouter.ai/settings/privacy), allow the providers of
   the free models.
4. Watch a few dry runs, then set `ISSUE_TRIAGE_MODE` to `live`.

To check one issue by hand: Actions > Issue triage > Run workflow, with the issue number.
To turn it off: Actions > Issue triage > "..." menu > Disable workflow.

## Limits and privacy

- Free models allow about 20 requests a minute and 50 a day (1000 a day after buying at least
  $10 of OpenRouter credits). A new issue costs one request, each re-check one more.
- Issue text is sent to OpenRouter and the model provider, which may log it or train on it. Issues
  are public anyway, but do not rely on the action for anything private.
- Free models come and go. If runs fail, set `OPENROUTER_MODEL` to models that exist.
