package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("cc_call_session")
public class AiCallRecordingSource {
    @TableId
    private Long id;
    private String businessCallId;
    private Long nodeId;
    private LocalDateTime startedAt;
    private LocalDateTime answeredAt;
    private Long recordingOssId;
    private Long recordingMediaId;
    private String recordingFileName;
    private String recordingStatus;
}
