"""API tests against the real crime.db.

    cd app && python -m unittest discover tests
"""

import sqlite3
import unittest
import warnings

warnings.filterwarnings("ignore", category=DeprecationWarning)

from fastapi.testclient import TestClient  # noqa: E402

from backend.db import database_path  # noqa: E402
from backend.main import app  # noqa: E402
from backend.queries import QUERY_FILE_CANDIDATES, read_named_queries  # noqa: E402

client = TestClient(app)


def raw(sql, params=()):
    conn = sqlite3.connect(database_path())
    conn.row_factory = sqlite3.Row
    try:
        return [dict(r) for r in conn.execute(sql, params)]
    finally:
        conn.close()


def notebook_query(name):
    path = next(p for p in QUERY_FILE_CANDIDATES if p.exists())
    return raw(read_named_queries(path)[name])


class UnfilteredMatchesNotebookTest(unittest.TestCase):
    """With no filters, each endpoint returns what queries.sql returns."""

    def test_incidents_by_hour(self):
        self.assertEqual(client.get("/api/stats/incidents-by-hour").json(),
                         notebook_query("incidents_by_hour"))

    def test_top_categories_by_district(self):
        self.assertEqual(client.get("/api/stats/top-categories-by-district").json(),
                         notebook_query("top_categories_by_district"))

    def test_monthly_trend(self):
        api = client.get("/api/stats/monthly-trend").json()
        expected = notebook_query("monthly_trend")
        strip = lambda rows: [{k: r[k] for k in ("month", "incidents", "mom_pct_change",
                                                 "same_month_last_year", "yoy_pct_change")}
                              for r in rows]
        self.assertEqual(strip(api), strip(expected))
        self.assertFalse(any(r["partial"] for r in api))

    def test_day_of_week(self):
        api = {r["dow"]: r for r in client.get("/api/stats/day-of-week").json()}
        for r in notebook_query("avg_per_day_of_week"):
            for key in ("day_of_week", "days", "avg_incidents", "min_incidents", "max_incidents"):
                self.assertEqual(api[r["dow"]][key], r[key], f"{r['day_of_week']} {key}")

    def test_arrest_rate_by_category(self):
        self.assertEqual(client.get("/api/stats/arrest-rate-by-category").json(),
                         notebook_query("arrest_rate_by_category"))

    def test_community_area_domestic_share(self):
        self.assertEqual(client.get("/api/stats/community-area-domestic-share").json(),
                         notebook_query("community_areas_domestic_share"))


class FilterTest(unittest.TestCase):
    def count(self, **params):
        return client.get("/api/stats/summary", params=params).json()["incidents"]

    def test_district(self):
        expected = raw("SELECT COUNT(*) n FROM incidents WHERE district_id = 11")[0]["n"]
        self.assertEqual(self.count(district=11), expected)

    def test_category(self):
        expected = raw("""SELECT COUNT(*) n FROM incidents JOIN incident_types USING (iucr)
                          WHERE category_id = 5""")[0]["n"]
        self.assertEqual(self.count(category=5), expected)

    def test_end_date_is_inclusive(self):
        expected = raw("""SELECT COUNT(*) n FROM incidents
                          WHERE occurred_at >= '2025-03-01' AND occurred_at < '2025-04-01'""")[0]["n"]
        self.assertEqual(self.count(start="2025-03-01", end="2025-03-31"), expected)

    def test_flags_combine(self):
        expected = raw("""SELECT COUNT(*) n FROM incidents WHERE arrest = 1 AND domestic = 0
                          AND community_area_id = 25""")[0]["n"]
        self.assertEqual(self.count(arrest="true", domestic="false", community_area=25), expected)

    def test_filters_apply_to_aggregates(self):
        hours = client.get("/api/stats/incidents-by-hour", params={"district": 1}).json()
        self.assertEqual(sum(h["incidents"] for h in hours), self.count(district=1))

    def test_every_endpoint_accepts_the_same_filters(self):
        params = {"start": "2025-06-01", "end": "2025-08-31", "category": 5,
                  "district": 12, "arrest": "false", "domestic": "true"}
        for path in ["incidents", "stats/summary", "stats/incidents-by-hour",
                     "stats/top-categories-by-district", "stats/monthly-trend",
                     "stats/day-of-week", "stats/arrest-rate-by-category",
                     "stats/community-area-domestic-share"]:
            self.assertEqual(client.get(f"/api/{path}", params=params).status_code, 200, path)


class SparseFilterTest(unittest.TestCase):
    """District 31 has only a few dozen incidents: most months and days have none."""

    def test_monthly_trend_includes_empty_months(self):
        rows = client.get("/api/stats/monthly-trend", params={"district": 31}).json()
        self.assertEqual(len(rows), 24)
        self.assertIn(0, [r["incidents"] for r in rows])

    def test_day_of_week_averages_over_all_calendar_days(self):
        rows = client.get("/api/stats/day-of-week", params={"district": 31}).json()
        total = raw("SELECT COUNT(*) n FROM incidents WHERE district_id = 31")[0]["n"]
        self.assertEqual(sum(r["total_incidents"] for r in rows), total)
        self.assertTrue(all(0 < r["avg_incidents"] < 1 for r in rows if r["total_incidents"]))
        self.assertTrue(all(r["min_incidents"] == 0 for r in rows))

    def test_partial_months_are_flagged(self):
        rows = client.get("/api/stats/monthly-trend",
                          params={"start": "2025-01-15", "end": "2025-03-10"}).json()
        self.assertEqual([r["month"] for r in rows], ["2025-01", "2025-02", "2025-03"])
        self.assertEqual([r["partial"] for r in rows], [True, False, True])


class IncidentsTest(unittest.TestCase):
    def test_pagination(self):
        first = client.get("/api/incidents", params={"page_size": 10}).json()
        second = client.get("/api/incidents", params={"page_size": 10, "page": 2}).json()
        self.assertEqual(first["total"], raw("SELECT COUNT(*) n FROM incidents")[0]["n"])
        self.assertEqual(len(first["items"]), 10)
        self.assertTrue(first["items"][-1]["occurred_at"] >= second["items"][0]["occurred_at"])
        ids = {r["incident_id"] for r in first["items"]} & {r["incident_id"] for r in second["items"]}
        self.assertFalse(ids)

    def test_rows_match_filters(self):
        data = client.get("/api/incidents", params={"district": 11, "arrest": "true",
                                                    "page_size": 50}).json()
        self.assertTrue(all(r["district"] == 11 and r["arrest"] for r in data["items"]))


class ValidationTest(unittest.TestCase):
    def test_rejects_bad_input(self):
        for params in [{"start": "2026-02-01", "end": "2026-01-01"}, {"district": "1 OR 1=1"},
                       {"page_size": 1000}, {"page": 0}, {"start": "yesterday"}]:
            self.assertEqual(client.get("/api/incidents", params=params).status_code, 422, params)

    def test_frontend_is_served(self):
        r = client.get("/")
        self.assertEqual(r.status_code, 200)
        self.assertIn("Chicago Crime Explorer", r.text)


if __name__ == "__main__":
    unittest.main()
