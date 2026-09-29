# Schedule Builder

A command-line tool that turns a list of Rutgers courses into every possible **conflict-free
weekly schedule** and then ranks them by the preferences you choose: fewer days on campus,
fewer idle gaps between classes, later mornings, or fewer campus switches. The search is a
**backtracking algorithm written from scratch**, run over real section data from the Rutgers
Schedule of Classes.

**Stack:** Python, standard library only (no dependencies)

```text
$ python -m schedule_builder 198:112 198:205 640:151
```

## The data: real Rutgers sections

- **Source:** Rutgers' public Schedule of Classes API,
  `https://classes.rutgers.edu/soc/api/courses.json` (no authentication needed).
- **Scope:** New Brunswick, **Fall 2026**, the most recent term published. Spring 2027 wasn't
  available yet when the data was pulled.
- **Subjects:** Computer Science (198) and Mathematics (640): **148 courses and 1,110 sections**.
- **Retrieved:** 2026-09-29, with [`scripts/fetch_sections.py`](scripts/fetch_sections.py).
  The saved copy is [`data/sections_fall2026.json`](data/sections_fall2026.json).
- **Fields kept:** course code and title, section number, registration index number,
  instructor, eligibility restriction (such as "1ST YEAR ONLY"), open or closed status, and
  each meeting's type (lecture, recitation, lab), day, start and end time, campus, and
  building and room.

**This is a snapshot.** Course offerings change every term, and sections, rooms and
instructors can change within a term. Open/closed status changes constantly, and the
saved status is only accurate for the retrieval time. To refresh the data, or to fetch
another term or subject, run:

```bash
python scripts/fetch_sections.py --year 2027 --term 1 --subjects 198 640 750
```

**How the fetch works:** the API ignores a subject filter and always returns the whole
New Brunswick catalog (4,394 courses, about 21 MB). So the script downloads it once and
keeps only the requested subjects and fields.

**Two data details matter for scheduling:**

- **Unscheduled sections:** 412 of the 1,110 sections have no scheduled meeting time.
  They are independent study, research and graduate thesis sections. Having no time
  slot, they can never conflict with anything.
- **Session dates:** no section here has separate session dates, so every section runs
  the whole term. A weekly time overlap is therefore always a real conflict.

## How conflict detection works

[`schedule_builder/conflicts.py`](schedule_builder/conflicts.py)

A section is a set of weekly meetings. For example, 198:205 section 01 has a Monday
lecture, a Thursday lecture and a Tuesday recitation.

- **Meetings:** two meetings conflict if they are on the **same day** and their time
  ranges overlap: `a.start < b.end and b.start < a.end`.
- **Back-to-back classes:** times are treated as half-open intervals, so a class ending at
  1:30pm and another starting at 1:30pm don't conflict.
- **Sections:** two sections conflict if **any** meeting of one overlaps **any** meeting of
  the other. A recitation clash counts even when the lectures fit.
- **No time slot:** meetings without a scheduled time never conflict.

## Backtracking vs. brute force

[`schedule_builder/search.py`](schedule_builder/search.py)

The problem: pick one section per course so that no two chosen sections conflict, and find
**every** way to do it.

**Brute force** generates every combination (37 × 11 × 36 = 14,652 for the example below)
and then checks each complete combination for conflicts. Suppose Data Structures section 01
clashes with Calculus section 05. Brute force still builds all 11 schedules that contain
that pair, one for each Discrete Structures section, and rejects each one separately.

**Backtracking** builds a schedule one course at a time:

1. Choose a section for the first course.
2. For the next course, try each section **against the sections already chosen**.
3. **If it conflicts, skip it immediately.** That one check discards every schedule that
   would have started with this partial schedule. This is the *prune*.
4. If it fits, add it and move to the next course, which is the recursive step.
5. After exploring that branch, remove the section again and try the next candidate. This
   undo step is the "back" in backtracking.
6. A schedule that reaches the last course is complete and conflict-free by construction,
   so it never needs a final check.

The search keeps its own statistics. Each prune records how many complete combinations it
eliminated, so for every run:

> schedules found + combinations eliminated by pruning = total combinations

The tests check this identity, and they check that backtracking finds exactly the same
schedules as brute force.

### How much it prunes on this data

Measured on the Fall 2026 data. Each time is the best of several runs; the 5-course row is
a single run.

