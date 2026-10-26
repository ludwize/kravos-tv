#!/usr/bin/env bash
# Pushes CI output to the "ci-logs" branch so it can be read through the plain REST contents API.
# usage: publish-log.sh <name> <file-or-dir>
set -u
NAME=$1
SRC=$2
DIR=$(mktemp -d)
git config --global user.email "ci@kravos.invalid"
git config --global user.name "ci"
REMOTE="https://x-access-token:${GITHUB_TOKEN:-$ACTIONS_TOKEN}@github.com/${GITHUB_REPOSITORY}.git"
if git ls-remote --exit-code --heads origin ci-logs >/dev/null 2>&1; then
  git clone --quiet --depth 1 --branch ci-logs "$REMOTE" "$DIR"
else
  git init --quiet "$DIR"
  git -C "$DIR" checkout --quiet -b ci-logs
fi
rm -rf "$DIR/$NAME"
mkdir -p "$DIR/$NAME"
if [ -d "$SRC" ]; then
  cp -r "$SRC"/. "$DIR/$NAME/"
elif [ -f "$SRC" ]; then
  # Keep the interesting part of long Gradle logs.
  grep -n -E "^e: |^w: .*error|error:|What went wrong|FAILURE|Could not|Execution failed|Caused by|> Task .*FAILED" -A 4 "$SRC" | head -400 > "$DIR/$NAME/errors.txt" || true
  tail -n 300 "$SRC" > "$DIR/$NAME/tail.txt"
fi
echo "${GITHUB_RUN_NUMBER:-?} ${GITHUB_SHA:-?}" > "$DIR/$NAME/run.txt"
cd "$DIR"
git add -A
git commit --quiet -m "$NAME log for run ${GITHUB_RUN_NUMBER:-?}" || true
git push --quiet --force "$REMOTE" ci-logs
