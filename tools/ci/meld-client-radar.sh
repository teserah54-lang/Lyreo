#!/usr/bin/env bash
# Radar drift spesifikasi klien terhadap Meld — versi lokal (tanpa GitHub Actions).
#
# Kenapa skrip ini ada?
#   Sejak porting September 2026, `PlayerClientLadder.kt` menyalin spesifikasi klien
#   InnerTube (clientName/clientVersion/clientId/userAgent/device fields) BYTE-PER-BYTE
#   dari FrancescoGrazioso/Meld — klien musik anonim yang pengembangnya mengukur sendiri
#   klien mana yang sanggup melayani satu file utuh. Nilai-nilai itu dipin per versi
#   (mis. ANDROID_VR 1.65.10 vs 1.43.32 yang ter-gate), jadi drift di hulu berarti
#   tangga klien Lyreon perlahan basi tanpa ada yang menyadari.
#
#   Skrip ini membandingkan pin kita dengan `YouTubeClient.kt` di HEAD Meld dan
#   melaporkan selisihnya. Tidak menyentuh jaringan YouTube (yang diblokir di banyak
#   lingkungan CI) — hanya GitHub API.
#
#       bash tools/ci/meld-client-radar.sh
#       REPO=FrancescoGrazioso/Meld bash tools/ci/meld-client-radar.sh
#
# Butuh: bash, gh (sudah login), python3.
# Keluaran: ringkasan di stdout; exit 1 bila ada drift (siap dipakai sebagai langkah CI).

set -uo pipefail

cd "$(dirname "$0")/../.." || exit 2

REPO="${REPO:-FrancescoGrazioso/Meld}"
MELD_FILE="${MELD_FILE:-innertube/src/main/kotlin/com/metrolist/innertube/models/YouTubeClient.kt}"
OURS="app/src/main/java/com/lyreon/app/yt/innertube/PlayerClientLadder.kt"

command -v gh >/dev/null || { echo "gh belum terpasang / belum login"; exit 2; }
command -v python3 >/dev/null || { echo "python3 belum terpasang"; exit 2; }
[[ -f "$OURS" ]] || { echo "$OURS tidak ditemukan"; exit 2; }

TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT

echo "== Radar klien Meld =========================================="
echo "repo     : $REPO"
echo "file     : $MELD_FILE"

if ! gh api "repos/$REPO/contents/$MELD_FILE" --jq '.content' 2>/dev/null | base64 -d > "$TMP" || [[ ! -s "$TMP" ]]; then
  echo "GAGAL mengambil $MELD_FILE dari $REPO (periksa nama repo/berkas, atau izin gh)."
  exit 2
fi

MELD_SHA="$(gh api "repos/$REPO/commits?path=$MELD_FILE&per_page=1" --jq '.[0].sha' 2>/dev/null)"
MELD_DATE="$(gh api "repos/$REPO/commits?path=$MELD_FILE&per_page=1" --jq '.[0].commit.committer.date' 2>/dev/null)"
echo "HEAD     : ${MELD_SHA:0:12} ($MELD_DATE)"
echo

MELD_FILE_TMP="$TMP" OURS_FILE="$OURS" python3 - <<'PY'
import os, re, sys

meld_path = os.environ["MELD_FILE_TMP"]
ours_path = os.environ["OURS_FILE"]
meld = open(meld_path, encoding="utf-8", errors="replace").read()
ours = open(ours_path, encoding="utf-8", errors="replace").read()

def meld_clients(src):
    """{NAMA_VAL: {field: value}} dari blok `val X = YouTubeClient( ... )`."""
    out = {}
    for m in re.finditer(r"val\s+([A-Z0-9_]+)\s*=\s*YouTubeClient\((.*?)\n        \)", src, re.S):
        name, body = m.group(1), m.group(2)
        fields = {}
        for fm in re.finditer(r"(\w+)\s*=\s*(\"(?:[^\"\\]|\\.)*\"|true|false)", body):
            key = fm.group(1)
            val = fm.group(2)
            if val.startswith('"'):
                # gabung concatenation sederhana `"a" +\n "b"`
                val = "".join(re.findall(r'"((?:[^"\\]|\\.)*)"', val))
            fields[key] = val
        # clientVersion bisa berupa konkatenasi multi-baris
        cv = re.search(r'clientVersion\s*=\s*((?:"(?:[^"\\]|\\.)*"\s*\+?\s*)+)', body)
        if cv:
            fields["clientVersion"] = "".join(re.findall(r'"((?:[^"\\]|\\.)*)"', cv.group(1)))
        ua = re.search(r'userAgent\s*=\s*((?:"(?:[^"\\]|\\.)*"\s*\+?\s*)+)', body)
        if ua:
            fields["userAgent"] = "".join(re.findall(r'"((?:[^"\\]|\\.)*)"', ua.group(1)))
        out[name] = fields
    return out

def ours_constants(src):
    """{NAMA_KONSTANTA: nilai string} — UA di Lyreon disimpan sebagai konstanta."""
    consts = {}
    for m in re.finditer(
        r'(?:const val|val)\s+([A-Z0-9_]+)\s*=\s*((?:"(?:[^"\\]|\\.)*"\s*\+?\s*)+)',
        src,
    ):
        consts[m.group(1)] = "".join(re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(2)))
    return consts

