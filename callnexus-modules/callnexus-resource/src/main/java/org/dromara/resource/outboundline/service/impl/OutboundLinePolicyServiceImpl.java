package org.dromara.resource.outboundline.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.resource.node.domain.FreeSwitchNode;
import org.dromara.resource.node.mapper.FreeSwitchNodeMapper;
import org.dromara.resource.outboundline.domain.OutboundLinePolicy;
import org.dromara.resource.outboundline.domain.OutboundLinePolicyItem;
import org.dromara.resource.outboundline.domain.SkillGroupMemberRef;
import org.dromara.resource.outboundline.domain.SkillGroupOutboundPolicy;
import org.dromara.resource.outboundline.domain.SkillGroupRef;
import org.dromara.resource.outboundline.domain.request.OutboundLinePolicyItemRequest;
import org.dromara.resource.outboundline.domain.request.OutboundLinePolicyPageQuery;
import org.dromara.resource.outboundline.domain.request.OutboundLinePolicyRequest;
import org.dromara.resource.outboundline.domain.request.SkillGroupOutboundPolicyRequest;
import org.dromara.resource.outboundline.domain.response.OutboundLinePolicyItemResponse;
import org.dromara.resource.outboundline.domain.response.OutboundLinePolicyResponse;
import org.dromara.resource.outboundline.domain.response.SkillGroupOutboundPolicyResponse;
import org.dromara.resource.outboundline.mapper.OutboundLinePolicyItemMapper;
import org.dromara.resource.outboundline.mapper.OutboundLinePolicyMapper;
import org.dromara.resource.outboundline.mapper.SkillGroupMemberRefMapper;
import org.dromara.resource.outboundline.mapper.SkillGroupOutboundPolicyMapper;
import org.dromara.resource.outboundline.mapper.SkillGroupRefMapper;
import org.dromara.resource.outboundline.service.OutboundLinePolicyService;
import org.dromara.resource.phone.domain.PhoneNumber;
import org.dromara.resource.phone.domain.response.PhoneNumberOutboundRouteResponse;
import org.dromara.resource.phone.mapper.PhoneNumberMapper;
import org.dromara.resource.phone.service.PhoneNumberQueryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class OutboundLinePolicyServiceImpl implements OutboundLinePolicyService {

    private static final String REDIS_ROUND_ROBIN_KEY_PREFIX = "callnexus:outbound-line-policy:rr:";

    private final OutboundLinePolicyMapper policyMapper;
    private final OutboundLinePolicyItemMapper itemMapper;
    private final SkillGroupOutboundPolicyMapper skillGroupPolicyMapper;
    private final SkillGroupMemberRefMapper skillGroupMemberMapper;
    private final SkillGroupRefMapper skillGroupMapper;
    private final FreeSwitchNodeMapper nodeMapper;
    private final PhoneNumberMapper phoneNumberMapper;
    private final PhoneNumberQueryService phoneNumberQueryService;

    @Override
    public TableDataInfo<OutboundLinePolicyResponse> page(OutboundLinePolicyPageQuery query, PageQuery pageQuery) {
        LambdaQueryWrapper<OutboundLinePolicy> wrapper = new LambdaQueryWrapper<OutboundLinePolicy>()
            .eq(query.getNodeId() != null, OutboundLinePolicy::getNodeId, query.getNodeId())
            .like(StringUtils.isNotBlank(query.getPolicyCode()), OutboundLinePolicy::getPolicyCode, query.getPolicyCode())
            .like(StringUtils.isNotBlank(query.getPolicyName()), OutboundLinePolicy::getPolicyName, query.getPolicyName())
            .eq(StringUtils.isNotBlank(query.getPolicyType()), OutboundLinePolicy::getPolicyType, query.getPolicyType())
            .eq(query.getDefaultPolicy() != null, OutboundLinePolicy::getDefaultPolicy, query.getDefaultPolicy())
            .eq(query.getEnabled() != null, OutboundLinePolicy::getEnabled, query.getEnabled())
            .orderByAsc(OutboundLinePolicy::getNodeId)
            .orderByAsc(OutboundLinePolicy::getPolicyCode);
        Page<OutboundLinePolicy> page = policyMapper.selectPage(pageQuery.build(), wrapper);
        return new TableDataInfo<>(page.getRecords().stream().map(this::toResponse).toList(), page.getTotal());
    }

    @Override
    public OutboundLinePolicyResponse get(Long id) {
        OutboundLinePolicy policy = policyMapper.selectById(id);
        if (policy == null) throw new ServiceException("外呼线路策略不存在");
        return toResponse(policy);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(OutboundLinePolicyRequest request) {
        ensureNodeExists(request.getNodeId());
        ensurePolicyCodeUnique(request.getPolicyCode(), null);
        ensureItemsValid(request.getNodeId(), request.getItems());
        OutboundLinePolicy policy = new OutboundLinePolicy();
        apply(policy, request);
        policyMapper.insert(policy);
        saveItems(policy.getId(), request.getItems());
        if (Boolean.TRUE.equals(policy.getDefaultPolicy())) {
            clearOtherDefaultPolicies(policy.getId(), policy.getNodeId());
        }
        log.info("新增外呼线路策略，policyId={}，policyCode={}，nodeId={}，policyType={}，defaultPolicy={}",
            policy.getId(), policy.getPolicyCode(), policy.getNodeId(), policy.getPolicyType(), policy.getDefaultPolicy());
        return policy.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, OutboundLinePolicyRequest request) {
        OutboundLinePolicy policy = policyMapper.selectById(id);
        if (policy == null) throw new ServiceException("外呼线路策略不存在");
        ensureNodeExists(request.getNodeId());
        ensurePolicyCodeUnique(request.getPolicyCode(), id);
        ensureItemsValid(request.getNodeId(), request.getItems());
        apply(policy, request);
        policy.setVersion(request.getVersion());
        if (policyMapper.updateById(policy) != 1) throw new ServiceException("外呼线路策略已被其他用户修改，请刷新后重试");
        itemMapper.delete(new LambdaQueryWrapper<OutboundLinePolicyItem>().eq(OutboundLinePolicyItem::getPolicyId, id));
        saveItems(id, request.getItems());
        if (Boolean.TRUE.equals(policy.getDefaultPolicy())) {
            clearOtherDefaultPolicies(policy.getId(), policy.getNodeId());
        }
        log.info("更新外呼线路策略，policyId={}，policyCode={}，nodeId={}，policyType={}，defaultPolicy={}，enabled={}",
            policy.getId(), policy.getPolicyCode(), policy.getNodeId(), policy.getPolicyType(), policy.getDefaultPolicy(), policy.getEnabled());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        OutboundLinePolicy policy = policyMapper.selectById(id);
        if (policy == null) throw new ServiceException("外呼线路策略不存在");
        if (skillGroupPolicyMapper.exists(new LambdaQueryWrapper<SkillGroupOutboundPolicy>()
            .eq(SkillGroupOutboundPolicy::getOutboundLinePolicyId, id))) {
            throw new ServiceException("外呼线路策略已被技能组引用，无法删除");
        }
        itemMapper.delete(new LambdaQueryWrapper<OutboundLinePolicyItem>().eq(OutboundLinePolicyItem::getPolicyId, id));
        policyMapper.deleteById(id);
        log.info("删除外呼线路策略，policyId={}，policyCode={}，nodeId={}", id, policy.getPolicyCode(), policy.getNodeId());
    }

    @Override
    public PhoneNumberOutboundRouteResponse selectRoute(String tenantId, Long nodeId) {
        return selectRoute(tenantId, nodeId, null, null);
    }

    @Override
    public PhoneNumberOutboundRouteResponse selectRoute(String tenantId, Long nodeId, Long agentId, Long skillGroupId) {
        if (nodeId == null) return null;
        return TenantHelper.dynamic(tenantId, () -> {
            PolicySelection selection = findPolicyForOutbound(nodeId, agentId, skillGroupId);
            OutboundLinePolicy policy = selection == null ? null : selection.policy();
            if (policy == null) return null;
            List<OutboundLinePolicyItem> items = findEnabledItems(policy.getId());
            if (items.isEmpty()) {
                log.warn("外呼线路策略没有启用的线路明细，tenantId={}，nodeId={}，policyId={}，policyCode={}",
                    tenantId, nodeId, policy.getId(), policy.getPolicyCode());
                return null;
            }
            for (OutboundLinePolicyItem item : orderedCandidates(tenantId, policy, items)) {
                PhoneNumberOutboundRouteResponse route = phoneNumberQueryService.findOutboundRouteByNumberId(tenantId, nodeId, item.getPhoneNumberId());
                if (route != null) {
                    route.setPolicyId(policy.getId());
                    route.setPolicyCode(policy.getPolicyCode());
                    route.setPolicyName(policy.getPolicyName());
                    route.setPolicyType(policy.getPolicyType());
                    route.setPolicyItemId(item.getId());
                    log.info("外呼线路策略选线成功，tenantId={}，nodeId={}，agentId={}，skillGroupId={}，scope={}，policyCode={}，policyType={}，phoneNumberId={}，gatewayCode={}",
                        tenantId, nodeId, agentId, selection.skillGroupId(), selection.scope(), policy.getPolicyCode(),
                        policy.getPolicyType(), item.getPhoneNumberId(), route.getGatewayCode());
                    return route;
                }
                log.warn("外呼线路策略明细不可用，tenantId={}，nodeId={}，policyCode={}，phoneNumberId={}",
                    tenantId, nodeId, policy.getPolicyCode(), item.getPhoneNumberId());
            }
            log.warn("外呼线路策略没有可用线路，tenantId={}，nodeId={}，policyCode={}", tenantId, nodeId, policy.getPolicyCode());
            return null;
        });
    }

    @Override
    public List<SkillGroupOutboundPolicyResponse> listSkillGroupPolicies(Long skillGroupId) {
        return skillGroupPolicyMapper.selectList(new LambdaQueryWrapper<SkillGroupOutboundPolicy>()
                .eq(skillGroupId != null, SkillGroupOutboundPolicy::getSkillGroupId, skillGroupId)
                .orderByAsc(SkillGroupOutboundPolicy::getNodeId)
                .orderByAsc(SkillGroupOutboundPolicy::getId))
            .stream().map(this::toSkillGroupPolicyResponse).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long saveSkillGroupPolicy(SkillGroupOutboundPolicyRequest request) {
        ensureNodeExists(request.getNodeId());
        SkillGroupRef group = skillGroupMapper.selectById(request.getSkillGroupId());
        if (group == null || !Boolean.TRUE.equals(group.getEnabled())) {
            throw new ServiceException("技能组不存在或已停用");
        }
        OutboundLinePolicy policy = policyMapper.selectById(request.getOutboundLinePolicyId());
        if (policy == null || !Boolean.TRUE.equals(policy.getEnabled()) || !request.getNodeId().equals(policy.getNodeId())) {
            throw new ServiceException("外呼线路策略不存在、已停用或不属于当前节点");
        }

        SkillGroupOutboundPolicy binding = skillGroupPolicyMapper.selectOne(new LambdaQueryWrapper<SkillGroupOutboundPolicy>()
            .eq(SkillGroupOutboundPolicy::getNodeId, request.getNodeId())
            .eq(SkillGroupOutboundPolicy::getSkillGroupId, request.getSkillGroupId())
            .last("limit 1"));
        if (binding == null) {
            binding = new SkillGroupOutboundPolicy();
            binding.setNodeId(request.getNodeId());
            binding.setSkillGroupId(request.getSkillGroupId());
        } else {
            binding.setVersion(request.getVersion());
        }
        binding.setOutboundLinePolicyId(request.getOutboundLinePolicyId());
        binding.setEnabled(request.getEnabled() == null || request.getEnabled());
        binding.setRemark(request.getRemark());
        if (binding.getId() == null) {
            skillGroupPolicyMapper.insert(binding);
        } else if (skillGroupPolicyMapper.updateById(binding) != 1) {
            throw new ServiceException("技能组外呼策略绑定已被其他用户修改，请刷新后重试");
        }
        return binding.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteSkillGroupPolicy(Long id) {
        skillGroupPolicyMapper.deleteById(id);
    }

    private PolicySelection findPolicyForOutbound(Long nodeId, Long agentId, Long skillGroupId) {
        if (skillGroupId != null) {
            SkillGroupOutboundPolicy binding = findSkillGroupBinding(nodeId, skillGroupId);
            if (binding == null) return null;
            OutboundLinePolicy policy = findEnabledPolicy(binding.getOutboundLinePolicyId(), nodeId);
            return policy == null ? null : new PolicySelection(policy, "SKILL_GROUP", skillGroupId);
        }
        if (agentId != null) {
            List<SkillGroupOutboundPolicy> bindings = findAgentSkillGroupBindings(nodeId, agentId);
            if (bindings.size() == 1) {
                SkillGroupOutboundPolicy binding = bindings.get(0);
                OutboundLinePolicy policy = findEnabledPolicy(binding.getOutboundLinePolicyId(), nodeId);
                if (policy != null) return new PolicySelection(policy, "AGENT_SKILL_GROUP", binding.getSkillGroupId());
            }
            if (bindings.size() > 1) {
                log.warn("坐席命中多个技能组外呼策略，拒绝自动选择，nodeId={}，agentId={}，skillGroupIds={}",
                    nodeId, agentId, bindings.stream().map(SkillGroupOutboundPolicy::getSkillGroupId).toList());
                throw new ServiceException("坐席属于多个已配置外呼策略的技能组，请指定外呼技能组或只保留一个默认策略");
            }
        }
        OutboundLinePolicy defaultPolicy = findDefaultPolicy(nodeId);
        return defaultPolicy == null ? null : new PolicySelection(defaultPolicy, "NODE_DEFAULT", null);
    }

    private SkillGroupOutboundPolicy findSkillGroupBinding(Long nodeId, Long skillGroupId) {
        return skillGroupPolicyMapper.selectOne(new LambdaQueryWrapper<SkillGroupOutboundPolicy>()
            .eq(SkillGroupOutboundPolicy::getNodeId, nodeId)
            .eq(SkillGroupOutboundPolicy::getSkillGroupId, skillGroupId)
            .eq(SkillGroupOutboundPolicy::getEnabled, true)
            .last("limit 1"));
    }

    private List<SkillGroupOutboundPolicy> findAgentSkillGroupBindings(Long nodeId, Long agentId) {
        List<Long> groupIds = skillGroupMemberMapper.selectList(new LambdaQueryWrapper<SkillGroupMemberRef>()
                .eq(SkillGroupMemberRef::getAgentId, agentId)
                .orderByAsc(SkillGroupMemberRef::getPriority)
                .orderByAsc(SkillGroupMemberRef::getId))
            .stream().map(SkillGroupMemberRef::getSkillGroupId).distinct().toList();
        if (groupIds.isEmpty()) return List.of();
        return skillGroupPolicyMapper.selectList(new LambdaQueryWrapper<SkillGroupOutboundPolicy>()
            .eq(SkillGroupOutboundPolicy::getNodeId, nodeId)
            .in(SkillGroupOutboundPolicy::getSkillGroupId, groupIds)
            .eq(SkillGroupOutboundPolicy::getEnabled, true)
            .orderByAsc(SkillGroupOutboundPolicy::getId));
    }

    private OutboundLinePolicy findEnabledPolicy(Long policyId, Long nodeId) {
        return policyMapper.selectOne(new LambdaQueryWrapper<OutboundLinePolicy>()
            .eq(OutboundLinePolicy::getId, policyId)
            .eq(OutboundLinePolicy::getNodeId, nodeId)
            .eq(OutboundLinePolicy::getEnabled, true)
            .last("limit 1"));
    }

    private OutboundLinePolicy findDefaultPolicy(Long nodeId) {
        return policyMapper.selectOne(new LambdaQueryWrapper<OutboundLinePolicy>()
            .eq(OutboundLinePolicy::getNodeId, nodeId)
            .eq(OutboundLinePolicy::getEnabled, true)
            .eq(OutboundLinePolicy::getDefaultPolicy, true)
            .orderByAsc(OutboundLinePolicy::getId)
            .last("limit 1"));
    }

    private List<OutboundLinePolicyItem> findEnabledItems(Long policyId) {
        return itemMapper.selectList(new LambdaQueryWrapper<OutboundLinePolicyItem>()
            .eq(OutboundLinePolicyItem::getPolicyId, policyId)
            .eq(OutboundLinePolicyItem::getEnabled, true)
            .orderByAsc(OutboundLinePolicyItem::getSortOrder)
            .orderByAsc(OutboundLinePolicyItem::getId));
    }

    private List<OutboundLinePolicyItem> orderedCandidates(String tenantId, OutboundLinePolicy policy, List<OutboundLinePolicyItem> items) {
        List<OutboundLinePolicyItem> sorted = items.stream()
            .sorted(Comparator.comparing(OutboundLinePolicyItem::getSortOrder, Comparator.nullsLast(Integer::compareTo))
                .thenComparing(OutboundLinePolicyItem::getId))
            .toList();
        if ("FIXED".equals(policy.getPolicyType()) || sorted.size() == 1) {
            return sorted;
        }
        int index = "WEIGHT".equals(policy.getPolicyType())
            ? weightedIndex(tenantId, policy, sorted)
            : roundRobinIndex(tenantId, policy, sorted.size());
        List<OutboundLinePolicyItem> candidates = new ArrayList<>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            candidates.add(sorted.get((index + i) % sorted.size()));
        }
        return candidates;
    }

    private int roundRobinIndex(String tenantId, OutboundLinePolicy policy, int size) {
        long sequence = RedisUtils.incrAtomicValue(redisSequenceKey(tenantId, policy));
        return (int) Math.floorMod(sequence - 1, size);
    }

    private int weightedIndex(String tenantId, OutboundLinePolicy policy, List<OutboundLinePolicyItem> items) {
        int totalWeight = items.stream().mapToInt(item -> Math.max(1, item.getWeight() == null ? 1 : item.getWeight())).sum();
        long sequence = RedisUtils.incrAtomicValue(redisSequenceKey(tenantId, policy));
        int cursor = (int) Math.floorMod(sequence - 1, totalWeight);
        for (int i = 0; i < items.size(); i++) {
            cursor -= Math.max(1, items.get(i).getWeight() == null ? 1 : items.get(i).getWeight());
            if (cursor < 0) return i;
        }
        return 0;
    }

    private String redisSequenceKey(String tenantId, OutboundLinePolicy policy) {
        return REDIS_ROUND_ROBIN_KEY_PREFIX + tenantId + ":" + policy.getId();
    }

    private void apply(OutboundLinePolicy policy, OutboundLinePolicyRequest request) {
        policy.setNodeId(request.getNodeId());
        policy.setPolicyCode(request.getPolicyCode());
        policy.setPolicyName(request.getPolicyName());
        policy.setPolicyType(request.getPolicyType());
        policy.setDefaultPolicy(request.getDefaultPolicy());
        policy.setEnabled(request.getEnabled());
        policy.setRemark(request.getRemark());
    }

    private void saveItems(Long policyId, List<OutboundLinePolicyItemRequest> requests) {
        for (OutboundLinePolicyItemRequest request : requests) {
            OutboundLinePolicyItem item = new OutboundLinePolicyItem();
            item.setPolicyId(policyId);
            item.setPhoneNumberId(request.getPhoneNumberId());
            item.setWeight(request.getWeight() == null ? 1 : request.getWeight());
            item.setSortOrder(request.getSortOrder() == null ? 0 : request.getSortOrder());
            item.setEnabled(request.getEnabled());
            itemMapper.insert(item);
        }
    }

    private void ensureNodeExists(Long nodeId) {
        FreeSwitchNode node = nodeMapper.selectById(nodeId);
        if (node == null) throw new ServiceException("FreeSWITCH 节点不存在");
    }

    private void ensurePolicyCodeUnique(String policyCode, Long excludeId) {
        Long count = policyMapper.selectCount(new LambdaQueryWrapper<OutboundLinePolicy>()
            .eq(OutboundLinePolicy::getPolicyCode, policyCode)
            .ne(excludeId != null, OutboundLinePolicy::getId, excludeId));
        if (count != null && count > 0) throw new ServiceException("外呼线路策略编码已存在");
    }

    private void ensureItemsValid(Long nodeId, List<OutboundLinePolicyItemRequest> items) {
        Set<Long> numberIds = new HashSet<>();
        for (OutboundLinePolicyItemRequest item : items) {
            if (!numberIds.add(item.getPhoneNumberId())) {
                throw new ServiceException("外呼线路策略中存在重复号码");
            }
            PhoneNumber number = phoneNumberMapper.selectById(item.getPhoneNumberId());
            if (number == null || !nodeId.equals(number.getNodeId()) || !Boolean.TRUE.equals(number.getEnabled())
                || number.getGatewayId() == null || !("CALLER_ID".equals(number.getNumberType()) || "BOTH".equals(number.getNumberType()))) {
                throw new ServiceException("外呼线路策略只能选择当前节点下已启用且可外呼的号码");
            }
        }
    }

    private void clearOtherDefaultPolicies(Long policyId, Long nodeId) {
        List<OutboundLinePolicy> policies = policyMapper.selectList(new LambdaQueryWrapper<OutboundLinePolicy>()
            .eq(OutboundLinePolicy::getNodeId, nodeId)
            .eq(OutboundLinePolicy::getDefaultPolicy, true)
            .ne(OutboundLinePolicy::getId, policyId));
        for (OutboundLinePolicy policy : policies) {
            policy.setDefaultPolicy(false);
            policyMapper.updateById(policy);
        }
    }

    private OutboundLinePolicyResponse toResponse(OutboundLinePolicy policy) {
        OutboundLinePolicyResponse response = new OutboundLinePolicyResponse();
        response.setId(policy.getId());
        response.setNodeId(policy.getNodeId());
        response.setPolicyCode(policy.getPolicyCode());
        response.setPolicyName(policy.getPolicyName());
        response.setPolicyType(policy.getPolicyType());
        response.setDefaultPolicy(policy.getDefaultPolicy());
        response.setEnabled(policy.getEnabled());
        response.setRemark(policy.getRemark());
        response.setVersion(policy.getVersion());
        response.setCreateTime(policy.getCreateTime());
        FreeSwitchNode node = nodeMapper.selectById(policy.getNodeId());
        if (node != null) response.setNodeName(node.getNodeName());
        List<OutboundLinePolicyItem> items = itemMapper.selectList(new LambdaQueryWrapper<OutboundLinePolicyItem>()
            .eq(OutboundLinePolicyItem::getPolicyId, policy.getId())
            .orderByAsc(OutboundLinePolicyItem::getSortOrder)
            .orderByAsc(OutboundLinePolicyItem::getId));
        Map<Long, PhoneNumber> numbers = items.isEmpty()
            ? Map.of()
            : phoneNumberMapper.selectBatchIds(items.stream().map(OutboundLinePolicyItem::getPhoneNumberId).toList())
                .stream().collect(Collectors.toMap(PhoneNumber::getId, Function.identity(), (left, right) -> left));
        response.setItems(items.stream().map(item -> toItemResponse(item, numbers.get(item.getPhoneNumberId()))).toList());
        return response;
    }

    private SkillGroupOutboundPolicyResponse toSkillGroupPolicyResponse(SkillGroupOutboundPolicy binding) {
        SkillGroupOutboundPolicyResponse response = new SkillGroupOutboundPolicyResponse();
        response.setId(binding.getId());
        response.setNodeId(binding.getNodeId());
        response.setSkillGroupId(binding.getSkillGroupId());
        response.setOutboundLinePolicyId(binding.getOutboundLinePolicyId());
        response.setEnabled(binding.getEnabled());
        response.setRemark(binding.getRemark());
        response.setVersion(binding.getVersion());
        response.setCreateTime(binding.getCreateTime());
        FreeSwitchNode node = nodeMapper.selectById(binding.getNodeId());
        if (node != null) response.setNodeName(node.getNodeName());
        SkillGroupRef group = skillGroupMapper.selectById(binding.getSkillGroupId());
        if (group != null) response.setSkillGroupName(group.getGroupName());
        OutboundLinePolicy policy = policyMapper.selectById(binding.getOutboundLinePolicyId());
        if (policy != null) {
            response.setPolicyCode(policy.getPolicyCode());
            response.setPolicyName(policy.getPolicyName());
            response.setPolicyType(policy.getPolicyType());
        }
        return response;
    }

    private OutboundLinePolicyItemResponse toItemResponse(OutboundLinePolicyItem item, PhoneNumber number) {
        OutboundLinePolicyItemResponse response = new OutboundLinePolicyItemResponse();
        response.setId(item.getId());
        response.setPolicyId(item.getPolicyId());
        response.setPhoneNumberId(item.getPhoneNumberId());
        response.setPhoneNumber(number == null ? null : number.getNumber());
        response.setPhoneNumberName(number == null ? null : number.getNumberName());
        response.setWeight(item.getWeight());
        response.setSortOrder(item.getSortOrder());
        response.setEnabled(item.getEnabled());
        response.setVersion(item.getVersion());
        return response;
    }

    private record PolicySelection(OutboundLinePolicy policy, String scope, Long skillGroupId) {
    }
}
