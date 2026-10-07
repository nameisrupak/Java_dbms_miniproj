-- Run AFTER schema.sql. Adds the admin role, a turnout view and fairness locks.

ALTER TABLE student ADD (is_admin NUMBER(1) DEFAULT 0 NOT NULL
                         CONSTRAINT ck_student_admin CHECK (is_admin IN (0, 1)));

-- Demo admin (password = admin123)
INSERT INTO student(student_id, full_name, email, pwd_hash, dept_id, is_admin)
VALUES ('ADMIN', 'Election Officer', 'admin@campus.edu', STANDARD_HASH('admin123', 'SHA256'), 1, 1);

-- Turnout per post. Uses voter_log only, so it never reveals who voted for whom.
CREATE OR REPLACE VIEW v_turnout AS
SELECT e.title AS election,
       p.post_title,
       (SELECT COUNT(*) FROM student WHERE is_admin = 0) AS eligible,
       COUNT(l.student_id) AS voted,
       ROUND(100 * COUNT(l.student_id)
             / NULLIF((SELECT COUNT(*) FROM student WHERE is_admin = 0), 0), 1) AS turnout_pct
FROM   election e
JOIN   election_post p ON p.election_id = e.election_id
LEFT JOIN voter_log l  ON l.post_id = p.post_id
GROUP BY e.election_id, e.title, p.post_id, p.post_title;

-- Fairness: no new posts or candidates once an election has started.
CREATE OR REPLACE TRIGGER trg_post_lock
BEFORE INSERT ON election_post FOR EACH ROW
DECLARE v_start TIMESTAMP;
BEGIN
  SELECT start_time INTO v_start FROM election WHERE election_id = :NEW.election_id;
  IF LOCALTIMESTAMP >= v_start THEN
    RAISE_APPLICATION_ERROR(-20010, 'Election already started; posts are locked.');
  END IF;
END;
/

CREATE OR REPLACE TRIGGER trg_candidate_lock
BEFORE INSERT OR UPDATE OR DELETE ON candidate FOR EACH ROW
DECLARE v_start TIMESTAMP;
BEGIN
  SELECT e.start_time INTO v_start
  FROM   election e JOIN election_post p ON p.election_id = e.election_id
  WHERE  p.post_id = NVL(:NEW.post_id, :OLD.post_id);
  IF LOCALTIMESTAMP >= v_start THEN
    RAISE_APPLICATION_ERROR(-20011, 'Election already started; candidates are locked.');
  END IF;
END;
/
