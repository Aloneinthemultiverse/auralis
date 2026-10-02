package ai.auralis.design;

import java.util.Map;

import org.springframework.stereotype.Component;

import ai.auralis.tools.PythonComputeTool;

/**
 * Domain-neutral fallback: the design ships a Python model of itself that
 * computes its key metrics and checks them against the requirements, and
 * validation runs that model in the sandbox. Works for any engineering domain
 * that has no specialised adapter connected.
 */
@Component
public class GeneralAdapter implements DomainAdapter {

    private final PythonComputeTool python;

    public GeneralAdapter(PythonComputeTool python) {
        this.python = python;
    }

    @Override
    public String name() {
        return "general";
    }

    @Override
    public String description() {
        return "general engineering design without specialised tools: systems, mechanical, "
                + "electrical, software or process design checked with a numerical model";
    }

    @Override
    public String guidance() {
        return """
                Besides the design description, provide a file `model.py`: a self-contained
                Python model of the design (numpy/scipy allowed) that computes its key metrics
                from the chosen parameters, checks each measurable requirement, and prints one
                line per requirement as `PASS <requirement>: <value>` or
                `FAIL <requirement>: <value> (limit <limit>)`, then `OVERALL PASS` or
                `OVERALL FAIL`.""";
    }

    @Override
    public String validate(Map<String, String> artifacts) {
        StringBuilder report = new StringBuilder();
        artifacts.forEach((name, code) -> {
            if (name.endsWith(".py")) {
                report.append("== run ").append(name).append(" ==\n")
                        .append(python.compute(code)).append("\n\n");
            }
        });
        return report.isEmpty()
                ? "No executable model was provided (expected model.py); nothing could be checked."
                : report.toString();
    }
}
