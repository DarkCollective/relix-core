#!/usr/bin/env python3
"""Tests for the manual-PDF renderer (``generate_pdf.py``).

Run inside the render image (``./gradlew docsPdfTest``), or on any machine with
reportlab installed — the font bootstrap below falls back to the built-in Type1
faces when the DejaVu TTFs the image provides are absent, since none of these
assertions depend on the actual typeface.

Each test names the defect it pins down. They are all failures the renderer had
against real pages in ``docs/reference``: a page's Markdown was accepted, then
rendered into something other than what it said.
"""

from __future__ import annotations

import os
import unittest

from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.pdfmetrics import Font, registerFont, registerFontFamily

import generate_pdf as G

REPO_ROOT = os.environ.get("RELIX_REPO_ROOT", ".")
REF_DIR = os.path.join(REPO_ROOT, "docs", "reference")
GUIDE_DIR = os.path.join(REPO_ROOT, "docs", "guide")

# Frame width of the A4 body page (see build(): A4 width less 2×2.2cm margins).
AVAIL = 470.0


def _bootstrap_fonts() -> None:
    """Register the faces the styles name, whichever fonts this host has."""
    try:
        G.register_fonts()
        return
    except Exception:                                    # noqa: BLE001
        pass
    for name, base in [
        ("Body", "Helvetica"), ("Body-Bold", "Helvetica-Bold"),
        ("Body-Oblique", "Helvetica-Oblique"),
        ("Body-BoldOblique", "Helvetica-BoldOblique"),
        ("Mono", "Courier"), ("Mono-Bold", "Courier-Bold"),
    ]:
        registerFont(Font(name, base, "WinAnsiEncoding"))
    registerFontFamily("Body", normal="Body", bold="Body-Bold",
                       italic="Body-Oblique", boldItalic="Body-BoldOblique")
    registerFontFamily("Mono", normal="Mono", bold="Mono-Bold",
                       italic="Mono", boldItalic="Mono-Bold")


_bootstrap_fonts()
STYLES = G.build_styles()


def render(markdown: str, label: str = "Description"):
    return G.render_section_body(label, markdown.split("\n"), STYLES, None, AVAIL, None)


def style_names(flows):
    return [f.style.name for f in flows if hasattr(f, "style")]


def plain_text(flows):
    return " ".join(f.getPlainText() for f in flows if hasattr(f, "getPlainText"))


class ListRendering(unittest.TestCase):
    """Lists were not rendered as lists — they were glued into run-on paragraphs."""

    def test_bullets_become_bullets(self):
        flows = render("- First item.\n- Second item.")
        self.assertEqual(style_names(flows).count("bullet"), 2)
        self.assertNotIn("- Second", plain_text(flows))

    def test_wrapped_item_is_not_boxed_as_code(self):
        """The 2-space-indent heuristic used to box list continuations as code.

        87 lines across 16 pages rendered as a grey monospace box containing a
        sentence fragment (`language/relate.md` alone had 19).
        """
        flows = render(
            "- **Known relations:** an identifier already defined in the session\n"
            "  is highlighted as known.\n"
            "- Second item."
        )
        self.assertFalse([s for s in style_names(flows) if s.startswith("code")])
        self.assertIn("is highlighted as known.", plain_text(flows))

    def test_numbered_list_keeps_its_numbering(self):
        flows = render("1. First.\n2. Second.\n3. Third.")
        markers = [f.bulletText for f in flows if getattr(f, "bulletText", None)]
        self.assertEqual(markers, ["1.", "2.", "3."])

    def test_code_block_after_a_list_is_still_code(self):
        flows = render("- An item.\n\n  σ age > 18 (Users)")
        self.assertTrue([s for s in style_names(flows) if s.startswith("code")])


class HeadingAndQuoteRendering(unittest.TestCase):

    def test_sub_headings_are_headings_not_literal_hashes(self):
        """49 '##' headings on 12 pages printed verbatim in the body copy."""
        flows = render("## SQL pushdown\n\nBody.\n\n### Detail")
        self.assertIn("sub", style_names(flows))
        self.assertIn("subsub", style_names(flows))
        self.assertNotIn("#", plain_text(flows))

    def test_blockquote_loses_its_marker(self):
        flows = render("> guidance\n> continued")
        self.assertEqual(style_names(flows), ["quote"])
        self.assertNotIn(">", plain_text(flows))


