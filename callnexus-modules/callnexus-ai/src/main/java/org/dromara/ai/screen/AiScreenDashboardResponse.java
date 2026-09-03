package org.dromara.ai.screen;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 话务大屏聚合响应（仅大屏使用，独立于业务领域模型）。
 */
@Data
public class AiScreenDashboardResponse {

    private List<KpiItem> kpis = new ArrayList<>();
    private HeroCore heroCore = new HeroCore();
    private HeroExtras heroExtras = new HeroExtras();
    private List<OutcomeItem> outcomes = new ArrayList<>();
    private List<IntentRankItem> intentRanking = new ArrayList<>();
    private List<FeedItem> feed = new ArrayList<>();
    private List<TrafficPoint> trafficTrend = new ArrayList<>();
    private List<LatencyPoint> latencyTrend = new ArrayList<>();

    @Data
    public static class KpiItem {
        private String label;
        private String value;
        private String extra;
        private String tone;
    }

    @Data
    public static class HeroCore {
        private double resolve;
        private double transfer;
        private double failRate;
        private long inbound;
        private double avgConfidence;
    }

    @Data
    public static class HeroExtras {
        private long faqPending;
        private long todaySessions;
        private long activeAgents;
    }

    @Data
    public static class OutcomeItem {
        private String label;
        private long value;
        private String color;
    }

    @Data
    public static class IntentRankItem {
        private String name;
        private long count;
        private int percent;
    }

    @Data
    public static class FeedItem {
        private String id;
        private String time;
        private String intent;
        private String reason;
        private String status;
        private String tagClass;
    }

    @Data
    public static class TrafficPoint {
        private String hour;
        private long ai;
        private long human;
        private long resolved;
    }

    @Data
    public static class LatencyPoint {
        private String hour;
        private long asr;
        private long tts;
    }
}
