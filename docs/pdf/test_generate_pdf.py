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

import io
import os
import unittest

from reportlab.lib.pagesizes import A4
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.pdfmetrics import Font, registerFont, registerFontFamily
from reportlab.pdfgen.canvas import Canvas
from reportlab.platypus import Paragraph

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


class TableColumnWidths(unittest.TestCase):
    """A column was sized by counting characters, which is not what it is drawn in."""

    NAMES = [
        ["`boundedness`", "`row_count`", "What it means"],
        ["`bounded`", "a number", "finite, and counted"],
        ["`bounded`", "NULL",
         "finite, but nobody has counted it \u2014 a CSV file nothing has read"],
    ]

    def _text_room(self, rows, col):
        widths = G.table_columns(rows, STYLES, AVAIL)
        return widths[col] - 2 * G.TBL_CELL_PAD

    def test_a_header_of_column_names_is_sized_for_monospace(self):
        """`boundedness` wrapped to "boundednes/s": mono is wider than the prose
        the character count assumed, and a name is the one wrap a reader reads as
        a fault."""
        name = pdfmetrics.stringWidth("boundedness", "Mono", G.CODE_SPAN_SIZE)
        self.assertGreaterEqual(self._text_room(self.NAMES, 0), name)

    def test_an_href_is_counted_as_nothing_because_it_is_drawn_as_nothing(self):
        """A link's target is characters the page never shows."""
        bare = [["Page", "What it does"], ["Getting started", "Your first script"]]
        linked = [["Page", "What it does"],
                  ["[Getting started](language/getting-started.md)",
                   "Your first script"]]
        self.assertAlmostEqual(self._text_room(bare, 0),
                               self._text_room(linked, 0), places=3)

    def test_the_shortfall_comes_from_the_column_that_can_wrap(self):
        """A prose column gives up a word to the next line; a column of commands
        has nothing to break, so sharing the shortfall by size breaks the one
        cell that cannot afford it."""
        rows = [["Command", "Effect"],
                ['`:rels name <n> "Name"`',
                 "Rename learned relationship n, so it can be named in a question "
                 "and so that this table is wider than the frame it is drawn in"]]
        command = pdfmetrics.stringWidth(':rels name <n> "Name"', "Mono",
                                         G.CODE_SPAN_SIZE)
        widths = G.table_columns(rows, STYLES, AVAIL)
        self.assertGreater(sum(widths), AVAIL - 0.5)   # it did not fit
        self.assertGreaterEqual(widths[0] - 2 * G.TBL_CELL_PAD, command)

    def test_the_columns_fill_the_frame(self):
        for rows in (self.NAMES, [["A", "B"], ["x", "y"]]):
            self.assertAlmostEqual(sum(G.table_columns(rows, STYLES, AVAIL)),
                                   AVAIL, places=3)


class CodeSpansOnTheHeaderBand(unittest.TestCase):
    """The chip is a tint of what is behind it, and the header band is violet."""

    def test_a_body_span_keeps_the_near_white_chip(self):
        self.assertIn(f'backColor="{G.CODE_SPAN_BG}"', G.inline("a `name` here"))

    def test_a_header_span_is_not_drawn_in_the_body_chip(self):
        """White text on a near-white chip is how `boundedness` became unreadable."""
        rendered = G.inline("a `name` here", on_dark=True)
        self.assertNotIn(G.CODE_SPAN_BG, rendered)
        self.assertIn(G.TBL_HEADER_CODE_BG.hexval()[2:], rendered)

    def test_the_header_row_is_the_only_row_drawn_on_the_band(self):
        flows = G.table_flowable(TableColumnWidths.NAMES, STYLES, AVAIL, None)
        table = next(f for f in flows if hasattr(f, "_cellvalues"))
        header, body = table._cellvalues[0], table._cellvalues[1]
        self.assertNotIn(G.CODE_SPAN_BG, header[0].text)
        self.assertIn(G.CODE_SPAN_BG, body[0].text)


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


class _LinkSpy(Canvas):
    """A canvas that records which kind of link each hotspot turned out to be.

    Both kinds draw the same coloured text over the same rectangle, so the only
    way to tell a jump to page 84 from a dead web address is to ask which call
    ReportLab made. That is the whole reason this is a spy rather than an
    assertion on the markup: ``_doLink`` decides from the href, and the claim
    worth pinning is the decision, not the string we hand it.
    """

    def __init__(self):
        super().__init__(io.BytesIO(), pagesize=A4)
        self.destinations: list[str] = []
        self.urls: list[str] = []

    def linkRect(self, contents, destinationname, *args, **kwargs):
        self.destinations.append(destinationname)
        return super().linkRect(contents, destinationname, *args, **kwargs)

    def linkURL(self, url, *args, **kwargs):
        self.urls.append(url)
        return super().linkURL(url, *args, **kwargs)


