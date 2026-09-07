-- =====================================================================
--  Verified Digital Skill-Badge Portal  --  Schema
--  PBL 3 : CGI vs Servlet Benchmark
--
--  Run with:  mysql -u root -p < sql/01_schema.sql
-- =====================================================================

DROP DATABASE IF EXISTS badgeportal;
CREATE DATABASE badgeportal
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;
USE badgeportal;

-- ---------------------------------------------------------------------
-- Students
-- ---------------------------------------------------------------------
CREATE TABLE students (
  student_id   INT AUTO_INCREMENT PRIMARY KEY,
  name         VARCHAR(100)  NOT NULL,
  email        VARCHAR(150)  NOT NULL UNIQUE,
  enrolled_on  DATE          NOT NULL DEFAULT (CURRENT_DATE)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- Modules  (a micro-credential is earned per module)
--   code_prefix is the human-readable stem of the verification code,
--   e.g. module 'Servlet Fundamentals' -> codes look like  SF-3A9F-B21C
-- ---------------------------------------------------------------------
CREATE TABLE modules (
  module_id    INT AUTO_INCREMENT PRIMARY KEY,
  title        VARCHAR(120)  NOT NULL,
  unit         VARCHAR(60)   NOT NULL,
  code_prefix  CHAR(2)       NOT NULL,
  UNIQUE KEY uq_modules_title (title)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- Module completions
--   A badge may only be issued once a completion row exists.
--   score drives the badge tier (stretch goal: Bronze / Silver / Gold).
-- ---------------------------------------------------------------------
CREATE TABLE module_completions (
  completion_id INT AUTO_INCREMENT PRIMARY KEY,
  student_id    INT           NOT NULL,
  module_id     INT           NOT NULL,
  score         DECIMAL(5,2)  NOT NULL,
  completed_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uq_completion (student_id, module_id),
  CONSTRAINT fk_comp_student FOREIGN KEY (student_id)
      REFERENCES students(student_id) ON DELETE CASCADE,
  CONSTRAINT fk_comp_module  FOREIGN KEY (module_id)
      REFERENCES modules(module_id)  ON DELETE CASCADE,
  CONSTRAINT ck_comp_score CHECK (score >= 0 AND score <= 100)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- Badges
--
--  verification_code : the public, tamper-evident credential id.
--  issued_at_ms      : the CANONICAL issue timestamp (epoch millis).
--                      This is the value fed into the hash. It is stored
--                      as a plain BIGINT so that Java and Python derive
--                      byte-identical digests with no timezone or
--                      fractional-second drift between the two runtimes.
--  issued_at         : the same instant as a DATETIME, for display and
--                      for the indicative schema in the problem brief.
--
--  The UNIQUE index on verification_code is what makes the lookup an
--  O(log n) index probe in BOTH implementations -- so the benchmark
--  measures the request lifecycle, not a table scan.
-- ---------------------------------------------------------------------
CREATE TABLE badges (
  badge_id          INT AUTO_INCREMENT PRIMARY KEY,
  student_id        INT           NOT NULL,
  module_id         INT           NOT NULL,
  verification_code VARCHAR(20)   NOT NULL,
  tier              ENUM('BRONZE','SILVER','GOLD') NOT NULL DEFAULT 'BRONZE',
  score             DECIMAL(5,2)  NOT NULL,
  issued_at_ms      BIGINT        NOT NULL,
  issued_at         DATETIME(3)   NOT NULL,
  revoked           TINYINT(1)    NOT NULL DEFAULT 0,
  UNIQUE KEY uq_badge_code (verification_code),
  UNIQUE KEY uq_badge_student_module (student_id, module_id),
  KEY idx_badge_student (student_id),
  CONSTRAINT fk_badge_student FOREIGN KEY (student_id)
      REFERENCES students(student_id) ON DELETE CASCADE,
  CONSTRAINT fk_badge_module  FOREIGN KEY (module_id)
      REFERENCES modules(module_id)  ON DELETE CASCADE
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- Least-privilege application account.
-- Both the Servlet and the CGI script log in as this user, so neither
-- implementation gets an unfair advantage from connection privileges.
-- ---------------------------------------------------------------------
DROP USER IF EXISTS 'badgeuser'@'localhost';
CREATE USER 'badgeuser'@'localhost' IDENTIFIED BY 'badgepass123';
GRANT SELECT, INSERT, UPDATE, DELETE ON badgeportal.* TO 'badgeuser'@'localhost';
FLUSH PRIVILEGES;
