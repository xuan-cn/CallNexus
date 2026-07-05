package org.dromara.ai.domain.response;

import lombok.Data;

import java.util.Date;

@Data
public class AiSpeechProviderResponse {
    private Long id;
    private String providerCode;
    private String providerName;
    private String providerType;
    private Boolean ttsEnabled;
    private Boolean recordingAsrEnabled;
    private Boolean streamingAsrEnabled;
    private Boolean defaultTts;
    private Boolean defaultRecordingAsr;
    private Boolean defaultStreamingAsr;
    private String endpointUrl;
    private String httpMethod;
    private String authType;
    private String authHeaderName;
    private Boolean authConfigured;
    private String defaultVoice;
    private String defaultFormat;
    private Integer defaultSampleRate;
    private Integer timeoutSeconds;
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
    private Date createTime;
}

