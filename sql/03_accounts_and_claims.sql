-- =====================================================================
--  Verified Digital Skill-Badge Portal
--  Migration 03 -- accounts, badge claims, and the admin review trail
--
--  Run with:  mysql -u root -p < sql/03_accounts_and_claims.sql
--
--  THIS IS A MIGRATION, NOT A REBUILD.
--
--  It deliberately does not DROP DATABASE. The badges already issued
--  carry verification codes that appear in REPORT.md, in screenshots and
--  in the benchmark data; dropping them would invalidate the written
--  work. Everything here is CREATE TABLE or ALTER TABLE, and is safe to
--  run against a database seeded by 01_schema.sql and 02_seed.sql.
--
--  It is also idempotent: re-running it is harmless. Every statement
--  uses IF NOT EXISTS, and the seed rows use INSERT IGNORE.
--
--  WHAT THIS ADDS
--  --------------
--  Until now badges could only be issued one way: a student completed a
--  module and IssueServlet minted the badge. This migration adds the
--  second path the portal needs -- a student submits a claim for a
--  credential earned elsewhere, and an admin verifies it before any
--  badge exists.
--
--  The ordering matters. Nothing is issued until an admin approves, so
--  a student can never award themselves a credential. That is what
--  keeps the tamper-evidence argument in REPORT.md honest: every
--  verification code in the badges table was minted by the server,
--  never by the person it describes.
-- =====================================================================

USE badgeportal;

