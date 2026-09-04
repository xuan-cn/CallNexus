package org.dromara.outbound.screen;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.agent.domain.response.CallQueueMonitorOverviewResponse;
import org.dromara.agent.domain.response.CallQueueMonitorResponse;
import org.dromara.agent.service.CallQueueMonitorService;
import org.dromara.call.domain.CallSession;
import org.dromara.call.domain.VoiceMailMessage;
import org.dromara.call.domain.response.DispatchExtensionStatusResponse;
import org.dromara.call.mapper.CallSessionMapper;
import org.dromara.call.mapper.VoiceMailMessageMapper;
import org.dromara.call.service.DispatchCallMonitorService;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.customer.customer.domain.Customer;
import org.dromara.customer.customer.domain.CustomerAssignment;
import org.dromara.customer.customer.mapper.CustomerAssignmentMapper;
import org.dromara.customer.customer.mapper.CustomerMapper;
import org.dromara.customer.ticket.domain.Ticket;
import org.dromara.customer.ticket.domain.TicketStatus;
import org.dromara.customer.ticket.mapper.TicketMapper;
import org.dromara.outbound.domain.response.AutoOutboundTaskResponse;
import org.dromara.outbound.domain.response.OutboundTaskResponse;
import org.dromara.outbound.service.AutoOutboundTaskService;
import org.dromara.outbound.service.OutboundTaskService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 首页运营大屏只读聚合。
 * 中心话务指标与趋势、动态统一取自 cc_call_session；
 * 队列/技能组仍走队列监控口径。
 */
@Service
@RequiredArgsConstructor
public class HomeScreenDashboardService {

    private static final DateTimeFormatter HOUR_FMT = DateTimeFormatter.ofPattern("HH:00");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final CallQueueMonitorService callQueueMonitorService;
    private final DispatchCallMonitorService dispatchCallMonitorService;
    private final CallSessionMapper callSessionMapper;
    private final VoiceMailMessageMapper voiceMailMessageMapper;
    private final OutboundTaskService outboundTaskService;
    private final AutoOutboundTaskService autoOutboundTaskService;
    private final TicketMapper ticketMapper;
    private final CustomerMapper customerMapper;
    private final CustomerAssignmentMapper customerAssignmentMapper;

    public HomeScreenDashboardResponse overview() {
        CallQueueMonitorOverviewResponse overview = callQueueMonitorService.overview();
        List<CallQueueMonitorResponse> queues = callQueueMonitorService.list();

        AgentCounts agents = summarizeAgents(overview);
        OutboundCounts outbound = summarizeOutbound();
        long voicemailPending = countUnhandledVoicemail();
        List<CallSession> todaySessions = listTodaySessions();
        SessionTraffic todayTraffic = summarizeSessions(todaySessions);
        long yesterdayInbound = countInboundBetween(
            LocalDate.now().minusDays(1).atStartOfDay(),
            LocalDate.now().atStartOfDay());

        HomeScreenDashboardResponse response = new HomeScreenDashboardResponse();
        response.setKpis(buildKpis(overview, agents, outbound, voicemailPending));
        response.setHeroCore(buildHero(todayTraffic, yesterdayInbound));
        response.setAgentSummary(buildAgentSummary(agents));
        response.setQueueRanking(buildQueueRanking(queues));
        response.setSkillGroups(buildSkillGroups(queues));
        response.setTrendHours(buildTrendHours(todaySessions));
        response.setLiveFeed(buildLiveFeed());
        response.setTicketSummary(buildTicketSummary());
        response.setCustomerSummary(buildCustomerSummary());
        return response;
    }

