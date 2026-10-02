package ai.auralis.laya;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One typed question for Laya's {@code /v1/systemone} endpoint.
 * {@code criteria} is a label→description map for {@code choice},
 * an ordered level list for {@code score}, and absent for {@code noul}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LayaQuestion(String type, String instructions, Object criteria) {

    /** Yes/no question; the answer is the probability of "yes". */
    public static LayaQuestion yesNo(String instructions) {
        return new LayaQuestion("noul", instructions, null);
    }

    public static LayaQuestion choice(String instructions, Map<String, String> options) {
        return new LayaQuestion("choice", instructions, options);
    }

    public static LayaQuestion score(String instructions, List<String> levels) {
        return new LayaQuestion("score", instructions, levels);
    }
}
