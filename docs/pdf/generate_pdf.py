#!/usr/bin/env python3
"""Render one of the Relix manuals as a PDF.

Both manuals share this layout: a title page, a two-level table of contents with
dotted leaders and page numbers, blue chapter and entry headings, bold
sub-section labels, and light-grey monospace code boxes.

Which manual is a ``MANUALS`` profile, chosen by the third argument:

    generate_pdf.py docs/reference build/docs/reference.pdf reference
    generate_pdf.py docs/guide     build/docs/guide.pdf     guide

The structure of either is driven entirely by its ``README.md`` — the ``##``
headings define the chapters (and their intro paragraphs), and the order of the
``[text](path.md)`` links within each chapter defines which pages are included
and in what order. This is how we "control which files are loaded": only pages
registered in the README appear, in the README's order.

What differs is the shape of a *page*, and it is one flag on the profile. A
reference page is a run of labelled sections::

    # Name: <title shown in the TOC and as the entry heading>
    # Syntax:
    ...verbatim code...
    # Description:
    ...prose...
    # See Also:
    [other](other.md), ...

A guide page (``narrative``) is ordinary markdown: an ``#`` title, then prose,
``##`` sections, fenced code and tables. That needs no second renderer — the
whole page becomes one unlabelled section, and the body renderer already handles
headings, fences, tables, quotes and lists.

Run inside the Docker image built from docs/pdf/Dockerfile.
"""

from __future__ import annotations

import datetime as _dt
import html
import os
import re
import sys

import hashlib
import subprocess
import tempfile

from reportlab.lib import colors
from reportlab.lib.colors import HexColor
from reportlab.lib.enums import TA_CENTER
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import cm, mm
from reportlab.lib.utils import ImageReader
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import (
    BaseDocTemplate,
    Frame,
    Image,
    KeepTogether,
    NextPageTemplate,
    PageBreak,
    Paragraph,
    Spacer,
    Table,
    TableStyle,
    XPreformatted,
)
from reportlab.platypus.tableofcontents import TableOfContents

# --------------------------------------------------------------------------- #
# Palette / fonts                                                             #
# --------------------------------------------------------------------------- #
# Brand palette sampled from logo-assets/logo.png: the signature violet glow
# (#6020C0) and the magenta it shades into (#A0289C).
ACCENT = HexColor("#6020C0")      # chapter + entry headings, TOC, title
LINK_HEX = "#A0289C"              # inline links (logo magenta)
LINK = HexColor(LINK_HEX)
INK = HexColor("#222222")         # body text
MUTED = HexColor("#666666")       # chapter intro, footer, captions
CODE_BG = HexColor("#F4F4F4")     # code-box background
CODE_BORDER = HexColor("#E0E0E0")
RULE = HexColor("#BFBFBF")        # footer rule
TBL_HEADER_BG = ACCENT                # table header band (logo violet)
TBL_HEADER_FG = colors.white
TBL_GRID = HexColor("#CCCCCC")
TBL_ZEBRA = HexColor("#F6F2FB")       # alternating body row (faint violet)

# --------------------------------------------------------------------------- #
# Manual profiles                                                             #
# --------------------------------------------------------------------------- #
# Everything that differs between the two manuals, in one table. The layout,
# the fonts, the TOC and the body renderer are shared: a second manual should
# cost a profile, not a fork.
MANUALS = {
    "reference": {
        "title": "Relix Language Reference",
        "subtitle": "Language Reference Manual",
        "tagline": (
            "The complete reference for the Relix relational-algebra language — "
            "one entry per keyword, operator and built-in function."
        ),
        # A page is a run of `# Label:` sections.
        "narrative": False,
    },
    "guide": {
        "title": "Relix Programming Guide",
        "subtitle": "Programming Guide",
        "tagline": (
            "Using Relix from Java — building queries, inspecting what the engine "
            "makes of them, and running them."
        ),
        # A page is ordinary markdown prose.
        "narrative": True,
    },
}


FONT_DIR = "/usr/share/fonts/truetype/dejavu"


# Some maths operators are outside DejaVu's repertoire. Symbola covers them, so we
# register it as a fallback face and wrap just the uncovered codepoints in it (see
# fontify()). Which ones those are is *measured* per face rather than hardcoded:
# the hardcoded list held only ⨝ ⟕ ⟖ ⟗, and missed that DejaVu Sans **Mono** also
# lacks ⋈ and ⋉ — so the natural-join and semi-join glyphs rendered as empty boxes
# in every code box and inline code span, which is most places a reader meets them.
FALLBACK_FACE = "Sym"
FALLBACK_CHECK_FACES = ("Body", "Body-Bold", "Mono")
_fallback_ok = False
_coverage_cache: dict[str, bool] = {}


