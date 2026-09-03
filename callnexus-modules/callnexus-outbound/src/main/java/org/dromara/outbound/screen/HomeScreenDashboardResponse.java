package org.dromara.outbound.screen;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 首页运营大屏聚合响应（仅大屏使用，独立于业务领域模型）。
 */
@Data
public class HomeScreenDashboardResponse {

    private List<KpiItem> kpis = new ArrayList<>();
    private HeroCore heroCore = new HeroCore();
    private AgentSummary agentSummary = new AgentSummary();
    private List<QueueRankItem> queueRanking = new ArrayList<>();
    private List<SkillRateItem> skillGroups = new ArrayList<>();
    private List<TrendPoint> trendHours = new ArrayList<>();
    private List<FeedItem> liveFeed = new ArrayList<>();

    @Data
    public static class KpiItem {
        private String label;
        private String value;
        private String extra;
        private String tone;
    }

    @Data
    public static class HeroCore {
        private String inbound;
        private String inboundExtra;
        private String inboundTone;
        private double answerRate;
    }

    @Data
    public static class AgentSummary {
        private long total;
        private List<AgentStatItem> items = new ArrayList<>();
    }

    @Data
    public static class AgentStatItem {
        private String label;
        private long value;
        private String color;
    }

    @Data
    public static class QueueRankItem {
        private String name;
        private long waiting;
        private int percent;
    }

    @Data
    public static class SkillRateItem {
        private String name;
        private int rate;
    }

    @Data
    public static class TrendPoint {
        private String hour;
        private long inbound;
        private long outbound;
        private long answered;
    }

    @Data
    public static class FeedItem {
        private String id;
        private String time;
        private String type;
        private String phone;
        private String target;
        private String status;
        private String tagClass;
    }
}