| Courses requested | Sections per course | All combinations (brute force) | Conflict-free | Partial schedules backtracking visits | Combinations never generated | Conflict checks: backtracking vs. brute force | Time: backtracking vs. brute force |
|---|---|---:|---:|---:|---:|---|---|
| 198:112, 198:205, 640:151 | 37 × 11 × 36 | **14,652** | 5,889 (40%) | **6,195** | 8,763 (60%) | 17,981 vs. 32,226 (1.8× fewer) | 24 ms vs. 42 ms (1.7×) |
| + 640:250 | 37 × 11 × 36 × 14 | 205,128 | 57,353 (28%) | 63,548 | 147,775 (72%) | 240,653 vs. 690,598 (2.9×) | 242 ms vs. 814 ms (3.4×) |
| + 198:211 | 37 × 11 × 11 × 36 × 14 | 2,256,408 | 283,945 (13%) | 320,759 | 1,972,463 (87%) | 1.8M vs. 9.4M (5.1×) | 2.0 s vs. 11.5 s (5.9×) |

**The three-course example:**

- Brute force builds all 14,652 combinations.
- Backtracking visits 6,195 partial and complete schedules. 5,889 of them are the final
  answers, so only 306 are intermediate steps.
- It never generates the other 8,763 combinations.

**The trend:** each additional course multiplies the brute-force space, while the share of
conflict-free schedules shrinks: from 40% with three courses to 13% with five. So more of
the space can be pruned early, and the advantage grows with each course. The limit is the
output itself: backtracking still has to produce every valid schedule, so when most
combinations are valid there is little to prune.

**Course order:** I also tested branching on the course with the fewest sections first, a
common heuristic. On this data it didn't reduce the work consistently, so the search uses
the courses in the order given.

## Ranking

[`schedule_builder/ranking.py`](schedule_builder/ranking.py)

Every conflict-free schedule is scored using only its real days, times and campuses:

| Criterion (`--prefer` name) | Measures | Better is |
|---|---|---|
| `days` | distinct days with an in-person class | fewer |
| `gaps` | idle minutes between consecutive classes on the same day, summed over the week | fewer |
| `late-start` | the average, over class days, of each day's first start time | later |
| `switches` | back-to-back classes on the same day held on *different* campuses | fewer |

Rutgers–New Brunswick classes are spread over five campuses connected by bus, so a Busch
class followed by a Cook/Douglass class is a real problem. `switches` is optional; it
counts campus changes and doesn't assume any travel time.

**How schedules are sorted:** the ranking is *lexicographic*. Schedules are sorted by your
first criterion, ties are broken by the second, and so on. `--prefer switches days` means
"minimize campus switches; among those, fewest days". The default order is `days`, `gaps`,
`late-start`, with whole-number criteria first so the continuous one only breaks ties.

**Grouping:** many sections of a course share the same lecture and differ only in room or
recitation instructor. Schedules with identical days, times and campuses are therefore
grouped. The best one is shown, and the interchangeable sections are listed underneath.
For the example, the 5,889 conflict-free schedules collapse into 1,342 distinct
timetables.

## Example runs

These are real outputs on the Fall 2026 data.

**1. Data Structures, Discrete Structures I, Calculus I, with the default ranking**
(fewest days, then fewest gaps, then latest start):

```text
$ python -m schedule_builder 198:112 198:205 640:151 -n 1
5,889 conflict-free schedules found, 1,342 distinct timetables (schedules that differ only in room or recitation instructor are grouped). Top 1:

#1 of 1,342  |  3 days on campus  |  3.3 h of gaps  |  5 campus switches  |  avg first class 9:53am
   198:112 sec 39  (index 11463, CLOSED)  Data Structures  (AMIEL, DAVID)
   198:205 sec 03  (index 11517, open)  Introduction To Discrete Structures I  (HAMIDI)
   640:151 sec 23  (index 12954, open)  Calculus I For Mathematical And Physicalsciences  (TABANLI, SHEILA; GUYETT, RILEY)
   Mon  10:20am-11:40am 640:151 LEC    Douglas/Cook LOR-020
        12:10pm-1:30pm  198:205 LEC    Busch HLL-114
         2:00pm-3:20pm  198:112 LEC    Busch ARC-103
   Wed  10:35am-11:30am 198:205 RECIT  College Avenue MU-213
        12:10pm-1:30pm  640:151 RECIT  Douglas/Cook HCK-210
         2:00pm-3:20pm  198:112 LEC    Busch ARC-103
   Thu   8:45am-9:40am  198:112 RECIT  Livingston BE-003
        10:20am-11:40am 640:151 LEC    Douglas/Cook LOR-020
        12:10pm-1:30pm  198:205 LEC    Busch HLL-114
   Same days, times and campuses also with:
     198:205: sec 05 (index 11519, CLOSED), sec 06 (index 11520, CLOSED)
```

Everything fits into three days, but it takes **five campus switches**, including Cook to
Busch with 30 minutes between classes.

