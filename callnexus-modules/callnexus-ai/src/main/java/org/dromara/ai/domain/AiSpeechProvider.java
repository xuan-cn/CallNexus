package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cc_ai_speech_provider")
public class AiSpeechProvider extends TenantEntity {
    @TableId
    private Long id;
    private String providerCode;
    private String providerName;
    private String providerType;
    private Boolean ttsEnabled;
    private Boolean streamingTtsEnabled;
    private Boolean recordingAsrEnabled;
    private Boolean streamingAsrEnabled;
    private Boolean defaultTts;
    private Boolean defaultStreamingTts;
    private Boolean defaultRecordingAsr;
    private Boolean defaultStreamingAsr;
    private String endpointUrl;
    private String httpMethod;
    private String authType;
    private String authHeaderName;
    private String authToken;
    private String defaultVoice;
    private String defaultFormat;
    private Integer defaultSampleRate;
    private Integer timeoutSeconds;
    private String streamingTtsEndpointUrl;
    private String streamingTtsOptionsJson;
    private String recordingAsrEndpointUrl;
    private String streamingAsrEndpointUrl;
    private String asrLanguage;
    private String asrFormat;
    private Integer asrSampleRate;
    private Boolean asrEnablePunctuation;
    private Boolean asrEnableItn;
    private Boolean asrEnableIntermediateResult;
    private Integer asrSilenceTimeoutMs;
    private Integer asrMaxSentenceMs;
    private String asrOptionsJson;
    private Boolean enabled;
    private String remark;
    @Version
    private Integer version;
    @TableLogic
    private Boolean deleted;
}