class InlineCodeInHeadings(unittest.TestCase):
    """A section label and a page title were the one place a code span was not one."""

    def test_a_run_of_backticks_is_one_span_not_three(self):
        """``  `name`  `` is one span holding a backtick, not three with two empty."""
        rendered = G.inline("a `` `name` `` here")
        self.assertEqual(rendered.count('face="Mono"'), 1)
        self.assertIn("`name`", rendered)

    def test_a_label_renders_its_code_span(self):
        """The label went through esc() while every body line went through inline()."""
        para = G.heading_paragraph("Legacy `_r` disambiguation", "label", STYLES)
        self.assertEqual(para.text.count('face="Mono"'), 1)
        self.assertNotIn("`", para.text)
        self.assertIn("_r", para.getPlainText())

    def test_a_title_renders_its_code_span(self):
        para = G.heading_paragraph("Reserved namespace (`relix.catalog`)", "entry", STYLES)
        self.assertEqual(para.text.count('face="Mono"'), 1)
        self.assertNotIn("`", para.text)

    def test_the_outline_gets_plain_text_where_the_entry_gets_markup(self):
        """A bookmark takes text, so the two cannot both be the rendered form."""
        para = G.heading_paragraph("Reserved namespace (`relix.catalog`)", "entry", STYLES)
        self.assertEqual(para.getPlainText(), "Reserved namespace (relix.catalog)")


class CodeBoxWidth(unittest.TestCase):
    """XPreformatted never wraps, so an over-wide line ran off the page."""

    def _width(self, text, style):
        return max(pdfmetrics.stringWidth(line, "Mono", style.fontSize)
                   for line in text.split("\n"))

    def test_ordinary_code_keeps_the_base_size(self):
        style, columns = G.fit_code_style("σ age > 18 (Users)", STYLES, AVAIL)
        self.assertEqual(style.fontSize, STYLES["code"].fontSize)
        self.assertIsNone(columns)

    def test_wide_code_shrinks_rather_than_wrapping(self):
        """Shrinking keeps ASCII result tables aligned; wrapping would destroy them."""
        wide = "x" * 135          # the widest real line in the corpus
        style, columns = G.fit_code_style(wide, STYLES, AVAIL)
        self.assertLess(style.fontSize, STYLES["code"].fontSize)
        self.assertIsNone(columns)
        self.assertLessEqual(self._width(wide, style), AVAIL - G.CODE_PADDING + 0.5)

    def test_absurd_line_hard_wraps_at_the_floor_size(self):
        huge = "z" * 1200         # e.g. the 329-char JSON line in advanced/repl.md
        style, columns = G.fit_code_style(huge, STYLES, AVAIL)
        self.assertEqual(style.fontSize, G.CODE_MIN_SIZE)
        self.assertIsNotNone(columns)
        self.assertLessEqual(
            self._width(G.hard_wrap(huge, columns), style),
            AVAIL - G.CODE_PADDING + 0.5,
        )


class TableParsing(unittest.TestCase):

    def test_escaped_pipes_do_not_break_a_row(self):
        """`\\|><\\|` (full outer join) and `MINIMIZE\\|MAXIMIZE` appear in the index.

        Splitting on every pipe gave those rows too many cells, so the join and
        advanced chapter tables were rejected wholesale and silently dropped.
        """
        row = r"| [Full outer join](joins/full-outer-join.md) | `⟗` / `\|><\|` | Keep all rows |"
        cells = G._split_row(row)
        self.assertEqual(len(cells), 3)
        self.assertIn("|><|", cells[1])


