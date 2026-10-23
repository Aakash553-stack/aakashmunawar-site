# aakashmunawar.com

Personal portfolio site for Aakash Munawar — CS student at Rutgers University–New Brunswick.

## Structure

- `index.html` — the portfolio site: HTML, CSS, JavaScript, the profile photo (WebP, base64) and
  the project illustrations (inline SVG) are all inside it. The only external request is Google
  Fonts, which loads without blocking the first paint.
- `assets/laptop3d.js` — the 3D laptop in the hero, modelled in code (no downloaded model). It
  loads after the page, only where WebGL2 is available; otherwise the HTML/CSS laptop in
  `index.html` is shown. Its screen is real HTML (`#laptop-screen`) and its pop-out panels are
  `#hub-panel`, both in `index.html`.
- `assets/three/` — [three.js](https://threejs.org) r170 (MIT, see `assets/three/LICENSE`), copied
  unmodified from the `three@0.170.0` npm package: `build/three.module.min.js` plus two addons.
  It is served from this site; `index.html` has an import map that points `three` at it.
- `resume.pdf` — the one-page résumé linked from the site, built by `resume/build_resume.py`.
- `projects/` — source for the projects shown on the site. Each one has its own README, and the
  live ones deploy separately (see each project's README).

## Previewing locally

No build step or dependencies:

```bash
python3 -m http.server 4173      # from the repo root
# open http://localhost:4173
```

## Deploying

The site is deployed on Vercel from this repository. [`vercel.json`](vercel.json) sets the build
command to `mkdir -p dist && cp -R index.html resume.pdf assets dist/` with `dist` as the output
directory, so **only `index.html`, `resume.pdf` and `assets/` are published**. A new public file
outside those must be added to that command, or it will 404 in production. (The two project apps
are separate Vercel projects rooted in their own folders, so this file doesn't affect them.)

Alternatively, GitHub Pages works too — a `CNAME` file with the domain is already included for that path.

## Editing

`index.html` is one file — open it in any editor. Section by section:
- Tokens (colors, fonts, spacing): the `:root` block at the top of the `<style>`
- Hero, the laptop and its four shortcut cards: `<section class="hero">` (the `.hub` element)
- Projects: `<section id="work">` — the HackRU feature card, then one `<article class="card">` per
  project. A card's `data-tags` (`fullstack`, `data`, `algorithms`) decide which filter shows it.
- About (with the "at a glance" card), skills: `<section id="about">`, `<section id="skills">`
- Résumé: `<section id="resume">` links to `/resume.pdf` (view and download), with "Request a
  copy" by email as a fallback.
- Contact: `<section id="contact">`

## Résumé

`resume.pdf` is generated, so edit the content in `resume/build_resume.py`, not the PDF:

```bash
pip install reportlab
python resume/build_resume.py      # rewrites resume.pdf; fails if it no longer fits one page
```

It uses only facts that are also on the site and were checked against the project repositories.
Graduation date, degree type and GPA are not included because they haven't been confirmed. Contact
details are the public ones only (email, site, GitHub, LinkedIn): no phone number or address.
