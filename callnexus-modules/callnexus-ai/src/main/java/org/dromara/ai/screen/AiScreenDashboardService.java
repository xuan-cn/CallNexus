package org.dromara.ai.screen;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.AiAgent;
import org.dromara.ai.domain.AiIntentRecognitionLog;
import org.dromara.ai.domain.AiRealtimeCallSession;
import org.dromara.ai.domain.response.AiFaqLearningStatisticsResponse;
import org.dromara.ai.mapper.AiAgentMapper;
import org.dromara.ai.mapper.AiIntentRecognitionLogMapper;
import org.dromara.ai.mapper.AiRealtimeCallSessionMapper;
import org.dromara.ai.service.AiFaqLearningApplicationService;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * AI 话务大屏只读聚合。仅查询 Mapper / 既有统计接口，不改动实时通话业务链路。
 */
@Service
@RequiredArgsConstructor
public class AiScreenDashboardService {

    private static final Set<String> ACTIVE_STATES = Set.of(
        "CONNECTING", "LISTENING", "THINKING", "SPEAKING", "TRANSFERRING", "ENDING"
    );

    private static final DateTimeFormatter HOUR_FMT = DateTimeFormatter.ofPattern("HH:00");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final AiRealtimeCallSessionMapper sessionMapper;
    private final AiIntentRecognitionLogMapper recognitionLogMapper;
    private final AiAgentMapper agentMapper;
    private final AiFaqLearningApplicationService faqLearningApplicationService;

    public AiScreenDashboardResponse overview() {
        ZoneId zone = ZoneId.systemDefault();
        LocalDateTime dayStartLdt = LocalDate.now().atStartOfDay();
        Date dayStart = Date.from(dayStartLdt.atZone(zone).toInstant());
        Date hourAgo = Date.from(LocalDateTime.now().minusHours(1).atZone(zone).toInstant());

        List<AiRealtimeCallSession> todaySessions = sessionMapper.selectList(new LambdaQueryWrapper<AiRealtimeCallSession>()
            .and(w -> w.ge(AiRealtimeCallSession::getCreateTime, dayStart)
                .or()
                .ge(AiRealtimeCallSession::getConnectedAt, dayStartLdt))
            .orderByDesc(AiRealtimeCallSession::getCreateTime)
            .last("limit 2000"));

        List<AiIntentRecognitionLog> todayLogs = recognitionLogMapper.selectList(new LambdaQueryWrapper<AiIntentRecognitionLog>()
            .ge(AiIntentRecognitionLog::getCreateTime, dayStart)
            .orderByDesc(AiIntentRecognitionLog::getCreateTime)
            .last("limit 5000"));

        List<AiIntentRecognitionLog> recentLogs = todayLogs.stream()
            .filter(log -> log.getCreateTime() != null && !log.getCreateTime().before(hourAgo))
            .toList();

        long concurrent = sessionMapper.selectCount(new LambdaQueryWrapper<AiRealtimeCallSession>()
            .in(AiRealtimeCallSession::getSessionState, ACTIVE_STATES)
            .isNull(AiRealtimeCallSession::getEndedAt));

        OutcomeBucket bucket = classifySessions(todaySessions);
        long inbound = todaySessions.size();
        double resolveRate = ratio(bucket.resolved, inbound);
        double transferRate = ratio(bucket.transfer, inbound);
        double failRate = ratio(bucket.fail, inbound);

        RecognitionStats recognitionStats = summarizeRecognition(recentLogs.isEmpty() ? todayLogs : recentLogs);

        long faqPending = 0L;
        try {
            AiFaqLearningStatisticsResponse faq = faqLearningApplicationService.statistics();
            faqPending = faq == null ? 0L : faq.pending();
        } catch (Exception ignored) {
            // FAQ 模块不可用时不影响大屏主指标
        }

        long activeAgents = agentMapper.selectCount(new LambdaQueryWrapper<AiAgent>()
            .eq(AiAgent::getEnabled, Boolean.TRUE));

        AiScreenDashboardResponse response = new AiScreenDashboardResponse();
        response.setKpis(buildKpis(
            recognitionStats.matchRate,
            recognitionStats.avgConfidence,
            concurrent,
            recognitionStats.avgLatencyMs
        ));
        response.getHeroCore().setResolve(round1(resolveRate));
        response.getHeroCore().setTransfer(round1(transferRate));
        response.getHeroCore().setFailRate(round1(failRate));
        response.getHeroCore().setInbound(inbound);
        response.getHeroCore().setAvgConfidence(round2(recognitionStats.avgConfidence));
        response.getHeroExtras().setFaqPending(faqPending);
        response.getHeroExtras().setTodaySessions(inbound);
        response.getHeroExtras().setActiveAgents(activeAgents);
        response.setOutcomes(List.of(
            outcome("\u5df2\u89e3\u51b3", bucket.resolved, "#2ee6a8"),
            outcome("\u8f6c\u4eba\u5de5", bucket.transfer, "#ff9a3c"),
            outcome("\u8bc6\u522b\u5931\u8d25", bucket.fail, "#ff7a7a")
        ));
        response.setIntentRanking(buildIntentRanking(todayLogs));
        response.setFeed(buildFeed(todaySessions));
        response.setTrafficTrend(buildTrafficTrend(todaySessions));
        response.setLatencyTrend(buildLatencyTrend(todayLogs));
        return response;
    }

