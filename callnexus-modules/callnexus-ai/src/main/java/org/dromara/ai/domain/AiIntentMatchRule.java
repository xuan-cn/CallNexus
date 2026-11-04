package org.dromara.ai.domain;

import lombok.Data;
import java.util.List;

@Data
public class AiIntentMatchRule {
    private String polarity;
    private String mode;
    private Boolean ignorePunctuation = true;
    private List<List<String>> groups;
}