def register_fonts() -> None:
    """Register DejaVu (Greek/maths: σ ⋈ ∀ λ …) + a Symbola fallback face."""
    global _fallback_ok
    faces = {
        "Body": "DejaVuSans.ttf",
        "Body-Bold": "DejaVuSans-Bold.ttf",
        "Body-Oblique": "DejaVuSans-Oblique.ttf",
        "Body-BoldOblique": "DejaVuSans-BoldOblique.ttf",
        "Mono": "DejaVuSansMono.ttf",
        "Mono-Bold": "DejaVuSansMono-Bold.ttf",
    }
    for name, fname in faces.items():
        pdfmetrics.registerFont(TTFont(name, os.path.join(FONT_DIR, fname)))
    pdfmetrics.registerFontFamily(
        "Body",
        normal="Body",
        bold="Body-Bold",
        italic="Body-Oblique",
        boldItalic="Body-BoldOblique",
    )

    import glob

    sym = next(iter(glob.glob("/usr/share/fonts/**/Symbola*.ttf", recursive=True)), None)
    if sym:
        pdfmetrics.registerFont(TTFont(FALLBACK_FACE, sym))
        _fallback_ok = True
    else:
        sys.stderr.write("  ! Symbola not found; ⨝ ⟕ ⟖ ⟗ may render as boxes\n")


# --------------------------------------------------------------------------- #
# Styles                                                                      #
# --------------------------------------------------------------------------- #
def build_styles() -> dict:
    ss = getSampleStyleSheet()
    s = {}
    s["title"] = ParagraphStyle(
        "title", parent=ss["Title"], fontName="Body-Bold", fontSize=36,
        leading=44, textColor=ACCENT, alignment=TA_CENTER, spaceAfter=10,
    )
    s["subtitle"] = ParagraphStyle(
        "subtitle", fontName="Body", fontSize=15, leading=20, textColor=INK,
        alignment=TA_CENTER, spaceAfter=18,
    )
    s["tagline"] = ParagraphStyle(
        "tagline", fontName="Body", fontSize=11.5, textColor=MUTED,
        alignment=TA_CENTER, leading=17,
    )
    s["chapter"] = ParagraphStyle(
        "chapter", fontName="Body-Bold", fontSize=22, leading=27, textColor=ACCENT,
        spaceBefore=6, spaceAfter=6, keepWithNext=True,
    )
    s["chapter_intro"] = ParagraphStyle(
        "chapter_intro", fontName="Body-Oblique", fontSize=10.5,
        textColor=MUTED, leading=15, spaceAfter=10,
    )
    s["entry"] = ParagraphStyle(
        "entry", fontName="Body-Bold", fontSize=15, leading=19, textColor=ACCENT,
        spaceBefore=16, spaceAfter=6, keepWithNext=True,
    )
    s["label"] = ParagraphStyle(
        # explicit leading + a real gap below: without them a following code box
        # (XPreformatted with borderPadding) draws its grey background up into
        # the label and clips it (notably "Syntax", which is always code).
        "label", fontName="Body-Bold", fontSize=10.5, leading=14, textColor=INK,
        spaceBefore=10, spaceAfter=7, keepWithNext=True,
    )
    s["body"] = ParagraphStyle(
        "body", fontName="Body", fontSize=10, textColor=INK,
        leading=14.5, spaceAfter=6,
    )
    # Sub-headings inside a section. Pages use "## …" / "### …" to structure long
    # entries (worked examples, "SQL pushdown", …); without these the markup used
    # to print verbatim as body text.
    s["sub"] = ParagraphStyle(
        "sub", fontName="Body-Bold", fontSize=11.5, leading=15, textColor=INK,
        spaceBefore=10, spaceAfter=4, keepWithNext=True,
    )
    s["subsub"] = ParagraphStyle(
        "subsub", fontName="Body-Bold", fontSize=10, leading=14, textColor=MUTED,
        spaceBefore=8, spaceAfter=3, keepWithNext=True,
    )
    s["bullet"] = ParagraphStyle(
        "bullet", parent=s["body"], leftIndent=16, bulletIndent=4, spaceAfter=3,
    )
    s["quote"] = ParagraphStyle(
        "quote", fontName="Body-Oblique", fontSize=9.5, leading=14,
        textColor=MUTED, leftIndent=12, spaceBefore=4, spaceAfter=6,
    )
    s["caption"] = ParagraphStyle(
        "caption", fontName="Body-Oblique", fontSize=9.5, textColor=MUTED,
        spaceBefore=4, spaceAfter=2,
    )
    s["th"] = ParagraphStyle(
        "th", fontName="Body-Bold", fontSize=9, leading=12,
        textColor=TBL_HEADER_FG,
    )
    s["td"] = ParagraphStyle(
        "td", fontName="Body", fontSize=9, leading=12, textColor=INK,
    )
    s["code"] = ParagraphStyle(
        "code", fontName="Mono", fontSize=8.5, textColor=INK, leading=11.5,
        backColor=CODE_BG, borderColor=CODE_BORDER, borderWidth=0.5,
        borderPadding=(7, 7, 7, 7), spaceBefore=4, spaceAfter=8, leftIndent=0,
    )
    # TOC styles
    s["toc_title"] = ParagraphStyle(
        "toc_title", fontName="Body-Bold", fontSize=24, leading=30, textColor=ACCENT,
        spaceAfter=16,
    )
    s["toc0"] = ParagraphStyle(
        "toc0", fontName="Body-Bold", fontSize=12, textColor=ACCENT,
        leading=18, spaceBefore=10,
    )
    s["toc1"] = ParagraphStyle(
        "toc1", fontName="Body", fontSize=10, textColor=INK,
        leading=15.5, leftIndent=14,
    )
    return s


