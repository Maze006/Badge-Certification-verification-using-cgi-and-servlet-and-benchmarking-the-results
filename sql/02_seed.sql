-- =====================================================================
--  Verified Digital Skill-Badge Portal  --  Seed data
--
--  Deterministic on purpose: re-running gives identical rows, so the
--  CGI-vs-Servlet benchmark is repeatable across machines.
--
--  Run with:  mysql -u root -p < sql/02_seed.sql
-- =====================================================================
USE badgeportal;

SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE TABLE badges;
TRUNCATE TABLE module_completions;
TRUNCATE TABLE modules;
TRUNCATE TABLE students;
SET FOREIGN_KEY_CHECKS = 1;

-- ---------------------------------------------------------------------
-- Modules -- the six micro-credentials on offer
-- ---------------------------------------------------------------------
INSERT INTO modules (module_id, title, unit, code_prefix) VALUES
  (1, 'HTML5 & CSS3 Essentials',      'Unit 1 - Web Foundations',        'HC'),
  (2, 'JavaScript & DOM Scripting',   'Unit 2 - Client-Side Scripting',  'JS'),
  (3, 'Servlet Fundamentals',         'Unit 3 - Server-Side Java',       'SF'),
  (4, 'JSP & Session Management',     'Unit 3 - Server-Side Java',       'JP'),
  (5, 'CGI & Web Server Architecture','Unit 3 - Server-Side Java',       'CG'),
  (6, 'JDBC Basics',                  'Unit 4 - Database Connectivity',  'JD');

-- ---------------------------------------------------------------------
-- Students -- 24 named (nice for demo screenshots) ...
-- ---------------------------------------------------------------------
INSERT INTO students (student_id, name, email, enrolled_on) VALUES
  ( 1,'Aarav Sharma',      'aarav.sharma@college.edu',      '2026-07-15'),
  ( 2,'Diya Patel',        'diya.patel@college.edu',        '2026-07-15'),
  ( 3,'Rohan Mehta',       'rohan.mehta@college.edu',       '2026-07-15'),
  ( 4,'Ishita Nair',       'ishita.nair@college.edu',       '2026-07-16'),
  ( 5,'Kabir Singh',       'kabir.singh@college.edu',       '2026-07-16'),
  ( 6,'Ananya Rao',        'ananya.rao@college.edu',        '2026-07-16'),
  ( 7,'Vivaan Gupta',      'vivaan.gupta@college.edu',      '2026-07-17'),
  ( 8,'Saanvi Iyer',       'saanvi.iyer@college.edu',       '2026-07-17'),
  ( 9,'Arjun Reddy',       'arjun.reddy@college.edu',       '2026-07-17'),
  (10,'Myra Joshi',        'myra.joshi@college.edu',        '2026-07-18'),
  (11,'Aditya Verma',      'aditya.verma@college.edu',      '2026-07-18'),
  (12,'Kiara Bose',        'kiara.bose@college.edu',        '2026-07-18'),
  (13,'Reyansh Kulkarni',  'reyansh.kulkarni@college.edu',  '2026-07-19'),
  (14,'Aadhya Menon',      'aadhya.menon@college.edu',      '2026-07-19'),
  (15,'Shaurya Das',       'shaurya.das@college.edu',       '2026-07-19'),
  (16,'Pari Chatterjee',   'pari.chatterjee@college.edu',   '2026-07-20'),
  (17,'Atharv Pillai',     'atharv.pillai@college.edu',     '2026-07-20'),
  (18,'Navya Desai',       'navya.desai@college.edu',       '2026-07-20'),
  (19,'Krishna Malhotra',  'krishna.malhotra@college.edu',  '2026-07-21'),
  (20,'Anika Ghosh',       'anika.ghosh@college.edu',       '2026-07-21'),
  (21,'Ayaan Khan',        'ayaan.khan@college.edu',        '2026-07-21'),
  (22,'Riya Banerjee',     'riya.banerjee@college.edu',     '2026-07-22'),
  (23,'Dhruv Trivedi',     'dhruv.trivedi@college.edu',     '2026-07-22'),
  (24,'Tara Krishnan',     'tara.krishnan@college.edu',     '2026-07-22');

-- ... and 36 generated, so the badges table is large enough that the
-- verification lookup is a real index probe rather than a toy scan.
INSERT INTO students (student_id, name, email, enrolled_on)
WITH RECURSIVE seq(n) AS (
  SELECT 25 UNION ALL SELECT n + 1 FROM seq WHERE n < 60
)
SELECT n,
       CONCAT('Student ', LPAD(n, 3, '0')),
       CONCAT('student', LPAD(n, 3, '0'), '@college.edu'),
       DATE_ADD('2026-07-15', INTERVAL (n % 10) DAY)
FROM seq;

-- ---------------------------------------------------------------------
-- Module completions
--
-- Not every student finishes every module -- students whose id is
-- divisible by 7 skip the later modules, so the wallet page has to cope
-- with partial progress. Scores are a deterministic spread across the
-- 55..99 range so the Bronze / Silver / Gold tiers are all exercised.
-- ---------------------------------------------------------------------
INSERT INTO module_completions (student_id, module_id, score, completed_at)
SELECT s.student_id,
       m.module_id,
       55 + ((s.student_id * 17 + m.module_id * 29) % 45),
       TIMESTAMP('2026-08-01') + INTERVAL ((s.student_id * 7 + m.module_id * 11) % 700) HOUR
FROM students s
CROSS JOIN modules m
WHERE NOT (s.student_id % 7 = 0 AND m.module_id > 3);

-- ---------------------------------------------------------------------
SELECT 'students'    AS table_name, COUNT(*) AS rows_seeded FROM students
UNION ALL SELECT 'modules',            COUNT(*) FROM modules
UNION ALL SELECT 'module_completions', COUNT(*) FROM module_completions
UNION ALL SELECT 'badges (issued later by the servlet)', COUNT(*) FROM badges;
