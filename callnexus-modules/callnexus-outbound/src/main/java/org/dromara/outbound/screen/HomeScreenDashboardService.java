package org.dromara.outbound.screen;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.agent.domain.response.CallQueueMonitorOverviewResponse;
import org.dromara.agent.domain.response.CallQueueMonitorResponse;
import org.dromara.agent.domain.response.CallQueueRecentCallResponse;
import org.dromara.agent.service.CallQueueMonitorService;
import org.dromara.call.domain.CallSession;
import org.dromara.call.domain.VoiceMailMessage;
import org.dromara.call.domain.response.DispatchActiveCallResponse;
import org.dromara.call.domain.response.DispatchExtensionStatusResponse;
import org.dromara.call.mapper.CallSessionMapper;
import org.dromara.call.mapper.VoiceMailMessageMapper;
import org.dromara.call.service.DispatchCallMonitorService;
import org.dromara.common.core.utils.StringUtils;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 首页运营大屏只读聚合。组合队列监控 / 调度 / 留言 / 外呼既有能力，不改动业务核心。
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

    public HomeScreenDashboardResponse overview() {
        CallQueueMonitorOverviewResponse overview = callQueueMonitorService.overview();
        List<CallQueueMonitorResponse> queues = callQueueMonitorService.list();

        AgentCounts agents = summarizeAgents(overview);
        OutboundCounts outbound = summarizeOutbound();
        long voicemailPending = countUnhandledVoicemail();
        long yesterdayInbound = countYesterdayInbound();

        HomeScreenDashboardResponse response = new HomeScreenDashboardResponse();
        response.setKpis(buildKpis(overview, agents, outbound, voicemailPending));
        response.setHeroCore(buildHero(overview, yesterdayInbound));
        response.setAgentSummary(buildAgentSummary(agents));
        response.setQueueRanking(buildQueueRanking(queues));
        response.setSkillGroups(buildSkillGroups(queues));
        response.setTrendHours(buildTrendHours());
        response.setLiveFeed(buildLiveFeed(queues));
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

    private HomeScreenDashboardResponse.HeroCore buildHero(CallQueueMonitorOverviewResponse overview,
                                                           long yesterdayInbound) {
        long inbound = nz(overview.getTodayEnteredCount());
        long answerRate = nz(overview.getAnswerRate());
        HomeScreenDashboardResponse.HeroCore hero = new HomeScreenDashboardResponse.HeroCore();
        hero.setInbound(String.valueOf(inbound));
        hero.setAnswerRate(answerRate);
        if (yesterdayInbound <= 0) {
            hero.setInboundExtra("\u4eca\u65e5\u961f\u5217\u8fdb\u5165");
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

    private List<HomeScreenDashboardResponse.TrendPoint> buildTrendHours() {
        ZoneId zone = ZoneId.systemDefault();
        LocalDateTime dayStart = LocalDate.now().atStartOfDay();
        Date dayStartDate = Date.from(dayStart.atZone(zone).toInstant());

        List<CallSession> sessions = callSessionMapper.selectList(new LambdaQueryWrapper<CallSession>()
            .and(w -> w.ge(CallSession::getStartedAt, dayStart)
                .or()
                .ge(CallSession::getCreateTime, dayStartDate))
            .orderByDesc(CallSession::getStartedAt)
            .last("limit 5000"));

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
            String direction = safe(session.getDirection()).toUpperCase(Locale.ROOT);
            if ("OUTBOUND".equals(direction)) {
                arr[1]++;
            } else {
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

    private List<HomeScreenDashboardResponse.FeedItem> buildLiveFeed(List<CallQueueMonitorResponse> queues) {
        List<HomeScreenDashboardResponse.FeedItem> feed = new ArrayList<>();

        try {
            List<DispatchActiveCallResponse> active = dispatchCallMonitorService.listActiveCalls();
            active.stream().limit(6).forEach(call -> feed.add(fromActiveCall(call)));
        } catch (Exception ignored) {
            // 调度监控不可用时不影响主指标
        }

        List<CallQueueMonitorResponse> topQueues = queues.stream()
            .sorted(Comparator.comparingLong((CallQueueMonitorResponse q) ->
                nz(q.getWaitingCount()) + nz(q.getEnteredCount())).reversed())
            .limit(3)
            .toList();
        for (CallQueueMonitorResponse queue : topQueues) {
            try {
                List<CallQueueRecentCallResponse> recent = callQueueMonitorService.recentCalls(queue.getQueueId(), 4);
                for (CallQueueRecentCallResponse call : recent) {
                    feed.add(fromRecentCall(call, queue.getQueueName()));
                }
            } catch (Exception ignored) {
                // 单队列失败忽略
            }
        }

        return feed.stream()
            .sorted(Comparator.comparing(HomeScreenDashboardResponse.FeedItem::getTime).reversed())
            .limit(8)
            .toList();
    }

    private HomeScreenDashboardResponse.FeedItem fromActiveCall(DispatchActiveCallResponse call) {
        HomeScreenDashboardResponse.FeedItem item = new HomeScreenDashboardResponse.FeedItem();
        item.setId("active-" + Objects.toString(call.getBusinessCallId(), String.valueOf(call.getSessionId())));
        LocalDateTime t = call.getStartedAt() != null ? call.getStartedAt() : LocalDateTime.now();
        item.setTime(t.format(TIME_FMT));
        item.setType(directionText(call.getDirection()));
        item.setPhone(maskPhone(preferredPhone(call.getDirection(), call.getCallerNumber(), call.getCalledNumber())));
        item.setTarget(StringUtils.isNotBlank(call.getOwnerAgentExtension())
            ? "\u5750\u5e2d " + call.getOwnerAgentExtension()
            : StringUtils.blankToDefault(call.getQueueName(), "-"));
        item.setStatus(statusText(call.getCallStatus()));
        item.setTagClass(statusTag(call.getCallStatus()));
        return item;
    }

    private HomeScreenDashboardResponse.FeedItem fromRecentCall(CallQueueRecentCallResponse call, String queueName) {
        HomeScreenDashboardResponse.FeedItem item = new HomeScreenDashboardResponse.FeedItem();
        item.setId("recent-" + Objects.toString(call.getBusinessCallId(), String.valueOf(call.getSessionId())));
        LocalDateTime t = call.getStartedAt() != null ? call.getStartedAt()
            : (call.getAnsweredAt() != null ? call.getAnsweredAt() : LocalDateTime.now());
        item.setTime(t.format(TIME_FMT));
        item.setType(directionText(call.getDirection()));
        item.setPhone(maskPhone(preferredPhone(call.getDirection(), call.getCallerNumber(), call.getCalledNumber())));
        item.setTarget(StringUtils.isNotBlank(call.getAgentExtension())
            ? "\u5750\u5e2d " + call.getAgentExtension()
            : StringUtils.blankToDefault(queueName, "-"));
        item.setStatus(statusText(call.getCallStatus()));
        item.setTagClass(statusTag(call.getCallStatus()));
        return item;
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

    private long countUnhandledVoicemail() {
        try {
            return voiceMailMessageMapper.selectCount(new LambdaQueryWrapper<VoiceMailMessage>()
                .eq(VoiceMailMessage::getStatus, "UNHANDLED"));
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private long countYesterdayInbound() {
        try {
            LocalDateTime start = LocalDate.now().minusDays(1).atStartOfDay();
            LocalDateTime end = LocalDate.now().atStartOfDay();
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
        return "OUTBOUND".equalsIgnoreCase(safe(direction)) ? "\u547c\u51fa" : "\u547c\u5165";
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

    private String statusText(String status) {
        String s = safe(status).toUpperCase(Locale.ROOT);
        return switch (s) {
            case "RINGING", "AGENT_RING" -> "\u632f\u94c3";
            case "QUEUED", "WAITING", "QUEUE_WAIT" -> "\u6392\u961f\u4e2d";
            case "ANSWERED", "BRIDGED", "IN_CALL", "TALKING" -> "\u901a\u8bdd\u4e2d";
            case "AFTER_CALL", "WRAP_UP" -> "\u8bdd\u540e\u5904\u7406";
            case "ABANDONED", "ABANDON", "TIMEOUT" -> "\u5df2\u653e\u5f03";
            case "HANGUP", "ENDED", "COMPLETED" -> "\u5df2\u63a5\u901a";
            default -> StringUtils.isBlank(status) ? "-" : status;
        };
    }

    private String statusTag(String status) {
        String s = safe(status).toUpperCase(Locale.ROOT);
        if (s.contains("ABANDON") || s.contains("TIMEOUT") || s.contains("FAIL")) {
            return "is-danger";
        }
        if (s.contains("QUEUE") || s.contains("WAIT")) {
            return "is-warning";
        }
        if (s.contains("RING")) {
            return "is-info";
        }
        if (s.contains("ANSWER") || s.contains("BRIDGE") || s.contains("TALK") || s.contains("HANGUP") || s.contains("END")) {
            return "is-success";
        }
        return "is-info";
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
}
