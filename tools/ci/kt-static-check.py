#!/usr/bin/env python3
"""
Pemeriksa statis Kotlin ringan untuk lingkungan tanpa JDK.

Yang diperiksa (bukan pengganti kompilasi, hanya penjaga kesalahan kasar):
  1. Keseimbangan (), {}, [] di luar string & komentar.
  2. Komentar blok BERSARANG ala Kotlin: glob `*/` di dalam KDoc menaikkan
     kedalaman dan bisa menelan seluruh berkas (lihat notes/01 §B6).
  3. `*/` atau `/*` yang muncul di tengah baris komentar (gejala §B6).
  4. Karakter non-ASCII di luar komentar (aturan: tanpa emoji di UI/kode).

Pemakaian: python3 tools/ci/kt-static-check.py [path ...]
"""
import sys
import os

BLOCK_START = "/*"
BLOCK_END = "*/"


def strip_kotlin(source: str):
    """Buang komentar & literal string; kembalikan (kode, daftar masalah)."""
    out = []
    problems = []
    i = 0
    n = len(source)
    depth_block = 0
    line = 1
    while i < n:
        c = source[i]
        nxt = source[i + 1] if i + 1 < n else ""

        if c == "\n":
            line += 1

        # --- di dalam komentar blok (bersarang) ---
        if depth_block > 0:
            if c == "/" and nxt == "*":
                depth_block += 1
                problems.append((line, "nested /* inside block comment"))
                i += 2
                continue
            if c == "*" and nxt == "/":
                depth_block -= 1
                i += 2
                continue
            i += 1
            continue

        # --- komentar baris ---
        if c == "/" and nxt == "/":
            while i < n and source[i] != "\n":
                i += 1
            continue

        # --- mulai komentar blok ---
        if c == "/" and nxt == "*":
            depth_block += 1
            i += 2
            continue

        # --- string mentah (triple quote) ---
        if c == '"' and source[i:i + 3] == '"""':
            i += 3
            while i < n and source[i:i + 3] != '"""':
                if source[i] == "\n":
                    line += 1
                i += 1
            i += 3
            out.append('""')
            continue

        # --- string biasa ---
        if c == '"':
            i += 1
            while i < n and source[i] != '"':
                if source[i] == "\\":
                    i += 2
                    continue
                if source[i] == "\n":
                    problems.append((line, "unterminated string literal"))
                    break
                i += 1
            i += 1
            out.append('""')
            continue

        # --- karakter literal ---
        if c == "'":
            i += 1
            while i < n and source[i] != "'":
                if source[i] == "\\":
                    i += 2
                    continue
                i += 1
            i += 1
            out.append("' '")
            continue

        out.append(c)
        i += 1

    if depth_block != 0:
        problems.append((line, "unbalanced block comment (depth %d)" % depth_block))
    return "".join(out), problems


PAIRS = {")": "(", "}": "{", "]": "["}


def check_file(path: str):
    with open(path, "r", encoding="utf-8") as fh:
        source = fh.read()

    code, problems = strip_kotlin(source)
    stack = []
    for idx, ch in enumerate(code):
        if ch in "({[":
            stack.append((ch, idx))
        elif ch in ")}]":
            if not stack:
                line = code.count("\n", 0, idx) + 1
                problems.append((line, "unmatched %s" % ch))
            else:
                stack.pop()
    for ch, idx in stack:
        line = code.count("\n", 0, idx) + 1
        problems.append((line, "unclosed %s" % ch))

    # Gejala notes/01 §B6: `/*` atau `*/` di tengah baris komentar KDoc.
    for lineno, text in enumerate(source.splitlines(), 1):
        stripped = text.strip()
        if stripped.startswith("*") and (BLOCK_END in stripped[1:] or BLOCK_START in stripped):
            problems.append((lineno, "glob/star inside block comment line"))

    # Non-ASCII di luar komentar (emoji / seni ascii dilarang).
    for lineno, text in enumerate(code.splitlines(), 1):
        for ch in text:
            if ord(ch) > 0x7F:
                problems.append((lineno, "non-ascii %r outside comment" % ch))
                break

    return problems


def main(argv):
    paths = argv[1:]
    if not paths:
        paths = ["app/src/main/java"]
    files = []
    for p in paths:
        if os.path.isdir(p):
            for root, _dirs, names in os.walk(p):
                files += [os.path.join(root, nm) for nm in names if nm.endswith(".kt")]
        elif p.endswith(".kt"):
            files.append(p)
    failed = 0
    for f in sorted(files):
        problems = check_file(f)
        if problems:
            failed += 1
            print("MASALAH %s" % f)
            for line, msg in problems[:12]:
                print("   baris %s: %s" % (line, msg))
    print("diperiksa %d berkas Kotlin, %d berkas bermasalah" % (len(files), failed))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
