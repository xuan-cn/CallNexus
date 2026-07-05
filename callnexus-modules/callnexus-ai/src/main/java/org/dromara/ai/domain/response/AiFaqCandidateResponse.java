package org.dromara.ai.domain.response;
import lombok.Data;
import java.math.BigDecimal;
import java.util.List;
@Data
public class AiFaqCandidateResponse {
    private Long id; private Long batchId; private Integer rowNumber; private String faqCode; private String faqName;
    private String standardQuestion; private String standardAnswer; private List<String> aliases; private String answerMode;
    private String sourceLocation; private String sourceText; private BigDecimal confidence; private String status;
    private String errorMessage; private Long faqId; private Integer version;
}
