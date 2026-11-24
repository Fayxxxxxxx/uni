#!/usr/bin/env bash
set -euo pipefail

test_root=$(cd "$(dirname "$0")" && pwd)
source_root="$test_root/../current/campus-pocket/app/src/main/java/dev/campuspocket/app"
work=$(mktemp -d "${TMPDIR:-/tmp}/campus-regressions.XXXXXX")
trap 'rm -rf "$work"' EXIT
mkdir -p "$work"/{audio,flow,tts}

if [[ -n ${ECJ_JAR:-} ]]; then
    compiler=(java -jar "$ECJ_JAR" -17 -nowarn -proc:none)
else
    compiler=(javac --release 17)
fi

mapfile -d '' -t audio_sources < <(find "$test_root/audio/src" -name '*.java' -print0)
"${compiler[@]}" -encoding UTF-8 -d "$work/audio" "${audio_sources[@]}" \
    "$source_root/TrainingAudio.java" "$source_root/TrainingWave.java"
java -Djava.io.tmpdir="$work/tts" -cp "$work/audio" dev.campuspocket.app.NaturalPlaybackRegression

mapfile -d '' -t flow_sources < <(find "$test_root/flow/src" -name '*.java' -print0)
for unit in USpeakingTraining ChatRecordingGate TrainingHold TrainingPage TrainingScore TrainingMarkers TrainingVisualControls PageModel \
    CoursePage ReadAloudPage SpeakingCards USpeakingReadout USpeakingEngine; do
    flow_sources+=("$source_root/$unit.java")
done
"${compiler[@]}" -encoding UTF-8 -d "$work/flow" "${flow_sources[@]}"
for check in NaturalReadingRegression HoldReleaseRegression RecoveryCycleRegression AudioRecoveryRegression ChatRecordingRecoveryRegression TaskCardRecoveryRegression ResultSubmitRegression TrainingVisualControlsRegression TutorialRecoveryRegression; do
    java -cp "$work/flow" "dev.campuspocket.app.$check"
done