# --------------------------------------------------------------------------- #
# README parsing: chapters + ordered page list                                #
# --------------------------------------------------------------------------- #
LINK_RE = re.compile(r"\[(?P<text>[^\]]+)\]\((?P<href>[^)]+)\)")


def parse_readme(ref_dir: str):
    """Return (preface_lines, [(title, intro_text, table_lines, [page_relpath, …]), …]).

    Three things come out of the index, not one:

    * the **preface** — everything before the first ``## `` heading. It explains how
      to read the manual and that every operator has a Unicode and an ASCII form;
      dropping it left the PDF opening cold on chapter 1.
    * each chapter's **intro** prose, and its **summary table**. The tables are the
      best quick-reference in the corpus (glyph / ASCII / one-line gloss per
      operator) and used to be discarded outright, because table rows start with
      ``|`` and the intro collector skipped them.
    * the ordered **page list**, which decides what the PDF contains.
    """
    path = os.path.join(ref_dir, "README.md")
    with open(path, encoding="utf-8") as fh:
        lines = fh.read().splitlines()

    preface: list[str] = []
    chapters = []
    # Deliberately global, not per-chapter: the index cross-lists a page in more
    # than one section (COLLECT sits under both "Grouping & aggregation" and
    # "Nested data"), which used to render the whole entry twice. The cross-link
    # in the second chapter's table still resolves — to the one printed entry.
    seen: set[str] = set()
    cur = None  # (title, intro_lines, table_lines, pages)
    for line in lines:
        m = re.match(r"^##\s+(.*\S)\s*$", line)
        if m:
            if cur:
                chapters.append(cur)
            cur = [m.group(1), [], [], []]
            continue
        if cur is None:
            # Preface: prose only — skip the H1, the horizontal rule and blockquote
            # callouts, which carry no information the chapter pages don't repeat.
            if line.strip() and not line.startswith("#") and not line.startswith("---"):
                preface.append(line.rstrip())
            continue
        title, intro_lines, table_lines, pages = cur
        # collect .md links in document order (skip non-page links)
        for lm in LINK_RE.finditer(line):
            href = lm.group("href").split("#")[0].strip()
            if not href.endswith(".md"):
                continue
            if href in seen:
                continue
            full = os.path.normpath(os.path.join(ref_dir, href))
            if os.path.isfile(full):
                seen.add(href)
                pages.append(href)
        if line.lstrip().startswith("|"):
            table_lines.append(line.rstrip())
            continue
        # intro = prose paragraph before any link/table line
        if not pages and line.strip() and not line.lstrip().startswith(("[", ">", "-")) \
                and "](" not in line and not line.startswith("**"):
            intro_lines.append(line.strip())
    if cur:
        chapters.append(cur)
    return preface, [(t, " ".join(i), tbl, p) for (t, i, tbl, p) in chapters]


# --------------------------------------------------------------------------- #
# Page parsing: # Label: sections                                             #
# --------------------------------------------------------------------------- #
SECTION_RE = re.compile(r"^#\s+(?P<label>[A-Za-z][^:]*):\s*(?P<rest>.*)$")


def parse_page(path: str):
    """Return (name, [(label, [content_line, ...]), ...])."""
    with open(path, encoding="utf-8") as fh:
        lines = fh.read().splitlines()

    name = os.path.splitext(os.path.basename(path))[0]
    sections = []
    cur_label = None
    cur_body: list[str] = []
    for line in lines:
        m = SECTION_RE.match(line)
        if m:
            if cur_label is not None:
                sections.append((cur_label, cur_body))
            cur_label = m.group("label").strip()
            cur_body = []
            rest = m.group("rest").strip()
            if rest:
                cur_body.append(rest)
        else:
            if cur_label is not None:
                cur_body.append(line)
    if cur_label is not None:
        sections.append((cur_label, cur_body))

    out = []
    for label, body in sections:
        if label == "Name":
            name = " ".join(b.strip() for b in body if b.strip()).strip() or name
        else:
            out.append((label, body))
    return name, out


def parse_narrative_page(path: str):
    """Return (name, [(None, lines)]) for a prose page.

    A guide page has no labelled sections — it is an ``#`` title followed by
    ordinary markdown — so the whole body is one section with no label, and the
    body renderer takes it from there. The title is the ``#`` heading if there is
    one, else the file name, which is the same fallback ``parse_page`` uses.
    """
    with open(path, encoding="utf-8") as fh:
        lines = fh.read().splitlines()

    name = os.path.splitext(os.path.basename(path))[0]
    body = lines
    for i, line in enumerate(lines):
        if line.startswith("# "):
            name = line[2:].strip() or name
            body = lines[i + 1:]
            break
    return name, [(None, body)]


# --------------------------------------------------------------------------- #
# Inline markdown -> ReportLab mini-HTML                                       #
# --------------------------------------------------------------------------- #
_CODE_TOKEN = "\x00CODE%d\x00"


