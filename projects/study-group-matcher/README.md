# Study Group Matcher

A full-stack web app for Rutgers–New Brunswick students.

- **Set up a profile:** sign up, add the courses you're taking this semester, and paint your
  weekly free time on a grid.
- **Get recommendations:** see the best-fitting study groups to join, and classmates without
  a group to start one with. They're ranked by a matching algorithm written from scratch.
- **Manage groups:** create, join and leave groups for your courses.

**Stack:** Java 21, Spring Boot 4.1 (Web MVC, Data JPA, Security), JWT auth, PostgreSQL,
Flyway, JUnit 5. The frontend is plain HTML, CSS and JavaScript.

## Try it

Click **Try the demo** on the login page, or log in by hand:

| Email | Password |
|---|---|
| `recruiter@example.com` | `demo1234` |

**What the demo account starts with:**

- three real courses and weekly free time,
- one study group already joined,
- four ranked matches: two groups to join and two classmates to start a group with.

**What the demo data is:** the demo account's classmates and groups are sample accounts,
all labeled "(demo)". They live in a **separate sandbox**:

- real students never see demo accounts or demo groups, in matches, group lists or joins;
- the demo account never sees real students, so a public login exposes nobody's name or
  free time.

**Resetting:** the demo account works like any other account, so you can edit its courses
and free time, and join, leave or create groups. It can only ever affect the demo sandbox.
[`DemoSeeder`](backend/src/main/java/com/aakashmunawar/studygroups/service/DemoSeeder.java)
deletes and rebuilds the sandbox each time the server starts. The free Render instance
restarts after 15 idle minutes, so most visitors get a fresh demo. The other demo accounts
can't log in.

**Real data otherwise.** The course list is the real Fall 2026 Rutgers Computer Science
(198) and Mathematics (640) catalog: 148 courses from the Rutgers Schedule of Classes, the
same data as [Schedule Builder](../schedule-builder/). Apart from the labeled demo sandbox,
everything in the app is created by the people using it. Randomly generated data appears
only in the test and benchmark code.

## Architecture

```
Browser ── study.aakashmunawar.com (Vercel, static files: frontend/)
   │  fetch() with "Authorization: Bearer <JWT>"   (CORS: only that origin)
   ▼
Spring Boot API (Render, Docker, backend/)
   │  Spring Security: stateless JWT (HS256), BCrypt passwords
   │  REST controllers → services → matching algorithm (pure Java)
   ▼
PostgreSQL (Neon)        ← schema managed by Flyway migrations
```

**Why the frontend and backend are hosted separately:** a free Render instance sleeps after
15 minutes idle, and waking takes about a minute.

- **If Spring Boot also served the page,** a visitor would see a blank tab for that minute.
- **With the page on Vercel's CDN,** it loads instantly and shows "waking up the server"
  while the first API call waits.

Calls go straight to the API, with CORS restricted to the frontend's origin. They don't
route through a Vercel rewrite, because a proxied request could time out during a cold
start.

## Database schema

Flyway migrations: [`V1__schema.sql`](backend/src/main/resources/db/migration/V1__schema.sql)
and [`V2__seed_rutgers_courses.sql`](backend/src/main/resources/db/migration/V2__seed_rutgers_courses.sql).

```
students ─┬─< student_courses >── courses (148 real Rutgers courses)
          │                           │
          ├─< availability_blocks     │ 1
          │                           │
          ├─< group_members >── study_groups
          └─< (owner) ─────────────────┘
```

| Table | Columns | Notes |
|---|---|---|
| `students` | id, email (unique, lower-cased), password_hash (BCrypt), display_name, created_at, demo | Other students only ever see `display_name`; emails aren't returned. `demo` marks the sandbox accounts (migration V3), and `@example.com` sign-ups are reserved for them. |
| `courses` | id, code (unique, e.g. `198:112`), title | Seeded by migration; read-only. |
| `student_courses` | student_id, course_id (composite PK) | The courses a student takes. Indexed by course, to find classmates. |
| `availability_blocks` | id, student_id, day_of_week (1–7, ISO), start_minute, end_minute | Weekly free time as half-open `[start, end)` minutes. CHECK constraints enforce `0 ≤ start < end ≤ 1440`. Stored merged: overlapping blocks are combined on save. |
| `study_groups` | id, course_id, name, description, capacity (2–12), owner_id, created_at | Each group belongs to exactly one course. |
| `group_members` | group_id, student_id (composite PK), joined_at | `joined_at` decides who becomes organizer if the owner leaves. |

**Rules the API enforces:**

- You must take a course to create or join a group for it.
- You can be in at most one group per course.
- Groups can't exceed their capacity. Joining locks the group's row, so two students can't
  both take the last seat.
- You can't drop a course while you're in its group.
- When the last member leaves, the group is deleted.

## The matching algorithm

