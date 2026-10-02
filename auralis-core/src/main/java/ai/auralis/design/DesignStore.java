package ai.auralis.design;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Persistence for designs: alternatives, the version tree, and the event trace. */
@Repository
public class DesignStore {

    public enum Status { RUNNING, VERIFIED, NEEDS_INPUT, EXHAUSTED, FAILED }

    public record Design(UUID id, String objective, UUID researchSessionId, Status status,
                         int maxIterations, String error) {
    }

    public record Alternative(UUID id, String name, String summary, String pros, String cons,
                              String assumptions, Double fitScore, boolean selected) {
    }

    public record Version(UUID id, UUID parentId, String label, int iteration, String alternative,
                          String spec, Map<String, String> artifacts, String rationale,
                          String constraints, String verification, String verdict) {
    }

    public record Event(long id, String at, int iteration, String type, String message) {
    }

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public DesignStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public UUID create(String objective, UUID researchSessionId, int maxIterations) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO design (id, objective, research_session_id, status, max_iterations)
                VALUES (?, ?, ?, ?, ?)""")
                .params(id, objective, researchSessionId, Status.RUNNING.name(), maxIterations)
                .update();
        return id;
    }

    public Optional<Design> design(UUID id) {
        return jdbc.sql("""
                SELECT id, objective, research_session_id, status, max_iterations, error
                FROM design WHERE id = ?""")
                .param(id)
                .query((rs, n) -> new Design(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getObject(3, UUID.class), Status.valueOf(rs.getString(4)),
                        rs.getInt(5), rs.getString(6)))
                .optional();
    }

    public void setStatus(UUID id, Status status, String error) {
        jdbc.sql("UPDATE design SET status = ?, error = ?, updated_at = now() WHERE id = ?")
                .params(status.name(), error, id).update();
    }

    public int failInterrupted() {
        return jdbc.sql("""
                UPDATE design SET status = 'FAILED', error = 'interrupted by server restart',
                    updated_at = now()
                WHERE status = 'RUNNING'""").update();
    }

    // ── alternatives ─────────────────────────────────────────────────

    public UUID addAlternative(UUID designId, String name, String summary, String pros,
                               String cons, String assumptions) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO design_alternative (id, design_id, name, summary, pros, cons, assumptions)
                VALUES (?, ?, ?, ?, ?, ?, ?)""")
                .params(id, designId, name, summary, pros, cons, assumptions).update();
        return id;
    }

    public void scoreAlternative(UUID id, double fit, boolean selected) {
        jdbc.sql("UPDATE design_alternative SET fit_score = ?, selected = ? WHERE id = ?")
                .params(fit, selected, id).update();
    }

    public List<Alternative> alternatives(UUID designId) {
        return jdbc.sql("""
                SELECT id, name, summary, pros, cons, assumptions, fit_score, selected
                FROM design_alternative WHERE design_id = ? ORDER BY selected DESC, fit_score DESC NULLS LAST""")
                .param(designId)
                .query((rs, n) -> new Alternative(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                        (Double) rs.getObject(7), rs.getBoolean(8)))
                .list();
    }

    // ── versions ─────────────────────────────────────────────────────

    public UUID addVersion(UUID designId, UUID parentId, String label, int iteration,
                           String alternative, String spec, Map<String, String> artifacts,
                           String rationale, String constraints) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO design_version (id, design_id, parent_id, label, iteration, alternative,
                    spec, artifacts, rationale, constraints)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)""")
                .params(id, designId, parentId, label, iteration, alternative, spec,
                        json.writeValueAsString(artifacts), rationale, constraints)
                .update();
        return id;
    }

    public void verifyVersion(UUID id, String verification, String verdict) {
        jdbc.sql("UPDATE design_version SET verification = ?, verdict = ? WHERE id = ?")
                .params(verification, verdict, id).update();
    }

    public List<Version> versions(UUID designId) {
        return jdbc.sql("""
                SELECT id, parent_id, label, iteration, alternative, spec, artifacts::text,
                       rationale, constraints, verification, verdict
                FROM design_version WHERE design_id = ? ORDER BY created_at""")
                .param(designId)
                .query((rs, n) -> new Version(rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class), rs.getString(3), rs.getInt(4),
                        rs.getString(5), rs.getString(6),
                        json.readValue(rs.getString(7), new TypeReference<Map<String, String>>() { }),
                        rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11)))
                .list();
    }

    public Optional<Version> version(UUID designId, UUID versionId) {
        return versions(designId).stream().filter(v -> v.id().equals(versionId)).findFirst();
    }

    /** Children of a version are labelled parent.1, parent.2, ...; roots v1, v2, ... */
    public String nextLabel(UUID designId, Version parent) {
        List<Version> all = versions(designId);
        if (parent == null) {
            return "v" + (all.stream().filter(v -> v.parentId() == null).count() + 1);
        }
        long siblings = all.stream().filter(v -> parent.id().equals(v.parentId())).count();
        return parent.label() + "." + (siblings + 1);
    }

    // ── trace ────────────────────────────────────────────────────────

    public void event(UUID designId, int iteration, String type, String message) {
        jdbc.sql("INSERT INTO design_event (design_id, iteration, type, message) VALUES (?, ?, ?, ?)")
                .params(designId, iteration, type, message).update();
    }

    public List<Event> events(UUID designId) {
        return jdbc.sql("""
                SELECT id, to_char(at, 'HH24:MI:SS'), iteration, type, message
                FROM design_event WHERE design_id = ? ORDER BY id""")
                .param(designId)
                .query((rs, n) -> new Event(rs.getLong(1), rs.getString(2), rs.getInt(3),
                        rs.getString(4), rs.getString(5)))
                .list();
    }
}
