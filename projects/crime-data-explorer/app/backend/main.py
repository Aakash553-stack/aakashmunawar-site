"""FastAPI backend for the Crime Data Explorer dashboard.

    uvicorn backend.main:app --reload        (run from the app/ folder)

All endpoints are read-only GETs over crime.db. Every endpoint accepts the
same filters (see `filter_params`), so the charts and the incident table
always describe the same slice of the data.
"""

from datetime import date, timedelta
from pathlib import Path

from fastapi import Depends, FastAPI, HTTPException, Query, Response

from .db import connect
from .queries import Filters, load_queries

QUERIES = load_queries()
FRONTEND_DIR = Path(__file__).resolve().parent.parent / "frontend"

app = FastAPI(
    title="Crime Data Explorer API",
    description="Chicago Police Department incidents, Sep 2024 - Aug 2026, "
                "from the City of Chicago Data Portal (dataset ijzp-q8t2).",
    version="1.0.0",
    docs_url="/api/docs",
    openapi_url="/api/openapi.json",
)

# The data only changes when the database is rebuilt, so responses can be
# cached by browsers briefly and by Vercel's CDN for a day.
CACHE = "public, max-age=300, s-maxage=86400, stale-while-revalidate=86400"


def filter_params(
    start: date | None = Query(None, description="First day to include (YYYY-MM-DD)"),
    end: date | None = Query(None, description="Last day to include (YYYY-MM-DD)"),
    category: int | None = Query(None, description="crime_categories.category_id"),
    district: int | None = Query(None, description="Police district number"),
    community_area: int | None = Query(None, description="Community area number (1-77)"),
    arrest: bool | None = Query(None, description="true / false; omit for both"),
    domestic: bool | None = Query(None, description="true / false; omit for both"),
) -> Filters:
    if start and end and start > end:
        raise HTTPException(422, "start must be on or before end")
    return Filters(start, end, category, district, community_area, arrest, domestic)


def run(name, filters, **params):
    where, where_params = filters.where()
    sql = QUERIES[name].replace("{where}", where)
    with connect() as conn:
        return [dict(r) for r in conn.execute(sql, {**where_params, **params})]


def data_window(conn):
    lo, hi = conn.execute("SELECT MIN(date(occurred_at)), MAX(date(occurred_at)) "
                          "FROM incidents").fetchone()
    return date.fromisoformat(lo), date.fromisoformat(hi)


def effective_window(filters):
    """The filter's date range, clipped to the dates the data covers."""
    with connect() as conn:
        lo, hi = data_window(conn)
    return max(filters.start or lo, lo), min(filters.end or hi, hi)


# --------------------------------------------------------------------------
# Reference data
# --------------------------------------------------------------------------

@app.get("/api/meta")
def meta(response: Response):
    """Filter options and the extent of the data."""
    response.headers["Cache-Control"] = CACHE
    with connect() as conn:
        lo, hi = data_window(conn)
        categories = conn.execute("""
            SELECT c.category_id AS id, c.name, COUNT(*) AS incidents
            FROM incidents i JOIN incident_types t USING (iucr)
            JOIN crime_categories c USING (category_id)
            GROUP BY c.category_id ORDER BY c.name""").fetchall()
        districts = conn.execute("""
            SELECT d.district_id AS id, d.name, COUNT(*) AS incidents
            FROM incidents i JOIN districts d USING (district_id)
            GROUP BY d.district_id ORDER BY d.district_id""").fetchall()
        areas = conn.execute("""
            SELECT community_area_id AS id, name FROM community_areas
            ORDER BY name""").fetchall()
        total = conn.execute("SELECT COUNT(*) FROM incidents").fetchone()[0]
    return {
        "source": "City of Chicago Data Portal, Crimes - 2001 to Present (ijzp-q8t2)",
        "first_date": lo.isoformat(),
        "last_date": hi.isoformat(),
        "total_incidents": total,
        "categories": [dict(r) for r in categories],
        "districts": [dict(r) for r in districts],
        "community_areas": [dict(r) for r in areas],
    }


# --------------------------------------------------------------------------
# Incidents (the table)
# --------------------------------------------------------------------------

