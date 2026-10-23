#!/usr/bin/env bash
# Publishes the latest GitHub release to Zapstore. Run it after pushing a v* tag: it waits for the
# Release workflow, then zsp opens a local page where your NIP-07 extension signs the events.
# Extra arguments go to `zsp publish` (e.g. --overwrite-release).
set -euo pipefail
cd "$(dirname "$0")/.."
REPO=lawalletio/android-wallet

run=$(gh run list -R "$REPO" --workflow=release.yml --event=push --limit 1 \
  --json databaseId,status --jq '.[] | select(.status != "completed") | .databaseId')
if [ -n "$run" ]; then gh run watch "$run" -R "$REPO" --exit-status; fi

GITHUB_TOKEN="${GITHUB_TOKEN:-$(gh auth token)}" SIGN_WITH=browser exec zsp publish zapstore.yaml "$@"