Code: [`matching/WeeklySchedule.java`](backend/src/main/java/com/aakashmunawar/studygroups/matching/WeeklySchedule.java)
and [`matching/Matcher.java`](backend/src/main/java/com/aakashmunawar/studygroups/matching/Matcher.java).
It's plain Java with no Spring, database or library code, so it's tested on its own.

### Representing free time

A `WeeklySchedule` holds seven lists, one per day. Each list contains time intervals that
are **sorted and don't overlap**.

- **Building one:** `WeeklySchedule.of(blocks)` sorts each day and merges overlapping or
  touching blocks, so Mon 9:00–10:00 plus Mon 9:30–11:00 becomes Mon 9:00–11:00. This takes
  O(n log n), because of the sort.
- **Why it matters:** because each day's list is sorted and never overlaps, intersecting two
  schedules is a **two-pointer merge**. Keep one pointer in each list. Emit the overlap of
  the two current intervals, then move whichever interval ends first, since it can't
  overlap anything later in the other list.

  This is **O(a + b)** for lists of *a* and *b* intervals, instead of O(a·b) for comparing
  every pair.

### Recommending

For a student (the "seeker"):

1. **Candidates must share a course.**
   - A **group** is a candidate only if it's for one of the seeker's courses, isn't full,
     and the seeker isn't already in it.
   - A **classmate** is a candidate for each course they share with the seeker where
     *neither* of them has a group yet.
   - Courses where the seeker already has a group are skipped entirely.
2. **Compute the time they're free together.**
   - For a group, that's the time when *every* member is free: the intersection of all
     members' schedules, folded one member at a time. Then intersect it with the seeker's.
   - For a classmate, it's their schedule intersected with the seeker's.
3. **Keep only usable blocks.** Only overlapping blocks of at least **60 contiguous minutes**
   count. Twenty spare minutes between classes isn't a study session. A candidate with no
   usable block is dropped.
4. **Rank**, comparing field by field until one differs:
   1. usable minutes together per week (more is better),
   2. longest single block (one 2-hour window beats two 1-hour windows),
   3. number of shared courses (a classmate in two of your courses ranks higher),
   4. number of days with a usable block (more scheduling options),
   5. existing groups before classmates, then by id, so the order is stable.

**Why field-by-field ranking instead of a weighted score:** each rule can be stated in one
line and checked directly by a test, and there are no weights that would need tuning
without real usage data to tune them on.

### Complexity

Let *k* be the number of free-time intervals per person, typically under 15 after merging,
*G* the candidate groups with *m* members each, and *P* the candidate classmates.

- **Group common time:** O(m·k) per group, folding pairwise intersections.
- **Seeker vs. each candidate:** O(k) per intersection.
- **Total:** **O(Σ m·k + P·k + R log R)**, where R is the number of candidates that pass the
  filters. It's linear in the number of candidates, plus the final sort.

**Database load:** the service fetches everything in **a fixed number of queries**, whatever
the number of candidates:

1. groups for the seeker's courses, with their members,
2. who is enrolled in those courses,
3. who is already in a group for them,
4. everyone's free time.

### Correctness testing

The tests are in [`src/test/java/.../matching`](backend/src/test/java/com/aakashmunawar/studygroups/matching),
and they check the algorithm the same way Schedule Builder's tests check its backtracking
against brute force.

- **The brute-force model:** a deliberately naive reference, `MinuteGrid`, stores one
  true/false value for each of the 10,080 minutes in a week. Intersection is a
  minute-by-minute AND, and it shares no code with `WeeklySchedule`.
