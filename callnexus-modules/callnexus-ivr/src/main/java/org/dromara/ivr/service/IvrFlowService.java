package org.dromara.ivr.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.ivr.domain.IvrFlow;
import org.dromara.ivr.domain.IvrFlowRequest;
import org.dromara.ivr.domain.IvrFlowResponse;
import org.dromara.ivr.domain.IvrFlowVersion;
import org.dromara.ivr.graph.IvrGraphDefinition;
import org.dromara.ivr.graph.IvrGraphParser;
import org.dromara.ivr.graph.IvrGraphValidator;
import org.dromara.ivr.mapper.IvrFlowMapper;
import org.dromara.ivr.mapper.IvrFlowVersionMapper;
import org.dromara.resource.node.group.domain.FreeSwitchNodeGroup;
import org.dromara.resource.node.group.domain.FreeSwitchNodeGroupMember;
import org.dromara.resource.node.group.mapper.FreeSwitchNodeGroupMapper;
import org.dromara.resource.node.group.mapper.FreeSwitchNodeGroupMemberMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class IvrFlowService {

    private final IvrFlowMapper flowMapper;
    private final IvrFlowVersionMapper versionMapper;
    private final FreeSwitchNodeGroupMapper groupMapper;
    private final FreeSwitchNodeGroupMemberMapper memberMapper;
    private final IvrGraphParser graphParser;
    private final IvrGraphValidator graphValidator;

    public List<IvrFlowResponse> list() {
        return flowMapper.selectList(new LambdaQueryWrapper<IvrFlow>().orderByAsc(IvrFlow::getFlowCode))
            .stream()
            .map(this::response)
            .toList();
    }

    public IvrFlowResponse get(Long id) {
        return response(require(id));
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
        flow.setPublishStatus(flow.getLatestVersionNo() > 0 ? "PUBLISHED" : "DRAFT");
        flow.setVersion(request.getVersion());
        if (flowMapper.updateById(flow) != 1) {
            throw new ServiceException("IVR_FLOW_UPDATE_CONFLICT");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void publish(Long id) {
        IvrFlow flow = require(id);
        IvrGraphDefinition graph = graphParser.parse(flow.getDraftGraphJson());
        graphValidator.validate(flow, graph);
        IvrFlowVersion version = new IvrFlowVersion();
        version.setFlowId(flow.getId());
        version.setVersionNo(flow.getLatestVersionNo() + 1);
        version.setGraphJson(flow.getDraftGraphJson());
        version.setStatus("PUBLISHED");
        version.setPublishedAt(LocalDateTime.now());
        versionMapper.insert(version);
        flow.setLatestVersionNo(version.getVersionNo());
        flow.setPublishStatus("PUBLISHED");
        flowMapper.updateById(flow);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        IvrFlow flow = require(id);
        if ("PUBLISHED".equals(flow.getPublishStatus())) {
            throw new ServiceException("IVR_FLOW_IS_PUBLISHED");
        }
        flowMapper.deleteById(id);
    }

    public IvrFlow requirePublished(Long id) {
        IvrFlow flow = require(id);
        if (!Boolean.TRUE.equals(flow.getEnabled()) || !"PUBLISHED".equals(flow.getPublishStatus())) {
            throw new ServiceException("IVR_FLOW_NOT_PUBLISHED");
        }
        return flow;
    }

    public IvrFlowVersion latestVersion(IvrFlow flow) {
        IvrFlowVersion version = versionMapper.selectOne(new LambdaQueryWrapper<IvrFlowVersion>()
            .eq(IvrFlowVersion::getFlowId, flow.getId())
            .eq(IvrFlowVersion::getVersionNo, flow.getLatestVersionNo())
            .last("limit 1"));
        if (version == null) {
            throw new ServiceException("IVR_VERSION_NOT_FOUND");
        }
        return version;
    }

    private IvrFlow require(Long id) {
        IvrFlow flow = flowMapper.selectById(id);
        if (flow == null) {
            throw new ServiceException("IVR_FLOW_NOT_FOUND");
        }
        return flow;
    }

    private FreeSwitchNodeGroup requireGroup(Long id) {
        FreeSwitchNodeGroup group = groupMapper.selectById(id);
        if (group == null || !Boolean.TRUE.equals(group.getEnabled())) {
            throw new ServiceException("NODE_GROUP_NOT_FOUND_OR_DISABLED");
        }
        return group;
    }

    private void ensureCode(String code, Long excluded) {
        if (flowMapper.exists(new LambdaQueryWrapper<IvrFlow>()
            .eq(IvrFlow::getFlowCode, code)
            .ne(excluded != null, IvrFlow::getId, excluded))) {
            throw new ServiceException("IVR_FLOW_CODE_EXISTS");
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
}
