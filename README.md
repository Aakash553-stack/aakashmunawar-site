# aakashmunawar.com

Personal portfolio site for Aakash Munawar — CS student at Rutgers University–New Brunswick.

## Structure

- `index.html` — the portfolio site. It's a single self-contained file: HTML, CSS, JavaScript,
  the profile photo (WebP, base64) and the project illustrations (inline SVG) are all inside it.
  The only external request is Google Fonts, which loads without blocking the first paint.
- `projects/` — source for the projects shown on the site. Each one has its own README, and the
  live ones deploy separately (see each project's README).

## Previewing locally

No build step or dependencies:

```bash
python3 -m http.server 4173      # from the repo root
# open http://localhost:4173
```

## Deploying

The site is deployed on Vercel from this repository. The Vercel project's build command is
`mkdir -p dist && cp index.html dist/` with `dist` as the output directory, so **only
`index.html` is published**. Anything the page needs must stay inside that file; a separate
CSS, JS or image file would 404 in production.

Alternatively, GitHub Pages works too — a `CNAME` file with the domain is already included for that path.

## Editing

`index.html` is one file — open it in any editor. Section by section:
- Tokens (colors, fonts, spacing): the `:root` block at the top of the `<style>`
- Hero and the "at a glance" card: `<section class="hero">`
- Projects: `<section id="work">` — the HackRU feature card, then one `<article class="card">` per
  project. A card's `data-tags` (`fullstack`, `data`, `algorithms`) decide which filter shows it.
- About, skills: `<section id="about">`, `<section id="skills">`
- Résumé: `<section id="resume">`. The "Save as PDF" button prints the page, and the print
  stylesheet at the end of the `<style>` hides everything except the résumé.
- Contact: `<section id="contact">`
