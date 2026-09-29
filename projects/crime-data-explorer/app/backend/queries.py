"""The notebook's analytical queries (sql/queries.sql), made filterable.

The SQL text is read from sql/queries.sql, the same file the notebook uses, so
the dashboard and the notebook share one definition of each analysis. Two
mechanical changes are applied when a query is loaded:

1. Filtering. A `filtered` CTE holding the incidents that match the current
   filters is prepended, and each `FROM incidents` becomes `FROM filtered`.
   With no filters, `filtered` is the whole table and the query returns
   exactly what the notebook shows (the tests check this).
2. A few hard-coded constants become parameters, e.g. the notebook's
   `LIMIT 15` becomes `LIMIT :limit`. Each substitution must match exactly
   once, so an edit to queries.sql fails loudly at startup instead of
   silently changing results.
"""

import re
from dataclasses import dataclass
from datetime import date, timedelta
from pathlib import Path

HERE = Path(__file__).resolve().parent
# Vercel's build step copies queries.sql next to this file (see build.py);
# locally it is read from the project's sql/ folder.
QUERY_FILE_CANDIDATES = [
    HERE / "sql" / "queries.sql",
    HERE.parent.parent / "sql" / "queries.sql",
]

# (query name) -> [(regex, replacement)], each applied exactly once.
PARAMETERIZE = {
    "top_categories_by_district": [
        (r"r\.rank <= 3", "r.rank <= :top_n"),
    ],
    "avg_per_day_of_week": [
        # Also return the raw total so averages can be taken over every
        # calendar day in the window, including days with zero matches.
        (r"COUNT\(\*\)(\s+)AS days,", r"COUNT(*)\1AS days,\n    SUM(incidents) AS total_incidents,"),
    ],
    "arrest_rate_by_category": [
        (r"HAVING COUNT\(\*\) >= 1000", "HAVING COUNT(*) >= :min_incidents"),
    ],
    "community_areas_domestic_share": [
        (r"LIMIT 15;", "LIMIT :limit;"),
    ],
}


def read_named_queries(path):
    """Split queries.sql on its `-- name: ...` markers."""
    text = Path(path).read_text()
    blocks = re.split(r"^-- name: ", text, flags=re.M)[1:]
    return {b.split("\n", 1)[0].strip(): b.split("\n", 1)[1] for b in blocks}


def strip_comments(sql):
    return "\n".join(line for line in sql.splitlines()
                     if not line.lstrip().startswith("--")).strip()


def make_filterable(name, sql):
    sql = strip_comments(sql)
    for pattern, replacement in PARAMETERIZE.get(name, []):
        sql, n = re.subn(pattern, replacement, sql)
        if n != 1:
            raise RuntimeError(f"queries.sql changed: {pattern!r} matched {n} times in {name}")

    sql, n = re.subn(r"\bFROM incidents\b", "FROM filtered", sql)
    if n == 0:
        raise RuntimeError(f"queries.sql changed: {name} no longer reads FROM incidents")

    cte = "filtered AS (SELECT * FROM incidents WHERE {where})"
    if re.match(r"WITH\b", sql, flags=re.I):
        return re.sub(r"^WITH\b", "WITH " + cte + ",", sql, count=1, flags=re.I)
    return "WITH " + cte + "\n" + sql


def load_queries():
    for path in QUERY_FILE_CANDIDATES:
        if path.exists():
            return {name: make_filterable(name, sql)
                    for name, sql in read_named_queries(path).items()}
    raise FileNotFoundError(f"queries.sql not found in {[str(p) for p in QUERY_FILE_CANDIDATES]}")


@dataclass
class Filters:
    """Validated filter values. None means "don't filter on this"."""
    start: date | None = None          # inclusive
    end: date | None = None            # inclusive
    category_id: int | None = None
    district_id: int | None = None
    community_area_id: int | None = None
    arrest: bool | None = None
    domestic: bool | None = None

    def where(self, alias=""):
        """SQL WHERE body plus named parameters. Values are never inlined."""
        p = f"{alias}." if alias else ""
        clauses, params = [], {}
        if self.start:
            clauses.append(f"{p}occurred_at >= :f_start")
            params["f_start"] = self.start.isoformat()
        if self.end:
            clauses.append(f"{p}occurred_at < :f_end")  # end is inclusive: < next day
            params["f_end"] = (self.end + timedelta(days=1)).isoformat()
        if self.category_id is not None:
            clauses.append(f"{p}iucr IN (SELECT iucr FROM incident_types "
                           f"WHERE category_id = :f_category)")
            params["f_category"] = self.category_id
        if self.district_id is not None:
            clauses.append(f"{p}district_id = :f_district")
            params["f_district"] = self.district_id
        if self.community_area_id is not None:
            clauses.append(f"{p}community_area_id = :f_area")
            params["f_area"] = self.community_area_id
        if self.arrest is not None:
            clauses.append(f"{p}arrest = :f_arrest")
            params["f_arrest"] = int(self.arrest)
        if self.domestic is not None:
            clauses.append(f"{p}domestic = :f_domestic")
            params["f_domestic"] = int(self.domestic)
        return (" AND ".join(clauses) or "1 = 1"), params