    private List<HomeScreenDashboardResponse.KpiItem> buildKpis(CallQueueMonitorOverviewResponse overview,
                                                                AgentCounts agents,
                                                                OutboundCounts outbound,
                                                                long voicemailPending) {
        long online = agents.online > 0 ? agents.online : nz(overview.getOnlineAgentCount());
        long total = agents.total > 0 ? agents.total : nz(overview.getTotalAgentCount());
        long waiting = nz(overview.getCurrentWaitingCount());
        int signInRate = total <= 0 ? 0 : (int) Math.round(online * 100.0 / total);

        List<HomeScreenDashboardResponse.KpiItem> list = new ArrayList<>();
        list.add(kpi("\u5728\u7ebf\u5750\u5e2d", String.valueOf(online),
            "\u7b7e\u5165\u7387 " + signInRate + "%", null));
        list.add(kpi("\u5f53\u524d\u6392\u961f", String.valueOf(waiting),
            waiting > 12 ? "\u9700\u5173\u6ce8" : "\u6b63\u5e38",
            waiting > 12 ? "is-down" : null));
        list.add(kpi("\u5916\u547c\u4efb\u52a1", String.valueOf(outbound.total),
            "\u5b8c\u6210\u7387 " + outbound.completionRate + "%", null));
        list.add(kpi("\u7559\u8a00\u5f85\u5904\u7406", String.valueOf(voicemailPending),
            "\u4f18\u5148\u5904\u7406",
            voicemailPending > 10 ? "is-down" : null));
        return list;
    }

    private HomeScreenDashboardResponse.HeroCore buildHero(SessionTraffic today, long yesterdayInbound) {
        long inbound = today.inbound;
        HomeScreenDashboardResponse.HeroCore hero = new HomeScreenDashboardResponse.HeroCore();
        hero.setInbound(String.valueOf(inbound));
        hero.setAnswerRate(inbound <= 0 ? 0L : Math.round(today.inboundAnswered * 100.0 / inbound));
        if (yesterdayInbound <= 0) {
            hero.setInboundExtra(inbound > 0 ? "\u4eca\u65e5\u547c\u5165" : "\u6682\u65e0\u547c\u5165");
            hero.setInboundTone(null);
        } else {
            double delta = (inbound - yesterdayInbound) * 100.0 / yesterdayInbound;
            String sign = delta >= 0 ? "+" : "";
            hero.setInboundExtra("\u8f83\u6628\u65e5 " + sign + round1(delta) + "%");
            hero.setInboundTone(delta >= 0 ? "is-up" : "is-down");
        }
        return hero;
    }

    private HomeScreenDashboardResponse.AgentSummary buildAgentSummary(AgentCounts agents) {
        HomeScreenDashboardResponse.AgentSummary summary = new HomeScreenDashboardResponse.AgentSummary();
        summary.setTotal(agents.total);
        summary.setItems(List.of(
            agentStat("\u7a7a\u95f2", agents.idle, "#34d399"),
            agentStat("\u901a\u8bdd\u4e2d", agents.talking, "#38bdf8"),
            agentStat("\u8bdd\u540e\u5904\u7406", agents.wrap, "#818cf8"),
            agentStat("\u79bb\u7ebf", agents.away, "#fbbf24")
        ));
        return summary;
    }

    private List<HomeScreenDashboardResponse.QueueRankItem> buildQueueRanking(List<CallQueueMonitorResponse> queues) {
        List<CallQueueMonitorResponse> ranked = queues.stream()
            .sorted(Comparator.comparingLong((CallQueueMonitorResponse q) -> nz(q.getWaitingCount())).reversed())
            .limit(5)
            .toList();
        long max = ranked.stream().mapToLong(q -> nz(q.getWaitingCount())).max().orElse(1L);
        List<HomeScreenDashboardResponse.QueueRankItem> items = new ArrayList<>();
        for (CallQueueMonitorResponse queue : ranked) {
            HomeScreenDashboardResponse.QueueRankItem item = new HomeScreenDashboardResponse.QueueRankItem();
            item.setName(StringUtils.blankToDefault(queue.getQueueName(), "\u961f\u5217"));
            item.setWaiting(nz(queue.getWaitingCount()));
            item.setPercent((int) Math.round(nz(queue.getWaitingCount()) * 100.0 / Math.max(max, 1)));
            items.add(item);
        }
        return items;
    }

