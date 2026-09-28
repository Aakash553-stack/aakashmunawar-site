# aakashmunawar.com

Personal portfolio site for Aakash Munawar — CS student at Rutgers.

## Structure

- `index.html` — the portfolio site (single self-contained file: HTML, CSS, and the profile photo embedded)
- `projects/` — future project write-ups and links go here as they're finished

## Deploying

This is a static site (no build step). To go live:

1. Push this repo to GitHub.
2. Connect the repo to [Vercel](https://vercel.com) or [Netlify](https://netlify.com) (free tier, auto-deploys on every push).
3. In your domain registrar (IONOS), point `aakashmunawar.com`'s DNS at the host's provided records.

Alternatively, GitHub Pages works too — a `CNAME` file with the domain is already included for that path.

## Editing

`index.html` is one file — open it in any editor. Section by section:
- Masthead / avatar: near the top of the body
- About + skills: `<section id="about">`
- Projects: `<section id="projects">` — see the commented template inside for how to add a new project card
- Contact: `<section id="contact">`
