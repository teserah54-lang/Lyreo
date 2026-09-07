#!/usr/bin/env bash
# Radar drift dependency extractor — versi lokal (tanpa GitHub Actions).
#
# Kenapa ada dua versi?
#   GitHub App yang dipakai untuk mendorong commit tidak punya izin `workflows`,
#   jadi berkas workflow tidak bisa diletakkan di .github/workflows/ dari sesi ini.
#   Skrip ini memberi kemampuan yang sama dan bisa dijalankan siapa saja:
#
#       bash tools/ci/extractor-radar.sh                 # bandingkan pin vs HEAD fork
#       bash tools/ci/extractor-radar.sh --open-issue    # sekalian buka issue bila tertinggal
#       REPO=TeamNewPipe/NewPipeExtractor bash tools/ci/extractor-radar.sh
#
# Butuh: bash, jq, gh (sudah login) — semuanya ada di devcontainer repo ini.
#
# Keluaran: ringkasan di stdout + exit code 1 bila pin tertinggal (berguna untuk
# dipakai sebagai langkah CI mandiri bila suatu saat izin workflows tersedia).

set -uo pipefail

cd "$(dirname "$0")/../.." || exit 2

BUILD_FILE="app/build.gradle.kts"
DEFAULT_REPO="MetrolistGroup/MetrolistExtractor"
REPO="${REPO:-$DEFAULT_REPO}"
OPEN_ISSUE=0
[[ "${1:-}" == "--open-issue" ]] && OPEN_ISSUE=1

command -v jq >/dev/null || { echo "jq belum terpasang"; exit 2; }
command -v gh >/dev/null || { echo "gh belum terpasang / belum login"; exit 2; }

# 1) Ambil pin (commit SHA) dari build.gradle.kts
PIN="$(grep -oE "com\.github\.[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[0-9a-f]{7,40}" "$BUILD_FILE" \
  | head -1 | awk -F: '{print $3}')"
if [[ -z "${PIN}" ]]; then
  echo "PIN tidak ditemukan di $BUILD_FILE — apakah dependency masih berupa SHA commit?"
  echo "(Bila sudah pindah ke tag/versi semver, sesuaikan regex di skrip ini.)"
  exit 2
fi

ARTIFACT="$(grep -oE "com\.github\.[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[0-9a-f]{7,40}" "$BUILD_FILE" \
  | head -1 | cut -d: -f1-2)"

echo "== Radar extractor ==========================================="
echo "artifact : $ARTIFACT"
echo "pin      : $PIN"
echo "repo     : $REPO"

# 2) HEAD upstream
HEAD_SHA="$(gh api "repos/$REPO/commits?per_page=1" --jq '.[0].sha' 2>/dev/null)"
HEAD_DATE="$(gh api "repos/$REPO/commits?per_page=1" --jq '.[0].commit.committer.date' 2>/dev/null)"
if [[ -z "${HEAD_SHA}" ]]; then
  echo "GAGAL mengambil HEAD $REPO (periksa nama repo / akses gh)."
  exit 2
fi
echo "HEAD     : ${HEAD_SHA:0:12} ($HEAD_DATE)"

# Bila repo yang ditanyakan bukan pemilik pin (mis. REPO=TeamNewPipe/NewPipeExtractor),
# perbandingan SHA tidak bermakna — cukup laporkan keadaan repo itu.
PIN_OWNER="$(printf '%s' "$ARTIFACT" | sed 's/^com\.github\.//' | cut -d: -f1)"
if [[ "$REPO" != "$PIN_OWNER" ]]; then
  echo "STATUS   : ℹ️ repo pembanding (pin milik $PIN_OWNER)"
  echo
  echo "-- rilis/push terbaru --"
  gh api "repos/$REPO/tags?per_page=5" --jq '.[].name' 2>/dev/null | sed 's/^/   tag: /'
  echo "   push: $(gh api "repos/$REPO" --jq '.pushed_at' 2>/dev/null)"
  exit 0
fi

if [[ "$HEAD_SHA" == "$PIN"* || "$PIN" == "$HEAD_SHA"* ]]; then
  echo "STATUS   : ✅ pin = HEAD upstream (tidak ada drift)"
  exit 0
fi

# 3) Bandingkan: berapa commit di depan?
COMPARE_JSON="$(gh api "repos/$REPO/compare/${PIN}...${HEAD_SHA}" 2>/dev/null)"
if [[ -z "$COMPARE_JSON" ]]; then
  echo "STATUS   : ⚠️ perbandingan gagal (pin mungkin bukan commit di repo ini)"
  exit 2
fi
AHEAD="$(printf '%s' "$COMPARE_JSON" | jq -r '.ahead_by // 0')"
BEHIND="$(printf '%s' "$COMPARE_JSON" | jq -r '.behind_by // 0')"

if [[ "$AHEAD" == "0" ]]; then
  echo "STATUS   : ℹ️ HEAD bukan keturunan pin (behind_by=$BEHIND) — pin di branch lain?"
  exit 0
fi

echo "STATUS   : ⚠️ pin TERTINGGAL $AHEAD commit (behind_by=$BEHIND)"
echo
echo "-- commit yang terlewat (maks. 40) --"
printf '%s' "$COMPARE_JSON" \
  | jq -r '.commits[]? | "\(.sha[0:9])  \(.commit.author.date[0:10])  \(.commit.message | split("\n")[0])"' \
  | head -40

# 4) Pembanding strategis: extractor lain di ekosistem
echo
echo "-- rilis/push terbaru extractor pembanding --"
for ALT in InfinityLoop1308/PipePipeExtractor TeamNewPipe/NewPipeExtractor; do
  TAG="$(gh api "repos/$ALT/tags?per_page=1" --jq '.[0].name // "-"' 2>/dev/null)"
  PUSH="$(gh api "repos/$ALT" --jq '.pushed_at // "-"' 2>/dev/null)"
  echo "   $ALT  tag=$TAG  push=$PUSH"
done

# 5) Opsional: buka issue (butuh izin issue di repo ini)
if [[ "$OPEN_ISSUE" == "1" ]]; then
  BODY="$(mktemp)"
  {
    echo "Pin \`$ARTIFACT\` di \`$BUILD_FILE\` tertinggal **$AHEAD commit** dari HEAD \`$REPO\`."
    echo
    echo '```'
    printf '%s' "$COMPARE_JSON" \
      | jq -r '.commits[]? | "\(.sha[0:9])  \(.commit.author.date[0:10])  \(.commit.message | split("\n")[0])"' \
      | head -40
    echo '```'
    echo
    echo "Lihat \`docs/streaming-resilience.md\` §7 (kebijakan pin) sebelum menaikkan."
  } > "$BODY"

  EXISTING="$(gh issue list --label extractor-drift --state open --json number --jq '.[0].number // empty' 2>/dev/null)"
  if [[ -n "$EXISTING" ]]; then
    gh issue comment "$EXISTING" --body-file "$BODY" && echo "issue #$EXISTING diperbarui"
  else
    gh label create extractor-drift --color D93F0B \
      --description "Pin extractor tertinggal dari upstream" >/dev/null 2>&1
    gh issue create --title "Extractor drift: pin tertinggal $AHEAD commit ($REPO)" \
      --label extractor-drift --body-file "$BODY"
  fi
  rm -f "$BODY"
fi

exit 1