def needs_fallback(ch: str) -> bool:
    """True when any face we render with has no glyph for ``ch``.

    A character missing from *one* face (typically Mono) has to go to Symbola
    everywhere, since escaping happens before we know which face will draw it —
    better one consistent substitute than a box in half the contexts.
    """
    cached = _coverage_cache.get(ch)
    if cached is not None:
        return cached
    missing = False
    for face in FALLBACK_CHECK_FACES:
        try:
            charmap = getattr(pdfmetrics.getFont(face).face, "charToGlyph", None)
        except Exception:                       # noqa: BLE001 - face not registered
            continue
        if charmap and ord(ch) not in charmap:
            missing = True
            break
    _coverage_cache[ch] = missing
    return missing


def fontify(escaped: str) -> str:
    """Wrap fallback-only glyphs in the Symbola face.

    Operates on already-escaped text: the glyphs aren't XML-significant, so
    wrapping them after escaping is safe, and the emitted <font> tags survive
    the paragraph parser. No-op when Symbola is unavailable.
    """
    if not _fallback_ok:
        return escaped
    out = []
    for ch in escaped:
        if ord(ch) > 0x7F and needs_fallback(ch):
            out.append(f'<font name="{FALLBACK_FACE}">{ch}</font>')
        else:
            out.append(ch)
    return "".join(out)


def esc(s: str) -> str:
    """Escape XML-significant chars (keep quotes literal) + fallback-font glyphs.

    ReportLab's parser decodes ``&amp; &lt; &gt;`` but not ``&quot;``/``&#x27;``,
    so escaping quotes would leak entities into the PDF. The single chokepoint
    here means every Paragraph/Preformatted path picks up the Symbola fallback.
    """
    return fontify(html.escape(s, quote=False))


def inline(text: str, link_resolver=None) -> str:
    # protect inline code spans
    spans: list[str] = []

    def _grab(m):
        # A span opened by n backticks closes on a run of exactly n, which is what lets
        # a name containing a backtick be written ``  `order`  ``. Matching a single one
        # instead split that into three spans, two of them empty.
        span = m.group(2)
        if len(span) > 1 and span.startswith(" ") and span.endswith(" "):
            span = span[1:-1]   # the one pad that lets a span begin with a backtick
        spans.append(span)
        return _CODE_TOKEN % (len(spans) - 1)

    text = re.sub(r"(`+)(.+?)(?<!`)\1(?!`)", _grab, text)
    text = esc(text)

    # links [text](href)
    def _link(m):
        label = m.group("text")
        href = m.group("href")
        target = link_resolver(href) if link_resolver else None
        if target:
            return f'<a href="{target}" color="{LINK_HEX}">{label}</a>'
        return f'<font color="{LINK_HEX}">{label}</font>'

    text = LINK_RE.sub(_link, text)
    # bold then italic
    text = re.sub(r"\*\*(.+?)\*\*", r"<b>\1</b>", text)
    text = re.sub(r"(?<![\w*])\*([^*\n]+?)\*(?![\w*])", r"<i>\1</i>", text)
    text = re.sub(r"(?<![\w_])_([^_\n]+?)_(?![\w_])", r"<i>\1</i>", text)

    # restore code spans as monospace
    def _restore(m):
        idx = int(m.group(1))
        return (
            '<font face="Mono" size="9" backColor="#EFEFEF">'
            + esc(spans[idx])
            + "</font>"
        )

    return re.sub(r"\x00CODE(\d+)\x00", _restore, text)


def heading_paragraph(text, style_name, styles, link_resolver=None):
    """A page title or a section label, rendered as markdown like any other line.

    One function for both so the rule cannot hold in one place and not the other,
    which is how it was lost: every body path went through ``inline`` and these two
    went through ``esc``, so the eight pages that name a relation or a column in a
    heading printed the backticks.
    """
    return Paragraph(inline(text, link_resolver), styles[style_name])


# --------------------------------------------------------------------------- #
# Section content -> flowables                                                #
# --------------------------------------------------------------------------- #
CODE_PADDING = 14.0      # borderPadding, left + right
CODE_MIN_SIZE = 5.5      # below this, shrinking hurts more than wrapping


def fit_code_style(text: str, styles, avail_width: float):
    """Return (style, hard_wrap_columns) for a code box holding ``text``.

    ``XPreformatted`` never wraps, so a line wider than the frame used to run off
    the page — 67 lines did, the worst 329 characters long. Shrink the font until
    the widest line fits (this preserves the column alignment of ASCII result
    tables, which re-wrapping would destroy), and only when that would push the
    type below :data:`CODE_MIN_SIZE` fall back to hard-wrapping at the widest
    column that still fits.
    """
    lines = text.splitlines() or [""]
    longest = max(len(line) for line in lines)
    base = styles["code"]
    if longest == 0:
        return base, None

    usable = avail_width - CODE_PADDING
    per_char = pdfmetrics.stringWidth("M" * longest, "Mono", base.fontSize) / longest
    if longest * per_char <= usable:
        return base, None

    size = usable / (longest * per_char / base.fontSize)
    if size >= CODE_MIN_SIZE:
        return _resized(base, size), None

    unit = per_char / base.fontSize * CODE_MIN_SIZE
    return _resized(base, CODE_MIN_SIZE), max(20, int(usable / unit))


def _resized(base, size: float):
    return ParagraphStyle(
        f"code{size:.2f}", parent=base, fontSize=size, leading=size * 1.35
    )