def ours_specs(src):
    """[{field: value}] dari blok `PlayerClientSpec( ... )`."""
    consts = ours_constants(src)
    out = []
    for m in re.finditer(r"PlayerClientSpec\((.*?)\n        \)", src, re.S):
        body = m.group(1)
        fields = {}
        for key in ("key", "clientName", "clientVersion", "clientId", "userAgent",
                    "osName", "osVersion", "deviceMake", "deviceModel"):
            fm = re.search(key + r'\s*=\s*((?:"(?:[^"\\]|\\.)*"\s*\+?\s*)+)', body)
            if fm:
                fields[key] = "".join(re.findall(r'"((?:[^"\\]|\\.)*)"', fm.group(1)))
                continue
            # nilai berupa konstanta (mis. `userAgent = VISIONOS_UA`)
            fm = re.search(key + r'\s*=\s*([A-Z0-9_]+)\s*[,\n)]', body)
            if fm and fm.group(1) in consts:
                fields[key] = consts[fm.group(1)]
        for key in ("useSignatureTimestamp", "useWebPoTokens", "loginRequired",
                    "preferManifest", "probeOnly"):
            fm = re.search(key + r"\s*=\s*(true|false)", body)
            if fm:
                fields[key] = fm.group(1) == "true"
        if fields.get("key"):
            out.append(fields)
    return out

meld_clients_map = meld_clients(meld)
our_specs = ours_specs(ours)

# Klien yang di tangga putar Meld (`STREAM_FALLBACK_CLIENTS`) — yang paling penting
# untuk tetap sinkron, urut.
CHAIN = ["VISIONOS", "ANDROID_VR_1_65_10", "TVHTML5", "ANDROID_VR_1_43_32", "IPADOS", "IOS"]

def find_ours(client_name, client_version):
    for spec in our_specs:
        if spec.get("clientName") == client_name and spec.get("clientVersion") == client_version:
            return spec
    return None

drift = []
print("== Rantai stream Meld vs tangga Lyreon ==")
for name in CHAIN:
    m = meld_clients_map.get(name)
    if not m:
        print(f"  {name:22s} ? tidak ditemukan di YouTubeClient.kt hulu")
        drift.append((name, "hilang di hulu"))
        continue
    cn, cv = m.get("clientName"), m.get("clientVersion")
    match = find_ours(cn, cv)
    if match:
        # bandingkan field yang menentukan bentuk request
        diffs = []
        for field in ("clientId", "userAgent", "osName", "osVersion",
                      "deviceMake", "deviceModel"):
            mv = m.get(field)
            ov = match.get(field)
            if mv is not None and ov != mv:
                diffs.append(field)
        if diffs:
            print(f"  {name:22s} ~ {cn} {cv} — field beda: {', '.join(diffs)}")
            drift.append((name, "field beda: " + ", ".join(diffs)))
        else:
            print(f"  {name:22s} = {cn} {cv} (kunci Lyreon: {match.get('key')})")
    else:
        # versi kita ada tapi berbeda → laporkan keduanya
        same_name = [s for s in our_specs if s.get("clientName") == cn]
        ours_ver = ", ".join(sorted({s.get("clientVersion", "?") for s in same_name})) or "-"
        print(f"  {name:22s} ! hulu {cn} {cv} — Lyreon punya versi: {ours_ver}")
        drift.append((name, f"versi beda (hulu {cv} vs Lyreon {ours_ver})"))

# Versi klien web yang dipakai diagnostik (WEB, WEB_REMIX, WEB_CREATOR, TVHTML5_SIMPLY…)
print()
print("== Versi klien web (diagnostik) ==")
for name in ("WEB", "WEB_REMIX", "WEB_CREATOR", "MOBILE", "ANDROID_VR_NO_AUTH", "ANDROID_VR_1_61_48"):
    m = meld_clients_map.get(name)
    if not m:
        continue
    cn, cv = m.get("clientName"), m.get("clientVersion")
    match = find_ours(cn, cv)
    if match:
        print(f"  {name:22s} = {cn} {cv}")
    else:
        same_name = [s for s in our_specs if s.get("clientName") == cn]
        ours_ver = ", ".join(sorted({s.get("clientVersion", "?") for s in same_name})) or "-"
        note = " (tidak dipakai Lyreon)" if not same_name else ""
        print(f"  {name:22s} ~ hulu {cn} {cv} — Lyreon: {ours_ver}{note}")
        if same_name:
            drift.append((name, f"versi klien diagnostik beda (hulu {cv} vs Lyreon {ours_ver})"))

print()
if drift:
    print(f"DRIFT terdeteksi pada {len(drift)} klien:")
    for name, why in drift:
        print(f"  - {name}: {why}")
    print()
    print("Tindakan: selaraskan PlayerClientLadder.kt dengan YouTubeClient.kt Meld,")
    print("lalu uji 'Tes koneksi' (Settings → Kesehatan stream) di perangkat.")
    sys.exit(1)
print("Sinkron dengan HEAD Meld — tidak ada drift spesifikasi klien.")
PY
RC=$?
echo
echo "== Selesai (exit $RC) =="
exit $RC