    private List<HomeScreenDashboardResponse.SkillRateItem> buildSkillGroups(List<CallQueueMonitorResponse> queues) {
        Map<String, long[]> buckets = new LinkedHashMap<>();
        for (CallQueueMonitorResponse queue : queues) {
            String name = StringUtils.isNotBlank(queue.getSkillGroupName())
                ? queue.getSkillGroupName()
                : StringUtils.blankToDefault(queue.getQueueName(), "\u672a\u5206\u7ec4");
            long[] arr = buckets.computeIfAbsent(name, key -> new long[2]);
            arr[0] += nz(queue.getEnteredCount());
            arr[1] += nz(queue.getAnsweredCount());
        }
        return buckets.entrySet().stream()
            .map(entry -> {
                HomeScreenDashboardResponse.SkillRateItem item = new HomeScreenDashboardResponse.SkillRateItem();
                item.setName(entry.getKey());
                long entered = entry.getValue()[0];
                long answered = entry.getValue()[1];
                item.setRate(entered <= 0 ? 0 : (int) Math.round(answered * 100.0 / entered));
                return item;
            })
            .sorted(Comparator.comparingInt(HomeScreenDashboardResponse.SkillRateItem::getRate).reversed())
            .limit(5)
            .toList();
    }

    private List<HomeScreenDashboardResponse.TrendPoint> buildTrendHours(List<CallSession> sessions) {
        Map<String, long[]> buckets = new LinkedHashMap<>();
        for (int hour = 8; hour <= 18; hour++) {
            buckets.put(String.format(Locale.ROOT, "%02d:00", hour), new long[3]);
        }
        for (CallSession session : sessions) {
            LocalDateTime t = session.getStartedAt() != null
                ? session.getStartedAt()
                : toLocalDateTime(session.getCreateTime());
            if (t == null) {
                continue;
            }
            String key = t.format(HOUR_FMT);
            long[] arr = buckets.get(key);
            if (arr == null) {
                continue;
            }
            if (isOutbound(session.getDirection())) {
                arr[1]++;
            } else if (isInbound(session.getDirection())) {
                arr[0]++;
            }
            if (session.getAnsweredAt() != null) {
                arr[2]++;
            }
        }

        List<HomeScreenDashboardResponse.TrendPoint> points = new ArrayList<>();
        buckets.forEach((hour, arr) -> {
            HomeScreenDashboardResponse.TrendPoint point = new HomeScreenDashboardResponse.TrendPoint();
            point.setHour(hour);
            point.setInbound(arr[0]);
            point.setOutbound(arr[1]);
            point.setAnswered(arr[2]);
            points.add(point);
        });
        return points;
    }

    private List<HomeScreenDashboardResponse.FeedItem> buildLiveFeed() {
        List<CallSession> sessions = callSessionMapper.selectList(new LambdaQueryWrapper<CallSession>()
            .orderByDesc(CallSession::getStartedAt)
            .last("limit 24"));
        return sessions.stream()
            .sorted(Comparator
                .comparing((CallSession s) -> isActiveSession(s) ? 0 : 1)
                .thenComparing(CallSession::getStartedAt, Comparator.nullsLast(Comparator.reverseOrder())))
            .limit(8)
            .map(this::fromSession)
            .toList();
    }

    private HomeScreenDashboardResponse.FeedItem fromSession(CallSession session) {
        HomeScreenDashboardResponse.FeedItem item = new HomeScreenDashboardResponse.FeedItem();
        item.setId(Objects.toString(session.getBusinessCallId(), String.valueOf(session.getId())));
        LocalDateTime t = session.getStartedAt() != null
            ? session.getStartedAt()
            : (session.getAnsweredAt() != null ? session.getAnsweredAt() : LocalDateTime.now());
        item.setTime(t.format(TIME_FMT));
        item.setType(directionText(session.getDirection()));
        item.setPhone(maskPhone(preferredPhone(session.getDirection(), session.getCallerNumber(), session.getCalledNumber())));
        String agentExt = StringUtils.isNotBlank(session.getOwnerAgentExtension())
            ? session.getOwnerAgentExtension()
            : session.getAgentExtension();
        item.setTarget(StringUtils.isNotBlank(agentExt)
            ? agentExt
            : StringUtils.blankToDefault(session.getHandlingQueueName(), "-"));
        applyFeedOutcome(item, session);
        return item;
    }

