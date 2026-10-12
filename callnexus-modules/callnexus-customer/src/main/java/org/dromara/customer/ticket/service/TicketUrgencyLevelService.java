package org.dromara.customer.ticket.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.customer.ticket.domain.Ticket;
import org.dromara.customer.ticket.domain.TicketUrgencyLevel;
import org.dromara.customer.ticket.domain.request.TicketUrgencyLevelRequest;
import org.dromara.customer.ticket.domain.response.TicketUrgencyLevelResponse;
import org.dromara.customer.ticket.mapper.TicketMapper;
import org.dromara.customer.ticket.mapper.TicketUrgencyLevelMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TicketUrgencyLevelService {
    private final TicketUrgencyLevelMapper levelMapper;
    private final TicketMapper ticketMapper;

    public List<TicketUrgencyLevelResponse> list(boolean includeDisabled) {
        ensureDefaults();
        return levelMapper.selectList(new LambdaQueryWrapper<TicketUrgencyLevel>()
                .eq(!includeDisabled, TicketUrgencyLevel::getEnabled, true)
                .orderByDesc(TicketUrgencyLevel::getIsDefault)
                .orderByAsc(TicketUrgencyLevel::getSortOrder)
                .orderByDesc(TicketUrgencyLevel::getPriorityWeight))
            .stream().map(this::response).toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public Long create(TicketUrgencyLevelRequest request) {
        ensureDefaults();
        validateUnique(request.getLevelCode(), null);
        TicketUrgencyLevel level = new TicketUrgencyLevel();
        apply(level, request);
        if (Boolean.TRUE.equals(level.getIsDefault())) clearOtherDefaults(null);
        levelMapper.insert(level);
        return level.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, TicketUrgencyLevelRequest request) {
        TicketUrgencyLevel level = require(id);
        validateUnique(request.getLevelCode(), id);
        if (Boolean.TRUE.equals(level.getIsDefault()) && !Boolean.TRUE.equals(request.getIsDefault())) {
            throw new ServiceException("请先将其他紧急程度设为默认值");
        }
        if (Boolean.TRUE.equals(request.getIsDefault()) && !Boolean.TRUE.equals(request.getEnabled())) {
            throw new ServiceException("默认紧急程度必须启用");
        }
        apply(level, request);
        if (Boolean.TRUE.equals(level.getIsDefault())) clearOtherDefaults(id);
        if (levelMapper.updateById(level) != 1) throw new ServiceException("紧急程度已被其他用户修改，请刷新后重试");
        ticketMapper.update(null, new LambdaUpdateWrapper<Ticket>()
            .eq(Ticket::getUrgencyLevelId, id)
            .set(Ticket::getUrgencyCode, level.getLevelCode())
            .set(Ticket::getUrgencyName, level.getLevelName())
            .set(Ticket::getUrgencyWeight, level.getPriorityWeight())
            .set(Ticket::getUrgencyColor, level.getTagColor()));
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        TicketUrgencyLevel level = require(id);
        if (Boolean.TRUE.equals(level.getIsDefault())) throw new ServiceException("默认紧急程度不能删除");
        if (ticketMapper.exists(new LambdaQueryWrapper<Ticket>().eq(Ticket::getUrgencyLevelId, id))) {
            throw new ServiceException("紧急程度已被工单使用，请停用而不是删除");
        }
        levelMapper.deleteById(id);
    }

    public void applyToTicket(Ticket ticket, Long levelId) {
        TicketUrgencyLevel level = levelId == null ? defaultLevel() : requireEnabled(levelId);
        ticket.setUrgencyLevelId(level.getId());
        ticket.setUrgencyCode(level.getLevelCode());
        ticket.setUrgencyName(level.getLevelName());
        ticket.setUrgencyWeight(level.getPriorityWeight());
        ticket.setUrgencyColor(level.getTagColor());
    }

    private TicketUrgencyLevel defaultLevel() {
        ensureDefaults();
        TicketUrgencyLevel level = levelMapper.selectOne(new LambdaQueryWrapper<TicketUrgencyLevel>()
            .eq(TicketUrgencyLevel::getEnabled, true)
            .eq(TicketUrgencyLevel::getIsDefault, true)
            .last("LIMIT 1"));
        if (level == null) {
            level = levelMapper.selectOne(new LambdaQueryWrapper<TicketUrgencyLevel>()
                .eq(TicketUrgencyLevel::getEnabled, true)
                .orderByAsc(TicketUrgencyLevel::getSortOrder)
                .last("LIMIT 1"));
        }
        if (level == null) throw new ServiceException("没有可用的工单紧急程度");
        return level;
    }

    private TicketUrgencyLevel requireEnabled(Long id) {
        TicketUrgencyLevel level = require(id);
        if (!Boolean.TRUE.equals(level.getEnabled())) throw new ServiceException("所选紧急程度已停用");
        return level;
    }

    private TicketUrgencyLevel require(Long id) {
        TicketUrgencyLevel level = levelMapper.selectById(id);
        if (level == null) throw new ServiceException("紧急程度不存在");
        return level;
    }

    private void ensureDefaults() {
        if (!levelMapper.exists(new LambdaQueryWrapper<TicketUrgencyLevel>())) {
            try {
                insertDefault("NORMAL", "普通", 10, "#909399", true, 10);
                insertDefault("MEDIUM", "较急", 30, "#409EFF", false, 20);
                insertDefault("URGENT", "紧急", 60, "#E6A23C", false, 30);
                insertDefault("CRITICAL", "特急", 100, "#F56C6C", false, 40);
            } catch (DuplicateKeyException ignored) {
                // Another request initialized the tenant at the same time.
            }
        }
        backfillLegacyTickets();
    }

    private void backfillLegacyTickets() {
        if (!ticketMapper.exists(new LambdaQueryWrapper<Ticket>().isNull(Ticket::getUrgencyLevelId))) return;
        TicketUrgencyLevel level = levelMapper.selectOne(new LambdaQueryWrapper<TicketUrgencyLevel>()
            .eq(TicketUrgencyLevel::getEnabled, true)
            .eq(TicketUrgencyLevel::getIsDefault, true)
            .last("LIMIT 1"));
        if (level == null) return;
        ticketMapper.update(null, new LambdaUpdateWrapper<Ticket>()
            .isNull(Ticket::getUrgencyLevelId)
            .set(Ticket::getUrgencyLevelId, level.getId())
            .set(Ticket::getUrgencyCode, level.getLevelCode())
            .set(Ticket::getUrgencyName, level.getLevelName())
            .set(Ticket::getUrgencyWeight, level.getPriorityWeight())
            .set(Ticket::getUrgencyColor, level.getTagColor()));
    }

    private void insertDefault(String code, String name, int weight, String color, boolean defaultValue, int sortOrder) {
        TicketUrgencyLevel level = new TicketUrgencyLevel();
        level.setLevelCode(code);
        level.setLevelName(name);
        level.setPriorityWeight(weight);
        level.setTagColor(color);
        level.setIsDefault(defaultValue);
        level.setEnabled(true);
        level.setSortOrder(sortOrder);
        levelMapper.insert(level);
    }

    private void clearOtherDefaults(Long excludedId) {
        levelMapper.update(null, new LambdaUpdateWrapper<TicketUrgencyLevel>()
            .ne(excludedId != null, TicketUrgencyLevel::getId, excludedId)
            .eq(TicketUrgencyLevel::getIsDefault, true)
            .set(TicketUrgencyLevel::getIsDefault, false));
    }

    private void validateUnique(String code, Long excludedId) {
        if (levelMapper.exists(new LambdaQueryWrapper<TicketUrgencyLevel>()
            .eq(TicketUrgencyLevel::getLevelCode, code.trim().toUpperCase())
            .ne(excludedId != null, TicketUrgencyLevel::getId, excludedId))) {
            throw new ServiceException("紧急程度编码已存在");
        }
    }

    private void apply(TicketUrgencyLevel target, TicketUrgencyLevelRequest source) {
        target.setLevelCode(source.getLevelCode().trim().toUpperCase());
        target.setLevelName(source.getLevelName().trim());
        target.setPriorityWeight(source.getPriorityWeight());
        target.setTagColor(source.getTagColor().toUpperCase());
        target.setIsDefault(source.getIsDefault());
        target.setEnabled(source.getEnabled());
        target.setSortOrder(source.getSortOrder());
        target.setRemark(source.getRemark() == null ? null : source.getRemark().trim());
        target.setVersion(source.getVersion());
    }

    private TicketUrgencyLevelResponse response(TicketUrgencyLevel source) {
        TicketUrgencyLevelResponse value = new TicketUrgencyLevelResponse();
        value.setId(source.getId());
        value.setLevelCode(source.getLevelCode());
        value.setLevelName(source.getLevelName());
        value.setPriorityWeight(source.getPriorityWeight());
        value.setTagColor(source.getTagColor());
        value.setIsDefault(source.getIsDefault());
        value.setEnabled(source.getEnabled());
        value.setSortOrder(source.getSortOrder());
        value.setRemark(source.getRemark());
        value.setVersion(source.getVersion());
        if (source.getCreateTime() != null) {
            value.setCreateTime(LocalDateTime.ofInstant(source.getCreateTime().toInstant(), ZoneId.systemDefault()));
        }
        return value;
    }
}
