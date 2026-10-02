package ai.auralis.research;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * The main LLM's role in research: the deep-reasoning steps (planning,
 * evidence synthesis, gap finding, reporting). Control decisions are Laya's.
 */
@Component
public class ResearchAnalyst {

    /** qwen3 honours this switch; other models ignore it. */
    private static final String NO_THINK = "\n/no_think";
    private static final Pattern THINK = Pattern.compile("(?s)<think>.*?</think>");

    record Questions(List<String> questions) {
    }

    /** A cited source line handed to the LLM as [n]. */
    public record Source(String title, String content) {
    }

    /**
     * A cited answer plus the analyst's own judgement of whether the evidence
     * actually answers the question — the gate that decides escalation.
     */
    public record Answer(String answer, boolean fullyAnswered, String missing) {
    }

    /** A question with its synthesized answer, for gap analysis and reporting. */
    public record Finding(String question, String answer, boolean resolved) {
    }

    private final ChatClient chat;

    public ResearchAnalyst(ChatClient.Builder builder) {
        this.chat = builder
                .defaultSystem("""
                        You are the research analyst of Auralis, an AI engineering scientist.
                        You are rigorous: you separate what the evidence shows from what you
                        assume, you never invent numbers or sources, and you say plainly when
                        evidence is missing.""")
                .build();
    }

    public List<String> plan(String objective, int max) {
        Questions q = chat.prompt()
                .user(u -> u.text("""
                        Engineering objective:
                        {objective}

                        Break it into at most {max} specific research questions whose answers
                        are needed to make sound engineering decisions for this objective.
                        Each question must be answerable from technical literature (papers,
                        datasheets, standards, manuals). Prefer questions about quantities,
                        trade-offs, constraints and validation methods.""" + NO_THINK)
                        .param("objective", objective)
                        .param("max", max))
                .call()
                .entity(Questions.class);
        return clean(q, max);
    }

    public Answer answer(String question, List<Source> sources) {
        if (sources.isEmpty()) {
            return new Answer("No evidence was found for this question.", false,
                    "any source on this question");
        }
        StringBuilder ctx = new StringBuilder();
        for (int i = 0; i < sources.size(); i++) {
            ctx.append('[').append(i + 1).append("] ").append(sources.get(i).title()).append('\n')
                    .append(sources.get(i).content()).append("\n\n");
        }
        Answer a = chat.prompt()
                .user(u -> u.text("""
                        Question: {question}

                        Evidence:
                        {evidence}
                        Answer the question using only the evidence above. Cite every claim
                        with its source number like [1]. Give concrete values and units where
                        the evidence has them. Keep it under 200 words.

                        Then judge strictly: fullyAnswered is true only if the evidence itself
                        states what the question asks for (the specific quantity, comparison or
                        method). Evidence on a related topic is not enough. If false, put in
                        missing exactly what information is still needed.""" + NO_THINK)
                        .param("question", question)
                        .param("evidence", ctx.toString()))
                .call()
                .entity(Answer.class);
        if (a == null || a.answer() == null) {
            return new Answer("The analyst returned no answer.", false, "a usable answer");
        }
        return new Answer(strip(a.answer()), a.fullyAnswered(), a.missing());
    }

    public List<String> gaps(String objective, List<Finding> findings, int max) {
        Questions q = chat.prompt()
                .user(u -> u.text("""
                        Engineering objective:
                        {objective}

                        What has been established so far:
                        {findings}
                        List at most {max} NEW research questions that would close the most
                        important remaining gaps, unresolved points or contradictions before
                        an engineering decision can be made. Do not repeat questions already
                        asked. Return an empty list if nothing important is missing.""" + NO_THINK)
                        .param("objective", objective)
                        .param("findings", render(findings))
                        .param("max", max))
                .call()
                .entity(Questions.class);
        return clean(q, max);
    }

    public String report(String objective, List<Finding> findings) {
        String text = chat.prompt()
                .user(u -> u.text("""
                        Engineering objective:
                        {objective}

                        Research findings (question → evidence-based answer):
                        {findings}
                        Write a concise research brief for an engineer with these sections:
                        1. Key findings (with the numbers found)
                        2. Trade-offs and constraints
                        3. Open questions and risks (what is still unverified)
                        4. Recommended next steps toward a design
                        Keep the [n] citations from the findings where you use them. Do not
                        add facts that are not in the findings.""" + NO_THINK)
                        .param("objective", objective)
                        .param("findings", render(findings)))
                .call()
                .content();
        return strip(text);
    }

    /** Compact text form of the research state, also used as Laya's input. */
    public static String render(List<Finding> findings) {
        StringBuilder sb = new StringBuilder();
        for (Finding f : findings) {
            sb.append("Q: ").append(f.question())
                    .append(f.resolved() ? "" : "  [UNRESOLVED]").append('\n')
                    .append("A: ").append(f.answer()).append("\n\n");
        }
        return sb.toString();
    }

    static String strip(String text) {
        return text == null ? "" : THINK.matcher(text).replaceAll("").strip();
    }

    private static List<String> clean(Questions q, int max) {
        if (q == null || q.questions() == null) {
            return List.of();
        }
        var unique = new LinkedHashSet<String>();
        for (String s : q.questions()) {
            if (s != null && !s.isBlank()) {
                unique.add(strip(s));
            }
        }
        return unique.stream().limit(max).toList();
    }
}
