package org.dromara.ai.domain.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

@Data
public class AiSpeechProviderRequest {
    private Long id;
    private String providerCode;
    @NotBlank
    private String providerName;
    @NotBlank
    private String providerType;
    private Boolean ttsEnabled;
    private Boolean streamingTtsEnabled;
    private Boolean recordingAsrEnabled;
    private Boolean streamingAsrEnabled;
    private Boolean defaultTts;
    private Boolean defaultStreamingTts;
    private Boolean defaultRecordingAsr;
    private Boolean defaultStreamingAsr;
    private String ttsModel;
    private String streamingTtsModel;
    private String recordingAsrModel;
    private String streamingAsrModel;
    private String ttsVoice;
    private String streamingTtsVoice;
    private String ttsEndpointMode;
    private String streamingTtsEndpointMode;
    private String recordingAsrEndpointMode;
    private String streamingAsrEndpointMode;
    private Map<String, Object> credentials;
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
    private Integer version;
}
