"""Build resume.pdf, the one-page public résumé linked from aakashmunawar.com.

    pip install reportlab
    python resume/build_resume.py          # writes resume.pdf at the repo root

Every fact here is also on the website and was checked against the project
repositories (tests, data and READMEs). Things that couldn't be verified,
such as graduation date, degree type and GPA, are deliberately left out
rather than guessed.

Privacy: public contact points only (the public email, the site, GitHub and
LinkedIn). No phone number or address, and the PDF metadata names only the
author.
"""

from pathlib import Path

from reportlab.lib.colors import HexColor
from reportlab.lib.enums import TA_LEFT
from reportlab.lib.pagesizes import LETTER
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.units import inch
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import HRFlowable, ListFlowable, ListItem, Paragraph, SimpleDocTemplate, Spacer

OUT = Path(__file__).resolve().parent.parent / "resume.pdf"

INK = HexColor("#151b29")       # body text
MUTED = HexColor("#4a5468")     # secondary text
ACCENT = HexColor("#1f5fd6")    # the site's electric blue, darkened for print (5.6:1 on white)
RULE = HexColor("#c9d3e6")

# Built-in Helvetica writes "•" without a Unicode mapping, so text extraction
# (and applicant-tracking systems) would read the bullets as junk. Drawing just
# the bullets in an embedded TrueType font fixes that. Without one, use an en
# dash, which Helvetica maps correctly.
BULLET_FONT, BULLET = "Helvetica", "–"
for path in ("/System/Library/Fonts/Supplemental/Arial.ttf", "/usr/share/fonts/truetype/msttcorefonts/Arial.ttf",
             "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"):
    if Path(path).exists():
        pdfmetrics.registerFont(TTFont("BulletFont", path))
        BULLET_FONT, BULLET = "BulletFont", "•"
        break

CONTACT = [
    ("aakashmunawar.dev@gmail.com", "mailto:aakashmunawar.dev@gmail.com"),
    ("aakashmunawar.com", "https://aakashmunawar.com"),
    ("github.com/Aakash553-stack", "https://github.com/Aakash553-stack"),
    ("linkedin.com/in/aakash-munawar", "https://www.linkedin.com/in/aakash-munawar-760019268"),
]

EDUCATION = [
    ("Rutgers University–New Brunswick", "Computer Science"),
    ("Raritan Valley Community College", "Computer Science coursework before transferring to Rutgers"),
]
COURSEWORK = "Data Structures, Algorithms, Computer Architecture, Discrete Math, Databases"

AWARD = ("HackRU 2026: Best Regeneron Entry",
         "Team award for Protocol Pre-Flight, a review tool for clinical trial protocols.")

# (name, context, stack, link text, link url, bullets)
PROJECTS = [
    ("Protocol Pre-Flight", "Team project, HackRU 2026", "Next.js, React, TypeScript, Tailwind CSS", None, None, [
        "Built the upload page where a reviewer submits a protocol PDF, and the PDF review workspace that "
        "highlights each finding on the page it cites so the evidence can be checked in place.",
        "Wrote the typed TypeScript API client that connects the frontend to the FastAPI backend my teammates built.",
    ]),
    ("Study Group Matcher", "Solo", "Java, Spring Boot, Spring Security, PostgreSQL, JUnit",
     "study.aakashmunawar.com", "https://study.aakashmunawar.com", [
        "Full-stack app that matches Rutgers students into study groups by shared courses and overlapping weekly "
        "free time: stateless JWT sign-in with BCrypt, Flyway migrations, deployed on Render and Vercel.",
        "Wrote the matching algorithm by hand (merged time intervals, two-pointer intersection). It matched an "
        "independent brute-force model on <b>3,000</b> random schedule pairs and <b>1,575</b> ranked "
        "recommendations; <b>41 JUnit tests</b> pass.",
    ]),
    ("Chicago Crime Explorer", "Solo", "Python, SQL, SQLite, FastAPI, Chart.js",
     "crime.aakashmunawar.com", "https://crime.aakashmunawar.com", [
        "Designed a normalized SQLite schema from the city's flat CSV and loaded <b>478,089</b> Chicago police "
        "incident records (Sept 2024 – Aug 2026).",
        "Built a FastAPI backend and a filterable Chart.js dashboard that reuse the analysis notebook's SQL; "
        "<b>19 API tests</b> confirm unfiltered results match the notebook exactly.",
    ]),
    ("Schedule Builder", "Solo", "Python (standard library)", None, None, [
        "Backtracking search over real Fall 2026 Rutgers sections that drops a partial schedule at its first "
        "conflict. On a five-course search it never generates <b>87%</b> of 2.26 million combinations and runs "
        "<b>5.9× faster</b> than brute force; 18 tests confirm both find identical schedules.",
    ]),
    ("Campus Route Finder", "Solo", "Python", None, None, [
        "Dijkstra's algorithm and a binary-heap priority queue, both written from scratch, over 24 real "
        "OpenStreetMap locations; every route matches an independent Floyd–Warshall computation for every pair.",
    ]),
]