def _draw(markup):
    """Render one line of paragraph markup and report the links it laid down."""
    para = Paragraph(markup, STYLES["body"])
    canvas = _LinkSpy()
    para.wrapOn(canvas, AVAIL, 200)
    para.drawOn(canvas, 0, 400)
    return canvas


class CrossReferenceLinks(unittest.TestCase):
    """Every cross-reference in both manuals was a link to a URL that is not one.

    ReportLab reads an href starting with ``#`` as a destination inside the
    document and hands anything else to ``linkURL``. The renderer emitted the
    bare bookmark name, so all 1546 cross-references in the reference manual and
    all 80 in the guide were annotated as web addresses like
    ``pg-operators-group-md`` -- coloured, clickable, and going nowhere. Nothing
    caught it because a broken link renders exactly like a working one.
    """

    RESOLVED = "pg-operators-group-md"

    def _markup(self, text, target=RESOLVED):
        return G.inline(text, lambda _href: target)

    def test_a_cross_reference_is_a_jump_within_the_document(self):
        canvas = _draw(self._markup("see [group](operators/group.md)"))
        self.assertEqual(canvas.destinations, [self.RESOLVED])
        self.assertEqual(canvas.urls, [])

    def test_the_destination_is_the_one_the_entry_is_bookmarked_under(self):
        """The link and the bookmark are two uses of one name; they must agree."""
        canvas = _draw(G.inline("see [group](operators/group.md)",
                                G.resolver_for("docs/reference", "docs/reference",
                                               {"operators/group.md": G.bookmark_name(
                                                   "operators/group.md")})))
        self.assertEqual(canvas.destinations, [G.bookmark_name("operators/group.md")])

    def test_an_unresolved_target_is_not_a_link_at_all(self):
        """A label the manual cannot reach is coloured prose, never a dead hotspot."""
        canvas = _draw(G.inline("see [elsewhere](../design/adr-0011.md)",
                                lambda _href: None))
        self.assertEqual(canvas.destinations, [])
        self.assertEqual(canvas.urls, [])


class HrefResolution(unittest.TestCase):
    """Which entry a Markdown href names, from wherever the href was written."""

    def setUp(self):
        chapters = [
            ("Operators", "", [], ["operators/group.md", "operators/select.md"]),
            ("Joins", "", [], ["joins/theta-join.md"]),
        ]
        self.bookmarks = G.page_bookmarks(chapters)

    def _resolve(self, page_dir, href, ref_dir="."):
        return G.resolver_for(page_dir, ref_dir, self.bookmarks)(href)

    def test_a_sibling_page_resolves(self):
        self.assertEqual(self._resolve("operators", "select.md"),
                         G.bookmark_name("operators/select.md"))

    def test_a_page_in_another_chapter_resolves_through_the_relative_path(self):
        """Pages are nested one directory deep, so most links climb and descend."""
        self.assertEqual(self._resolve("operators", "../joins/theta-join.md"),
                         G.bookmark_name("joins/theta-join.md"))

    def test_a_fragment_lands_on_the_page_that_holds_the_section(self):
        """The PDF bookmarks entries, not sections; dropping it beats dropping the link."""
        self.assertEqual(self._resolve("operators", "../joins/theta-join.md#syntax"),
                         G.bookmark_name("joins/theta-join.md"))

    def test_an_external_url_resolves_to_nothing(self):
        self.assertIsNone(self._resolve("operators", "https://no-color.org/"))

    def test_an_unregistered_page_resolves_to_nothing(self):
        """A page the index does not list is not in the PDF, so nothing can point at it."""
        self.assertIsNone(self._resolve("operators", "../README.md"))

    def test_one_recipe_names_the_destination(self):
        """bookmark_name is the single home; the entry and the link both read it."""
        for href in ("operators/group.md", "joins/theta-join.md"):
            self.assertEqual(self.bookmarks[os.path.normpath(href)],
                             G.bookmark_name(href))