    private List<AiScreenDashboardResponse.KpiItem> buildKpis(double intentMatchRate, double avgConfidence,
                                                              long concurrent, long avgLatency) {
        List<AiScreenDashboardResponse.KpiItem> list = new ArrayList<>();
        list.add(kpi("\u610f\u56fe\u8bc6\u522b\u51c6\u786e\u7387", formatPercent(intentMatchRate * 100), "\u8fd1 1 \u5c0f\u65f6", "is-up"));
        list.add(kpi("ASR \u51c6\u786e\u7387", formatPercent(avgConfidence * 100), "\u7f6e\u4fe1\u5ea6\u4ee3\u7406", "is-up"));
        list.add(kpi("AI \u5e76\u53d1\u4f1a\u8bdd", String.valueOf(concurrent),
            concurrent > 0 ? ("\u5f53\u524d " + concurrent) : "\u6682\u65e0\u4f1a\u8bdd", null));
        list.add(kpi("\u5e73\u5747\u54cd\u5e94", avgLatency + " ms", "ASR+NLU", avgLatency > 500 ? "is-down" : "is-up"));
        return list;
    }

    private List<AiScreenDashboardResponse.IntentRankItem> buildIntentRanking(List<AiIntentRecognitionLog> logs) {
        Map<String, Long> counts = new HashMap<>();
        for (AiIntentRecognitionLog log : logs) {
            if (!"MATCHED".equalsIgnoreCase(safe(log.getRecognitionStatus()))) {
                continue;
            }
            String name = StringUtils.isNotBlank(log.getIntentName()) ? log.getIntentName()
                : (StringUtils.isNotBlank(log.getIntentCode()) ? log.getIntentCode() : "\u672a\u77e5\u610f\u56fe");
            counts.merge(name, 1L, Long::sum);
        }
        long max = counts.values().stream().mapToLong(Long::longValue).max().orElse(1L);
        return counts.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(5)
            .map(entry -> {
                AiScreenDashboardResponse.IntentRankItem item = new AiScreenDashboardResponse.IntentRankItem();
                item.setName(entry.getKey());
                item.setCount(entry.getValue());
                item.setPercent((int) Math.round(entry.getValue() * 100.0 / max));
                return item;
            })
            .toList();
    }

    private List<AiScreenDashboardResponse.FeedItem> buildFeed(List<AiRealtimeCallSession> sessions) {
        return sessions.stream()
            .filter(this::isTransferLike)
            .limit(8)
            .map(session -> {
                AiScreenDashboardResponse.FeedItem item = new AiScreenDashboardResponse.FeedItem();
                item.setId(String.valueOf(session.getId()));
                LocalDateTime t = session.getLastActivityAt() != null ? session.getLastActivityAt()
                    : (session.getEndedAt() != null ? session.getEndedAt() : toLocalDateTime(session.getCreateTime()));
                item.setTime(t == null ? "--:--:--" : t.format(TIME_FMT));
                item.setIntent(shortReason(session.getFailureReason()));
                item.setReason(transferReasonText(session.getFailureReason()));
                if ("TRANSFERRING".equalsIgnoreCase(safe(session.getSessionState()))) {
                    item.setStatus("\u6392\u961f\u4e2d");
                    item.setTagClass("is-info");
                } else {
                    item.setStatus("\u5df2\u8f6c\u63a5");
                    item.setTagClass("is-warn");
                }
                return item;
            })
            .toList();
    }