def hard_wrap(text: str, columns: int) -> str:
    """Fold over-long lines, marking continuations so they read as one logical line."""
    out = []
    for line in text.splitlines():
        while len(line) > columns:
            out.append(line[:columns])
            line = "↳ " + line[columns:]
        out.append(line)
    return "\n".join(out)


def code_flowable(text: str, styles, caption: str | None = None,
                  avail_width: float | None = None):
    # XPreformatted (a Paragraph subclass) — unlike Preformatted it honours the
    # style's backColor/border (the grey box), decodes &lt;/&gt;/&amp; back to
    # </>/&, and parses the <font> tags esc() injects for the Symbola-fallback
    # operators (⨝ ⟕ ⟖ ⟗), all while preserving whitespace for ASCII alignment.
    flows = []
    if caption:
        flows.append(Paragraph(f"<i>{esc(caption)}</i>", styles["caption"]))
    body = text.rstrip("\n")
    style = styles["code"]
    if avail_width:
        style, columns = fit_code_style(body, styles, avail_width)
        if columns:
            body = hard_wrap(body, columns)
    flows.append(XPreformatted(esc(body), style))
    return flows


# --- GFM tables ------------------------------------------------------------ #
_SEP_RE = re.compile(r"^\s*\|?(?:\s*:?-{2,}:?\s*\|)+\s*:?-*:?\s*\|?\s*$")


def _split_row(line: str):
    """Split a GFM row on *unescaped* pipes, unescaping ``\\|`` into a literal pipe.

    Splitting naively cost the two best tables in the manual: the join and advanced
    chapters spell the ASCII outer joins ``\\|><\\|`` and ``MINIMIZE\\|MAXIMIZE``,
    so their rows came out with more cells than the header and
    :func:`parse_clean_table` rejected the whole table.
    """
    r = line.strip()
    if not r.startswith("|") or not r.endswith("|"):
        return None
    cells, cur, k = [], [], 0
    while k < len(r):
        if r[k] == "\\" and k + 1 < len(r) and r[k + 1] == "|":
            cur.append("|")
            k += 2
            continue
        if r[k] == "|":
            cells.append("".join(cur).strip())
            cur = []
            k += 1
            continue
        cur.append(r[k])
        k += 1
    cells.append("".join(cur).strip())
    return cells[1:-1]      # the empty cells either side of the outer pipes


def parse_clean_table(buf: list[str]):
    """Return [header, *body] cell-lists for a *clean* GFM table, else None.

    Clean = a separator row whose neighbouring rows all open and close with a
    pipe and share the header's column count. Annotated/ragged tables (trailing
    comments after the last pipe, mismatched widths) return None so the caller
    keeps them as a verbatim code box.
    """
    lines = [b for b in buf if b.strip()]
    sep = next((k for k, b in enumerate(lines) if _SEP_RE.match(b)), None)
    if sep is None or sep == 0:
        return None
    header = _split_row(lines[sep - 1])
    if not header:
        return None
    ncol = len(header)
    rows = [header]
    for k, b in enumerate(lines):
        if k == sep or k == sep - 1:
            continue
        cells = _split_row(b)
        if cells is None or len(cells) != ncol:
            return None
        rows.append(cells)
    return rows if len(rows) >= 1 else None


def table_flowable(rows, styles, avail_width, link_resolver):
    ncol = len(rows[0])
    # proportional column widths from the longest cell per column (wrapped)
    weights = [1] * ncol
    for r in rows:
        for c in range(ncol):
            weights[c] = max(weights[c], len(r[c]) or 1)
    total = sum(weights)
    col_widths = [max(1.6 * cm, avail_width * w / total) for w in weights]
    scale = avail_width / sum(col_widths)
    col_widths = [w * scale for w in col_widths]

    data = []
    for ri, r in enumerate(rows):
        style = styles["th"] if ri == 0 else styles["td"]
        data.append([Paragraph(inline(c, link_resolver), style) for c in r])

    t = Table(data, colWidths=col_widths, repeatRows=1, hAlign="LEFT")
    ts = [
        ("BACKGROUND", (0, 0), (-1, 0), TBL_HEADER_BG),
        ("GRID", (0, 0), (-1, -1), 0.5, TBL_GRID),
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("LEFTPADDING", (0, 0), (-1, -1), 6),
        ("RIGHTPADDING", (0, 0), (-1, -1), 6),
        ("TOPPADDING", (0, 0), (-1, -1), 4),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 4),
    ]
    if len(rows) > 2:
        ts.append(("ROWBACKGROUNDS", (0, 1), (-1, -1), [colors.white, TBL_ZEBRA]))
    t.setStyle(TableStyle(ts))
    return [Spacer(1, 2), t, Spacer(1, 8)]


