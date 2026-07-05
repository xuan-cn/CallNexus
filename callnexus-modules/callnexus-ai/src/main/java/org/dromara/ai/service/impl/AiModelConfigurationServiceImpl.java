package org.dromara.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.*;
import org.dromara.ai.domain.request.*;
import org.dromara.ai.domain.response.*;
import org.dromara.ai.mapper.*;
import org.dromara.ai.provider.*;
import org.dromara.ai.service.AiModelConfigurationService;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

@Service
@RequiredArgsConstructor
public class AiModelConfigurationServiceImpl implements AiModelConfigurationService {
    private static final Set<String> CAPABILITIES = Set.of("CHAT", "EMBEDDING", "RERANK");
    private final AiModelProviderMapper providerMapper;
    private final AiModelMapper modelMapper;
    private final AiKnowledgeBaseMapper knowledgeBaseMapper;
    private final AiAgentMapper agentMapper;
    private final ChatProviderRegistry chatRegistry;
    private final EmbeddingProviderRegistry embeddingRegistry;

    @Override
    public List<AiModelProviderResponse> providers() {
        return providerMapper.selectList(new LambdaQueryWrapper<AiModelProvider>().orderByAsc(AiModelProvider::getProviderCode))
            .stream().map(this::providerResponse).toList();
    }

    @Override
    public Long createProvider(AiModelProviderRequest request) {
        ensureProviderCode(request.getProviderCode(), null);
        AiModelProvider provider = new AiModelProvider();
        fillProvider(provider, request, true);
        providerMapper.insert(provider);
        return provider.getId();
    }

    @Override
    public void updateProvider(Long id, AiModelProviderRequest request) {
        ensureProviderCode(request.getProviderCode(), id);
        AiModelProvider provider = requireProvider(id);
        fillProvider(provider, request, false);
        provider.setVersion(request.getVersion());
        if (providerMapper.updateById(provider) != 1) throw new ServiceException("模型服务商已被其他用户修改，请刷新后重试");
    }

    @Override
    public void deleteProvider(Long id) {
        if (modelMapper.selectCount(new LambdaQueryWrapper<AiModel>().eq(AiModel::getProviderId, id)) > 0) {
            throw new ServiceException("模型服务商仍有关联模型，不能删除");
        }
        if (providerMapper.deleteById(id) != 1) throw new ServiceException("模型服务商不存在");
    }

