package ai.auralis.decision;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import ai.auralis.decision.DecisionService.Decision;
import ai.auralis.decision.DecisionService.LayaVote;
import ai.auralis.decision.DecisionService.LlmVerdict;
import ai.auralis.decision.DecisionService.Spec;

/** Persists every decision, and reports how often Laya agrees with the LLM. */
@Repository
public class DecisionLedger {

    private static final int MAX_EXCERPT = 4000;

    public record Entry(UUID id, String at, String scope, UUID scopeId, String kind, String policy,
                        String question, String layaAnswer, Double layaP, String llmAnswer,
                        String finalAnswer, String decidedBy, String rationale, long latencyMs) {
    }

    /** Per decision kind: how many, how many the LLM also judged, and how often Laya agreed. */
    public record Agreement(String kind, long decisions, long compared, long agreed) {
    }

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public DecisionLedger(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    Decision record(Spec spec, LayaVote laya, LlmVerdict llm, String finalAnswer,
                    String decidedBy, long latencyMs) {
        UUID id = UUID.randomUUID();
        String excerpt = spec.state().length() <= MAX_EXCERPT
                ? spec.state() : spec.state().substring(0, MAX_EXCERPT);
        jdbc.sql("""
                INSERT INTO decision (id, scope, scope_id, kind, policy, question, state_excerpt,
                    options, laya_answer, laya_p, laya_probs, llm_answer, llm_rationale,
                    final_answer, decided_by, latency_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)""")
                .params(id, spec.scope(), spec.scopeId(), spec.kind(), spec.policy().name(),
                        spec.question(), excerpt,
                        spec.options() == null ? null : json.writeValueAsString(spec.options()),
                        laya == null ? null : laya.answer(), laya == null ? null : laya.p(),
                        laya == null ? null : json.writeValueAsString(laya.probabilities()),
                        llm == null ? null : llm.answer(), llm == null ? null : llm.rationale(),
                        finalAnswer, decidedBy, latencyMs)
                .update();
        return new Decision(id, finalAnswer, decidedBy, laya == null ? null : laya.p(),
                laya == null ? null : laya.probabilities(), laya == null ? null : laya.answer(),
                llm == null ? null : llm.rationale());
    }

    public List<Entry> recent(String scope, UUID scopeId, int limit) {
        return jdbc.sql("""
                SELECT id, to_char(created_at, 'YYYY-MM-DD HH24:MI:SS'), scope, scope_id, kind,
                       policy, question, laya_answer, laya_p, llm_answer, final_answer,
                       decided_by, llm_rationale, latency_ms
                FROM decision
                WHERE (?::text IS NULL OR scope = ?) AND (?::uuid IS NULL OR scope_id = ?)
                ORDER BY created_at DESC LIMIT ?""")
                .params(scope, scope, scopeId, scopeId, limit)
                .query((rs, n) -> new Entry(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getString(3), rs.getObject(4, UUID.class), rs.getString(5),
                        rs.getString(6), rs.getString(7), rs.getString(8),
                        (Double) rs.getObject(9), rs.getString(10), rs.getString(11),
                        rs.getString(12), rs.getString(13), rs.getLong(14)))
                .list();
    }

    public List<Agreement> agreement() {
        return jdbc.sql("""
                SELECT kind, count(*),
                       count(*) FILTER (WHERE laya_answer IS NOT NULL AND llm_answer IS NOT NULL),
                       count(*) FILTER (WHERE laya_answer = llm_answer)
                FROM decision GROUP BY kind ORDER BY kind""")
                .query((rs, n) -> new Agreement(rs.getString(1), rs.getLong(2), rs.getLong(3),
                        rs.getLong(4)))
                .list();
    }
}