# --- mermaid diagrams ------------------------------------------------------ #
class DiagramRenderer:
    """Render mermaid blocks to PNG via mermaid-cli (mmdc), cached by content.

    The PNGs live for the whole run so ReportLab's two-pass build (TOC) can
    re-read them. If mmdc is unavailable or fails, render() returns None and the
    caller falls back to showing the mermaid source as a code box.
    """

    def __init__(self):
        self.dir = tempfile.mkdtemp(prefix="relix-mmd-")
        self.cache: dict[str, str | None] = {}

    def render(self, src: str):
        key = hashlib.md5(src.encode("utf-8")).hexdigest()
        if key in self.cache:
            return self.cache[key]
        mmd = os.path.join(self.dir, key + ".mmd")
        png = os.path.join(self.dir, key + ".png")
        with open(mmd, "w", encoding="utf-8") as fh:
            fh.write(src)
        cmd = ["mmdc", "-i", mmd, "-o", png, "-b", "white", "-s", "2"]
        if os.path.exists("/opt/puppeteer-config.json"):
            cmd += ["-p", "/opt/puppeteer-config.json"]
        try:
            subprocess.run(cmd, check=True, capture_output=True, timeout=180)
            result = png if os.path.exists(png) else None
        except Exception as exc:  # noqa: BLE001 - degrade to source on any error
            sys.stderr.write(f"  ! mermaid render failed: {exc}\n")
            result = None
        self.cache[key] = result
        return result


def diagram_flowable(png: str, avail_width: float):
    iw, ih = ImageReader(png).getSize()
    width = min(avail_width, iw / 2.0)        # mmdc -s2 -> halve to native pt
    width = max(width, min(avail_width, 6 * cm))
    height = width * ih / iw
    max_h = 17 * cm
    if height > max_h:
        height = max_h
        width = height * iw / ih
    img = Image(png, width=width, height=height)
    img.hAlign = "CENTER"
    return [Spacer(1, 4), KeepTogether([img]), Spacer(1, 8)]


HEADING_RE = re.compile(r"^(#{2,6})\s+(.*\S)\s*$")
LIST_RE = re.compile(r"^([-*]|\d+[.)])\s+(.*)$")


def render_section_body(label, body, styles, link_resolver, avail_width, diagrams):
    """Turn one section's raw lines into flowables."""
    flows = []
    # The Syntax section is always a single verbatim code box.
    if label == "Syntax":
        text = "\n".join(body).strip("\n")
        if text.strip():
            flows += code_flowable(text, styles, avail_width=avail_width)
        return flows

    i = 0
    n = len(body)
    para: list[str] = []

    def flush_para():
        nonlocal para
        joined = " ".join(p.strip() for p in para).strip()
        if joined:
            flows.append(Paragraph(inline(joined, link_resolver), styles["body"]))
        para = []

    while i < n:
        line = body[i]
        stripped = line.strip()

        # fenced block
        fence = re.match(r"^\s*```(\w*)\s*$", line)
        if fence:
            flush_para()
            lang = fence.group(1)
            i += 1
            buf = []
            while i < n and not re.match(r"^\s*```\s*$", body[i]):
                buf.append(body[i])
                i += 1
            i += 1  # skip closing fence

            if lang == "mermaid":
                png = diagrams.render("\n".join(buf)) if diagrams else None
                if png:
                    flows += diagram_flowable(png, avail_width)
                else:
                    flows += code_flowable(
                        "\n".join(buf), styles, "Diagram (Mermaid source)",
                        avail_width=avail_width,
                    )
                continue

            # a bare fenced *clean* GFM table becomes a real table; relix code
            # and ASCII/annotated blocks stay verbatim.
            if lang == "":
                rows = parse_clean_table(buf)
                if rows:
                    flows += table_flowable(rows, styles, avail_width, link_resolver)
                    continue
            flows += code_flowable("\n".join(buf), styles, avail_width=avail_width)
            continue

        # blank line -> paragraph break
        if not stripped:
            flush_para()
            i += 1
            continue

        # "## …" sub-heading. Long entries structure themselves this way ("SQL
        # pushdown", "Example 2 — org chart"); without this the hashes printed
        # verbatim in the body copy.
        hm = HEADING_RE.match(stripped)
        if hm:
            flush_para()
            style = styles["sub"] if len(hm.group(1)) == 2 else styles["subsub"]
            flows.append(Paragraph(inline(hm.group(2), link_resolver), style))
            i += 1
            continue

        # blockquote run
        if stripped.startswith(">"):
            flush_para()
            quoted = []
            while i < n and body[i].strip().startswith(">"):
                quoted.append(body[i].strip().lstrip(">").strip())
                i += 1
            flows.append(
                Paragraph(inline(" ".join(quoted), link_resolver), styles["quote"])
            )
            continue

        # bullet / numbered list. This must precede the indented-code branch: a
        # wrapped list item continues on a two-space-indented line, which that
        # branch used to mistake for code and box in grey mid-sentence (87 lines
        # across 16 pages did exactly that).
        lm = LIST_RE.match(stripped)
        if lm and not line.startswith("    "):
            flush_para()
            ordered = lm.group(1)[0].isdigit()
            items: list[str] = []
            while i < n:
                cur = body[i]
                cur_stripped = cur.strip()
                cm = LIST_RE.match(cur_stripped)
                if cm and not cur.startswith("    "):
                    items.append(cm.group(2))
                    i += 1
                    continue
                if not cur_stripped:
                    # a blank line only continues the list when another item
                    # follows; anything else (notably a code block) ends it
                    if i + 1 < n and LIST_RE.match(body[i + 1].strip()) \
                            and not body[i + 1].startswith("    "):
                        i += 1
                        continue
                    break
                if items and cur.startswith("  "):
                    items[-1] += " " + cur_stripped     # wrapped continuation
                    i += 1
                    continue
                break
            for k, item in enumerate(items, start=1):
                flows.append(Paragraph(
                    inline(item, link_resolver), styles["bullet"],
                    bulletText=f"{k}." if ordered else "•",
                ))
            flows.append(Spacer(1, 4))
            continue

        # bare (unfenced) GFM table -> real table. Authors usually fence tables
        # so parse_clean_table picks them up, but a plain table sitting in the
        # body would otherwise be swept into a paragraph and rendered as garbled
        # pipe-soup. Collect the contiguous run of "|"-delimited rows and render
        # it as a table when clean, else keep it verbatim.
        if stripped.startswith("|"):
            flush_para()
            buf = []
            while i < n and body[i].strip().startswith("|"):
                buf.append(body[i])
                i += 1
            rows = parse_clean_table(buf)
            if rows:
                flows += table_flowable(rows, styles, avail_width, link_resolver)
            else:
                flows += code_flowable("\n".join(buf), styles, avail_width=avail_width)
            continue

        # indented block -> code box (verbatim, keep relative indent). List items
        # and their continuations were consumed above, so what reaches here is
        # genuinely code.
        if line[:2] == "  ":
            flush_para()
            buf = []
            while i < n and (body[i][:2] == "  " or not body[i].strip()):
                if body[i].strip() == "" and not (i + 1 < n and body[i + 1][:2] == "  "):
                    break
                buf.append(body[i][2:] if body[i].startswith("  ") else body[i])
                i += 1
            while buf and not buf[-1].strip():
                buf.pop()
            flows += code_flowable("\n".join(buf), styles)
            continue

        para.append(line)
        i += 1

    flush_para()
    return flows


