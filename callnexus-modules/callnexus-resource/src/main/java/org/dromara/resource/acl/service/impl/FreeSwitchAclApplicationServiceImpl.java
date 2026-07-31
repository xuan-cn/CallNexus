package org.dromara.resource.acl.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.resource.acl.domain.FreeSwitchAcl;
import org.dromara.resource.acl.domain.FreeSwitchAclEntry;
import org.dromara.resource.acl.domain.FreeSwitchAclSnapshot;
import org.dromara.resource.acl.domain.FreeSwitchAclVersion;
import org.dromara.resource.acl.domain.request.FreeSwitchAclPageQuery;
import org.dromara.resource.acl.domain.request.FreeSwitchAclSaveRequest;
import org.dromara.resource.acl.domain.response.FreeSwitchAclIpTestResponse;
import org.dromara.resource.acl.domain.response.FreeSwitchAclResponse;
import org.dromara.resource.acl.mapper.FreeSwitchAclMapper;
import org.dromara.resource.acl.mapper.FreeSwitchAclVersionMapper;
import org.dromara.resource.acl.service.FreeSwitchAclApplicationService;
import org.dromara.resource.acl.service.FreeSwitchAclConfigurationProvider;
import org.dromara.resource.acl.service.FreeSwitchAclRuntimeSyncService;
import org.dromara.resource.acl.service.IpCidrMatcher;
import org.dromara.resource.node.service.FreeSwitchNodeQueryService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class FreeSwitchAclApplicationServiceImpl implements FreeSwitchAclApplicationService {
    private static final Set<String> RESERVED_ACL_CODES = Set.of(
        "loopback.auto", "rfc1918.auto", "lan", "wan_v4.auto", "wan_v6.auto", "localnet.auto", "domains");

    private final FreeSwitchAclMapper aclMapper;
    private final FreeSwitchAclVersionMapper versionMapper;
    private final FreeSwitchNodeQueryService nodeQueryService;
    private final FreeSwitchAclConfigurationProvider configurationProvider;
    private final ObjectProvider<FreeSwitchAclRuntimeSyncService> runtimeSyncServiceProvider;

    @Override
    public TableDataInfo<FreeSwitchAclResponse> page(FreeSwitchAclPageQuery query, PageQuery pageQuery) {
        IPage<FreeSwitchAcl> page = aclMapper.selectPage(pageQuery.build(),
            new LambdaQueryWrapper<FreeSwitchAcl>()
                .eq(query.getNodeId() != null, FreeSwitchAcl::getNodeId, query.getNodeId())
                .like(query.getAclName() != null && !query.getAclName().isBlank(), FreeSwitchAcl::getAclName, query.getAclName())
                .eq(query.getPurpose() != null && !query.getPurpose().isBlank(), FreeSwitchAcl::getPurpose, query.getPurpose())
                .eq(query.getEnabled() != null, FreeSwitchAcl::getEnabled, query.getEnabled())
                .orderByDesc(FreeSwitchAcl::getUpdateTime));
        return new TableDataInfo<>(page.getRecords().stream().map(this::response).toList(), page.getTotal());
    }

    @Override
    public FreeSwitchAclResponse get(Long id) {
        return response(requireAcl(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(FreeSwitchAclSaveRequest request) {
        validateRequest(null, request);
        FreeSwitchAcl acl = new FreeSwitchAcl();
        apply(acl, request);
        acl.setSyncStatus("NOT_PUBLISHED");
        acl.setVersion(0);
        aclMapper.insert(acl);
        return acl.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, FreeSwitchAclSaveRequest request) {
        FreeSwitchAcl acl = requireAcl(id);
        validateRequest(id, request);
        apply(acl, request);
        acl.setSyncStatus(acl.getPublishedVersionId() == null ? "NOT_PUBLISHED" : "DRAFT_CHANGED");
        acl.setSyncError(null);
        if (request.getVersion() != null) {
            acl.setVersion(request.getVersion());
        }
        if (aclMapper.updateById(acl) != 1) {
            throw new ServiceException("ACL 配置已被其他用户修改，请刷新后重试");
        }
    }

    @Override
    public void delete(Long id) {
        FreeSwitchAcl acl = requireAcl(id);
        if (acl.getPublishedVersionId() != null) {
            throw new ServiceException("已发布 ACL 不能直接删除，请先停用并发布");
        }
        aclMapper.deleteById(id);
    }

    @Override
    public synchronized void publish(Long id) {
        FreeSwitchAcl acl = requireAcl(id);
        List<FreeSwitchAclEntry> entries = entries(acl.getEntriesJson());
        validateEntries(acl.getDefaultAction(), acl.getEnabled(), entries);
        FreeSwitchAclVersion previous = currentVersion(id);
        int versionNo = nextVersionNo(id);
        if (previous != null) {
            setCurrent(previous.getId(), false);
        }
        FreeSwitchAclVersion published = new FreeSwitchAclVersion();
        published.setAclId(id);
        published.setNodeId(acl.getNodeId());
        published.setVersionNo(versionNo);
        published.setSnapshotJson(JsonUtils.toJsonString(snapshot(acl, entries)));
        published.setCurrentVersion(true);
        published.setPublishedAt(LocalDateTime.now());
        versionMapper.insert(published);
        acl.setPublishedVersionId(published.getId());
        acl.setPublishedVersionNo(versionNo);
        acl.setSyncStatus("SYNCING");
        acl.setSyncError(null);
        aclMapper.updateById(acl);
        try {
            requireRuntimeSync().reload(acl.getNodeId());
            acl.setSyncStatus("SYNCED");
            aclMapper.updateById(acl);
            log.info("FreeSWITCH ACL 发布成功，nodeId={}，aclId={}，aclCode={}，versionNo={}",
                acl.getNodeId(), acl.getId(), acl.getAclCode(), versionNo);
        } catch (RuntimeException exception) {
            setCurrent(published.getId(), false);
            if (previous != null) {
                setCurrent(previous.getId(), true);
                acl.setPublishedVersionId(previous.getId());
                acl.setPublishedVersionNo(previous.getVersionNo());
            } else {
                acl.setPublishedVersionId(null);
                acl.setPublishedVersionNo(null);
            }
            acl.setSyncStatus("FAILED");
            acl.setSyncError(limit(exception.getMessage()));
            aclMapper.updateById(acl);
            restoreRuntimeConfiguration(acl.getNodeId(), exception);
            throw exception;
        }
    }

    @Override
    public void rollback(Long id) {
        FreeSwitchAcl acl = requireAcl(id);
        FreeSwitchAclVersion current = currentVersion(id);
        if (current == null) {
            throw new ServiceException("当前 ACL 尚未发布");
        }
        if ("SYNCED".equals(acl.getSyncStatus()) && !draftMatchesVersion(acl, current)) {
            restoreDraftFromVersion(acl, current);
            acl.setSyncError(null);
            aclMapper.updateById(acl);
            log.info("已将 ACL 草稿恢复为当前生效版本，aclId={}，versionNo={}", id, current.getVersionNo());
            return;
        }
        FreeSwitchAclVersion previous = versionMapper.selectOne(new LambdaQueryWrapper<FreeSwitchAclVersion>()
            .eq(FreeSwitchAclVersion::getAclId, id)
            .lt(FreeSwitchAclVersion::getVersionNo, current.getVersionNo())
            .orderByDesc(FreeSwitchAclVersion::getVersionNo)
            .last("LIMIT 1"));
        if (previous == null) {
            throw new ServiceException("没有可回滚的历史版本");
        }
        setCurrent(current.getId(), false);
        setCurrent(previous.getId(), true);
        acl.setPublishedVersionId(previous.getId());
        acl.setPublishedVersionNo(previous.getVersionNo());
        acl.setSyncStatus("SYNCING");
        aclMapper.updateById(acl);
        try {
            requireRuntimeSync().reload(acl.getNodeId());
            restoreDraftFromVersion(acl, previous);
            acl.setSyncStatus("SYNCED");
            acl.setSyncError(null);
            aclMapper.updateById(acl);
        } catch (RuntimeException exception) {
            setCurrent(previous.getId(), false);
            setCurrent(current.getId(), true);
            acl.setPublishedVersionId(current.getId());
            acl.setPublishedVersionNo(current.getVersionNo());
            acl.setSyncStatus("FAILED");
            acl.setSyncError(limit(exception.getMessage()));
            aclMapper.updateById(acl);
            restoreRuntimeConfiguration(acl.getNodeId(), exception);
            throw exception;
        }
    }

    @Override
    public FreeSwitchAclIpTestResponse testIp(Long id, String ip) {
        FreeSwitchAcl acl = requireAcl(id);
        String normalizedIp = IpCidrMatcher.normalizeCidr(ip);
        normalizedIp = normalizedIp.substring(0, normalizedIp.lastIndexOf('/'));
        for (FreeSwitchAclEntry entry : entries(acl.getEntriesJson())) {
            if (IpCidrMatcher.matches(normalizedIp, entry.cidr())) {
                boolean allowed = "ALLOW".equals(entry.action());
                return new FreeSwitchAclIpTestResponse(normalizedIp, allowed, entry.action(), entry.cidr(),
                    "命中规则 " + entry.cidr() + "，执行" + (allowed ? "允许" : "拒绝"));
            }
        }
        boolean allowed = "ALLOW".equals(acl.getDefaultAction());
        return new FreeSwitchAclIpTestResponse(normalizedIp, allowed, acl.getDefaultAction(), null,
            "未命中明细规则，执行默认动作" + (allowed ? "允许" : "拒绝"));
    }

    @Override
    public String preview(Long id) {
        FreeSwitchAcl acl = requireAcl(id);
        return configurationProvider.render(acl.getTenantId(), acl.getNodeId());
    }

    private void validateRequest(Long id, FreeSwitchAclSaveRequest request) {
        nodeQueryService.getEnabledConnection(request.getNodeId());
        String aclCode = request.getAclCode().trim();
        if (RESERVED_ACL_CODES.contains(aclCode.toLowerCase())) {
            throw new ServiceException("ACL 编码不能使用 FreeSWITCH 内置列表名称：" + aclCode);
        }
        long duplicates = aclMapper.selectCount(new LambdaQueryWrapper<FreeSwitchAcl>()
            .eq(FreeSwitchAcl::getNodeId, request.getNodeId())
            .eq(FreeSwitchAcl::getAclCode, aclCode)
            .ne(id != null, FreeSwitchAcl::getId, id));
        if (duplicates > 0) {
            throw new ServiceException("当前节点已存在相同 ACL 编码");
        }
        validateEntries(request.getDefaultAction(), request.getEnabled(), request.getEntries());
    }

    private void validateEntries(String defaultAction, Boolean enabled, List<FreeSwitchAclEntry> entries) {
        List<String> seen = new ArrayList<>();
        boolean hasAllow = false;
        for (FreeSwitchAclEntry entry : entries == null ? List.<FreeSwitchAclEntry>of() : entries) {
            String action = entry.action() == null ? "" : entry.action().trim().toUpperCase();
            if (!"ALLOW".equals(action) && !"DENY".equals(action)) {
                throw new ServiceException("ACL 规则动作只能是允许或拒绝");
            }
            String cidr = IpCidrMatcher.normalizeCidr(entry.cidr());
            if (!seen.add(cidr)) {
                throw new ServiceException("ACL 中存在重复网段：" + cidr);
            }
            hasAllow |= "ALLOW".equals(action);
        }
        if (Boolean.TRUE.equals(enabled) && "DENY".equals(defaultAction) && !hasAllow) {
            throw new ServiceException("默认拒绝的启用 ACL 至少需要一条允许规则");
        }
    }

    private void apply(FreeSwitchAcl acl, FreeSwitchAclSaveRequest request) {
        List<FreeSwitchAclEntry> normalized = (request.getEntries() == null ? List.<FreeSwitchAclEntry>of() : request.getEntries())
            .stream()
            .map(entry -> new FreeSwitchAclEntry(entry.action().trim().toUpperCase(),
                IpCidrMatcher.normalizeCidr(entry.cidr()), entry.description()))
            .toList();
        acl.setNodeId(request.getNodeId());
        acl.setAclCode(request.getAclCode().trim());
        acl.setAclName(request.getAclName().trim());
        acl.setPurpose(request.getPurpose().trim());
        acl.setDefaultAction(request.getDefaultAction().trim().toUpperCase());
        acl.setEntriesJson(JsonUtils.toJsonString(normalized));
        acl.setEnabled(request.getEnabled());
    }

    private FreeSwitchAcl requireAcl(Long id) {
        FreeSwitchAcl acl = aclMapper.selectById(id);
        if (acl == null) {
            throw new ServiceException("ACL 配置不存在");
        }
        return acl;
    }

    private FreeSwitchAclVersion currentVersion(Long aclId) {
        return versionMapper.selectOne(new LambdaQueryWrapper<FreeSwitchAclVersion>()
            .eq(FreeSwitchAclVersion::getAclId, aclId)
            .eq(FreeSwitchAclVersion::getCurrentVersion, true)
            .last("LIMIT 1"));
    }

    private int nextVersionNo(Long aclId) {
        FreeSwitchAclVersion latest = versionMapper.selectOne(new LambdaQueryWrapper<FreeSwitchAclVersion>()
            .eq(FreeSwitchAclVersion::getAclId, aclId)
            .orderByDesc(FreeSwitchAclVersion::getVersionNo)
            .last("LIMIT 1"));
        return latest == null ? 1 : latest.getVersionNo() + 1;
    }

    private void setCurrent(Long versionId, boolean current) {
        versionMapper.update(null, new LambdaUpdateWrapper<FreeSwitchAclVersion>()
            .eq(FreeSwitchAclVersion::getId, versionId)
            .set(FreeSwitchAclVersion::getCurrentVersion, current));
    }

    private FreeSwitchAclRuntimeSyncService requireRuntimeSync() {
        FreeSwitchAclRuntimeSyncService service = runtimeSyncServiceProvider.getIfAvailable();
        if (service == null) {
            throw new ServiceException("FreeSWITCH ACL 运行时同步服务不可用");
        }
        return service;
    }

    private FreeSwitchAclSnapshot snapshot(FreeSwitchAcl acl, List<FreeSwitchAclEntry> entries) {
        return new FreeSwitchAclSnapshot(acl.getId(), acl.getNodeId(), acl.getAclCode(), acl.getAclName(),
            acl.getPurpose(), acl.getDefaultAction(), acl.getEnabled(), entries);
    }

    private void restoreDraftFromVersion(FreeSwitchAcl acl, FreeSwitchAclVersion version) {
        FreeSwitchAclSnapshot restored = JsonUtils.parseObject(version.getSnapshotJson(), FreeSwitchAclSnapshot.class);
        acl.setNodeId(restored.nodeId());
        acl.setAclCode(restored.aclCode());
        acl.setAclName(restored.aclName());
        acl.setPurpose(restored.purpose());
        acl.setDefaultAction(restored.defaultAction());
        acl.setEnabled(restored.enabled());
        acl.setEntriesJson(JsonUtils.toJsonString(restored.entries() == null ? List.of() : restored.entries()));
    }

    private boolean draftMatchesVersion(FreeSwitchAcl acl, FreeSwitchAclVersion version) {
        FreeSwitchAclSnapshot currentDraft = snapshot(acl, entries(acl.getEntriesJson()));
        FreeSwitchAclSnapshot published =
            JsonUtils.parseObject(version.getSnapshotJson(), FreeSwitchAclSnapshot.class);
        return currentDraft.equals(published);
    }

    private List<FreeSwitchAclEntry> entries(String json) {
        return json == null || json.isBlank() ? List.of() : JsonUtils.parseArray(json, FreeSwitchAclEntry.class);
    }

    private FreeSwitchAclResponse response(FreeSwitchAcl acl) {
        FreeSwitchAclResponse response = new FreeSwitchAclResponse();
        response.setId(acl.getId());
        response.setNodeId(acl.getNodeId());
        response.setAclCode(acl.getAclCode());
        response.setAclName(acl.getAclName());
        response.setPurpose(acl.getPurpose());
        response.setDefaultAction(acl.getDefaultAction());
        response.setEntries(entries(acl.getEntriesJson()));
        response.setEnabled(acl.getEnabled());
        response.setPublishedVersionNo(acl.getPublishedVersionNo());
        response.setSyncStatus(acl.getSyncStatus());
        response.setSyncError(acl.getSyncError());
        response.setVersion(acl.getVersion());
        response.setCreateTime(acl.getCreateTime());
        response.setUpdateTime(acl.getUpdateTime());
        return response;
    }

    private String limit(String value) {
        if (value == null) return "未知错误";
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    private void restoreRuntimeConfiguration(Long nodeId, RuntimeException originalException) {
        try {
            requireRuntimeSync().reload(nodeId);
            log.warn("ACL 发布失败后已恢复 FreeSWITCH 上一生效配置，nodeId={}", nodeId);
        } catch (RuntimeException restoreException) {
            originalException.addSuppressed(restoreException);
            log.error("ACL 发布失败且恢复 FreeSWITCH 上一配置失败，nodeId={}，error={}",
                nodeId, restoreException.getMessage(), restoreException);
        }
    }
}
