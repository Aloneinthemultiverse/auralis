package ai.auralis.research.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistence for research sessions: questions, evidence and the event trace. */
@Repository
public class ResearchStore {

    public enum Status { RUNNING, SUFFICIENT, EXHAUSTED, FAILED }

    public enum DeepMode { NONE, LOCAL, KAGGLE }

    public enum QuestionStatus { OPEN, ANSWERED, UNRESOLVED }

    public record Session(UUID id, String objective, Status status, DeepMode deepMode,
                          int maxRounds, int roundsRun, String report, String sufficiency,
                          String error) {
    }

    public record Question(UUID id, UUID parentId, int round, String origin, String text,
                           QuestionStatus status, String answer, Double answeredP) {
    }

    public record EvidenceRow(UUID id, UUID questionId, String kind, UUID chunkId,
                              String sourceTitle, String sourceUri, String content,
                              Double relevance, int round) {
    }

    public record Event(long id, String at, int round, String type, String message) {
    }

    private final JdbcClient jdbc;

    public ResearchStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ── sessions ─────────────────────────────────────────────────────

    public UUID createSession(String objective, DeepMode mode, int maxRounds) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO research_session (id, objective, status, deep_mode, max_rounds)
                VALUES (?, ?, ?, ?, ?)""")
                .params(id, objective, Status.RUNNING.name(), mode.name(), maxRounds)
                .update();
        return id;
    }

    public Optional<Session> session(UUID id) {
        return jdbc.sql("""
                SELECT id, objective, status, deep_mode, max_rounds, rounds_run, report,
                       sufficiency::text, error
                FROM research_session WHERE id = ?""")
                .param(id)
                .query((rs, n) -> new Session(rs.getObject(1, UUID.class), rs.getString(2),
                        Status.valueOf(rs.getString(3)), DeepMode.valueOf(rs.getString(4)),
                        rs.getInt(5), rs.getInt(6), rs.getString(7), rs.getString(8),
                        rs.getString(9)))
                .optional();
    }

    /** Sessions left RUNNING by a previous process can never finish; close them. */
    public int failInterrupted() {
        return jdbc.sql("""
                UPDATE research_session
                SET status = 'FAILED', error = 'interrupted by server restart', updated_at = now()
                WHERE status = 'RUNNING'""")
                .update();
    }

    public void roundCompleted(UUID id, int round, String sufficiencyJson) {
        jdbc.sql("""
                UPDATE research_session
                SET rounds_run = ?, sufficiency = ?::jsonb, updated_at = now()
                WHERE id = ?""")
                .params(round, sufficiencyJson, id)
                .update();
    }

    public void finish(UUID id, Status status, String report, String error) {
        jdbc.sql("""
                UPDATE research_session
                SET status = ?, report = ?, error = ?, updated_at = now()
                WHERE id = ?""")
                .params(status.name(), report, error, id)
                .update();
    }

    // ── questions ────────────────────────────────────────────────────

    public UUID addQuestion(UUID sessionId, UUID parentId, int round, String origin, String text) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO research_question (id, session_id, parent_id, round, origin, text, status)
                VALUES (?, ?, ?, ?, ?, ?, ?)""")
                .params(id, sessionId, parentId, round, origin, text, QuestionStatus.OPEN.name())
                .update();
        return id;
    }

    public void resolveQuestion(UUID id, QuestionStatus status, String answer, Double answeredP) {
        jdbc.sql("UPDATE research_question SET status = ?, answer = ?, answered_p = ? WHERE id = ?")
                .params(status.name(), answer, answeredP, id)
                .update();
    }

    public List<Question> questions(UUID sessionId) {
        return jdbc.sql("""
                SELECT id, parent_id, round, origin, text, status, answer, answered_p
                FROM research_question WHERE session_id = ? ORDER BY round, origin DESC, text""")
                .param(sessionId)
                .query(ResearchStore::question)
                .list();
    }

    public List<Question> openQuestions(UUID sessionId) {
        return questions(sessionId).stream()
                .filter(q -> q.status() == QuestionStatus.OPEN)
                .toList();
    }

    // ── evidence ─────────────────────────────────────────────────────

    public void addEvidence(UUID questionId, String kind, UUID chunkId, String title,
                            String uri, String content, Double relevance, int round) {
        jdbc.sql("""
                INSERT INTO research_evidence
                    (id, question_id, kind, chunk_id, source_title, source_uri, content, relevance, round)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(UUID.randomUUID(), questionId, kind, chunkId, title, uri, content,
                        relevance, round)
                .update();
    }

    public List<EvidenceRow> evidence(UUID questionId) {
        return jdbc.sql("""
                SELECT id, question_id, kind, chunk_id, source_title, source_uri, content,
                       relevance, round
                FROM research_evidence WHERE question_id = ?
                ORDER BY round, relevance DESC NULLS LAST""")
                .param(questionId)
                .query((rs, n) -> new EvidenceRow(rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class), rs.getString(3), rs.getObject(4, UUID.class),
                        rs.getString(5), rs.getString(6), rs.getString(7),
                        (Double) rs.getObject(8), rs.getInt(9)))
                .list();
    }

    // ── trace ────────────────────────────────────────────────────────

    public void event(UUID sessionId, int round, String type, String message) {
        jdbc.sql("INSERT INTO research_event (session_id, round, type, message) VALUES (?, ?, ?, ?)")
                .params(sessionId, round, type, message)
                .update();
    }

    public List<Event> events(UUID sessionId) {
        return jdbc.sql("""
                SELECT id, to_char(at, 'HH24:MI:SS'), round, type, message
                FROM research_event WHERE session_id = ? ORDER BY id""")
                .param(sessionId)
                .query((rs, n) -> new Event(rs.getLong(1), rs.getString(2), rs.getInt(3),
                        rs.getString(4), rs.getString(5)))
                .list();
    }

    private static Question question(ResultSet rs, int n) throws SQLException {
        return new Question(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getInt(3), rs.getString(4), rs.getString(5),
                QuestionStatus.valueOf(rs.getString(6)), rs.getString(7),
                (Double) rs.getObject(8));
    }
}
