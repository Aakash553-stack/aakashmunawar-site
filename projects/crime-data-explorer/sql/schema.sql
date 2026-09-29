-- Crime Data Explorer: normalized SQLite schema for Chicago PD incident data.
--
-- Reference tables are loaded first (from the city's own lookup datasets where
-- they exist); incidents reference them by foreign key.

PRAGMA foreign_keys = ON;

DROP TABLE IF EXISTS incidents;
DROP TABLE IF EXISTS incident_types;
DROP TABLE IF EXISTS crime_categories;
DROP TABLE IF EXISTS beats;
DROP TABLE IF EXISTS districts;
DROP TABLE IF EXISTS community_areas;
DROP TABLE IF EXISTS location_types;

-- ---------------------------------------------------------------------------
-- What happened
-- ---------------------------------------------------------------------------

-- Top-level category, e.g. THEFT, BATTERY (source: primary_type).
CREATE TABLE crime_categories (
    category_id   INTEGER PRIMARY KEY,
    name          TEXT NOT NULL UNIQUE
);

-- One row per Illinois Uniform Crime Reporting (IUCR) code. In the source data
-- the IUCR code fully determines primary_type, description and fbi_code, so
-- those attributes live here rather than being repeated on every incident.
CREATE TABLE incident_types (
    iucr            TEXT PRIMARY KEY,           -- e.g. '0486'
    category_id     INTEGER NOT NULL REFERENCES crime_categories(category_id),
    description     TEXT NOT NULL,              -- e.g. 'DOMESTIC BATTERY SIMPLE'
    fbi_code        TEXT,                       -- e.g. '08B'
    is_index_crime  INTEGER NOT NULL CHECK (is_index_crime IN (0, 1)),
    is_active       INTEGER NOT NULL CHECK (is_active IN (0, 1))
);

-- ---------------------------------------------------------------------------
-- Where it happened
-- ---------------------------------------------------------------------------

-- Police districts. District 61 appears in the incident data but not in the
-- city's district boundary file, so its name is left NULL.
CREATE TABLE districts (
    district_id   INTEGER PRIMARY KEY,          -- 1..25, 31, 61
    name          TEXT                          -- e.g. '4TH'
);

-- Police beats. home_district_id is the district encoded in the beat number
-- (beat 0421 -> district 4). A handful of incidents are recorded in a
-- different district than their beat's home district, which is why
-- incidents.district_id is stored separately rather than derived.
CREATE TABLE beats (
    beat_id           INTEGER PRIMARY KEY,      -- e.g. 421
    home_district_id  INTEGER NOT NULL REFERENCES districts(district_id)
);

-- Chicago's 77 official community areas.
CREATE TABLE community_areas (
    community_area_id  INTEGER PRIMARY KEY,     -- 1..77
    name               TEXT NOT NULL UNIQUE     -- e.g. 'AUSTIN'
);

-- Kind of place, e.g. STREET, APARTMENT, RESIDENCE (source: location_description).
CREATE TABLE location_types (
    location_type_id  INTEGER PRIMARY KEY,
    name              TEXT NOT NULL UNIQUE
);

-- ---------------------------------------------------------------------------
-- Fact table
-- ---------------------------------------------------------------------------

CREATE TABLE incidents (
    incident_id        INTEGER PRIMARY KEY,     -- source 'id'; case_number is not unique
    case_number        TEXT NOT NULL,           -- multi-victim cases share one
    occurred_at        TEXT NOT NULL,           -- 'YYYY-MM-DD HH:MM:SS', local time
    iucr               TEXT NOT NULL REFERENCES incident_types(iucr),
    location_type_id   INTEGER REFERENCES location_types(location_type_id),
    district_id        INTEGER NOT NULL REFERENCES districts(district_id),
    beat_id            INTEGER NOT NULL REFERENCES beats(beat_id),
    community_area_id  INTEGER REFERENCES community_areas(community_area_id),
    ward               INTEGER,                 -- 1..50; no attributes, so no table
    block              TEXT NOT NULL,           -- block-level address, e.g. '076XX S ESSEX AVE'
    latitude           REAL,
    longitude          REAL,
    arrest             INTEGER NOT NULL CHECK (arrest IN (0, 1)),
    domestic           INTEGER NOT NULL CHECK (domestic IN (0, 1)),
    updated_at         TEXT NOT NULL
);

CREATE INDEX idx_incidents_occurred_at    ON incidents(occurred_at);
CREATE INDEX idx_incidents_iucr           ON incidents(iucr);
CREATE INDEX idx_incidents_district       ON incidents(district_id);
CREATE INDEX idx_incidents_community_area ON incidents(community_area_id);