    @Override
    public Map<String, Object> testProvider(Long id) {
        AiModelProvider provider = requireEnabledProvider(id);
        try {
            String base = provider.getBaseUrl().replaceAll("/+$", "");
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + "/models"))
                .timeout(Duration.ofSeconds(provider.getReadTimeoutSeconds() == null ? 30 : provider.getReadTimeoutSeconds())).GET();
            if (StringUtils.isNotBlank(provider.getApiKey())) builder.header("Authorization", "Bearer " + provider.getApiKey());
            HttpResponse<String> response = HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ServiceException("模型服务连接测试失败，HTTP状态码=" + response.statusCode());
            }
            int count = JsonUtils.getObjectMapper().readTree(response.body()).path("data").size();
            return Map.of("success", true, "modelCount", count, "message", "模型服务连接成功");
        } catch (ServiceException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException("模型服务连接测试被中断");
        } catch (Exception e) {
            throw new ServiceException("模型服务连接测试失败：" + e.getMessage());
        }
    }

    @Override
    public List<AiModelResponse> models(String capability) {
        List<AiModelProvider> providers = providerMapper.selectList(null);
        Map<Long, String> names = new HashMap<>();
        providers.forEach(item -> names.put(item.getId(), item.getProviderName()));
        return modelMapper.selectList(new LambdaQueryWrapper<AiModel>()
                .eq(StringUtils.isNotBlank(capability), AiModel::getCapability, normalize(capability))
                .orderByAsc(AiModel::getCapability).orderByAsc(AiModel::getModelCode))
            .stream().map(item -> modelResponse(item, names.get(item.getProviderId()))).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createModel(AiModelRequest request) {
        requireProvider(request.getProviderId());
        ensureModelCode(request.getModelCode(), null);
        AiModel model = new AiModel();
        fillModel(model, request);
        if (Boolean.TRUE.equals(model.getDefaultModel())) clearDefault(model.getCapability(), null);
        modelMapper.insert(model);
        return model.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateModel(Long id, AiModelRequest request) {
        requireProvider(request.getProviderId());
        ensureModelCode(request.getModelCode(), id);
        AiModel model = requireModel(id);
        String oldCapability = model.getCapability();
        fillModel(model, request);
        model.setVersion(request.getVersion());
        if (Boolean.TRUE.equals(model.getDefaultModel())) clearDefault(model.getCapability(), id);
        if (!Objects.equals(oldCapability, model.getCapability()) && model.getVectorDimension() != null) model.setVectorDimension(null);
        if (modelMapper.updateById(model) != 1) throw new ServiceException("模型配置已被其他用户修改，请刷新后重试");
    }

    @Override
    public void deleteModel(Long id) {
        if (knowledgeBaseMapper.selectCount(new LambdaQueryWrapper<AiKnowledgeBase>()
            .and(wrapper -> wrapper.eq(AiKnowledgeBase::getEmbeddingModelId, id)
                .or().eq(AiKnowledgeBase::getPendingEmbeddingModelId, id))) > 0) {
            throw new ServiceException("模型仍被知识库使用，不能删除");
        }
        if (agentMapper.selectCount(new LambdaQueryWrapper<AiAgent>().eq(AiAgent::getChatModelId, id)) > 0) {
            throw new ServiceException("模型仍被 AI 助手使用，不能删除");
        }
        if (modelMapper.deleteById(id) != 1) throw new ServiceException("模型不存在");
    }

    @Override
    public Map<String, Object> testModel(Long id) {
        AiModel model = requireEnabledModel(id);
        AiModelProvider provider = requireEnabledProvider(model.getProviderId());
        long started = System.currentTimeMillis();
        if ("EMBEDDING".equals(model.getCapability())) {
            EmbeddingResult result = embeddingRegistry.get(provider.getProviderType()).embed(
                new EmbeddingRequest(provider, model, List.of("CallNexus 模型连接测试")));
            model.setVectorDimension(result.dimension());
            modelMapper.updateById(model);
            return Map.of("success", true, "dimension", result.dimension(), "elapsedMs", System.currentTimeMillis() - started);
        }
        if ("CHAT".equals(model.getCapability())) {
            ChatResult result = chatRegistry.get(provider.getProviderType()).chat(new ChatRequest(provider, model,
            List.of(new ChatMessage("user", "请只回复：连接成功")), null, 512));
            return Map.of("success", true, "content", result.content(), "elapsedMs", System.currentTimeMillis() - started);
        }
        throw new ServiceException("第一版暂不支持测试 RERANK 模型");
    }

    private void fillProvider(AiModelProvider provider, AiModelProviderRequest request, boolean creating) {
        provider.setProviderCode(normalize(request.getProviderCode()));
        provider.setProviderName(request.getProviderName().trim());
        provider.setProviderType(normalize(request.getProviderType()));
        if (!"OPENAI_COMPATIBLE".equals(provider.getProviderType())) throw new ServiceException("第一版仅支持 OPENAI_COMPATIBLE");
        provider.setBaseUrl(validateUrl(request.getBaseUrl()));
        if (creating || StringUtils.isNotBlank(request.getApiKey())) provider.setApiKey(request.getApiKey());
        provider.setOrganizationId(request.getOrganizationId());
        provider.setConnectTimeoutSeconds(request.getConnectTimeoutSeconds() == null ? 10 : request.getConnectTimeoutSeconds());
        provider.setReadTimeoutSeconds(request.getReadTimeoutSeconds() == null ? 120 : request.getReadTimeoutSeconds());
        if (StringUtils.isNotBlank(request.getExtraConfigJson()) && !JsonUtils.isJsonObject(request.getExtraConfigJson())) {
            throw new ServiceException("服务商扩展配置必须是 JSON 对象");
        }
        provider.setExtraConfigJson(request.getExtraConfigJson());
        provider.setEnabled(request.getEnabled() == null || request.getEnabled());
    }

    private void fillModel(AiModel model, AiModelRequest request) {
        String capability = normalize(request.getCapability());
        if (!CAPABILITIES.contains(capability)) throw new ServiceException("模型能力不支持：" + capability);
        if (StringUtils.isNotBlank(request.getRequestOptionsJson()) && !JsonUtils.isJsonObject(request.getRequestOptionsJson())) {
            throw new ServiceException("模型扩展参数必须是 JSON 对象");
        }
        model.setProviderId(request.getProviderId());
        model.setModelCode(normalize(request.getModelCode()));
        model.setModelName(request.getModelName().trim());
        model.setCapability(capability);
        model.setMaxBatchSize(request.getMaxBatchSize() == null ? 16 : request.getMaxBatchSize());
        model.setMaxInputTokens(request.getMaxInputTokens());
        model.setDefaultModel(Boolean.TRUE.equals(request.getDefaultModel()));
        model.setRequestOptionsJson(request.getRequestOptionsJson());
        model.setEnabled(request.getEnabled() == null || request.getEnabled());
    }

    private void clearDefault(String capability, Long exceptId) {
        modelMapper.update(null, new LambdaUpdateWrapper<AiModel>().eq(AiModel::getCapability, capability)
            .ne(exceptId != null, AiModel::getId, exceptId).set(AiModel::getDefaultModel, false));
    }

    private AiModelProvider requireProvider(Long id) {
        AiModelProvider value = providerMapper.selectById(id);
        if (value == null) throw new ServiceException("模型服务商不存在");
        return value;
    }
    private AiModelProvider requireEnabledProvider(Long id) {
        AiModelProvider value = requireProvider(id);
        if (!Boolean.TRUE.equals(value.getEnabled())) throw new ServiceException("模型服务商已停用");
        return value;
    }
    private AiModel requireModel(Long id) {
        AiModel value = modelMapper.selectById(id);
        if (value == null) throw new ServiceException("模型不存在");
        return value;
    }
    private AiModel requireEnabledModel(Long id) {
        AiModel value = requireModel(id);
        if (!Boolean.TRUE.equals(value.getEnabled())) throw new ServiceException("模型已停用");
        return value;
    }
    private void ensureProviderCode(String code, Long exclude) {
        long count = providerMapper.selectCount(new LambdaQueryWrapper<AiModelProvider>().eq(AiModelProvider::getProviderCode, normalize(code))
            .ne(exclude != null, AiModelProvider::getId, exclude));
        if (count > 0) throw new ServiceException("模型服务商编码已存在");
    }
    private void ensureModelCode(String code, Long exclude) {
        long count = modelMapper.selectCount(new LambdaQueryWrapper<AiModel>().eq(AiModel::getModelCode, normalize(code))
            .ne(exclude != null, AiModel::getId, exclude));
        if (count > 0) throw new ServiceException("模型编码已存在");
    }
    private String validateUrl(String value) {
        try {
            URI uri = URI.create(value.trim());
            if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null) throw new IllegalArgumentException();
            return value.trim().replaceAll("/+$", "");
        } catch (Exception e) {
            throw new ServiceException("模型服务地址必须是合法的 HTTP/HTTPS 地址");
        }
    }
    private String normalize(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }
    private AiModelProviderResponse providerResponse(AiModelProvider item) {
        AiModelProviderResponse result = new AiModelProviderResponse();
        result.setId(item.getId()); result.setProviderCode(item.getProviderCode()); result.setProviderName(item.getProviderName());
        result.setProviderType(item.getProviderType()); result.setBaseUrl(item.getBaseUrl());
        result.setApiKeyConfigured(StringUtils.isNotBlank(item.getApiKey())); result.setOrganizationId(item.getOrganizationId());
        result.setConnectTimeoutSeconds(item.getConnectTimeoutSeconds()); result.setReadTimeoutSeconds(item.getReadTimeoutSeconds());
        result.setExtraConfigJson(item.getExtraConfigJson()); result.setEnabled(item.getEnabled()); result.setVersion(item.getVersion());
        result.setCreateTime(item.getCreateTime()); return result;
    }
    private AiModelResponse modelResponse(AiModel item, String providerName) {
        AiModelResponse result = new AiModelResponse();
        result.setId(item.getId()); result.setProviderId(item.getProviderId()); result.setProviderName(providerName);
        result.setModelCode(item.getModelCode()); result.setModelName(item.getModelName()); result.setCapability(item.getCapability());
        result.setVectorDimension(item.getVectorDimension()); result.setMaxBatchSize(item.getMaxBatchSize());
        result.setMaxInputTokens(item.getMaxInputTokens()); result.setDefaultModel(item.getDefaultModel());
        result.setRequestOptionsJson(item.getRequestOptionsJson()); result.setEnabled(item.getEnabled()); result.setVersion(item.getVersion());
        result.setCreateTime(item.getCreateTime()); return result;
    }
}