class RenderedManualLinks(unittest.TestCase):
    """The end-to-end claim, over a manual small enough to render in a test."""

    def _render(self, tmp):
        os.makedirs(os.path.join(tmp, "operators"), exist_ok=True)
        with open(os.path.join(tmp, "README.md"), "w", encoding="utf-8") as fh:
            fh.write("# Index\n\nHow to read this.\n\n## 1. Operators\n\n"
                     "- [Selection](operators/select.md)\n"
                     "- [Projection](operators/project.md)\n")
        with open(os.path.join(tmp, "operators", "select.md"), "w", encoding="utf-8") as fh:
            fh.write("# Name: Selection\n\n# Description:\n"
                     "Keeps rows. See [Projection](project.md).\n")
        with open(os.path.join(tmp, "operators", "project.md"), "w", encoding="utf-8") as fh:
            fh.write("# Name: Projection\n\n# Description:\nKeeps columns.\n")
        out = os.path.join(tmp, "manual.pdf")
        G.build(tmp, out, "reference")
        with open(out, "rb") as fh:
            return fh.read()

    def test_the_pdf_carries_destinations_and_no_bookmark_shaped_urls(self):
        import tempfile

        with tempfile.TemporaryDirectory() as tmp:
            pdf = self._render(tmp)
        self.assertIn(b"/Dest", pdf, "no internal destination was written at all")
        # A URI annotation naming a bookmark is the defect: the reader is offered
        # a web address spelled like a destination.
        self.assertNotIn(b"/URI (pg-", pdf)
        self.assertNotIn(b"/URI (ch-", pdf)


class ExternalLinks(unittest.TestCase):
    """A web address is the other thing a reader can follow, and it was dropped.

    The resolver answers only for pages this manual renders, so an ``https://``
    href fell through to the unlinked branch beside the genuinely unreachable
    ones: the two the reference manual carries were coloured like links and were
    not links. ReportLab needs no ``#`` here -- it reads the scheme.
    """

    def test_a_web_address_is_a_link_to_that_address(self):
        canvas = _draw(G.inline("honours [NO_COLOR](https://no-color.org/)"))
        self.assertEqual(canvas.urls, ["https://no-color.org/"])
        self.assertEqual(canvas.destinations, [])

    def test_a_query_string_survives_the_escaping(self):
        """The href is escaped with the rest of the line before it is read back."""
        canvas = _draw(G.inline("see [it](https://x.test/p?a=1&b=2)"))
        self.assertEqual(canvas.urls, ["https://x.test/p?a=1&b=2"])

    def test_an_in_page_anchor_is_not_a_link(self):
        """It cannot be one: ReportLab fails the *save* on an undefined
        destination, so one in-page anchor would take the whole manual down."""
        canvas = _draw(G.inline("see [below](#rows-as-your-own-types)"))
        self.assertEqual(canvas.urls, [])
        self.assertEqual(canvas.destinations, [])

    def test_a_page_this_manual_does_not_render_is_not_a_link(self):
        canvas = _draw(G.inline("see [ADR-0011](../../design/adr-0011.md)"))
        self.assertEqual(canvas.urls, [])
        self.assertEqual(canvas.destinations, [])

    def test_an_href_that_could_break_out_of_the_attribute_is_declined(self):
        """``esc`` leaves a double quote alone, so the pattern must not admit one."""
        for href in ('https://x.test/"a', "https://x.test/a b", "https://x.test/<b>"):
            self.assertIsNone(G.EXTERNAL_URL_RE.match(href), href)

    def test_a_resolved_page_beats_a_look_alike_scheme(self):
        """Resolution runs first; an external form only ever fills an absence."""
        canvas = _draw(G.inline("see [group](operators/group.md)",
                                lambda _h: "pg-operators-group-md"))
        self.assertEqual(canvas.destinations, ["pg-operators-group-md"])
        self.assertEqual(canvas.urls, [])


class ManualExternalLinks(unittest.TestCase):
    """The two web addresses the reference manual actually carries."""

    def test_the_corpus_links_are_recognised(self):
        if not os.path.isdir(REF_DIR):
            self.skipTest(f"{REF_DIR} not present")
        found = []
        for root, _dirs, files in os.walk(REF_DIR):
            for name in files:
                if not name.endswith(".md"):
                    continue
                with open(os.path.join(root, name), encoding="utf-8") as fh:
                    for m in G.LINK_RE.finditer(fh.read()):
                        href = m.group("href")
                        if href.startswith(("http://", "https://")):
                            found.append(href)
        self.assertTrue(found, "no external link in the manual to check")
        for href in found:
            self.assertIsNotNone(G.EXTERNAL_URL_RE.match(href),
                                 f"{href} would render unlinked")


if __name__ == "__main__":
    unittest.main(verbosity=2)
