package org.dromara.system.callcenterconfig.service.impl;

import cn.hutool.core.convert.Convert;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.system.callcenterconfig.domain.CallCenterConfigDefinition;
import org.dromara.system.callcenterconfig.domain.CallCenterConfigValue;
import org.dromara.system.callcenterconfig.domain.request.CallCenterConfigGroupSaveRequest;
import org.dromara.system.callcenterconfig.domain.response.CallCenterConfigGroupResponse;
import org.dromara.system.callcenterconfig.domain.response.CallCenterConfigItemResponse;
import org.dromara.system.callcenterconfig.mapper.CallCenterConfigDefinitionMapper;
import org.dromara.system.callcenterconfig.mapper.CallCenterConfigValueMapper;
import org.dromara.system.callcenterconfig.service.CallCenterConfigService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CallCenterConfigServiceImpl implements CallCenterConfigService {
    private static final String SOURCE_DEFAULT = "DEFAULT";
    private static final String SOURCE_TENANT = "TENANT";

    private final CallCenterConfigDefinitionMapper definitionMapper;
    private final CallCenterConfigValueMapper valueMapper;

    @Override
    public List<CallCenterConfigGroupResponse> listGroups() {
        List<CallCenterConfigDefinition> definitions = definitions(null);
        Map<String, CallCenterConfigValue> values = currentValues();
        return definitions.stream()
            .collect(Collectors.groupingBy(CallCenterConfigDefinition::getGroupCode, LinkedHashMap::new, Collectors.toList()))
            .values().stream()
            .map(items -> toGroup(items.get(0), items, values))
            .toList();
    }

    @Override
    public CallCenterConfigGroupResponse getGroup(String groupCode) {
        List<CallCenterConfigDefinition> definitions = definitions(groupCode);
        if (definitions.isEmpty()) {
            throw new ServiceException("配置分组不存在");
        }
        return toGroup(definitions.get(0), definitions, currentValues());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveGroup(String groupCode, CallCenterConfigGroupSaveRequest request) {
        Map<String, CallCenterConfigDefinition> definitionMap = definitions(groupCode).stream()
            .collect(Collectors.toMap(CallCenterConfigDefinition::getConfigKey, Function.identity()));
        if (definitionMap.isEmpty()) {
            throw new ServiceException("配置分组不存在");
        }
        for (CallCenterConfigGroupSaveRequest.Item item : request.getItems()) {
            CallCenterConfigDefinition definition = definitionMap.get(item.getConfigKey());
            if (definition == null) {
                throw new ServiceException("配置项不属于当前分组：" + item.getConfigKey());
            }
            String value = normalizeAndValidate(definition, item.getConfigValue());
            saveValue(definition.getConfigKey(), value);
        }
    }

    @Override
    public void reset(String configKey) {
        valueMapper.delete(new LambdaQueryWrapper<CallCenterConfigValue>().eq(CallCenterConfigValue::getConfigKey, configKey));
    }

    @Override
    public String getString(String configKey) {
        CallCenterConfigDefinition definition = requireDefinition(configKey);
        CallCenterConfigValue value = valueMapper.selectOne(new LambdaQueryWrapper<CallCenterConfigValue>().eq(CallCenterConfigValue::getConfigKey, configKey));
        return value == null ? definition.getDefaultValue() : value.getConfigValue();
    }

    @Override
    public Integer getInt(String configKey) {
        return Convert.toInt(getString(configKey));
    }

    @Override
    public Integer getIntOrDefault(String configKey, Integer defaultValue) {
        CallCenterConfigDefinition definition = findDefinition(configKey);
        if (definition == null) {
            return defaultValue;
        }
        CallCenterConfigValue value = valueMapper.selectOne(new LambdaQueryWrapper<CallCenterConfigValue>()
            .eq(CallCenterConfigValue::getConfigKey, configKey));
        String effectiveValue = value == null ? definition.getDefaultValue() : value.getConfigValue();
        return Convert.toInt(effectiveValue, defaultValue);
    }

    @Override
    public Boolean getBoolean(String configKey) {
        return Convert.toBool(getString(configKey));
    }

    private List<CallCenterConfigDefinition> definitions(String groupCode) {
        return definitionMapper.selectList(new LambdaQueryWrapper<CallCenterConfigDefinition>()
            .eq(CallCenterConfigDefinition::getEnabled, true)
            .eq(StringUtils.isNotBlank(groupCode), CallCenterConfigDefinition::getGroupCode, groupCode)
            .orderByAsc(CallCenterConfigDefinition::getGroupCode, CallCenterConfigDefinition::getSortOrder));
    }

    private CallCenterConfigDefinition requireDefinition(String configKey) {
        CallCenterConfigDefinition definition = findDefinition(configKey);
        if (definition == null) {
            throw new ServiceException("配置项不存在：" + configKey);
        }
        return definition;
    }

    private CallCenterConfigDefinition findDefinition(String configKey) {
        return definitionMapper.selectOne(new LambdaQueryWrapper<CallCenterConfigDefinition>()
            .eq(CallCenterConfigDefinition::getConfigKey, configKey)
            .eq(CallCenterConfigDefinition::getEnabled, true));
    }

    private Map<String, CallCenterConfigValue> currentValues() {
        return valueMapper.selectList(new LambdaQueryWrapper<CallCenterConfigValue>())
            .stream()
            .collect(Collectors.toMap(CallCenterConfigValue::getConfigKey, Function.identity(), (left, right) -> left));
    }

    private CallCenterConfigGroupResponse toGroup(CallCenterConfigDefinition first,
                                                  List<CallCenterConfigDefinition> definitions,
                                                  Map<String, CallCenterConfigValue> values) {
        CallCenterConfigGroupResponse response = new CallCenterConfigGroupResponse();
        response.setGroupCode(first.getGroupCode());
        response.setGroupName(first.getGroupName());
        response.setItems(definitions.stream().map(item -> toItem(item, values.get(item.getConfigKey()))).toList());
        return response;
    }

    private CallCenterConfigItemResponse toItem(CallCenterConfigDefinition definition, CallCenterConfigValue value) {
        CallCenterConfigItemResponse response = new CallCenterConfigItemResponse();
        response.setGroupCode(definition.getGroupCode());
        response.setGroupName(definition.getGroupName());
        response.setConfigKey(definition.getConfigKey());
        response.setConfigName(definition.getConfigName());
        response.setValueType(definition.getValueType());
        response.setEditorType(definition.getEditorType());
        response.setDefaultValue(definition.getDefaultValue());
        response.setConfigValue(value == null ? null : value.getConfigValue());
        response.setEffectiveValue(value == null ? definition.getDefaultValue() : value.getConfigValue());
        response.setSource(value == null ? SOURCE_DEFAULT : SOURCE_TENANT);
        response.setUnit(definition.getUnit());
        response.setOptionsJson(definition.getOptionsJson());
        response.setDescription(definition.getDescription());
        response.setRiskLevel(definition.getRiskLevel());
        response.setSortOrder(definition.getSortOrder());
        return response;
    }

    private String normalizeAndValidate(CallCenterConfigDefinition definition, String value) {
        String normalized = value == null ? null : value.trim();
        if (StringUtils.isBlank(normalized)) {
            return definition.getDefaultValue();
        }
        if ("INT".equals(definition.getValueType())) {
            try {
                int parsed = Integer.parseInt(normalized);
                if (parsed < 0) {
                    throw new ServiceException(definition.getConfigName() + "不能小于0");
                }
            } catch (NumberFormatException exception) {
                throw new ServiceException(definition.getConfigName() + "必须是整数");
            }
        }
        if ("BOOLEAN".equals(definition.getValueType()) && !List.of("true", "false").contains(normalized)) {
            throw new ServiceException(definition.getConfigName() + "必须是布尔值");
        }
        return normalized;
    }

    private void saveValue(String configKey, String configValue) {
        CallCenterConfigValue value = valueMapper.selectOne(new LambdaQueryWrapper<CallCenterConfigValue>()
            .eq(CallCenterConfigValue::getConfigKey, configKey));
        if (value == null) {
            value = new CallCenterConfigValue();
            value.setConfigKey(configKey);
            value.setConfigValue(configValue);
            value.setVersion(0);
            valueMapper.insert(value);
            return;
        }
        value.setConfigValue(configValue);
        valueMapper.updateById(value);
    }
}