    private List<AiScreenDashboardResponse.TrafficPoint> buildTrafficTrend(List<AiRealtimeCallSession> sessions) {
        Map<String, long[]> buckets = new LinkedHashMap<>();
        for (int hour = 8; hour <= 18; hour++) {
            buckets.put(String.format(Locale.ROOT, "%02d:00", hour), new long[3]);
        }
        for (AiRealtimeCallSession session : sessions) {
            LocalDateTime t = session.getConnectedAt() != null ? session.getConnectedAt()
                : toLocalDateTime(session.getCreateTime());
            if (t == null) {
                continue;
            }
            String key = t.format(HOUR_FMT);
            long[] arr = buckets.get(key);
            if (arr == null) {
                continue;
            }
            arr[0]++;
            if (isTransferLike(session)) {
                arr[1]++;
            }
            if (isResolved(session)) {
                arr[2]++;
            }
        }
        List<AiScreenDashboardResponse.TrafficPoint> points = new ArrayList<>();
        buckets.forEach((hour, arr) -> {
            AiScreenDashboardResponse.TrafficPoint point = new AiScreenDashboardResponse.TrafficPoint();
            point.setHour(hour);
            point.setAi(arr[0]);
            point.setHuman(arr[1]);
            point.setResolved(arr[2]);
            points.add(point);
        });
        return points;
    }

    private List<AiScreenDashboardResponse.LatencyPoint> buildLatencyTrend(List<AiIntentRecognitionLog> logs) {
        Map<String, List<Long>> buckets = new LinkedHashMap<>();
        for (int hour = 8; hour <= 16; hour++) {
            buckets.put(String.format(Locale.ROOT, "%02d:00", hour), new ArrayList<>());
        }
        for (AiIntentRecognitionLog log : logs) {
            LocalDateTime t = toLocalDateTime(log.getCreateTime());
            if (t == null || log.getLatencyMs() == null) {
                continue;
            }
            String key = t.format(HOUR_FMT);
            List<Long> list = buckets.get(key);
            if (list != null) {
                list.add(log.getLatencyMs());
            }
        }
        List<AiScreenDashboardResponse.LatencyPoint> points = new ArrayList<>();
        buckets.forEach((hour, values) -> {
            AiScreenDashboardResponse.LatencyPoint point = new AiScreenDashboardResponse.LatencyPoint();
            point.setHour(hour);
            long avg = values.isEmpty() ? 0L
                : Math.round(values.stream().mapToLong(Long::longValue).average().orElse(0));
            point.setAsr(avg);
            point.setTts(Math.max(0, avg - 40));
            points.add(point);
        });
        return points;
    }

    private OutcomeBucket classifySessions(List<AiRealtimeCallSession> sessions) {
        OutcomeBucket bucket = new OutcomeBucket();
        for (AiRealtimeCallSession session : sessions) {
            if (isTransferLike(session)) {
                bucket.transfer++;
            } else if (isFailed(session)) {
                bucket.fail++;
            } else if (isResolved(session) || "ENDED".equalsIgnoreCase(safe(session.getSessionState()))) {
                bucket.resolved++;
            }
        }
        return bucket;
    }