-- ---------------------------------------------------------------------
-- accounts
--
-- Login identities. Separate from `students` on purpose: a student is a
-- person the institution knows about, an account is a way to log in.
-- Admins have no students row at all, and a student can exist in the
-- data with no account (56 of the 60 seeded ones do).
--
-- email is UNIQUE across the whole table, which is what makes each
-- email exactly one account with exactly one password. Role lives on
-- the account, so an identity is ADMIN or STUDENT and never both --
-- signing in through the wrong door fails even with the right password.
--
-- password_hash holds a single self-describing string:
--
--     pbkdf2_sha256$120000$<salt_b64>$<key_b64>
--
-- rather than separate hash / salt / iteration columns. Everything
-- needed to verify travels with the hash, so the iteration count can be
-- raised later without invalidating existing rows. Generate values with
-- scripts/make_password_hash.py.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS accounts (
  account_id     INT AUTO_INCREMENT PRIMARY KEY,
  email          VARCHAR(150) NOT NULL,
  password_hash  VARCHAR(255) NOT NULL,
  role           ENUM('ADMIN','STUDENT') NOT NULL,
  student_id     INT          NULL,
  is_active      TINYINT(1)   NOT NULL DEFAULT 1,
  created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  last_login_at  DATETIME(3)  NULL,
  UNIQUE KEY uq_accounts_email (email),
  UNIQUE KEY uq_accounts_student (student_id),
  CONSTRAINT fk_accounts_student FOREIGN KEY (student_id)
      REFERENCES students(student_id) ON DELETE CASCADE,
  -- An admin must not be attached to a student row, and a student
  -- account is useless without one. Enforced here rather than trusted
  -- to application code.
  CONSTRAINT ck_accounts_role_student CHECK (
      (role = 'ADMIN'   AND student_id IS NULL) OR
      (role = 'STUDENT' AND student_id IS NOT NULL)
  )
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- badge_claims
--
-- A student's request for a badge covering something earned outside the
-- course modules -- a Coursera certificate, an NPTEL course, a
-- workshop. The student supplies evidence (a link, an uploaded file, or
-- both) and proposes a tier; an admin confirms or overrides it.
--
-- A claim is a REQUEST, never a credential. It has no verification
-- code, and nothing in it is verifiable through /api/verify. Only on
-- approval does a row appear in `badges` with a server-generated code.
-- Keeping the two tables separate is what makes that distinction
-- structural rather than a matter of remembering to check a flag.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS badge_claims (
  claim_id       INT AUTO_INCREMENT PRIMARY KEY,
  student_id     INT          NOT NULL,
  title          VARCHAR(160) NOT NULL,
  issuer         VARCHAR(120) NULL,
  external_url   VARCHAR(500) NULL,
  evidence_path  VARCHAR(300) NULL,
  evidence_name  VARCHAR(200) NULL,
  proposed_tier  ENUM('BRONZE','SILVER','GOLD') NOT NULL DEFAULT 'BRONZE',
  status         ENUM('PENDING','APPROVED','REJECTED') NOT NULL DEFAULT 'PENDING',
  submitted_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  reviewed_by    INT          NULL,
  reviewed_at    DATETIME(3)  NULL,
  review_note    VARCHAR(400) NULL,
  KEY idx_claims_student (student_id),
  KEY idx_claims_queue (status, submitted_at),
  CONSTRAINT fk_claims_student FOREIGN KEY (student_id)
      REFERENCES students(student_id) ON DELETE CASCADE,
  -- The reviewer is kept even if their account is later removed, so the
  -- audit trail does not quietly lose who approved what.
  CONSTRAINT fk_claims_reviewer FOREIGN KEY (reviewed_by)
      REFERENCES accounts(account_id) ON DELETE SET NULL
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- badges -- two structural changes
--
-- 1. module_id becomes NULLABLE. A claim-issued badge is not tied to
--    any course module, so there is nothing to put there.
--
--    The existing UNIQUE KEY (student_id, module_id) survives this
--    unchanged, because SQL treats NULLs as distinct in a unique index:
--    a student may hold many claim badges (all with NULL module_id) but
--    still only one badge per actual module. That is exactly the rule
--    we want, and it falls out of the existing index for free.
--
-- 2. Two new columns record where a badge came from.
--
-- Everything that makes a badge verifiable -- verification_code,
-- issued_at_ms, the hash over them -- is untouched. Both issuance paths
-- run through the same BadgeCode.generate(), so a claim badge and a
-- module badge are indistinguishable at verification time. The CGI
-- script needs no change whatsoever, which is what keeps the benchmark
-- comparing like with like.
-- ---------------------------------------------------------------------
-- MODIFY is naturally idempotent: setting a column to the type it
-- already has is a no-op.
ALTER TABLE badges
  MODIFY COLUMN module_id INT NULL;

-- MySQL 8 has no ADD COLUMN IF NOT EXISTS (that is MariaDB), so each
-- addition is guarded by an information_schema lookup and run through a
-- prepared statement. Verbose, but it makes re-running this file safe,
-- which matters when a migration is applied by hand.
SET @ddl = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE badges ADD COLUMN claim_id INT NULL AFTER module_id',
  'DO 0')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'badgeportal'
    AND TABLE_NAME   = 'badges'
    AND COLUMN_NAME  = 'claim_id');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE badges ADD COLUMN source ENUM(''MODULE_COMPLETION'',''ADMIN_APPROVED'') NOT NULL DEFAULT ''MODULE_COMPLETION'' AFTER claim_id',
  'DO 0')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'badgeportal'
    AND TABLE_NAME   = 'badges'
    AND COLUMN_NAME  = 'source');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE badges ADD CONSTRAINT fk_badges_claim FOREIGN KEY (claim_id) REFERENCES badge_claims(claim_id) ON DELETE SET NULL',
  'DO 0')
  FROM information_schema.TABLE_CONSTRAINTS
  WHERE TABLE_SCHEMA    = 'badgeportal'
    AND TABLE_NAME      = 'badges'
    AND CONSTRAINT_NAME = 'fk_badges_claim');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------------------------------------------------------------------