- **Randomized comparisons** (fixed seeds, so they're reproducible):
  - **3,000** random pairs of schedules: normalizing, intersecting, the 60-minute filter
    and commutativity all match the grid exactly.
  - **500** random groups of 1–6 members: the group common time matches.
  - **1,000** random populations of groups and classmates, with random session minimums:
    **1,575** recommendations are compared to a naive re-implementation of the whole
    recommender, in the **same rank order**.
- **Rule-by-rule scenario tests (15):**
  - shared course required,
  - full groups and your own groups skipped,
  - courses where you already have a group skipped,
  - a member with no free time means no common time,
  - 59-minute overlaps don't count and 60-minute ones do,
  - each ranking tie-break in turn.
- **Checking the tests themselves:** reversing the primary ranking field makes 3 tests
  fail, at the unit, randomized and end-to-end API levels. So the tests do detect a broken
  ranking.

**API tests** ([`ApiTest`](backend/src/test/java/com/aakashmunawar/studygroups/api/ApiTest.java),
11 tests, and [`DemoTest`](backend/src/test/java/com/aakashmunawar/studygroups/api/DemoTest.java),
5 tests) make real HTTP requests through Spring Security, with real JWTs, against H2:

- sign-up and login, including duplicate emails, validation, and a wrong password and an
  unknown email getting identical answers,
- missing, invalid and forged tokens,
- the real course seed,
- availability normalization,
- every group rule,
- a full end-to-end matching scenario,
- no emails leaking to other students,
- CORS,
- the demo account: its one-click login, its exact starting ranking, isolation from real
  students in both directions, and a reset restoring the sandbox without touching real
  accounts.

**All 41 tests pass.**

### Performance (measured)

[`MatcherBenchmark`](backend/src/test/java/com/aakashmunawar/studygroups/matching/MatcherBenchmark.java)
builds a **synthetic** population on the real 148-course catalog:

- each student takes 4–5 courses and has 6–13 weekly free blocks,
- about 30% of students are in groups of 2–5.

It then times only the matching step, for 1,000 random seekers, on an Apple Silicon laptop:

| Students | Candidates per seeker | Median | 95th percentile |
|---:|---:|---:|---:|
| 1,000 | 303 | 0.29 ms | 0.44 ms |
| 5,000 | 1,517 | 1.33 ms | 1.89 ms |
| 20,000 | 6,032 | 5.88 ms | 8.31 ms |

The time grows linearly with the number of candidates, as the complexity analysis
predicts. These numbers **exclude the database queries**, which dominate a real request.

## API

Requests and responses are JSON. Authenticated endpoints need `Authorization: Bearer <token>`.
Errors come back as RFC 9457 problem details (`{"status": 409, "detail": "This group is full"}`).

| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | `/api/auth/signup` | – | `{email, password (8–72 chars), displayName}` → `{token, student}` |
| POST | `/api/auth/login` | – | `{email, password}` → `{token, student}` |
| GET | `/api/courses?q=` | – | The course catalog, optionally searched |
| GET | `/api/me` | ✓ | Your profile: courses and free time |
| PUT | `/api/me/courses` | ✓ | `{courseIds: [...]}` (up to 10) |
| PUT | `/api/me/availability` | ✓ | `{blocks: [{day, start, end}]}` (minutes; merged on save) |
| GET | `/api/matches?limit=` | ✓ | Ranked recommendations with the overlapping time slots |
| GET | `/api/groups?courseId=` | ✓ | Groups for a course |
| GET | `/api/groups/mine` | ✓ | Your groups |
| GET | `/api/groups/{id}` | ✓ | Members and the time all members are free |
| POST | `/api/groups` | ✓ | `{courseId, name, description, capacity}` |
| POST | `/api/groups/{id}/join` | ✓ | Join |
| POST | `/api/groups/{id}/leave` | ✓ | Leave |
| GET | `/actuator/health` | – | Health check |

## Running it locally

You only need Java 21. The Maven Wrapper downloads Maven, and local runs use an in-memory
H2 database in PostgreSQL mode, so no database setup is needed.

```bash
cd projects/study-group-matcher/backend
./mvnw test                      # all 41 tests
./mvnw spring-boot:run           # API on http://localhost:8080

# in another terminal
cd projects/study-group-matcher/frontend
python3 -m http.server 5500      # open http://localhost:5500
```

On `localhost`, the frontend automatically calls `http://localhost:8080`, and the default
CORS setting allows `localhost:5500`. The in-memory database resets on restart.

To time the matcher:

```bash
./mvnw -q test-compile
java -cp target/classes:target/test-classes \
    com.aakashmunawar.studygroups.matching.MatcherBenchmark 5000
```

**To run against a real PostgreSQL database locally,** start it with the `prod` profile and
set `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`, `JWT_SECRET` (at least 32
bytes) and `CORS_ORIGINS`. Flyway creates the schema on first start.

## Deployment

- **Backend: Render.** The [`render.yaml`](../../render.yaml) Blueprint at the repo root
  defines a free Docker web service built from [`backend/Dockerfile`](backend/Dockerfile):
  a multi-stage build, run as a non-root user, with JVM settings for a 512 MB instance.
  Render generates the JWT secret, and only changes under `backend/` trigger a rebuild.
- **Database: Neon,** a free PostgreSQL instance. The Blueprint asks for its connection
  details (`DB_HOST`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`) when it's first applied, so the
  credentials live only in Render's environment settings, never in the repo. The app
  connects with `sslmode=require`, and Flyway creates the schema on first start.
- **Frontend: Vercel.** It's a static site with `frontend/` as the root directory, served
  at `study.aakashmunawar.com`. [`frontend/config.js`](frontend/config.js) holds the API
  address.

**Why Render for the API:** Railway's free plan now requires a credit card and gives $1 of
usage and 0.5 GB of RAM per month, which is tight for a JVM. Render's free web service
needs no card.

**Why Neon for the database:** Render's free PostgreSQL is deleted 30 days after it's
created. Neon's free plan ($0, no card, 0.5 GB storage, 100 compute-hours a month) has no
such limit.

**Free-tier behavior:**

- The API sleeps after 15 minutes without traffic, and the first request after that takes
  about a minute. The frontend explains this while it waits.
- Neon pauses compute after 5 idle minutes and resumes on the next query, adding a short
  delay.

## Not built (yet)

- Messaging between students, and invitations to a group.
- Email verification and password reset.
- Rate limiting on login.
- Meeting times chosen within a group: the app shows the group's common free time, but
  doesn't book a slot.