    /**
     * 大屏动态用「结果」口径：通话中 / 已接通 / 未接通（及振铃、排队中）。
     * 「未接通」是结果，不等于挂断原因里的「无人接听」。
     */
    private void applyFeedOutcome(HomeScreenDashboardResponse.FeedItem item, CallSession session) {
        String status = safe(session.getCallStatus()).toUpperCase(Locale.ROOT);
        if (isActiveSession(session)) {
            if (status.contains("RING")) {
                item.setStatus("\u632f\u94c3");
                item.setTagClass("is-info");
                return;
            }
            if (status.contains("QUEUE") || status.contains("WAIT")) {
                item.setStatus("\u6392\u961f\u4e2d");
                item.setTagClass("is-warning");
                return;
            }
            if (session.getAnsweredAt() != null
                || status.contains("ANSWER")
                || status.contains("BRIDGE")
                || status.contains("TALK")) {
                item.setStatus("\u901a\u8bdd\u4e2d");
                item.setTagClass("is-success");
                return;
            }
            item.setStatus("\u632f\u94c3");
            item.setTagClass("is-info");
            return;
        }
        if (session.getAnsweredAt() != null) {
            item.setStatus("\u5df2\u63a5\u901a");
            item.setTagClass("is-success");
            return;
        }
        item.setStatus("\u672a\u63a5\u901a");
        item.setTagClass("is-danger");
    }

    private AgentCounts summarizeAgents(CallQueueMonitorOverviewResponse overview) {
        AgentCounts counts = new AgentCounts();
        try {
            List<DispatchExtensionStatusResponse> extensions = dispatchCallMonitorService.listExtensionStatuses();
            for (DispatchExtensionStatusResponse ext : extensions) {
                if (ext.getAgentId() == null) {
                    continue;
                }
                counts.total++;
                String status = safe(ext.getAgentPresenceStatus()).toUpperCase(Locale.ROOT);
                switch (status) {
                    case "IDLE" -> {
                        counts.online++;
                        counts.idle++;
                    }
                    case "BUSY" -> {
                        counts.online++;
                        counts.talking++;
                    }
                    case "AFTER_CALL" -> {
                        counts.online++;
                        counts.wrap++;
                    }
                    default -> counts.away++;
                }
            }
        } catch (Exception ignored) {
            // fallback below
        }
        if (counts.total == 0 && overview != null) {
            counts.total = nz(overview.getTotalAgentCount());
            counts.online = nz(overview.getOnlineAgentCount());
            counts.idle = nz(overview.getIdleAgentCount());
            counts.talking = nz(overview.getBusyAgentCount());
            counts.wrap = 0;
            counts.away = Math.max(0, counts.total - counts.online);
        }
        return counts;
    }

    private OutboundCounts summarizeOutbound() {
        OutboundCounts counts = new OutboundCounts();
        try {
            List<OutboundTaskResponse> manual = outboundTaskService.list();
            for (OutboundTaskResponse task : manual) {
                counts.total += task.getTotalCount();
                counts.completed += task.getCompletedCount();
            }
        } catch (Exception ignored) {
            // ignore
        }
        try {
            List<AutoOutboundTaskResponse> auto = autoOutboundTaskService.list();
            for (AutoOutboundTaskResponse task : auto) {
                counts.total += task.getTotalCount();
                counts.completed += task.getCompletedCount();
            }
        } catch (Exception ignored) {
            // ignore
        }
        counts.completionRate = counts.total <= 0 ? 0
            : (int) Math.round(counts.completed * 100.0 / counts.total);
        return counts;
    }