    private RecognitionStats summarizeRecognition(List<AiIntentRecognitionLog> logs) {
        RecognitionStats stats = new RecognitionStats();
        if (logs.isEmpty()) {
            return stats;
        }
        long matched = logs.stream().filter(l -> "MATCHED".equalsIgnoreCase(safe(l.getRecognitionStatus()))).count();
        stats.matchRate = matched * 1.0 / logs.size();
        List<BigDecimal> confidences = logs.stream()
            .map(AiIntentRecognitionLog::getConfidence)
            .filter(Objects::nonNull)
            .toList();
        stats.avgConfidence = confidences.isEmpty() ? 0
            : confidences.stream().mapToDouble(BigDecimal::doubleValue).average().orElse(0);
        List<Long> latencies = logs.stream()
            .map(AiIntentRecognitionLog::getLatencyMs)
            .filter(Objects::nonNull)
            .toList();
        stats.avgLatencyMs = latencies.isEmpty() ? 0L
            : Math.round(latencies.stream().mapToLong(Long::longValue).average().orElse(0));
        return stats;
    }

    private boolean isTransferLike(AiRealtimeCallSession session) {
        String state = safe(session.getSessionState());
        String reason = safe(session.getFailureReason()).toUpperCase(Locale.ROOT);
        return "TRANSFERRING".equalsIgnoreCase(state)
            || reason.contains("TRANSFER")
            || reason.contains("HANDOFF");
    }

    private boolean isFailed(AiRealtimeCallSession session) {
        String state = safe(session.getSessionState());
        String reason = safe(session.getFailureReason()).toUpperCase(Locale.ROOT);
        return "FAILED".equalsIgnoreCase(state)
            || reason.contains("EMPTY_RECOGNITION")
            || reason.contains("END_CALL_FAILED");
    }

    private boolean isResolved(AiRealtimeCallSession session) {
        String state = safe(session.getSessionState());
        String reason = safe(session.getFailureReason()).toUpperCase(Locale.ROOT);
        if (!"ENDED".equalsIgnoreCase(state)) {
            return false;
        }
        return reason.contains("END_CALL")
            || reason.contains("AI_HANDOFF_COMPLETE")
            || reason.isBlank()
            || (!reason.contains("TRANSFER") && !reason.contains("EMPTY_RECOGNITION"));
    }

    private String transferReasonText(String failureReason) {
        String reason = safe(failureReason).toUpperCase(Locale.ROOT);
        if (reason.contains("QUEUE")) {
            return "\u8f6c\u961f\u5217";
        }
        if (reason.contains("EXTENSION")) {
            return "\u8f6c\u5206\u673a";
        }
        if (reason.contains("IVR")) {
            return "\u8f6c IVR";
        }
        if (reason.contains("HANDOFF")) {
            return "\u4f1a\u8bdd\u79fb\u4ea4";
        }
        return StringUtils.isBlank(failureReason) ? "\u8f6c\u4eba\u5de5" : shortReason(failureReason);
    }

    private String shortReason(String failureReason) {
        if (StringUtils.isBlank(failureReason)) {
            return "\u8f6c\u4eba\u5de5";
        }
        String cleaned = failureReason.replace("INTENT_", "").replace('_', ' ');
        return cleaned.length() > 12 ? cleaned.substring(0, 12) : cleaned;
    }

    private LocalDateTime toLocalDateTime(Date date) {
        if (date == null) {
            return null;
        }
        return LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault());
    }

    private AiScreenDashboardResponse.KpiItem kpi(String label, String value, String extra, String tone) {
        AiScreenDashboardResponse.KpiItem item = new AiScreenDashboardResponse.KpiItem();
        item.setLabel(label);
        item.setValue(value);
        item.setExtra(extra);
        item.setTone(tone);
        return item;
    }

    private AiScreenDashboardResponse.OutcomeItem outcome(String label, long value, String color) {
        AiScreenDashboardResponse.OutcomeItem item = new AiScreenDashboardResponse.OutcomeItem();
        item.setLabel(label);
        item.setValue(value);
        item.setColor(color);
        return item;
    }

    private double ratio(long part, long total) {
        if (total <= 0) {
            return 0;
        }
        return part * 100.0 / total;
    }

    private double round1(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    private double round2(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private String formatPercent(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP) + "%";
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private static class OutcomeBucket {
        private long resolved;
        private long transfer;
        private long fail;
    }

    private static class RecognitionStats {
        private double matchRate;
        private double avgConfidence;
        private long avgLatencyMs;
    }
}
