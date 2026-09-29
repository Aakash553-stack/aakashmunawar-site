# Crime Data Explorer: web dashboard

An interactive version of the [Crime Data Explorer notebook](../notebooks/explorer.ipynb).
It has a FastAPI backend over the project's SQLite database and a single-page Chart.js
dashboard.

- **Filters:** date range, crime category, police district, community area, and arrest and
  domestic flags. The KPI tiles, all six charts and the incident table update together.
- **Data:** 478,089 Chicago Police Department incidents from 2024-09-01 to 2026-08-31,
  from the City of Chicago Data Portal
  ([ijzp-q8t2](https://data.cityofchicago.org/d/ijzp-q8t2)), downloaded 2026-09-29.

## Architecture

```
browser ──► frontend/ (static HTML + CSS + JS, Chart.js)
               │  fetch("api/...")  (same origin: no CORS)
               ▼
            backend/main.py  (FastAPI)
               │  sql/queries.sql, with filters added (backend/queries.py)
               ▼
            crime.db  (SQLite, read-only)
```

**One app serves both parts.** FastAPI serves the API under `/api/*` and the dashboard at
`/` through `app.frontend()`. Every API route takes priority over frontend files. On
Vercel, the frontend files are served from the CDN, and the API runs as one Python
function.

### How the six analyses reuse the notebook's SQL

[`backend/queries.py`](backend/queries.py) reads the named queries from
[`../sql/queries.sql`](../sql/queries.sql), the same file the notebook uses. It doesn't
maintain a second copy. Each query gets two mechanical changes when it's loaded:

1. **Filters.** The query is prefixed with
   `WITH filtered AS (SELECT * FROM incidents WHERE <filters>)`, and each
   `FROM incidents` becomes `FROM filtered`. Filter values are always bound as SQL
   parameters, never pasted into the SQL text.
2. **Parameters.** A few constants become parameters. For example, the notebook's
   `LIMIT 15` becomes `LIMIT :limit`, and `HAVING COUNT(*) >= 1000` becomes
   `>= :min_incidents`. Each substitution must match exactly once, so if `queries.sql`
   changes shape, the app fails at startup instead of silently returning different
   numbers.

With no filters, every endpoint returns **exactly** what the notebook's queries return,
and the tests check this for all six.

**Two analyses need extra care when filtered.** The SQL only sees months and days that
contain at least one incident. That's fine for the whole city, but a narrow filter can
have empty months or days, so the backend fills those gaps:

- **Monthly trend:** months with no matching incidents are filled in as 0 before the
  month-over-month and year-over-year changes are computed. The formula is the same as
  in `queries.sql`. Months cut off by the date filter are flagged `partial`.
- **Day of week:** averages are taken over every calendar day of each weekday in the
  window, counting days with no incidents as 0.

### API

Interactive docs are at `/api/docs`. Every endpoint accepts the same filters: `start`
and `end` (inclusive, `YYYY-MM-DD`), `category` (an id from `/api/meta`), `district`,
`community_area`, `arrest` and `domestic` (`true`/`false`).

| Endpoint | Returns |
|---|---|
| `GET /api/meta` | filter options (categories, districts, community areas) and the date range |
| `GET /api/incidents?page=&page_size=` | matching incidents, newest first, paginated (`page_size` ≤ 100) |
| `GET /api/stats/summary` | incident count, arrest rate, domestic share |
| `GET /api/stats/incidents-by-hour` | per hour; exact 00:00 / 12:00 timestamps counted separately |
| `GET /api/stats/top-categories-by-district?top_n=3` | top categories per district, with their share |
| `GET /api/stats/monthly-trend` | per month, with month-over-month and year-over-year change |
| `GET /api/stats/day-of-week` | average, min and max incidents per calendar day, by weekday |
| `GET /api/stats/arrest-rate-by-category?min_incidents=1000` | arrest rate per category |
| `GET /api/stats/community-area-domestic-share?limit=15` | busiest community areas and their domestic share |

**Validation and caching:**

- Bad input, such as a non-numeric district, a start date after the end date or
  `page_size` over 100, gets a `422` response.
- The data only changes when the database is rebuilt, so responses are marked cacheable
  (`s-maxage=86400`). Vercel's CDN serves repeated identical queries without running
  Python.

## Running it locally

From this `app/` folder, with Python 3.12 or later:

```bash
python -m venv .venv && source .venv/bin/activate
pip install -r requirements-dev.txt     # fastapi, plus uvicorn and httpx for dev and tests

uvicorn backend.main:app --reload       # http://127.0.0.1:8000
python -m unittest discover tests       # 19 API tests against the real database
```

**Where the app finds the database,** in this order:

1. `$CRIME_DB`, if set.
2. `backend/data/crime.db`, created by `python build.py`.
3. The project's own `../crime.db`, built by the notebook workflow.
4. As a last resort, `backend/data/crime.db.gz`, unpacked into the temp directory.

**To refresh the data:**

```bash
cd ..                                   # the crime-data-explorer project folder
python scripts/fetch_data.py            # about 90 MB of CSVs from the Chicago Data Portal
python scripts/load_data.py             # builds crime.db
cd app && python scripts/package_db.py  # compresses it to backend/data/crime.db.gz (about 31 MB)
```

Then commit the new `crime.db.gz`.

## Deploying on Vercel

The app is set up for Vercel's FastAPI support:

- [`pyproject.toml`](pyproject.toml) points Vercel at `backend.main:app` and runs
  [`build.py`](build.py) after installing dependencies.
- `build.py` unpacks `crime.db.gz` into the function bundle and copies `queries.sql` in.

**Why the database is committed compressed:** Vercel builds from Git, and the raw
`crime.db` is 90 MB and gitignored. The compressed copy is 31 MB, under GitHub's 50 MB
warning size. Unpacked, the whole function stays well under Vercel's 500 MB Python bundle
limit. The trade-off is that each data refresh adds about 31 MB to the repository's
history.

**Setup, as a Vercel project:**

- **Root Directory:** `projects/crime-data-explorer/app`. Also keep "Include files
  outside the root directory in the Build Step" enabled, because the build step reads
  `../sql/queries.sql`.
- **Framework preset:** FastAPI, detected automatically from `pyproject.toml`.
- **Domain:** a subdomain such as `crime.aakashmunawar.com`, pointed at Vercel with a
  `CNAME` record.

## Files

```
app/
├── backend/
│   ├── main.py          # FastAPI app: routes, filters, post-processing, serves the frontend
│   ├── queries.py       # loads sql/queries.sql and makes it filterable
│   ├── db.py            # finds crime.db and opens read-only connections
│   └── data/crime.db.gz # packaged database (committed)
├── frontend/
│   ├── index.html       # dashboard layout
│   ├── styles.css       # same palette and fonts as aakashmunawar.com
│   └── app.js           # filters, API calls, Chart.js charts, paginated table
├── scripts/package_db.py
├── build.py             # deploy-time: unpack the database, copy queries.sql
├── pyproject.toml
└── tests/test_api.py
```
