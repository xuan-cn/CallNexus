package org.dromara.ai.speech.definition;

import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class SpeechProviderDefinitionRegistry {

    private final Map<String, SpeechProviderDefinition> definitions = new LinkedHashMap<>();

    public SpeechProviderDefinitionRegistry() {
        register(dashScope());
        register(aliyunNls());
        register(openAiCompatible());
        register(funAsr());
        register(kokoro());
        register(customHttp());
    }

    public List<SpeechProviderDefinition> list() {
        return List.copyOf(definitions.values());
    }

    public SpeechProviderDefinition get(String providerType) {
        SpeechProviderDefinition definition = definitions.get(normalize(providerType));
        if (definition == null) {
            throw new ServiceException("不支持的语音服务商类型：" + providerType);
        }
        return definition;
    }

    private void register(SpeechProviderDefinition definition) {
        String type = normalize(definition.providerType());
        if (definitions.put(type, definition) != null) {
            throw new IllegalStateException("语音服务商配置定义重复：" + type);
        }
    }

    private SpeechProviderDefinition dashScope() {
        Map<SpeechCapability, CapabilityDefinition> capabilities = capabilities(
            capability(SpeechCapability.TTS, "语音合成", "qwen3-tts-flash",
                ttsModel("qwen3-tts-flash", "Qwen3 TTS Flash", true,
                    List.of("wav", "pcm", "mp3"), List.of(8000, 16000, 24000, 48000),
                    qwen3Voices()),
                ttsModel("qwen-tts", "Qwen TTS", false,
                    List.of("wav", "pcm", "mp3"), List.of(8000, 16000, 24000),
                    qwenLegacyVoices())),
            capability(SpeechCapability.STREAMING_TTS, "实时语音合成", "qwen3-tts-flash-realtime",
                ttsModel("qwen3-tts-flash-realtime", "Qwen3 TTS Flash Realtime", true,
                    List.of("pcm"), List.of(8000, 16000, 24000),
                    qwen3Voices())),
            capability(SpeechCapability.RECORDING_ASR, "录音识别", "qwen3-asr-flash",
                model("qwen3-asr-flash", "Qwen3 ASR Flash", true)),
            capability(SpeechCapability.STREAMING_ASR, "实时语音识别", "qwen3-asr-flash-realtime",
                model("qwen3-asr-flash-realtime", "Qwen3 ASR Flash Realtime", true))
        );
        return definition("ALIYUN_DASHSCOPE", "阿里云百炼", "千问语音合成与语音识别",
            List.of(secret("apiKey", "API Key", "请输入百炼 API Key", true),
                text("workspaceId", "Workspace ID", "使用业务空间接口时填写", false)),
            capabilities, Map.of(
                SpeechCapability.TTS, "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation",
                SpeechCapability.STREAMING_TTS, "wss://dashscope.aliyuncs.com/api-ws/v1/realtime",
                SpeechCapability.RECORDING_ASR, "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
                SpeechCapability.STREAMING_ASR, "wss://dashscope.aliyuncs.com/api-ws/v1/realtime"));
    }

    private SpeechProviderDefinition aliyunNls() {
        return definition("ALIYUN_NLS", "阿里云智能语音交互 NLS", "阿里云 NLS TTS 与 ASR",
            List.of(text("accessKeyId", "AccessKey ID", "请输入 AccessKey ID", true),
                secret("accessKeySecret", "AccessKey Secret", "留空表示不修改", true),
                text("appKey", "AppKey", "请输入 NLS 项目 AppKey", true),
                select("region", "地域", "cn-shanghai", List.of(
                    option("cn-shanghai", "华东2 上海"), option("cn-beijing", "华北2 北京")))),
            capabilities(
                capability(SpeechCapability.TTS, "语音合成", "nls-tts"),
                unsupported(SpeechCapability.STREAMING_TTS, "实时语音合成"),
                capability(SpeechCapability.RECORDING_ASR, "录音识别", "nls-asr"),
                capability(SpeechCapability.STREAMING_ASR, "实时语音识别", "nls-realtime-asr")),
            Map.of(
                SpeechCapability.TTS, "wss://nls-gateway-{region}.aliyuncs.com/ws/v1",
                SpeechCapability.RECORDING_ASR, "wss://nls-gateway-{region}.aliyuncs.com/ws/v1",
                SpeechCapability.STREAMING_ASR, "wss://nls-gateway-{region}.aliyuncs.com/ws/v1"));
    }

    private SpeechProviderDefinition openAiCompatible() {
        return definition("OPENAI_COMPATIBLE", "OpenAI 兼容服务", "支持 OpenAI Audio HTTP 协议的服务",
            List.of(secret("apiKey", "API Key", "请输入服务 API Key", false)),
            capabilities(
                capability(SpeechCapability.TTS, "语音合成", "gpt-4o-mini-tts",
                    ttsModel("gpt-4o-mini-tts", "GPT-4o Mini TTS", true,
                        List.of("mp3", "wav", "pcm"), List.of(24000),
                        voice("alloy", "Alloy", true), voice("coral", "Coral", false),
                        voice("nova", "Nova", false), voice("onyx", "Onyx", false))),
                unsupported(SpeechCapability.STREAMING_TTS, "实时语音合成"),
                capability(SpeechCapability.RECORDING_ASR, "录音识别", "whisper-1",
                    model("whisper-1", "Whisper 1", true)),
                unsupported(SpeechCapability.STREAMING_ASR, "实时语音识别")),
            Map.of(
                SpeechCapability.TTS, "https://api.openai.com/v1/audio/speech",
                SpeechCapability.RECORDING_ASR, "https://api.openai.com/v1/audio/transcriptions"));
    }

    private SpeechProviderDefinition funAsr() {
        return definition("FUNASR", "FunASR", "本地或私有化 FunASR HTTP 服务",
            List.of(text("serviceUrl", "服务地址", "http://127.0.0.1:8000/v1/audio/transcriptions", true),
                secret("apiKey", "访问 Token", "没有认证可留空", false)),
            capabilities(
                unsupported(SpeechCapability.TTS, "语音合成"),
                unsupported(SpeechCapability.STREAMING_TTS, "实时语音合成"),
                capability(SpeechCapability.RECORDING_ASR, "录音识别", "sensevoice",
                    model("sensevoice", "SenseVoice", true)),
                unsupported(SpeechCapability.STREAMING_ASR, "实时语音识别")),
            Map.of(SpeechCapability.RECORDING_ASR, "{serviceUrl}"));
    }

    private SpeechProviderDefinition kokoro() {
        return definition("KOKORO_LOCAL", "Kokoro 本地语音", "本地 Kokoro FastAPI 语音合成服务",
            List.of(text("serviceUrl", "服务地址", "http://127.0.0.1:8880", true),
                secret("apiKey", "访问 Token", "没有认证可留空", false)),
            capabilities(
                capability(SpeechCapability.TTS, "语音合成", "kokoro",
                    model("kokoro", "Kokoro", true)),
                capability(SpeechCapability.STREAMING_TTS, "实时语音合成", "kokoro",
                    model("kokoro", "Kokoro", true)),
                unsupported(SpeechCapability.RECORDING_ASR, "录音识别"),
                unsupported(SpeechCapability.STREAMING_ASR, "实时语音识别")),
            Map.of(
                SpeechCapability.TTS, "{serviceUrl}",
                SpeechCapability.STREAMING_TTS, "{serviceUrl}"));
    }

    private SpeechProviderDefinition customHttp() {
        return definition("CUSTOM_HTTP", "通用 HTTP TTS", "自定义 HTTP 语音合成接口",
            List.of(secret("apiKey", "访问 Token", "没有认证可留空", false)),
            capabilities(
                capability(SpeechCapability.TTS, "语音合成", "custom"),
                unsupported(SpeechCapability.STREAMING_TTS, "实时语音合成"),
                unsupported(SpeechCapability.RECORDING_ASR, "录音识别"),
                unsupported(SpeechCapability.STREAMING_ASR, "实时语音识别")),
            Map.of());
    }

    private StaticSpeechProviderDefinition definition(String type, String label, String description,
                                                       List<FieldDefinition> credentials,
                                                       Map<SpeechCapability, CapabilityDefinition> capabilities,
                                                       Map<SpeechCapability, String> endpoints) {
        return new StaticSpeechProviderDefinition(type, label, description, credentials, capabilities, endpoints);
    }

    private Map<SpeechCapability, CapabilityDefinition> capabilities(CapabilityDefinition... definitions) {
        Map<SpeechCapability, CapabilityDefinition> result = new EnumMap<>(SpeechCapability.class);
        for (CapabilityDefinition definition : definitions) {
            result.put(definition.capability(), definition);
        }
        return result;
    }

    private CapabilityDefinition capability(SpeechCapability capability, String label, String defaultModel,
                                              ModelDefinition... models) {
        List<ModelDefinition> values = models.length == 0
            ? List.of(model(defaultModel, defaultModel, true)) : List.of(models);
        return new CapabilityDefinition(capability, label, true, defaultModel, values,
            capability == SpeechCapability.TTS || capability == SpeechCapability.STREAMING_TTS,
            capability == SpeechCapability.TTS, List.of());
    }

    private CapabilityDefinition unsupported(SpeechCapability capability, String label) {
        return new CapabilityDefinition(capability, label, false, null, List.of(), false, false, List.of());
    }

    private ModelDefinition model(String id, String label, boolean recommended) {
        return new ModelDefinition(id, label, recommended);
    }

    private ModelDefinition ttsModel(String id, String label, boolean recommended,
                                     List<String> formats, List<Integer> sampleRates,
                                     VoiceDefinition... voices) {
        return new ModelDefinition(id, label, recommended, formats, sampleRates, List.of(voices));
    }

    private ModelDefinition ttsModel(String id, String label, boolean recommended,
                                     List<String> formats, List<Integer> sampleRates,
                                     List<VoiceDefinition> voices) {
        return new ModelDefinition(id, label, recommended, formats, sampleRates, voices);
    }

    private List<VoiceDefinition> qwenLegacyVoices() {
        return List.of(
            voice("Cherry", "芊悦（Cherry）", true),
            voice("Serena", "苏瑶（Serena）", false),
            voice("Ethan", "晨煦（Ethan）", false),
            voice("Chelsie", "千雪（Chelsie）", false)
        );
    }

    private List<VoiceDefinition> qwen3Voices() {
        return List.of(
            voice("Cherry", "芊悦（Cherry）", true),
            voice("Serena", "苏瑶（Serena）", false),
            voice("Ethan", "晨煦（Ethan）", false),
            voice("Chelsie", "千雪（Chelsie）", false),
            voice("Momo", "茉兔（Momo）", false),
            voice("Vivian", "十三（Vivian）", false),
            voice("Moon", "月白（Moon）", false),
            voice("Maia", "四月（Maia）", false),
            voice("Kai", "凯（Kai）", false),
            voice("Nofish", "不吃鱼（Nofish）", false),
            voice("Bella", "萌宝（Bella）", false),
            voice("Jennifer", "詹妮弗（Jennifer）", false),
            voice("Ryan", "甜茶（Ryan）", false),
            voice("Katerina", "卡捷琳娜（Katerina）", false),
            voice("Aiden", "艾登（Aiden）", false),
            voice("Eldric Sage", "沧明子（Eldric Sage）", false),
            voice("Mia", "乖小妹（Mia）", false),
            voice("Mochi", "沙小弥（Mochi）", false),
            voice("Bellona", "燕铮莺（Bellona）", false),
            voice("Vincent", "田叔（Vincent）", false),
            voice("Bunny", "萌小姬（Bunny）", false),
            voice("Neil", "阿闻（Neil）", false),
            voice("Elias", "墨讲师（Elias）", false),
            voice("Arthur", "徐大爷（Arthur）", false),
            voice("Nini", "邻家妹妹（Nini）", false),
            voice("Seren", "小婉（Seren）", false),
            voice("Pip", "顽屁小孩（Pip）", false),
            voice("Stella", "少女阿月（Stella）", false),
            voice("Bodega", "博德加（Bodega）", false),
            voice("Sonrisa", "索尼莎（Sonrisa）", false),
            voice("Alek", "阿列克（Alek）", false),
            voice("Dolce", "多尔切（Dolce）", false),
            voice("Sohee", "素熙（Sohee）", false),
            voice("Ono Anna", "小野杏（Ono Anna）", false),
            voice("Lenn", "莱恩（Lenn）", false),
            voice("Emilien", "埃米尔安（Emilien）", false),
            voice("Andre", "安德雷（Andre）", false),
            voice("Radio Gol", "拉迪奥·戈尔（Radio Gol）", false),
            voice("Jada", "上海-阿珍（Jada）", false),
            voice("Dylan", "北京-晓东（Dylan）", false),
            voice("Li", "南京-老李（Li）", false),
            voice("Marcus", "陕西-秦川（Marcus）", false),
            voice("Roy", "闽南-阿杰（Roy）", false),
            voice("Peter", "天津-李彼得（Peter）", false),
            voice("Sunny", "四川-晴儿（Sunny）", false),
            voice("Eric", "四川-程川（Eric）", false),
            voice("Rocky", "粤语-阿强（Rocky）", false),
            voice("Kiki", "粤语-阿清（Kiki）", false)
        );
    }

    private VoiceDefinition voice(String id, String label, boolean recommended) {
        return new VoiceDefinition(id, label, recommended);
    }

    private FieldDefinition text(String key, String label, String placeholder, boolean required) {
        return new FieldDefinition(key, label, SpeechFieldType.TEXT, required, false, placeholder, null, List.of(), false);
    }

    private FieldDefinition secret(String key, String label, String placeholder, boolean required) {
        return new FieldDefinition(key, label, SpeechFieldType.PASSWORD, required, true, placeholder, null, List.of(), false);
    }

    private FieldDefinition select(String key, String label, Object defaultValue, List<OptionDefinition> options) {
        return new FieldDefinition(key, label, SpeechFieldType.SELECT, true, false, null, defaultValue, options, false);
    }

    private OptionDefinition option(String value, String label) {
        return new OptionDefinition(value, label);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
