package org.dromara.resource.node.group.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.resource.media.service.MediaPublicationService;
import org.dromara.resource.media.domain.MediaPublication;
import org.dromara.resource.media.mapper.MediaPublicationMapper;
import org.dromara.resource.node.domain.FreeSwitchNode;
import org.dromara.resource.node.group.domain.FreeSwitchNodeGroup;
import org.dromara.resource.node.group.domain.FreeSwitchNodeGroupMember;
import org.dromara.resource.node.group.domain.NodeGroupRequest;
import org.dromara.resource.node.group.domain.NodeGroupResponse;
import org.dromara.resource.node.group.mapper.FreeSwitchNodeGroupMapper;
import org.dromara.resource.node.group.mapper.FreeSwitchNodeGroupMemberMapper;
import org.dromara.resource.node.mapper.FreeSwitchNodeMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class NodeGroupService {
    private final FreeSwitchNodeGroupMapper groupMapper;
    private final FreeSwitchNodeGroupMemberMapper memberMapper;
    private final FreeSwitchNodeMapper nodeMapper;
    private final MediaPublicationService publicationService;
    private final MediaPublicationMapper publicationMapper;

    public List<NodeGroupResponse> list() {
        return groupMapper.selectList(new LambdaQueryWrapper<FreeSwitchNodeGroup>().orderByAsc(FreeSwitchNodeGroup::getGroupCode))
            .stream().map(this::response).toList();
    }

    public NodeGroupResponse get(Long id) {
        return response(requireGroup(id));
    }

    @Transactional(rollbackFor = Exception.class)
    public Long create(NodeGroupRequest request) {
        ensureCode(request.getGroupCode(), null);
        validateNodes(request.getNodeIds());
        FreeSwitchNodeGroup group = new FreeSwitchNodeGroup();
        apply(group, request);
        groupMapper.insert(group);
        replaceMembers(group.getId(), request.getNodeIds());
        return group.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, NodeGroupRequest request) {
        ensureCode(request.getGroupCode(), id);
        validateNodes(request.getNodeIds());
        FreeSwitchNodeGroup group = requireGroup(id);
        List<Long> oldIds = memberIds(id);
        apply(group, request);
        group.setVersion(request.getVersion());
        if (groupMapper.updateById(group) != 1) throw new ServiceException("节点分组已被其他用户修改，请刷新后重试");
        replaceMembers(id, request.getNodeIds());
        List<Long> added = request.getNodeIds().stream().filter(nodeId -> !oldIds.contains(nodeId)).toList();
        if (!added.isEmpty()) publicationService.syncNewGroupMembers(id, added);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        requireGroup(id);
        if (publicationMapper.exists(new LambdaQueryWrapper<MediaPublication>().eq(MediaPublication::getNodeGroupId, id)
            .ne(MediaPublication::getStatus, "UNPUBLISHED"))) {
            throw new ServiceException("节点分组下存在已发布的媒体，无法删除");
        }
        memberMapper.delete(new LambdaQueryWrapper<FreeSwitchNodeGroupMember>().eq(FreeSwitchNodeGroupMember::getGroupId, id));
        groupMapper.deleteById(id);
    }

    private void replaceMembers(Long groupId, List<Long> nodeIds) {
        memberMapper.delete(new LambdaQueryWrapper<FreeSwitchNodeGroupMember>().eq(FreeSwitchNodeGroupMember::getGroupId, groupId));
        for (Long nodeId : nodeIds.stream().distinct().toList()) {
            FreeSwitchNodeGroupMember member = new FreeSwitchNodeGroupMember();
            member.setGroupId(groupId);
            member.setNodeId(nodeId);
            memberMapper.insert(member);
        }
    }

    private List<Long> memberIds(Long groupId) {
        return memberMapper.selectList(new LambdaQueryWrapper<FreeSwitchNodeGroupMember>()
            .eq(FreeSwitchNodeGroupMember::getGroupId, groupId)).stream().map(FreeSwitchNodeGroupMember::getNodeId).toList();
    }

    private void validateNodes(List<Long> nodeIds) {
        long count = nodeMapper.selectCount(new LambdaQueryWrapper<FreeSwitchNode>().in(FreeSwitchNode::getId, nodeIds).eq(FreeSwitchNode::getEnabled, true));
        if (count != nodeIds.stream().distinct().count()) throw new ServiceException("节点分组包含无效或已停用的节点");
    }

    private void ensureCode(String code, Long excludedId) {
        if (groupMapper.exists(new LambdaQueryWrapper<FreeSwitchNodeGroup>().eq(FreeSwitchNodeGroup::getGroupCode, code)
            .ne(excludedId != null, FreeSwitchNodeGroup::getId, excludedId))) throw new ServiceException("节点分组编码已存在");
    }

    private FreeSwitchNodeGroup requireGroup(Long id) {
        FreeSwitchNodeGroup group = groupMapper.selectById(id);
        if (group == null) throw new ServiceException("节点分组不存在");
        return group;
    }

    private void apply(FreeSwitchNodeGroup group, NodeGroupRequest request) {
        group.setGroupCode(request.getGroupCode());
        group.setGroupName(request.getGroupName());
        group.setEnabled(request.getEnabled());
        group.setRemark(request.getRemark());
    }

    private NodeGroupResponse response(FreeSwitchNodeGroup group) {
        NodeGroupResponse response = new NodeGroupResponse();
        response.setId(group.getId());
        response.setGroupCode(group.getGroupCode());
        response.setGroupName(group.getGroupName());
        response.setNodeIds(memberIds(group.getId()));
        response.setMemberCount(response.getNodeIds().size());
        response.setEnabled(group.getEnabled());
        response.setRemark(group.getRemark());
        response.setVersion(group.getVersion());
        response.setCreateTime(group.getCreateTime());
        return response;
    }
}
