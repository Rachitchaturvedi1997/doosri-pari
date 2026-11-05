# =============================================================================
# Generate the platform string resources from one catalogue.
#
#     python -m python.tools.gen_strings            write the files
#     python -m python.tools.gen_strings --check    report, write nothing
#
# Plan section 6.16 requires ten launch languages. Writing ten sets of
# strings.xml and ten sets of Localizable.strings by hand is the kind of thing
# that is done once correctly and then edited in three of the ten for the rest
# of the project's life, so the ten are generated from one file and the file is
# the only thing anybody edits.
#
# The interesting part is not the emitting; it is what happens to a string that
# has not been translated yet. There are two bad answers and one good one:
#
#   * Emit nothing. The app falls back to the key name, and a pensioner sees
#     "sahayak.disclaimer" where a sentence should be.
#   * Emit machine-generated text and call it a translation. This is the worst
#     option available, and the reason is that this app's strings carry safety
#     meaning — a dose that reads MISSED, a disclaimer that says "not advice",
#     a pose that was withheld for a bad knee. A machine that gets one of those
#     subtly wrong produces a sentence a pensioner acts on.
#
# So the good answer is the third one: fall back to the ENGLISH string, and
# leave a visible marker in the generated file naming the language that is
# missing. The reader gets something legible, the gap is impossible to miss in
# a diff, and nobody has to remember which languages are done.
#
# Nothing here invents text. `catalogue.json` documents the same rule where a
# translator will actually read it.
# =============================================================================
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
CATALOGUE = REPO_ROOT / "i18n" / "catalogue.json"
ANDROID_RES = REPO_ROOT / "android" / "app" / "src" / "main" / "res"
IOS_RES = REPO_ROOT / "ios" / "DoosriPari"

UNTRANSLATED = "@UNTRANSLATED"
FALLBACK = "en"

# A resource name is used as an XML attribute and as a lookup key on both
# platforms. Keeping the allowed set narrow means a typo in the catalogue is a
# failure here rather than a key that silently never resolves at runtime.
_KEY_RE = re.compile(r"^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$")


def load() -> tuple[list[dict], dict[str, dict]]:
    data = json.loads(CATALOGUE.read_text(encoding="utf-8"))
    languages = data["languages"]
    strings = data["strings"]
    return languages, strings


def validate(languages: list[dict], strings: dict[str, dict]) -> list[str]:
    problems: list[str] = []
    codes = [lang["code"] for lang in languages]
    if FALLBACK not in codes:
        problems.append(f"catalogue has no {FALLBACK!r} language to fall back to")
    if len(set(codes)) != len(codes):
        problems.append("duplicate language code")

    for key, values in strings.items():
        if key.startswith("_"):
            continue
        if not _KEY_RE.match(key):
            problems.append(f"{key}: not a dotted lower_snake key")
        if not isinstance(values, dict):
            problems.append(f"{key}: value is not an object of language -> text")
            continue
        # Meta keys on a string entry are not languages. They carry the note a
        # translator needs about context, and they are skipped rather than
        # counted as a missing translation.
        for code in codes:
            if code not in values:
                problems.append(f"{key}: missing language {code!r}")
            elif not isinstance(values[code], str):
                problems.append(f"{key}: {code} is not a string")
            elif values[code] == "":
                # An empty string is the one value that is worse than a marker:
                # it renders as nothing at all, which reads as a bug in the app
                # rather than work still to do.
                problems.append(f"{key}: {code} is empty (use {UNTRANSLATED})")
    return problems


def untranslated(languages: list[dict], strings: dict[str, dict]) -> dict[str, list[str]]:
    """language code -> keys still waiting on a human translator."""
    out: dict[str, list[str]] = {lang["code"]: [] for lang in languages}
    for key, values in strings.items():
        if key.startswith("_"):
            continue
        for lang in languages:
            code = lang["code"]
            if values.get(code) in (None, UNTRANSLATED):
                out[code].append(key)
    return out


# --------------------------------------------------------------- shared --
# A placeholder is %s or an already-indexed %1$s. It is matched before anything
# else is escaped and put back afterwards, because escaping and re-finding
# placeholders in one string is how "Checked in at %s" comes out as "%%1$s" and
# a pensioner reads a percent sign where a time should be.
_PLACEHOLDER = re.compile(r"%(\d+)?s")


def _split_placeholders(text: str, literal, placeholder) -> str:
    """
    Escape the prose, keep the placeholders.

    Escaping runs only over the literal segments, so a % inside prose is
    escaped and a % that is a placeholder is not. A chain of replaces cannot
    tell the two apart, which is the whole reason this walks the string.
    """
    parts: list[str] = []
    last = 0
    ordinal = 0
    for match in _PLACEHOLDER.finditer(text):
        parts.append(literal(text[last : match.start()]))
        ordinal += 1
        # A placeholder written as %s is numbered in the order it appears; one
        # already written %2$s keeps the number the author chose, because an
        # author who reordered them did it on purpose.
        index = match.group(1) or str(ordinal)
        parts.append(placeholder(index, ordinal))
        last = match.end()
    parts.append(literal(text[last:]))
    return "".join(parts)


def _xml_literal(text: str) -> str:
    """What both platforms agree on before their own quoting rules."""
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


