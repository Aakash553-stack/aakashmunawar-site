-- Crime Data Explorer: analytical queries.
--
-- Each query starts with a "-- name:" line; notebooks/explorer.ipynb loads
-- them by that name. All run against crime.db (see scripts/load_data.py).


-- name: incidents_by_hour
-- When during the day are incidents recorded? Timestamps of exactly 00:00:00
-- and 12:00:00 are counted separately: they are far more common than any
-- other minute and are likely placeholders for "time unknown".
SELECT
    CAST(strftime('%H', occurred_at) AS INTEGER)                   AS hour,
    COUNT(*)                                                       AS incidents,
    SUM(time(occurred_at) IN ('00:00:00', '12:00:00'))             AS placeholder_time,
    COUNT(*) - SUM(time(occurred_at) IN ('00:00:00', '12:00:00'))  AS exact_time
FROM incidents
GROUP BY hour
ORDER BY hour;


-- name: top_categories_by_district
-- The three most common crime categories in each police district, and what
-- share of that district's incidents each one makes up.
WITH district_counts AS (
    SELECT i.district_id, c.name AS category, COUNT(*) AS incidents
    FROM incidents i
    JOIN incident_types t   USING (iucr)
    JOIN crime_categories c USING (category_id)
    GROUP BY i.district_id, c.name
),
ranked AS (
    SELECT
        district_id,
        category,
        incidents,
        ROUND(100.0 * incidents / SUM(incidents) OVER (PARTITION BY district_id), 1) AS pct_of_district,
        ROW_NUMBER() OVER (PARTITION BY district_id ORDER BY incidents DESC)          AS rank
    FROM district_counts
)
SELECT r.district_id, d.name AS district, r.rank, r.category, r.incidents, r.pct_of_district
FROM ranked r
JOIN districts d USING (district_id)
WHERE r.rank <= 3
ORDER BY r.district_id, r.rank;


-- name: monthly_trend
-- Incidents per month with month-over-month and year-over-year change.
WITH monthly AS (
    SELECT strftime('%Y-%m', occurred_at) AS month, COUNT(*) AS incidents
    FROM incidents
    GROUP BY month
)
SELECT
    month,
    incidents,
    ROUND(100.0 * (incidents - LAG(incidents) OVER w) / LAG(incidents) OVER w, 1)
        AS mom_pct_change,
    LAG(incidents, 12) OVER w AS same_month_last_year,
    ROUND(100.0 * (incidents - LAG(incidents, 12) OVER w) / LAG(incidents, 12) OVER w, 1)
        AS yoy_pct_change
FROM monthly
WINDOW w AS (ORDER BY month)
ORDER BY month;


-- name: avg_per_day_of_week
-- Average incidents per calendar day, by day of week. Averaging over dates
-- (rather than summing) corrects for weekdays that occur more often in the
-- two-year window.
WITH daily AS (
    SELECT date(occurred_at) AS day, COUNT(*) AS incidents
    FROM incidents
    GROUP BY day
)
SELECT
    CAST(strftime('%w', day) AS INTEGER) AS dow,  -- 0 = Sunday
    CASE strftime('%w', day)
        WHEN '0' THEN 'Sun' WHEN '1' THEN 'Mon' WHEN '2' THEN 'Tue'
        WHEN '3' THEN 'Wed' WHEN '4' THEN 'Thu' WHEN '5' THEN 'Fri'
        ELSE 'Sat'
    END                        AS day_of_week,
    COUNT(*)                   AS days,
    ROUND(AVG(incidents), 1)   AS avg_incidents,
    MIN(incidents)             AS min_incidents,
    MAX(incidents)             AS max_incidents
FROM daily
GROUP BY dow
ORDER BY dow;


-- name: arrest_rate_by_category
-- Share of incidents that resulted in an arrest, for categories with at least
-- 1,000 incidents in the window.
SELECT
    c.name                               AS category,
    COUNT(*)                             AS incidents,
    SUM(i.arrest)                        AS arrests,
    ROUND(100.0 * SUM(i.arrest) / COUNT(*), 1) AS arrest_rate_pct
FROM incidents i
JOIN incident_types t   USING (iucr)
JOIN crime_categories c USING (category_id)
GROUP BY c.name
HAVING COUNT(*) >= 1000
ORDER BY arrest_rate_pct DESC;


-- name: community_areas_domestic_share
-- The 15 community areas with the most incidents, with the share of those
-- incidents flagged as domestic-related.
SELECT
    ca.name                                        AS community_area,
    COUNT(*)                                       AS incidents,
    SUM(i.domestic)                                AS domestic,
    ROUND(100.0 * SUM(i.domestic) / COUNT(*), 1)   AS domestic_pct
FROM incidents i
JOIN community_areas ca USING (community_area_id)
GROUP BY ca.community_area_id
ORDER BY incidents DESC
LIMIT 15;
