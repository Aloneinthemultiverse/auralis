package ai.auralis.design;

import java.util.Map;

/**
 * How Auralis designs and validates in one engineering domain. The core design
 * loop is domain-neutral; adapters supply guidance for the LLM and the
 * automated validation of the design's files. Which adapter applies to an
 * objective is a Laya decision.
 */
public interface DomainAdapter {

    /** Short id, e.g. "semiconductor". */
    String name();

    /** What objectives this adapter is for. */
    String description();

    /**
     * A few words Laya routes on. Measured: long option descriptions misroute (a processor
     * went to "general"); short labels route correctly.
     */
    default String label() {
        String d = description();
        String afterColon = d.contains(":") ? d.substring(d.indexOf(':') + 1) : d;
        String[] items = afterColon.split(",");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(3, items.length); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(items[i].strip());
        }
        return sb.toString();
    }

    /** Instructions for the LLM: what files to produce and how. */
    String guidance();

    /** Runs the domain's automated checks on the design's files; returns a report. */
    String validate(Map<String, String> artifacts);
}
