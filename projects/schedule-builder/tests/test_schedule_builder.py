"""Run with:  python -m unittest discover tests"""

import unittest

from schedule_builder.conflicts import meetings_overlap, sections_conflict
from schedule_builder.data import Catalog, Meeting, Section
from schedule_builder.ranking import group_equivalent, metrics, rank
from schedule_builder.search import backtrack, brute_force


def meeting(day, start, end, campus="Busch"):
    h = lambda t: int(t[:2]) * 60 + int(t[3:])
    return Meeting("LEC", day, h(start), h(end), campus, "X-1")


def section(course, number, *meetings, open_=True):
    return Section(course, "T", number, f"{course}-{number}", open_, "", "", tuple(meetings))


class ConflictTest(unittest.TestCase):
    def test_overlap_on_same_day(self):
        self.assertTrue(meetings_overlap(meeting("Mon", "10:00", "11:20"),
                                         meeting("Mon", "11:00", "12:00")))

    def test_back_to_back_is_not_a_conflict(self):
        self.assertFalse(meetings_overlap(meeting("Mon", "10:00", "11:20"),
                                          meeting("Mon", "11:20", "12:00")))

    def test_different_days_do_not_conflict(self):
        self.assertFalse(meetings_overlap(meeting("Mon", "10:00", "11:20"),
                                          meeting("Tue", "10:00", "11:20")))

    def test_containment_is_a_conflict(self):
        self.assertTrue(meetings_overlap(meeting("Wed", "09:00", "12:00"),
                                         meeting("Wed", "10:00", "10:30")))

    def test_unscheduled_meeting_never_conflicts(self):
        unscheduled = Meeting("PROJ-IND", None, None, None, None, None)
        self.assertFalse(meetings_overlap(unscheduled, meeting("Mon", "10:00", "11:00")))

    def test_sections_conflict_if_any_meetings_overlap(self):
        a = section("A", "1", meeting("Mon", "10:00", "11:20"), meeting("Fri", "13:00", "14:00"))
        b = section("B", "1", meeting("Tue", "10:00", "11:20"), meeting("Fri", "13:30", "14:30"))
        self.assertTrue(sections_conflict(a, b))


class SearchTest(unittest.TestCase):
    def toy_courses(self):
        return {
            "A": [section("A", "1", meeting("Mon", "09:00", "10:00")),
                  section("A", "2", meeting("Mon", "11:00", "12:00"))],
            "B": [section("B", "1", meeting("Mon", "09:30", "10:30")),
                  section("B", "2", meeting("Mon", "10:00", "11:00"))],
            "C": [section("C", "1", meeting("Mon", "10:30", "11:30")),
                  section("C", "2", meeting("Tue", "09:00", "10:00"))],
        }

    def test_finds_exactly_the_valid_combinations(self):
        schedules, _ = backtrack(self.toy_courses())
        found = {tuple(s.section for s in sch) for sch in schedules}
        # A1+B1 overlap (9:30 < 10:00); the other three A/B pairs fit
        # (back-to-back is allowed). C1 (Mon 10:30-11:30) overlaps B2 or A2
        # in each of those, so only C2 (Tuesday) completes a schedule.
        expected = {("1", "2", "2"), ("2", "1", "2"), ("2", "2", "2")}
        self.assertEqual(found, expected)

    def test_prunes_before_reaching_complete_combinations(self):
        _, stats = backtrack(self.toy_courses())
        full_tree = 1 + 2 + 4 + 8  # every partial schedule, without pruning
        self.assertLess(stats.nodes_visited, full_tree)
        self.assertGreater(stats.branches_pruned, 0)

    def test_accounting_adds_up(self):
        # Every complete combination is either found or skipped by a prune.
        schedules, stats = backtrack(self.toy_courses())
        self.assertEqual(len(schedules) + stats.combinations_skipped, stats.total_combinations)


class RealDataTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.catalog = Catalog()
        cls.courses = {c: cls.catalog.get(c) for c in ["198:112", "198:205", "640:151"]}
        cls.schedules, cls.stats = backtrack(cls.courses)

    def test_matches_brute_force(self):
        expected, _ = brute_force(self.courses)
        self.assertEqual({frozenset(s) for s in self.schedules}, {frozenset(s) for s in expected})

    def test_every_schedule_is_conflict_free(self):
        for sch in self.schedules:
            for i in range(len(sch)):
                for j in range(i + 1, len(sch)):
                    self.assertFalse(sections_conflict(sch[i], sch[j]))

    def test_one_section_per_course_in_order(self):
        for sch in self.schedules[:100]:
            self.assertEqual([s.course for s in sch], ["198:112", "198:205", "640:151"])

    def test_accounting_adds_up(self):
        self.assertEqual(len(self.schedules) + self.stats.combinations_skipped,
                         self.stats.total_combinations)

    def test_open_only_filter(self):
        self.assertTrue(all(s.open for s in self.catalog.get("198:112", open_only=True)))

    def test_course_code_formats(self):
        self.assertEqual(Catalog.normalize_code("01:198:112"), "198:112")


class RankingTest(unittest.TestCase):
    def test_metrics(self):
        sch = (
            section("A", "1", meeting("Mon", "09:00", "10:00", "Busch"),
                    meeting("Wed", "13:00", "14:00", "Busch")),
            section("B", "1", meeting("Mon", "11:00", "12:00", "Livingston")),
        )
        m = metrics(sch)
        self.assertEqual(m.days_on_campus, 2)
        self.assertEqual(m.gap_minutes, 60)          # Mon 10:00 -> 11:00
        self.assertEqual(m.campus_switches, 1)       # Busch -> Livingston on Mon
        self.assertEqual(m.avg_start, (9 * 60 + 13 * 60) / 2)

    def test_rank_orders_by_first_criterion(self):
        early = (section("A", "1", meeting("Mon", "08:00", "09:00")),)
        late = (section("A", "2", meeting("Mon", "14:00", "15:00")),)
        ranked = rank([early, late], ["late-start"])
        self.assertEqual(ranked[0][0], late)

    def test_grouping_merges_identical_timetables(self):
        a1 = section("A", "1", meeting("Mon", "09:00", "10:00"))
        a2 = section("A", "2", meeting("Mon", "09:00", "10:00"))  # same time, other room
        a3 = section("A", "3", meeting("Tue", "09:00", "10:00"))
        groups = group_equivalent(rank([(a1,), (a2,), (a3,)], ["days"]))
        self.assertEqual(len(groups), 2)
        self.assertEqual(groups[0][2]["A"], [a2])


if __name__ == "__main__":
    unittest.main()
