-- Run as a DBA first:
--   CREATE USER evote IDENTITY... (or use your own schema), then run this script in that schema.

CREATE TABLE department (
  dept_id    NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  dept_name  VARCHAR2(80) NOT NULL UNIQUE
);

CREATE TABLE student (
  student_id VARCHAR2(12)  PRIMARY KEY,
  full_name  VARCHAR2(100) NOT NULL,
  email      VARCHAR2(120) NOT NULL UNIQUE,
  pwd_hash   RAW(32)       NOT NULL,              -- SHA-256, never plain text
  dept_id    NUMBER        NOT NULL REFERENCES department(dept_id)
);

CREATE TABLE election (
  election_id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  title       VARCHAR2(120) NOT NULL,
  start_time  TIMESTAMP NOT NULL,
  end_time    TIMESTAMP NOT NULL,
  CONSTRAINT ck_election_time CHECK (end_time > start_time)
);

CREATE TABLE election_post (
  post_id     NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  election_id NUMBER NOT NULL REFERENCES election(election_id),
  post_title  VARCHAR2(80) NOT NULL,
  CONSTRAINT uq_post UNIQUE (election_id, post_title)
);

CREATE TABLE candidate (
  candidate_id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  student_id   VARCHAR2(12) NOT NULL REFERENCES student(student_id),
  post_id      NUMBER       NOT NULL REFERENCES election_post(post_id),
  manifesto    VARCHAR2(500),
  CONSTRAINT uq_candidate UNIQUE (student_id, post_id)
);

-- WHO has voted for WHICH POST (no candidate recorded -> ballot secrecy).
-- The composite primary key is what guarantees one vote per student per post.
CREATE TABLE voter_log (
  post_id    NUMBER       NOT NULL REFERENCES election_post(post_id),
  student_id VARCHAR2(12) NOT NULL REFERENCES student(student_id),
  voted_at   TIMESTAMP DEFAULT LOCALTIMESTAMP NOT NULL,
  CONSTRAINT pk_voter_log PRIMARY KEY (post_id, student_id)
);

-- The anonymous ballot box (no student reference).
CREATE TABLE vote (
  vote_id      NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  candidate_id NUMBER NOT NULL REFERENCES candidate(candidate_id),
  cast_at      TIMESTAMP DEFAULT LOCALTIMESTAMP NOT NULL
);
CREATE INDEX ix_vote_candidate ON vote(candidate_id);

-- Results are derived, never stored (avoids update anomalies).
CREATE OR REPLACE VIEW v_results AS
SELECT e.election_id, e.title AS election, e.end_time,
       p.post_title, s.full_name AS candidate, COUNT(v.vote_id) AS votes
FROM   election e
JOIN   election_post p ON p.election_id = e.election_id
JOIN   candidate c     ON c.post_id = p.post_id
JOIN   student s       ON s.student_id = c.student_id
LEFT JOIN vote v       ON v.candidate_id = c.candidate_id
GROUP BY e.election_id, e.title, e.end_time, p.post_id, p.post_title, c.candidate_id, s.full_name;

-- Single atomic entry point for voting: both inserts succeed or neither does.
CREATE OR REPLACE PROCEDURE cast_vote(
  p_student   IN VARCHAR2,
  p_post      IN NUMBER,
  p_candidate IN NUMBER
) AS
  v_start TIMESTAMP;
  v_end   TIMESTAMP;
  v_cnt   NUMBER;
BEGIN
  SELECT e.start_time, e.end_time INTO v_start, v_end
  FROM   election e JOIN election_post p ON p.election_id = e.election_id
  WHERE  p.post_id = p_post;

  IF LOCALTIMESTAMP NOT BETWEEN v_start AND v_end THEN
    RAISE_APPLICATION_ERROR(-20001, 'This election is not open.');
  END IF;

  SELECT COUNT(*) INTO v_cnt FROM candidate
  WHERE  candidate_id = p_candidate AND post_id = p_post;
  IF v_cnt = 0 THEN
    RAISE_APPLICATION_ERROR(-20002, 'Candidate does not belong to this post.');
  END IF;

  BEGIN
    INSERT INTO voter_log(post_id, student_id) VALUES (p_post, p_student);
  EXCEPTION WHEN DUP_VAL_ON_INDEX THEN
    RAISE_APPLICATION_ERROR(-20003, 'You have already voted for this post.');
  END;

  INSERT INTO vote(candidate_id) VALUES (p_candidate);
  COMMIT;
EXCEPTION WHEN OTHERS THEN
  ROLLBACK;
  RAISE;
END;
/

-- Seed data (all demo passwords = pass123)
INSERT INTO department(dept_name) VALUES ('Computer Science');
INSERT INTO department(dept_name) VALUES ('Mechanical Engineering');

INSERT INTO student VALUES ('S001','Asha Rao',   'asha@campus.edu',  STANDARD_HASH('pass123','SHA256'), 1);
INSERT INTO student VALUES ('S002','Ravi Kumar', 'ravi@campus.edu',  STANDARD_HASH('pass123','SHA256'), 1);
INSERT INTO student VALUES ('S003','Meena Iyer', 'meena@campus.edu', STANDARD_HASH('pass123','SHA256'), 2);
INSERT INTO student VALUES ('S004','Karthik S',  'karthik@campus.edu',STANDARD_HASH('pass123','SHA256'), 2);

INSERT INTO election(title, start_time, end_time)
VALUES ('Student Council 2026', LOCALTIMESTAMP - INTERVAL '1' DAY, LOCALTIMESTAMP + INTERVAL '7' DAY);

INSERT INTO election_post(election_id, post_title) VALUES (1, 'President');
INSERT INTO election_post(election_id, post_title) VALUES (1, 'Secretary');

INSERT INTO candidate(student_id, post_id, manifesto) VALUES ('S001', 1, 'Better labs and Wi-Fi');
INSERT INTO candidate(student_id, post_id, manifesto) VALUES ('S002', 1, 'Longer library hours');
INSERT INTO candidate(student_id, post_id, manifesto) VALUES ('S003', 2, 'Transparent budgets');
INSERT INTO candidate(student_id, post_id, manifesto) VALUES ('S004', 2, 'More campus events');
COMMIT;

-- Hardening: let the app user vote only through the procedure.
-- GRANT EXECUTE ON cast_vote TO evote_app;
-- GRANT SELECT ON student, election, election_post, candidate, voter_log, v_results TO evote_app;
-- (no INSERT/UPDATE/DELETE on vote or voter_log for evote_app)
