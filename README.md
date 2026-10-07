# Campus E-Voting System

Java Swing UI, JDBC, Oracle Database.

## Run

1. Run `schema.sql`, then `admin_upgrade.sql`, in your Oracle schema (12c+ needed for identity columns and `STANDARD_HASH`).
2. Edit `URL`, `USER`, `PASS` in `EVotingApp.java`.
3. Compile and run (Java 16+, with the Oracle JDBC driver):
   ```
   javac -cp ojdbc11.jar *.java
   java  -cp .:ojdbc11.jar EVotingApp        (Windows: use ; instead of :)
   ```
4. Demo logins: voters `S001` ... `S004` with password `pass123`; admin `ADMIN` with password `admin123`.
   The admin console lets you create elections, posts and candidates, and view turnout per post.
   Once an election's start time passes, Oracle triggers lock its posts and candidates.
   Results appear only after an election's `end_time` has passed
   (e.g. `UPDATE election SET end_time = LOCALTIMESTAMP - INTERVAL '1' MINUTE;`).

## Normalization

**Unnormalized starting point** (one sheet, one row per vote):
`student_id, name, email, password, department, election_title, election_start, election_end, post, candidate_name, candidate_manifesto, vote_time`

Problems: repeated data, update/insert/delete anomalies, and the vote row identifies the voter.

**1NF** - every column atomic, each row identified by a key, no repeating groups
(e.g. a candidate list stored in one cell becomes one `candidate` row per candidate per post).

**2NF** - no partial dependency on part of a composite key. Attributes that depended on only part of the key were split out:
- student details depend on `student_id` alone, so they go to `student`
- election title and dates depend on `election_id` alone, so they go to `election`
- manifesto depends on (student, post) as a candidacy, so it is in `candidate`, not `student`

**3NF** - no transitive dependency (non-key to non-key):
- `student_id -> dept_id -> dept_name` was transitive, so `department` is separate
- `post_id -> election_id -> (title, start, end)` was transitive, so `election_post` and `election` are separate
- `candidate_id -> post_id` is not copied into `vote`; `vote` stores only `candidate_id`
- Results (vote counts) are **not stored**. They are derived by `v_results`, which avoids update anomalies.

All determinants are candidate keys, so the schema is also in **BCNF**.

| Table | Key | Purpose |
|---|---|---|
| department | dept_id | Department names, once |
| student | student_id | Voter records (hashed password) |
| election | election_id | Title and voting window |
| election_post | post_id | Posts (President, Secretary) per election |
| candidate | candidate_id, UNIQUE(student, post) | A student standing for a post |
| voter_log | (post_id, student_id) | Who has voted per post |
| vote | vote_id | Anonymous ballots |

### Deliberate design decision: splitting `voter_log` from `vote`

Normalization alone would not satisfy the requirements, so this split is a design choice:
- `voter_log` records *that* a student voted for a post; `vote` records *what* was chosen, with no student reference. Ballot secrecy is preserved even for someone browsing the tables.
- The composite primary key `(post_id, student_id)` makes "one vote per student per post" a database guarantee. A second attempt raises `DUP_VAL_ON_INDEX` even under concurrent sessions, with no reliance on Java-side checks.

## Integrity enforced by Oracle

- `cast_vote` is the only write path: it checks the time window, that the candidate belongs to the post, then inserts into `voter_log` and `vote` in one transaction (both or neither).
- Foreign keys, `UNIQUE`, `CHECK (end_time > start_time)` and `NOT NULL` constraints.
- Passwords are stored as SHA-256 hashes. Use `PreparedStatement` throughout (no SQL injection).
- Production hardening: grant the app user only `EXECUTE` on `cast_vote` plus `SELECT` on the read tables (commented at the end of `schema.sql`), and add a per-student salt to the password hash.