-- Seed: demo accounts
--
-- Six accounts, not sixty. Enough to demonstrate both roles and a queue
-- with several students in it; the other 54 seeded students stay as
-- data with no way to log in, which is realistic anyway.
--
-- DEMO CREDENTIALS -- development only, documented in README.md:
--     admin@college.edu          Admin@123
--     aarav.sharma@college.edu   Student@123   (and the four below)
--
-- These hashes are real PBKDF2 output, not placeholders. Replace them
-- for anything resembling a real deployment.
-- ---------------------------------------------------------------------
INSERT IGNORE INTO accounts (email, password_hash, role, student_id) VALUES
  ('admin@college.edu',
   'pbkdf2_sha256$120000$aYHA3+WQPvnSfMpu0IE2tQ==$J6JIAlH5n+AEpgqSb2qppKYcc5dwtdnsp/Z5sm1HJJY=',
   'ADMIN', NULL),
  ('aarav.sharma@college.edu',
   'pbkdf2_sha256$120000$+KHdUP2+EUBoj55BpbMzDg==$7fZGO9LBAqXRgT84AO+itfiMBCONxzOGyrNLa/8DFME=',
   'STUDENT', 1),
  ('diya.patel@college.edu',
   'pbkdf2_sha256$120000$Tg9cWpiqRJhMhNEDPvWtEA==$BWhn+jnkweJ2XPrpyfAiVdQxR+DlTzhelgyotdLsE9s=',
   'STUDENT', 2),
  ('rohan.mehta@college.edu',
   'pbkdf2_sha256$120000$IRePSDG1ijvPUyUyKvxhtg==$gfXdLPUhBjyweqm27xeE9ZiRa5QBuEuHsZetx94wNZU=',
   'STUDENT', 3),
  ('ishita.nair@college.edu',
   'pbkdf2_sha256$120000$06N2LfMNiNZeRQlr3gIEnA==$QpWax7/llMmkNDvHYkZ705lVt4erp4uSKNU+SrZaRgI=',
   'STUDENT', 4),
  ('kabir.singh@college.edu',
   'pbkdf2_sha256$120000$oXnW9szm9RZhPgjVUFWy1A==$PwfyMf7pmiDWHxz4JuGvn7oJXobaiaAbKyj3NoMY1p8=',
   'STUDENT', 5);

-- ---------------------------------------------------------------------
-- Seed: a few pending claims
--
-- So the admin queue is not empty when the project is demonstrated.
-- Deliberately varied: one with a link only, one with a link and a file,
-- one with a Gold request that an examiner might reasonably question.
-- ---------------------------------------------------------------------
INSERT IGNORE INTO badge_claims
  (claim_id, student_id, title, issuer, external_url, evidence_name,
   proposed_tier, status, submitted_at)
VALUES
  (1, 1, 'Responsive Web Design', 'freeCodeCamp',
   'https://freecodecamp.org/certification/example/responsive-web-design',
   NULL, 'SILVER', 'PENDING', TIMESTAMP('2026-09-10 09:14:00')),
  (2, 2, 'Python for Everybody', 'Coursera',
   'https://coursera.org/verify/example-python',
   'python-certificate.pdf', 'GOLD', 'PENDING', TIMESTAMP('2026-09-11 16:40:00')),
  (3, 3, 'Database Management Systems', 'NPTEL',
   NULL, 'nptel-dbms.pdf', 'SILVER', 'PENDING', TIMESTAMP('2026-09-12 11:05:00')),
  (4, 5, 'Introduction to Cyber Security', 'Cisco Networking Academy',
   'https://cisco.example/verify/abc123',
   NULL, 'BRONZE', 'PENDING', TIMESTAMP('2026-09-13 18:22:00'));

-- ---------------------------------------------------------------------
SELECT 'accounts'         AS table_name, COUNT(*) AS rows_now FROM accounts
UNION ALL SELECT 'badge_claims (pending)', COUNT(*) FROM badge_claims WHERE status = 'PENDING'
UNION ALL SELECT 'badges (unchanged)',     COUNT(*) FROM badges;
