#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")" && pwd)
source_root="$root/../current/campus-pocket/app/src/main/java/dev/campuspocket/app"
checks_root="$root/../current/campus-pocket/checks"
work=$(mktemp -d "${TMPDIR:-/tmp}/campus-source-checks.XXXXXX")
trap 'rm -rf "$work"' EXIT

mapfile -d '' -t sources < <(find "$root/flow/src" -name '*.java' -print0)
for unit in USpeakingTraining ChatRecordingGate TrainingHold TrainingPage TrainingScore TrainingMarkers \
    TrainingVisualControls PageModel CoursePage ReadAloudPage SpeakingCards USpeakingReadout \
    USpeakingEngine USpeakingFollowPage; do
    sources+=("$source_root/$unit.java")
done
mapfile -d '' -t checks < <(find "$checks_root" -maxdepth 1 -name '*Check.java' -print0)
sources+=("${checks[@]}")
javac --release 17 -encoding UTF-8 -d "$work" "${sources[@]}"

for check in PageModelCheck CoursePageCheck ReadAloudPageCheck SpeakingCardsCheck \
    USpeakingFollowPageCheck TrainingScoreCheck USpeakingEngineCheck; do
    java -cp "$work" "dev.campuspocket.app.$check"
done
