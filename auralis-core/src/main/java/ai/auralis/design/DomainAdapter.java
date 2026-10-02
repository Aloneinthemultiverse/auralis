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

    /** What objectives this adapter is for (Laya routes on it). */
    String description();

    /** Instructions for the LLM: what files to produce and how. */
    String guidance();

    /** Runs the domain's automated checks on the design's files; returns a report. */
    String validate(Map<String, String> artifacts);
}
