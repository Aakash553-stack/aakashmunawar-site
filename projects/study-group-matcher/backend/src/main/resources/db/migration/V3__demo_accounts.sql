-- Demo accounts form a separate sandbox so recruiters can try the app without
-- signing up. The API never shows demo accounts or their groups to real
-- students, and never shows real students to demo accounts. Demo data is
-- (re)created at startup by DemoSeeder.
ALTER TABLE students ADD COLUMN demo BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX idx_students_demo ON students (demo);