**2. The same courses, minimizing campus switches first:**

```text
$ python -m schedule_builder 198:112 198:205 640:151 --prefer switches -n 1
#1 of 1,342  |  5 days on campus  |  4.1 h of gaps  |  0 campus switches  |  avg first class 12:57pm
   198:112 sec 43  (index 11467, open)  Data Structures  (AMIEL, DAVID)
   198:205 sec 02  (index 11516, open)  Introduction To Discrete Structures I  (HAMIDI)
   640:151 sec 25  (index 12956, CLOSED)  Calculus I For Mathematical And Physicalsciences  (ULLMAN, PETER; MARTIN)
   Mon  12:10pm-1:30pm  198:205 LEC    Busch HLL-114
         2:00pm-3:20pm  198:112 LEC    Busch ARC-103
   Tue   3:50pm-5:10pm  640:151 LEC    Busch PH-115
         5:55pm-6:50pm  198:205 RECIT  Busch BME-102
   Wed   2:00pm-3:20pm  198:112 LEC    Busch ARC-103
         3:50pm-5:10pm  640:151 RECIT  Busch SEC-220
   Thu  12:10pm-1:30pm  198:205 LEC    Busch HLL-114
         3:50pm-5:10pm  640:151 LEC    Busch PH-115
   Fri  10:35am-11:30am 198:112 RECIT  Livingston TIL-123
```

Every day is on a single campus, almost all of it Busch, and no class starts before
10:35am. The trade-off is five days on campus instead of three.

**3. Computer Architecture, Algorithms, Linear Algebra, open sections only, latest start
first:**

```text
$ python -m schedule_builder 198:211 198:344 640:250 --open-only --prefer late-start -n 1
  198:211 Computer Architecture: 7 open sections
  198:344 Design And Analysis Of Computer Algorithms: 3 open sections
  640:250 Intro Linear Algebra: 8 open sections

111 conflict-free schedules found, 111 distinct timetables (schedules that differ only in room or recitation instructor are grouped). Top 1:

#1 of 111  |  5 days on campus  |  1.8 h of gaps  |  0 campus switches  |  avg first class 6:27pm
   198:211 sec 02  (index 11549, open)  Computer Architecture  (FIRNER)
   198:344 sec 06  (index 11596, open)  Design And Analysis Of Computer Algorithms  (SZEGEDY, MARIO)
   640:250 sec 13  (index 13063, open)  Intro Linear Algebra  (KRIVENTSOV; MATOS)
   Mon   5:40pm-7:00pm  198:344 LEC    Livingston TIL-254
         7:30pm-8:50pm  640:250 LEC    Livingston LSH-B267
         9:35pm-10:30pm 198:344 RECIT  Livingston BE-250
   Tue   7:30pm-8:50pm  198:211 LEC    Busch SEC-111
   Wed   5:40pm-7:00pm  198:344 LEC    Livingston TIL-254
         7:30pm-8:50pm  640:250 LEC    Livingston LSH-B267
   Thu   7:30pm-8:50pm  198:211 LEC    Busch SEC-111
   Fri   5:55pm-6:50pm  198:211 RECIT  Busch SEC-207
```

For a student who can't do mornings, there is a schedule made entirely of real evening
sections that were open when the data was pulled.

## Running it

Only the Python standard library is needed.

```bash
python -m schedule_builder 198:112 198:205 640:151             # top 3, default ranking
python -m schedule_builder 198:112 198:205 640:151 -n 5 --prefer late-start gaps
python -m schedule_builder 198:112 198:205 640:151 --open-only # skip closed sections
python -m schedule_builder 198:112 198:205 640:151 --stats     # backtracking vs. brute force
python -m unittest discover tests                              # 18 tests
```

**Options:**

- **Course codes:** both `198:112` and `01:198:112` work.
- **`--prefer`:** takes criteria in priority order. Any default criteria you don't list are
  appended as tiebreakers.
- **Closed sections:** included by default and marked `CLOSED`, because sections reopen
  as other students drop, and the saved status is already out of date. `--open-only`
  removes them.

## Project layout

```
schedule-builder/
├── data/sections_fall2026.json   # Fall 2026 CS + Math sections (from the Rutgers SOC API)
├── scripts/fetch_sections.py     # downloads and trims the catalog
├── schedule_builder/
│   ├── data.py                   # Meeting, Section, Catalog
│   ├── conflicts.py              # meeting and section overlap checks
│   ├── search.py                 # backtracking (and brute force for comparison)
│   ├── ranking.py                # metrics, lexicographic ranking, grouping
│   └── cli.py                    # command-line interface
└── tests/test_schedule_builder.py
```
