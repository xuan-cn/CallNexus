package org.dromara.ivr.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.ivr.domain.IvrFlow;
import org.dromara.ivr.domain.IvrFlowRequest;
import org.dromara.ivr.domain.IvrFlowResponse;
import org.dromara.ivr.domain.IvrFlowVersion;
import org.dromara.ivr.domain.IvrFlowVersionResponse;
import org.dromara.ivr.graph.IvrGraphDefinition;
import org.dromara.ivr.graph.IvrGraphParser;
import org.dromara.ivr.graph.IvrGraphValidator;
import org.dromara.ivr.mapper.IvrFlowMapper;
import org.dromara.ivr.mapper.IvrFlowVersionMapper;
import org.dromara.resource.node.group.domain.FreeSwitchNodeGroup;
import org.dromara.resource.node.group.domain.FreeSwitchNodeGroupMember;
import org.dromara.resource.node.group.mapper.FreeSwitchNodeGroupMapper;
import org.dromara.resource.node.group.mapper.FreeSwitchNodeGroupMemberMapper;
import org.dromara.resource.phone.domain.PhoneNumber;
import org.dromara.resource.phone.mapper.PhoneNumberMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class IvrFlowService {

    private final IvrFlowMapper flowMapper;
    private final IvrFlowVersionMapper versionMapper;
    private final FreeSwitchNodeGroupMapper groupMapper;
    private final FreeSwitchNodeGroupMemberMapper memberMapper;
    private final IvrGraphParser graphParser;
    private final IvrGraphValidator graphValidator;
    private final PhoneNumberMapper phoneNumberMapper;

    public List<IvrFlowResponse> list() {
        return flowMapper.selectList(new LambdaQueryWrapper<IvrFlow>().orderByAsc(IvrFlow::getFlowCode))
            .stream()
            .map(this::response)
            .toList();
    }

    public IvrFlowResponse get(Long id) {
        return response(require(id));
    }

    public List<IvrFlowVersionResponse> versions(Long id) {
        IvrFlow flow = require(id);
        return versionMapper.selectList(new LambdaQueryWrapper<IvrFlowVersion>()
                .eq(IvrFlowVersion::getFlowId, id)
                .orderByDesc(IvrFlowVersion::getVersionNo))
            .stream()
            .map(version -> versionResponse(flow, version, false))
            .toList();
    }

    public IvrFlowVersionResponse version(Long id, Integer versionNo) {
        IvrFlow flow = require(id);
        return versionResponse(flow, requireVersion(id, versionNo), true);
    }

    @Transactional(rollbackFor = Exception.class)
    public Long create(IvrFlowRequest request) {
        ensureCode(request.getFlowCode(), null);
        requireGroup(request.getNodeGroupId());
        graphParser.parse(request.getDraftGraphJson());
        IvrFlow flow = new IvrFlow();
        apply(flow, request);
        flow.setLatestVersionNo(0);
        flow.setPublishStatus("DRAFT");
        flowMapper.insert(flow);
        return flow.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, IvrFlowRequest request) {
        ensureCode(request.getFlowCode(), id);
        requireGroup(request.getNodeGroupId());
        graphParser.parse(request.getDraftGraphJson());
        IvrFlow flow = require(id);
        apply(flow, request);
        flow.setVersion(request.getVersion());
        if (flowMapper.updateById(flow) != 1) {
            throw new ServiceException("IVR 流程已被其他用户修改，请刷新后重试");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void publish(Long id) {
        IvrFlow flow = require(id);
        IvrGraphDefinition graph = graphParser.parse(flow.getDraftGraphJson());
        graphValidator.validate(flow, graph);
        IvrFlowVersion version = createVersion(flow, flow.getDraftGraphJson());
        flow.setLatestVersionNo(version.getVersionNo());
        flow.setPublishStatus("PUBLISHED");
        flowMapper.updateById(flow);
    }

    @Transactional(rollbackFor = Exception.class)
    public void rollback(Long id, Integer versionNo) {
        IvrFlow flow = require(id);
        if ("PUBLISHED".equals(flow.getPublishStatus()) && versionNo.equals(flow.getLatestVersionNo())) {
            throw new ServiceException("所选版本已是当前发布版本，无需回滚");
        }
        IvrFlowVersion source = requireVersion(id, versionNo);
        IvrGraphDefinition graph = graphParser.parse(source.getGraphJson());
        graphValidator.validate(flow, graph);
        IvrFlowVersion version = createVersion(flow, source.getGraphJson());
        flow.setDraftGraphJson(source.getGraphJson());
        flow.setLatestVersionNo(version.getVersionNo());
        flow.setPublishStatus("PUBLISHED");
        flowMapper.updateById(flow);
    }

    @Transactional(rollbackFor = Exception.class)
    public void unpublish(Long id) {
        IvrFlow flow = require(id);
        if (!"PUBLISHED".equals(flow.getPublishStatus())) {
            throw new ServiceException("IVR 流程尚未发布，无需取消发布");
        }
        ensureNotReferenced(id, "IVR 流程仍被号码呼入路由引用，请先解除号码绑定");
        flow.setPublishStatus("DRAFT");
        flowMapper.updateById(flow);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        IvrFlow flow = require(id);
        if ("PUBLISHED".equals(flow.getPublishStatus())) {
            throw new ServiceException("IVR 流程已发布，无法删除");
        }
        ensureNotReferenced(id, "IVR 流程仍被号码呼入路由引用，无法删除");
        flowMapper.deleteById(id);
    }

    public IvrFlow requirePublished(Long id) {
        IvrFlow flow = require(id);
        if (!Boolean.TRUE.equals(flow.getEnabled()) || !"PUBLISHED".equals(flow.getPublishStatus())) {
            throw new ServiceException("IVR 流程尚未发布");
        }
        return flow;
    }

    public IvrFlowVersion latestVersion(IvrFlow flow) {
        return requireVersion(flow.getId(), flow.getLatestVersionNo());
    }

    private IvrFlow require(Long id) {
        IvrFlow flow = flowMapper.selectById(id);
        if (flow == null) {
            throw new ServiceException("IVR 流程不存在");
        }
        return flow;
    }

    private FreeSwitchNodeGroup requireGroup(Long id) {
        FreeSwitchNodeGroup group = groupMapper.selectById(id);
        if (group == null || !Boolean.TRUE.equals(group.getEnabled())) {
            throw new ServiceException("节点分组不存在或已停用");
        }
        return group;
    }

    private IvrFlowVersion requireVersion(Long flowId, Integer versionNo) {
        IvrFlowVersion version = versionMapper.selectOne(new LambdaQueryWrapper<IvrFlowVersion>()
            .eq(IvrFlowVersion::getFlowId, flowId)
            .eq(IvrFlowVersion::getVersionNo, versionNo)
            .last("limit 1"));
        if (version == null) {
            throw new ServiceException("IVR 流程版本不存在");
        }
        return version;
    }

    private IvrFlowVersion createVersion(IvrFlow flow, String graphJson) {
        IvrFlowVersion version = new IvrFlowVersion();
        version.setFlowId(flow.getId());
        version.setVersionNo(flow.getLatestVersionNo() + 1);
        version.setGraphJson(graphJson);
        version.setStatus("PUBLISHED");
        version.setPublishedAt(LocalDateTime.now());
        versionMapper.insert(version);
        return version;
    }

    private void ensureCode(String code, Long excluded) {
        if (flowMapper.exists(new LambdaQueryWrapper<IvrFlow>()
            .eq(IvrFlow::getFlowCode, code)
            .ne(excluded != null, IvrFlow::getId, excluded))) {
            throw new ServiceException("IVR 流程编码已存在");
        }
    }

    private void ensureNotReferenced(Long flowId, String message) {
        if (phoneNumberMapper.exists(new LambdaQueryWrapper<PhoneNumber>()
            .eq(PhoneNumber::getRouteType, "IVR")
            .eq(PhoneNumber::getRouteTarget, String.valueOf(flowId)))) {
            throw new ServiceException(message);
        }
    }

    private void apply(IvrFlow flow, IvrFlowRequest request) {
        flow.setFlowCode(request.getFlowCode());
        flow.setFlowName(request.getFlowName());
        flow.setNodeGroupId(request.getNodeGroupId());
        flow.setDraftGraphJson(request.getDraftGraphJson());
        flow.setEnabled(request.getEnabled());
        flow.setRemark(request.getRemark());
    }

    private IvrFlowResponse response(IvrFlow flow) {
        IvrFlowResponse response = new IvrFlowResponse();
        response.setId(flow.getId());
        response.setFlowCode(flow.getFlowCode());
        response.setFlowName(flow.getFlowName());
        response.setNodeGroupId(flow.getNodeGroupId());
        FreeSwitchNodeGroup group = groupMapper.selectById(flow.getNodeGroupId());
        response.setNodeGroupName(group == null ? null : group.getGroupName());
        response.setNodeIds(memberMapper.selectList(new LambdaQueryWrapper<FreeSwitchNodeGroupMember>()
                .eq(FreeSwitchNodeGroupMember::getGroupId, flow.getNodeGroupId()))
            .stream()
            .map(FreeSwitchNodeGroupMember::getNodeId)
            .toList());
        response.setDraftGraphJson(flow.getDraftGraphJson());
        response.setLatestVersionNo(flow.getLatestVersionNo());
        response.setPublishStatus(flow.getPublishStatus());
        response.setEnabled(flow.getEnabled());
        response.setRemark(flow.getRemark());
        response.setVersion(flow.getVersion());
        response.setCreateTime(flow.getCreateTime());
        return response;
    }

    private IvrFlowVersionResponse versionResponse(IvrFlow flow, IvrFlowVersion version, boolean includeGraphJson) {
        IvrGraphDefinition graph = graphParser.parse(version.getGraphJson());
        Map<String, Integer> nodeTypeCounts = graph.nodes().stream()
            .collect(Collectors.toMap(
                node -> node.type(),
                node -> 1,
                Integer::sum,
                LinkedHashMap::new
            ));
        IvrFlowVersionResponse response = new IvrFlowVersionResponse();
        response.setId(version.getId());
        response.setFlowId(version.getFlowId());
        response.setVersionNo(version.getVersionNo());
        if (includeGraphJson) {
            response.setGraphJson(version.getGraphJson());
        }
        response.setStatus(version.getStatus());
        response.setPublishedAt(version.getPublishedAt());
        response.setNodeCount(graph.nodes().size());
        response.setEdgeCount(graph.edges().size());
        response.setNodeTypeCounts(nodeTypeCounts);
        response.setCurrentVersion(version.getVersionNo().equals(flow.getLatestVersionNo())
            && "PUBLISHED".equals(flow.getPublishStatus()));
        return response;
    }
}
