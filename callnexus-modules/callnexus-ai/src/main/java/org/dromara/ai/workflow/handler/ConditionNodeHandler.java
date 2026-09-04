package org.dromara.ai.workflow.handler;

import org.dromara.ai.workflow.AiWorkflowNodeContext;
import org.dromara.ai.workflow.AiWorkflowNodeHandler;
import org.dromara.ai.workflow.AiWorkflowNodeResult;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Set;

@Component
public class ConditionNodeHandler implements AiWorkflowNodeHandler {
    @Override
    public Set<String> nodeTypes() {
        return Set.of("CONDITION");
    }

    @Override
    public AiWorkflowNodeResult execute(AiWorkflowNodeContext context) {
        String variable = context.node().path("config").path("variable").asText();
        String operator = context.node().path("config").path("operator").asText();
        Object actual = context.variables().get(variable);
        String expected = context.node().path("config").path("compareValue").asText();
        boolean matched = evaluate(actual, operator, expected);
        return AiWorkflowNodeResult.continueWith(matched ? "TRUE" : "FALSE");
    }

    private boolean evaluate(Object actual, String operator, String expected) {
        String value = actual == null ? "" : String.valueOf(actual);
        return switch (operator) {
            case "EQ" -> value.equalsIgnoreCase(expected);
            case "NE" -> !value.equalsIgnoreCase(expected);
            case "CONTAINS" -> value.contains(expected);
            case "NOT_CONTAINS" -> !value.contains(expected);
            case "EMPTY" -> value.isBlank();
            case "NOT_EMPTY" -> !value.isBlank();
            case "GT" -> number(value).compareTo(number(expected)) > 0;
            case "GE" -> number(value).compareTo(number(expected)) >= 0;
            case "LT" -> number(value).compareTo(number(expected)) < 0;
            case "LE" -> number(value).compareTo(number(expected)) <= 0;
            default -> false;
        };
    }

    private BigDecimal number(String value) {
        try {
            return new BigDecimal(value);
        } catch (Exception ignored) {
            return BigDecimal.ZERO;
        }
    }
}