class IndexParsing(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        if not os.path.isdir(REF_DIR):
            raise unittest.SkipTest(f"{REF_DIR} not present")
        cls.preface, cls.chapters = G.parse_readme(REF_DIR)

    def test_preface_is_captured(self):
        """Everything before the first '##' used to be dropped, so the PDF opened cold."""
        self.assertTrue(self.preface)
        self.assertTrue(any("ASCII" in line for line in self.preface))

    def test_chapter_summary_tables_are_captured(self):
        """The index tables are the manual's only quick-reference; they were discarded."""
        with_tables = [t for (t, _i, tbl, _p) in self.chapters
                       if tbl and G.parse_clean_table(tbl)]
        # every chapter except the built-in functions list, which is prose links
        self.assertEqual(len(with_tables), len(self.chapters) - 1)

    def test_no_page_is_rendered_twice(self):
        """COLLECT is cross-listed in two chapters; its entry used to print twice."""
        hrefs = [h for (_t, _i, _tbl, pages) in self.chapters for h in pages]
        self.assertEqual(len(hrefs), len(set(hrefs)))

    def test_every_registered_page_exists(self):
        for (_t, _i, _tbl, pages) in self.chapters:
            for href in pages:
                self.assertTrue(os.path.isfile(os.path.join(REF_DIR, href)), href)


class GlyphFallback(unittest.TestCase):
    """Glyphs missing from a face rendered as empty boxes, not as the operator."""

    def test_coverage_is_measured_not_assumed(self):
        if not G._fallback_ok:
            self.skipTest("Symbola not installed on this host")
        # DejaVu Sans Mono has no natural-join or semi-join glyph, so every code
        # box and inline code span on those pages drew a box. The old hardcoded
        # fallback list ("theta and the outer joins") did not include them.
        for glyph in "\u22c8\u22c9\u2a1d\u27d5\u27d6\u27d7":
            self.assertTrue(G.needs_fallback(glyph), glyph)
        for covered in "\u03c3\u03c0\u2200\u2192":
            self.assertFalse(G.needs_fallback(covered), covered)

    def test_fontify_wraps_only_the_uncovered(self):
        if not G._fallback_ok:
            self.skipTest("Symbola not installed on this host")
        out = G.fontify("Users \u22c8 Orders \u03c3")
        self.assertIn(f'<font name="{G.FALLBACK_FACE}">\u22c8</font>', out)
        self.assertNotIn(f'<font name="{G.FALLBACK_FACE}">\u03c3</font>', out)


class RevisionStamp(unittest.TestCase):

    def test_env_override_wins(self):
        os.environ["RELIX_DOCS_REVISION"] = "abc123def456789"
        try:
            self.assertEqual(G.source_revision(REPO_ROOT), "abc123def456")
        finally:
            del os.environ["RELIX_DOCS_REVISION"]

    def test_falls_back_to_git_head(self):
        if not os.path.isdir(os.path.join(REPO_ROOT, ".git")):
            self.skipTest("not a git checkout")
        self.assertEqual(len(G.source_revision(REPO_ROOT) or ""), 12)


class NarrativePages(unittest.TestCase):
    """The guide's page shape: ordinary markdown, not `# Label:` sections.

    A reference page declares its structure; a guide page just is prose. Reading
    one with the reference parser yields a page with no sections at all — an
    entry heading over nothing — which is a silently empty manual rather than a
    failure, so it is pinned here.
    """

    def _write(self, text):
        import tempfile
        fd, path = tempfile.mkstemp(suffix=".md")
        with os.fdopen(fd, "w", encoding="utf-8") as fh:
            fh.write(text)
        self.addCleanup(os.remove, path)
        return path

    def test_title_comes_from_the_h1(self):
        path = self._write("# Getting started\n\nSome prose.\n")
        name, sections = G.parse_narrative_page(path)
        self.assertEqual(name, "Getting started")

    def test_the_whole_body_is_one_unlabelled_section(self):
        path = self._write("# Title\n\nProse.\n\n## A section\n\nMore.\n")
        _name, sections = G.parse_narrative_page(path)
        self.assertEqual(len(sections), 1)
        label, body = sections[0]
        self.assertIsNone(label)
        self.assertIn("## A section", body)
        self.assertNotIn("# Title", body)

    def test_headings_and_fences_survive_the_body_renderer(self):
        path = self._write(
            "# Title\n\n## Step one\n\nProse.\n\n```java\nint x = 1;\n```\n")
        _name, sections = G.parse_narrative_page(path)
        flows = G.render_section_body(None, sections[0][1], STYLES, None, AVAIL, None)
        names = style_names(flows)
        self.assertIn("sub", names)                                     # the ## heading
        self.assertTrue([n for n in names if n.startswith("code")])     # the fenced block

    def test_a_page_with_no_h1_falls_back_to_its_filename(self):
        path = self._write("Just prose, no title.\n")
        name, _sections = G.parse_narrative_page(path)
        self.assertEqual(name, os.path.splitext(os.path.basename(path))[0])


class ManualProfiles(unittest.TestCase):
    """Two manuals, one renderer — the differences live in MANUALS."""

    def test_every_profile_carries_the_whole_cover(self):
        for manual, profile in G.MANUALS.items():
            for key in ("title", "subtitle", "tagline", "narrative"):
                self.assertIn(key, profile, f"{manual} is missing {key}")

    def test_the_two_manuals_do_not_share_a_title(self):
        titles = [p["title"] for p in G.MANUALS.values()]
        self.assertEqual(len(titles), len(set(titles)))

    def test_the_guide_index_registers_pages_that_exist(self):
        if not os.path.isdir(GUIDE_DIR):
            self.skipTest("no docs/guide in this checkout")
        _preface, chapters = G.parse_readme(GUIDE_DIR)
        pages = [href for _t, _i, _tbl, hrefs in chapters for href in hrefs]
        self.assertTrue(pages, "the guide index registers no pages — the PDF "
                               "would render its chapters and nothing else")
        for href in pages:
            self.assertTrue(os.path.isfile(os.path.join(GUIDE_DIR, href)), href)


if __name__ == "__main__":
    unittest.main(verbosity=2)