@app.get("/api/incidents")
def incidents(
    response: Response,
    filters: Filters = Depends(filter_params),
    page: int = Query(1, ge=1),
    page_size: int = Query(25, ge=1, le=100),
):
    """Matching incidents, newest first, one page at a time."""
    response.headers["Cache-Control"] = CACHE
    where, params = filters.where(alias="i")
    with connect() as conn:
        total = conn.execute(f"SELECT COUNT(*) FROM incidents i WHERE {where}", params).fetchone()[0]
        rows = conn.execute(f"""
            SELECT i.incident_id, i.case_number, i.occurred_at,
                   c.name AS category, t.description, lt.name AS location_type,
                   i.block, i.district_id AS district, ca.name AS community_area,
                   i.arrest, i.domestic
            FROM incidents i
            JOIN incident_types t   USING (iucr)
            JOIN crime_categories c USING (category_id)
            LEFT JOIN location_types lt  USING (location_type_id)
            LEFT JOIN community_areas ca USING (community_area_id)
            WHERE {where}
            ORDER BY i.occurred_at DESC, i.incident_id DESC
            LIMIT :limit OFFSET :offset""",
            {**params, "limit": page_size, "offset": (page - 1) * page_size}).fetchall()
    return {
        "total": total,
        "page": page,
        "page_size": page_size,
        "pages": max(1, -(-total // page_size)),
        "items": [{**dict(r), "arrest": bool(r["arrest"]), "domestic": bool(r["domestic"])}
                  for r in rows],
    }


# --------------------------------------------------------------------------
# Aggregates: the six notebook analyses, plus a summary
# --------------------------------------------------------------------------

@app.get("/api/stats/summary")
def summary(response: Response, filters: Filters = Depends(filter_params)):
    response.headers["Cache-Control"] = CACHE
    where, params = filters.where()
    with connect() as conn:
        row = conn.execute(f"""
            SELECT COUNT(*) AS incidents,
                   ROUND(100.0 * AVG(arrest), 1)   AS arrest_rate_pct,
                   ROUND(100.0 * AVG(domestic), 1) AS domestic_pct
            FROM incidents WHERE {where}""", params).fetchone()
    return dict(row)


@app.get("/api/stats/incidents-by-hour")
def incidents_by_hour(response: Response, filters: Filters = Depends(filter_params)):
    """Incidents per hour of day; exact 00:00 / 12:00 timestamps counted separately."""
    response.headers["Cache-Control"] = CACHE
    rows = {r["hour"]: r for r in run("incidents_by_hour", filters)}
    empty = {"incidents": 0, "placeholder_time": 0, "exact_time": 0}
    return [{"hour": h, **{k: rows.get(h, empty)[k] for k in empty}} for h in range(24)]


@app.get("/api/stats/top-categories-by-district")
def top_categories_by_district(response: Response, filters: Filters = Depends(filter_params),
                               top_n: int = Query(3, ge=1, le=10)):
    response.headers["Cache-Control"] = CACHE
    return run("top_categories_by_district", filters, top_n=top_n)


@app.get("/api/stats/monthly-trend")
def monthly_trend(response: Response, filters: Filters = Depends(filter_params)):
    """Incidents per month with month-over-month and year-over-year change.

    The SQL skips months with no matching incidents, which would make its
    LAG() comparisons span the wrong months for narrow filters. Months in the
    window with no incidents are filled in with 0 here, and the percentage
    changes are recomputed with the same formula as queries.sql.
    """
    response.headers["Cache-Control"] = CACHE
    counts = {r["month"]: r["incidents"] for r in run("monthly_trend", filters)}
    lo, hi = effective_window(filters)
    months, cursor = [], date(lo.year, lo.month, 1)
    while cursor <= hi:
        months.append(cursor)
        cursor = date(cursor.year + cursor.month // 12, cursor.month % 12 + 1, 1)

    def pct(now, before):
        return round(100.0 * (now - before) / before, 1) if before else None

    out = []
    for i, m in enumerate(months):
        key = m.strftime("%Y-%m")
        n = counts.get(key, 0)
        prev = out[i - 1]["incidents"] if i >= 1 else None
        last_year = out[i - 12]["incidents"] if i >= 12 else None
        month_end = date(m.year + m.month // 12, m.month % 12 + 1, 1) - timedelta(days=1)
        out.append({
            "month": key,
            "incidents": n,
            "mom_pct_change": pct(n, prev) if prev is not None else None,
            "same_month_last_year": last_year,
            "yoy_pct_change": pct(n, last_year) if last_year is not None else None,
            "partial": m < lo or month_end > hi,   # window starts/ends mid-month
        })
    return out


@app.get("/api/stats/day-of-week")
def day_of_week(response: Response, filters: Filters = Depends(filter_params)):
    """Average incidents per calendar day for each weekday.

    Averages are taken over every calendar day of that weekday in the window,
    so days with zero matching incidents count as zeros (queries.sql only
    sees days that have incidents; unfiltered, every day does).
    """
    response.headers["Cache-Control"] = CACHE
    rows = {r["dow"]: r for r in run("avg_per_day_of_week", filters)}
    lo, hi = effective_window(filters)
    calendar = [0] * 7                       # index 0 = Sunday, as in SQLite's %w
    for offset in range((hi - lo).days + 1):
        calendar[((lo + timedelta(days=offset)).weekday() + 1) % 7] += 1
    names = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"]
    out = []
    for dow in [1, 2, 3, 4, 5, 6, 0]:        # Monday first
        r = rows.get(dow)
        total = r["total_incidents"] if r else 0
        days_with = r["days"] if r else 0
        avg = total / calendar[dow] if calendar[dow] else 0
        out.append({
            "dow": dow,
            "day_of_week": names[dow],
            "days": calendar[dow],
            # One decimal, as in queries.sql; two for sparse filters (< 10/day).
            "avg_incidents": round(avg, 1 if avg >= 10 else 2),
            "total_incidents": total,
            "min_incidents": r["min_incidents"] if r and days_with == calendar[dow] else 0,
            "max_incidents": r["max_incidents"] if r else 0,
        })
    return out


@app.get("/api/stats/arrest-rate-by-category")
def arrest_rate_by_category(response: Response, filters: Filters = Depends(filter_params),
                            min_incidents: int = Query(1000, ge=1)):
    """Arrest rate per category, for categories with at least `min_incidents`."""
    response.headers["Cache-Control"] = CACHE
    return run("arrest_rate_by_category", filters, min_incidents=min_incidents)


@app.get("/api/stats/community-area-domestic-share")
def community_area_domestic_share(response: Response, filters: Filters = Depends(filter_params),
                                  limit: int = Query(15, ge=1, le=77)):
    """The community areas with the most incidents, with their domestic share."""
    response.headers["Cache-Control"] = CACHE
    return run("community_areas_domestic_share", filters, limit=limit)


@app.get("/api/health")
def health():
    with connect() as conn:
        conn.execute("SELECT 1 FROM incidents LIMIT 1").fetchone()
    return {"status": "ok"}


# The dashboard itself. Registered last and at low priority, so every /api
# route above takes precedence. On Vercel these files are served from the CDN.
app.frontend("/", directory=str(FRONTEND_DIR))