# --------------------------------------------------------------------------- #
# Document template (footer + TOC notifications + outline)                    #
# --------------------------------------------------------------------------- #
class RefDoc(BaseDocTemplate):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self._pending_bookmark = None

    def afterFlowable(self, flowable):
        if not isinstance(flowable, Paragraph):
            return
        style = flowable.style.name
        plain = flowable.getPlainText()           # for the PDF outline (no markup)
        toc = getattr(flowable, "_toctext", plain)  # for the TOC (keeps <font>)
        if style == "chapter":
            key = "ch-%d" % self.page
            self.canv.bookmarkPage(key)
            self.notify("TOCEntry", (0, toc, self.page, key))
            self.canv.addOutlineEntry(plain, key, level=0, closed=False)
        elif style == "entry":
            key = getattr(flowable, "_bm", "en-%d" % id(flowable))
            self.canv.bookmarkPage(key)
            self.notify("TOCEntry", (1, toc, self.page, key))
            self.canv.addOutlineEntry(plain, key, level=1, closed=True)


def make_footer(width, manual_title):
    def footer(canvas, doc):
        canvas.saveState()
        x0 = doc.leftMargin
        x1 = doc.leftMargin + doc.width
        y = doc.bottomMargin - 6 * mm
        canvas.setStrokeColor(RULE)
        canvas.setLineWidth(0.5)
        canvas.line(x0, y + 4 * mm, x1, y + 4 * mm)
        canvas.setFont("Body", 8.5)
        canvas.setFillColor(MUTED)
        canvas.drawString(x0, y, manual_title)
        canvas.drawRightString(x1, y, str(canvas.getPageNumber()))
        canvas.restoreState()

    def blank(canvas, doc):
        pass

    return footer, blank


def source_revision(repo_root: str) -> str | None:
    """Short commit id the manual was rendered from, or None.

    A reference with only a date on it cannot be matched back to a build. Read
    ``RELIX_DOCS_REVISION`` if the caller set it, else resolve ``.git/HEAD`` by
    hand — the render image has no git binary, and mounting one in just to read a
    40-byte file is not worth it.
    """
    env = os.environ.get("RELIX_DOCS_REVISION", "").strip()
    if env:
        return env[:12]
    try:
        with open(os.path.join(repo_root, ".git", "HEAD"), encoding="utf-8") as fh:
            head = fh.read().strip()
        if head.startswith("ref:"):
            ref = head.split(":", 1)[1].strip()
            with open(os.path.join(repo_root, ".git", ref), encoding="utf-8") as fh:
                return fh.read().strip()[:12]
        return head[:12] or None
    except OSError:
        return None