# --------------------------------------------------------------- Android --
def android_escape(text: str) -> str:
    """
    Android string resources have three separate escaping rules and they are
    not the XML ones alone.

    `&`, `<` and `>` are XML. The apostrophe and the double quote are Android's
    own: the resource parser treats ' as the start of a quoted run, so "Don't"
    unescaped makes the resource unparseable at BUILD time, which is at least
    loud. The percent is the quiet one — see below.

    A literal % is doubled to %% ONLY in a string that also has a placeholder,
    because that is the only string a formatter will touch. Doubling it in a
    string with no arguments would render "100%%" on the screen, and a stray
    percent sign on a pensioner's pension slip is exactly the sort of detail
    that makes an app feel untrustworthy.
    """
    has_placeholder = _PLACEHOLDER.search(text) is not None

    def literal(segment: str) -> str:
        segment = _xml_literal(segment).replace("'", "\\'").replace('"', '\\"')
        return segment.replace("%", "%%") if has_placeholder else segment

    def placeholder(index: str, ordinal: int) -> str:
        # Android's Formatter throws on a bare %s once there is more than one
        # argument, so every placeholder is indexed, from 1.
        return f"%{index}$s"

    return _split_placeholders(text, literal, placeholder)


def emit_android(strings: dict[str, dict], lang: dict, report_only: bool) -> Path:
    code = lang["code"]
    values_dir = ANDROID_RES / lang["android"]
    path = values_dir / "strings.xml"

    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        "<!--",
        "  GENERATED — do not edit. Regenerate with:",
        "      python -m python.tools.gen_strings",
        "  The source is i18n/catalogue.json. Editing this file by hand is how",
        "  ten translations stop being ten translations.",
    ]
    if code != FALLBACK:
        lines.append(f"  Language: {lang['name']} ({code})")
    lines += ["-->", "<resources>"]

    for key, values in strings.items():
        if key.startswith("_"):
            continue
        raw = values.get(code)
        note = values.get("_note")
        if raw in (None, UNTRANSLATED):
            raw = values[FALLBACK]
            # The marker is a comment, not a prefix in the text: a prefix would
            # reach the screen and a pensioner would read it.
            lines.append(f"  <!-- TODO {code}: not yet translated — showing English -->")
            if note:
                lines.append(f"  <!-- {note} -->")
        elif note and code == FALLBACK:
            for paragraph in note.split("\n"):
                lines.append(f"  <!-- {paragraph} -->")

        text = android_escape(raw)
        lines.append(f'  <string name="{key}">{text}</string>')

    lines += ["</resources>", ""]
    body = "\n".join(lines)
    if not report_only:
        values_dir.mkdir(parents=True, exist_ok=True)
        path.write_text(body, encoding="utf-8")
    return path


# ------------------------------------------------------------------ iOS --
def ios_escape(text: str) -> str:
    """
    Localizable.strings is a plist, so backslash and double quote are the
    escapes and nothing else is.

    iOS formats strings with %@ rather than %s, and the same splitter is used
    as for Android so the catalogue can be written once in one notation. The
    order backslash-then-quote matters: escaping the quote first would escape
    the backslash that the quote's own escape just introduced.
    """

    def literal(segment: str) -> str:
        segment = segment.replace("\\", "\\\\").replace('"', '\\"')
        return segment.replace("\n", "\\n")

    def placeholder(index: str, ordinal: int) -> str:
        return f"%{index}$@"

    return _split_placeholders(text, literal, placeholder)


def emit_ios(strings: dict[str, dict], lang: dict, report_only: bool) -> Path:
    code = lang["code"]
    lproj = IOS_RES / lang["ios"]
    path = lproj / "Localizable.strings"

    lines = [
        "/*",
        "   GENERATED — do not edit. Regenerate with:",
        "       python -m python.tools.gen_strings",
        "   The source is i18n/catalogue.json. Editing this file by hand is how",
        "   ten translations stop being ten translations.",
    ]
    if code != FALLBACK:
        lines.append(f"   Language: {lang['name']} ({code})")
    lines += ["*/", ""]

    for key, values in strings.items():
        if key.startswith("_"):
            continue
        raw = values.get(code)
        if raw in (None, UNTRANSLATED):
            raw = values[FALLBACK]
            lines.append(f"/* TODO {code}: not yet translated — showing English */")
        lines.append(f'"{ios_escape(key)}" = "{ios_escape(raw)}";')

    lines.append("")
    body = "\n".join(lines)
    if not report_only:
        lproj.mkdir(parents=True, exist_ok=True)
        path.write_text(body, encoding="utf-8")
    return path


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="report only, write nothing")
    args = parser.parse_args(argv)

    languages, strings = load()
    problems = validate(languages, strings)
    if problems:
        print("catalogue is not valid:", file=sys.stderr)
        for problem in problems:
            print(f"  {problem}", file=sys.stderr)
        return 1

    written = []
    for lang in languages:
        written.append(emit_android(strings, lang, args.check))
        written.append(emit_ios(strings, lang, args.check))

    waiting = untranslated(languages, strings)
    total = len([k for k in strings if not k.startswith("_")])
    print(f"{total} strings x {len(languages)} languages")
    for lang in languages:
        code = lang["code"]
        left = len(waiting[code])
        status = "complete" if left == 0 else f"{left} awaiting a native speaker"
        print(f"  {code:3} {lang['name']:<12} {status}")

    if args.check:
        print("\ncheck only — nothing written")
    else:
        print(f"\nwrote {len(written)} files")

    # An incomplete translation is expected work, not a failure. A key missing
    # entirely is a failure, and validate() already returned 1 for that. So this
    # exits 0 with the gap printed: the gap must be visible without being
    # something a person learns to ignore.
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