    private HomeScreenDashboardResponse.TicketSummary buildTicketSummary() {
        HomeScreenDashboardResponse.TicketSummary summary = new HomeScreenDashboardResponse.TicketSummary();
        try {
            summary.setOpen(countTicket(TicketStatus.OPEN));
            summary.setProcessing(countTicket(TicketStatus.PROCESSING));
            summary.setResolved(countTicket(TicketStatus.RESOLVED));
            summary.setClosed(countTicket(TicketStatus.CLOSED));
        } catch (Exception ignored) {
            // keep zeros
        }
        return summary;
    }

    private HomeScreenDashboardResponse.CustomerSummary buildCustomerSummary() {
        HomeScreenDashboardResponse.CustomerSummary summary = new HomeScreenDashboardResponse.CustomerSummary();
        try {
            ZoneId zone = ZoneId.systemDefault();
            LocalDateTime dayStart = LocalDate.now().atStartOfDay();
            Date dayStartDate = Date.from(dayStart.atZone(zone).toInstant());
            Date dayEndDate = Date.from(dayStart.plusDays(1).atZone(zone).toInstant());

            long total = nz(customerMapper.selectCount(new LambdaQueryWrapper<>()));
            long todayNew = nz(customerMapper.selectCount(new LambdaQueryWrapper<Customer>()
                .ge(Customer::getCreateTime, dayStartDate)
                .lt(Customer::getCreateTime, dayEndDate)));
            Set<Long> assignedIds = loadAssignedCustomerIds();
            long unassigned = assignedIds.isEmpty()
                ? total
                : nz(customerMapper.selectCount(new LambdaQueryWrapper<Customer>()
                    .notIn(Customer::getId, assignedIds)));

            summary.setTotal(total);
            summary.setTodayNew(todayNew);
            summary.setUnassigned(Math.max(0, unassigned));
            summary.setRecent(listRecentCustomers());
        } catch (Exception ignored) {
            // keep zeros
        }
        return summary;
    }

    private List<HomeScreenDashboardResponse.CustomerRecentItem> listRecentCustomers() {
        List<Customer> rows = customerMapper.selectList(new LambdaQueryWrapper<Customer>()
            .orderByDesc(Customer::getCreateTime)
            .last("limit 6"));
        List<HomeScreenDashboardResponse.CustomerRecentItem> items = new ArrayList<>();
        for (Customer row : rows) {
            HomeScreenDashboardResponse.CustomerRecentItem item = new HomeScreenDashboardResponse.CustomerRecentItem();
            item.setId(Objects.toString(row.getId(), ""));
            item.setName(StringUtils.blankToDefault(row.getCustomerName(), "-"));
            item.setPhone(maskPhone(row.getPrimaryPhone()));
            LocalDateTime created = toLocalDateTime(row.getCreateTime());
            item.setTime(created != null ? created.format(TIME_FMT) : "-");
            items.add(item);
        }
        return items;
    }

    private long countTicket(TicketStatus status) {
        return nz(ticketMapper.selectCount(new LambdaQueryWrapper<Ticket>()
            .eq(Ticket::getTicketStatus, status)));
    }

    private Set<Long> loadAssignedCustomerIds() {
        List<CustomerAssignment> rows = customerAssignmentMapper.selectList(new LambdaQueryWrapper<CustomerAssignment>()
            .eq(CustomerAssignment::getEnabled, true)
            .select(CustomerAssignment::getCustomerId));
        Set<Long> ids = new HashSet<>();
        for (CustomerAssignment row : rows) {
            if (row.getCustomerId() != null) {
                ids.add(row.getCustomerId());
            }
        }
        return ids;
    }