SKILLS = [
    ("Languages", "Java, Python, SQL, TypeScript, JavaScript, HTML & CSS"),
    ("Backend", "Spring Boot, Spring Security, JWT, JPA, FastAPI, REST"),
    ("Databases", "PostgreSQL, SQLite, schema design, Flyway"),
    ("Frontend", "React, Next.js, Tailwind CSS, Chart.js"),
    ("Testing & tools", "JUnit, unittest, Git & GitHub, Docker, Maven, Vercel, Render"),
]


def styles(k=1.0):
    """Type styles. k scales every size and gap, so the page can be fitted to one sheet."""
    base = dict(fontName="Helvetica", textColor=INK, alignment=TA_LEFT)
    sized = ("fontSize", "leading", "spaceBefore", "spaceAfter")

    def style(name, **overrides):
        return ParagraphStyle(name, **{**base, **{key: (v * k if key in sized else v) for key, v in overrides.items()}})

    return {
        "name": style("name", fontName="Helvetica-Bold", fontSize=22, leading=26),
        "tagline": style("tagline", fontSize=10.5, leading=14, textColor=MUTED, spaceBefore=1),
        "contact": style("contact", fontSize=9.2, leading=13, spaceBefore=5),
        "h": style("h", fontName="Helvetica-Bold", fontSize=11, leading=14, textColor=ACCENT,
                   spaceBefore=13, spaceAfter=2),
        "row": style("row", fontSize=10, leading=13.6),
        "proj": style("proj", fontSize=10, leading=13.6, spaceBefore=8),
        "bullet": style("bullet", fontSize=9.7, leading=13),
        "k": k,
    }


def link(text, url):
    return f'<a href="{url}" color="{ACCENT.hexval().replace("0x", "#")}">{text}</a>'


def section(title, st):
    return [Paragraph(title, st["h"]), HRFlowable(width="100%", thickness=0.6, color=RULE, spaceBefore=0, spaceAfter=3)]


def bullets(items, st):
    return ListFlowable(
        [ListItem(Paragraph(t, st["bullet"]), leftIndent=11, value=BULLET) for t in items],
        bulletType="bullet", start=BULLET, leftIndent=11, bulletFontName=BULLET_FONT, bulletFontSize=8,
        bulletColor=MUTED, spaceBefore=2.5 * st["k"],
    )


def build(k=1.0):
    st = styles(k)
    sep = ' <font color="#9aa6bd">|</font> '
    story = [
        Paragraph("Aakash Munawar", st["name"]),
        Paragraph("Computer Science student at Rutgers University–New Brunswick, "
                  "seeking software engineering internships", st["tagline"]),
        Paragraph(sep.join(link(t, u) for t, u in CONTACT), st["contact"]),
    ]

    story += section("Education", st)
    for school, detail in EDUCATION:
        story.append(Paragraph(f"<b>{school}</b>: {detail}", st["row"]))
    story.append(Paragraph(f"<b>Coursework</b>: {COURSEWORK}", st["row"]))

    story += section("Award", st)
    story.append(Paragraph(f"<b>{AWARD[0]}</b>. {AWARD[1]}", st["row"]))

    story += section("Projects", st)
    for name, context, stack, link_text, url, items in PROJECTS:
        head = f"<b>{name}</b>{sep}<i>{context}</i>{sep}{stack}"
        if link_text:
            head += sep + link(link_text, url)
        story.append(Paragraph(head, st["proj"]))
        story.append(bullets(items, st))

    story += section("Skills", st)
    for label, items in SKILLS:
        story.append(Paragraph(f"<b>{label}</b>: {items}", st["row"]))

    doc = SimpleDocTemplate(
        str(OUT), pagesize=LETTER,
        leftMargin=0.62 * inch, rightMargin=0.62 * inch, topMargin=0.55 * inch, bottomMargin=0.5 * inch,
        title="Aakash Munawar – Résumé", author="Aakash Munawar",
        subject="Software engineering internship résumé", creator="aakashmunawar.com", keywords="",
    )
    doc.build(story)
    return doc.page   # number of pages produced


def build_one_page():
    """Use the largest type that still fits on one page (never below 85% of the base size)."""
    k = 1.0
    while build(k) > 1:
        k = round(k - 0.01, 2)
        if k < 0.85:
            raise SystemExit("Content no longer fits on one page; shorten it rather than shrinking the type further.")
    print(f"Wrote {OUT} (one page, type scale {k:.2f})")


if __name__ == "__main__":
    build_one_page()
