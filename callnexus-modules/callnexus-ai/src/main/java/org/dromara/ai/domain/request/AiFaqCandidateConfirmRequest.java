package org.dromara.ai.domain.request;
import lombok.Data;
import java.util.List;
@Data
public class AiFaqCandidateConfirmRequest { private List<Long> candidateIds; }
