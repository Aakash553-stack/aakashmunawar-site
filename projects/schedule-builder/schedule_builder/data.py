"""Load the saved Schedule of Classes data into small immutable objects."""

import json
from dataclasses import dataclass
from functools import cached_property
from pathlib import Path

DATA_PATH = Path(__file__).resolve().parent.parent / "data" / "sections_fall2026.json"

DAY_ORDER = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]


@dataclass(frozen=True)
class Meeting:
    type: str            # LEC, RECIT, LAB, ...
    day: str | None      # "Mon".."Sun", or None if not scheduled
    start: int | None    # minutes after midnight
    end: int | None
    campus: str | None   # "Busch", "Livingston", "College Avenue", "Douglas/Cook", "Online"
    location: str | None  # building code and room, e.g. "HLL-114"

    @property
    def is_scheduled(self):
        return self.day is not None


@dataclass(frozen=True)
class Section:
    course: str          # "198:112"
    title: str
    section: str         # "01"
    index: str           # registration index number
    open: bool           # enrollment status when the data was retrieved
    instructors: str
    eligibility: str     # e.g. "1ST YEAR ONLY"; empty if unrestricted
    meetings: tuple

    @cached_property
    def scheduled_meetings(self):
        return tuple(m for m in self.meetings if m.is_scheduled)

    def __str__(self):
        return f"{self.course} sec {self.section}"


class Catalog:
    def __init__(self, path=DATA_PATH):
        raw = json.loads(Path(path).read_text())
        self.term = raw["term"]
        self.retrieved_at = raw["retrieved_at"]
        self.titles = {}
        self.sections = {}
        for c in raw["courses"]:
            self.titles[c["code"]] = c["title"]
            self.sections[c["code"]] = [
                Section(c["code"], c["title"], s["section"], s["index"], s["open"],
                        s["instructors"], s["eligibility"],
                        tuple(Meeting(**m) for m in s["meetings"]))
                for s in c["sections"]
            ]

    @staticmethod
    def normalize_code(code):
        """Accept '198:112' or '01:198:112'."""
        parts = code.strip().split(":")
        if len(parts) == 3:
            parts = parts[1:]
        if len(parts) != 2 or not all(p.isdigit() for p in parts):
            raise KeyError(f'"{code}" is not a course code like 198:112')
        return f"{parts[0]}:{parts[1]}"

    def get(self, code, open_only=False):
        key = self.normalize_code(code)
        if key not in self.sections:
            raise KeyError(f"{key} is not offered in {self.term} (in the downloaded subjects)")
        sections = self.sections[key]
        return [s for s in sections if s.open] if open_only else sections