    private long countUnhandledVoicemail() {
        try {
            return voiceMailMessageMapper.selectCount(new LambdaQueryWrapper<VoiceMailMessage>()
                .eq(VoiceMailMessage::getStatus, "UNHANDLED"));
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private List<CallSession> listTodaySessions() {
        ZoneId zone = ZoneId.systemDefault();
        LocalDateTime dayStart = LocalDate.now().atStartOfDay();
        Date dayStartDate = Date.from(dayStart.atZone(zone).toInstant());
        return callSessionMapper.selectList(new LambdaQueryWrapper<CallSession>()
            .and(w -> w.ge(CallSession::getStartedAt, dayStart)
                .or()
                .ge(CallSession::getCreateTime, dayStartDate))
            .orderByDesc(CallSession::getStartedAt)
            .last("limit 5000"));
    }

    private SessionTraffic summarizeSessions(List<CallSession> sessions) {
        SessionTraffic traffic = new SessionTraffic();
        for (CallSession session : sessions) {
            if (isInbound(session.getDirection())) {
                traffic.inbound++;
                if (session.getAnsweredAt() != null) {
                    traffic.inboundAnswered++;
                }
            }
        }
        return traffic;
    }

    private long countInboundBetween(LocalDateTime start, LocalDateTime end) {
        try {
            return callSessionMapper.selectCount(new LambdaQueryWrapper<CallSession>()
                .ge(CallSession::getStartedAt, start)
                .lt(CallSession::getStartedAt, end)
                .and(w -> w.eq(CallSession::getDirection, "INBOUND")
                    .or()
                    .isNull(CallSession::getDirection)
                    .or()
                    .eq(CallSession::getDirection, "")));
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private boolean isInbound(String direction) {
        String value = safe(direction).toUpperCase(Locale.ROOT);
        return value.isEmpty() || "INBOUND".equals(value);
    }

    private boolean isOutbound(String direction) {
        return "OUTBOUND".equalsIgnoreCase(safe(direction));
    }

    private boolean isActiveSession(CallSession session) {
        return session.getEndedAt() == null
            && !"ENDED".equalsIgnoreCase(safe(session.getCallStatus()));
    }

    private HomeScreenDashboardResponse.KpiItem kpi(String label, String value, String extra, String tone) {
        HomeScreenDashboardResponse.KpiItem item = new HomeScreenDashboardResponse.KpiItem();
        item.setLabel(label);
        item.setValue(value);
        item.setExtra(extra);
        item.setTone(tone);
        return item;
    }

    private HomeScreenDashboardResponse.AgentStatItem agentStat(String label, long value, String color) {
        HomeScreenDashboardResponse.AgentStatItem item = new HomeScreenDashboardResponse.AgentStatItem();
        item.setLabel(label);
        item.setValue(value);
        item.setColor(color);
        return item;
    }

    private String directionText(String direction) {
        String value = safe(direction).toUpperCase(Locale.ROOT);
        return switch (value) {
            case "OUTBOUND" -> "\u547c\u51fa";
            case "INTERNAL" -> "\u5185\u90e8";
            default -> "\u547c\u5165";
        };
    }

    private String preferredPhone(String direction, String caller, String called) {
        if ("OUTBOUND".equalsIgnoreCase(safe(direction))) {
            return StringUtils.isNotBlank(called) ? called : caller;
        }
        return StringUtils.isNotBlank(caller) ? caller : called;
    }

    private String maskPhone(String phone) {
        if (StringUtils.isBlank(phone)) {
            return "****";
        }
        String digits = phone.trim();
        if (digits.length() < 7) {
            return digits;
        }
        if (digits.length() <= 11) {
            return digits.substring(0, 3) + "****" + digits.substring(digits.length() - 4);
        }
        return digits.substring(0, 3) + "****" + digits.substring(digits.length() - 4);
    }

    private LocalDateTime toLocalDateTime(Date date) {
        if (date == null) {
            return null;
        }
        return LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault());
    }

    private long nz(Long value) {
        return value == null ? 0L : value;
    }

    private double round1(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private static class AgentCounts {
        private long total;
        private long online;
        private long idle;
        private long talking;
        private long wrap;
        private long away;
    }

    private static class OutboundCounts {
        private long total;
        private long completed;
        private int completionRate;
    }

    private static class SessionTraffic {
        private long inbound;
        private long inboundAnswered;
    }
}