# --------------------------------------------------------------------------- #
# Build                                                                       #
# --------------------------------------------------------------------------- #
def build(ref_dir: str, out_path: str, manual: str = "reference") -> None:
    profile = MANUALS[manual]
    register_fonts()
    styles = build_styles()

    preface, chapters = parse_readme(ref_dir)
    diagrams = DiagramRenderer()
    # repo root = parent of the docs/ dir holding reference/, for logo-assets/
    repo_root = os.path.dirname(os.path.dirname(os.path.abspath(ref_dir)))

    # bookmark name for each page (relative href), so See-Also can link to it
    bookmark_of = {}
    for _t, _i, _tbl, pages in chapters:
        for href in pages:
            bookmark_of[os.path.normpath(href)] = "pg-" + re.sub(
                r"[^A-Za-z0-9]", "-", href
            )

    doc = RefDoc(
        out_path,
        pagesize=A4,
        leftMargin=2.2 * cm,
        rightMargin=2.2 * cm,
        topMargin=2.2 * cm,
        bottomMargin=2.4 * cm,
        title=profile["title"],
        author="Relix",
    )
    footer, blank = make_footer(doc.width, profile["title"])
    frame = Frame(
        doc.leftMargin, doc.bottomMargin, doc.width, doc.height, id="body"
    )
    from reportlab.platypus import PageTemplate

    doc.addPageTemplates([
        PageTemplate(id="Title", frames=[frame], onPage=blank),
        PageTemplate(id="Body", frames=[frame], onPage=footer),
    ])

    story = []

    # ---- title page -------------------------------------------------------
    logo_path = os.path.join(repo_root, "logo-assets", "logo.png")
    if os.path.exists(logo_path):
        story.append(Spacer(1, 4 * cm))
        iw, ih = ImageReader(logo_path).getSize()
        w = 8 * cm
        h = w * ih / iw
        if h > 10 * cm:          # cap tall logos
            h = 10 * cm
            w = h * iw / ih
        logo = Image(logo_path, width=w, height=h)
        logo.hAlign = "CENTER"
        story.append(logo)
        story.append(Spacer(1, 0.8 * cm))
    else:
        story.append(Spacer(1, 7 * cm))
        story.append(Paragraph("Relix", styles["title"]))
    story.append(Paragraph(profile["subtitle"], styles["subtitle"]))
    story.append(Paragraph(profile["tagline"], styles["tagline"]))
    story.append(Spacer(1, 1.2 * cm))
    today = _dt.date.today().strftime("%-d %B %Y")
    revision = source_revision(repo_root)
    stamp = f"Generated {today}" + (f" · revision {revision}" if revision else "")
    story.append(Paragraph(stamp, styles["tagline"]))
    story.append(NextPageTemplate("Body"))
    story.append(PageBreak())

    # ---- table of contents ------------------------------------------------
    story.append(Paragraph("Contents", styles["toc_title"]))
    toc = TableOfContents()
    toc.levelStyles = [styles["toc0"], styles["toc1"]]
    toc.dotsMinLevel = 0
    story.append(toc)
    story.append(PageBreak())

    # ---- preface (the index's own "how to read this") ----------------------
    if preface:
        story.append(Paragraph("About this manual", styles["chapter"]))
        for para in preface:
            story.append(Paragraph(inline(para), styles["body"]))
        story.append(PageBreak())

    # ---- chapters / pages -------------------------------------------------
    def resolver_for(page_dir):
        def resolve(href):
            tgt = os.path.normpath(os.path.join(page_dir, href.split("#")[0]))
            rel = os.path.relpath(tgt, ref_dir)
            return bookmark_of.get(os.path.normpath(rel))
        return resolve

    index_resolver = resolver_for(ref_dir)

    for ci, (title, intro, table_lines, pages) in enumerate(chapters, start=1):
        # each major chapter starts on a fresh page
        if ci > 1:
            story.append(PageBreak())
        # README headings already carry their own "N. " prefix; normalise it
        # so renumbering the README never desyncs from the rendered number.
        clean = re.sub(r"^\s*\d+\.\s*", "", title)
        ch_text = f"{ci}. {esc(clean)}"
        ch_para = Paragraph(ch_text, styles["chapter"])
        ch_para._toctext = ch_text
        story.append(ch_para)
        if intro:
            story.append(Paragraph(inline(intro), styles["chapter_intro"]))
        # the index's summary table — glyph, ASCII form and a one-line gloss per
        # entry. It is the quick-reference the manual otherwise lacks, and until
        # now it was parsed out of the README and thrown away.
        rows = parse_clean_table(table_lines) if table_lines else None
        if rows:
            story.extend(
                table_flowable(rows, styles, doc.width, index_resolver)
            )
        read_page = parse_narrative_page if profile["narrative"] else parse_page
        for href in pages:
            full = os.path.normpath(os.path.join(ref_dir, href))
            name, sections = read_page(full)
            resolver = resolver_for(os.path.dirname(full))
            # The visible entry renders its markup; the PDF outline takes the plain text
            # reportlab derives from it (getPlainText), and the table of contents keeps
            # the markup, as _toctext already did for chapters.
            entry = heading_paragraph(name, "entry", styles, resolver)
            entry_text = entry.text
            entry._bm = "pg-" + re.sub(r"[^A-Za-z0-9]", "-", href)
            story.append(entry)
            for label, body in sections:
                # A narrative page has no labels: its own `##` headings are the
                # structure, and the body renderer draws those.
                if label:
                    story.append(heading_paragraph(label, "label", styles, resolver))
                story.extend(
                    render_section_body(
                        label, body, styles, resolver, doc.width, diagrams
                    )
                )

    doc.multiBuild(story)


if __name__ == "__main__":
    ref = sys.argv[1] if len(sys.argv) > 1 else "docs/reference"
    out = sys.argv[2] if len(sys.argv) > 2 else "build/docs/reference.pdf"
    manual = sys.argv[3] if len(sys.argv) > 3 else "reference"
    if manual not in MANUALS:
        sys.exit(f"unknown manual '{manual}'; expected one of {sorted(MANUALS)}")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    build(ref, out, manual)
    print(f"Wrote {out}")
