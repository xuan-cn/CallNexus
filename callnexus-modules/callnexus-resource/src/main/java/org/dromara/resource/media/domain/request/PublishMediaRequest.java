package org.dromara.resource.media.domain.request;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import java.util.List;
@Data
public class PublishMediaRequest {
    @NotEmpty private List<Long> nodeGroupIds;
}
