package org.dromara.quality.mapper;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.dromara.quality.domain.request.QualityReportQuery;
import org.dromara.quality.service.QualityDataScopeService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualityReportMapperSqlTest {
    private final XMLLanguageDriver languageDriver = new XMLLanguageDriver();
    private final Configuration configuration = new Configuration();

    @Test
    void rendersAllReportQueriesForUnrestrictedScope() {
        assertAllQueriesRender(new QualityDataScopeService.Scope(false, Set.of(), Set.of()));
    }

    @Test
    void rendersAllReportQueriesWhenAgentHasNoQueueScope() {
        assertAllQueriesRender(new QualityDataScopeService.Scope(true, Set.of(1001L), Set.of()));
    }

    @Test
    void rendersAllReportQueriesForAgentAndQueueScope() {
        assertAllQueriesRender(new QualityDataScopeService.Scope(true, Set.of(1001L), Set.of(2001L)));
    }

    @Test
    void trendQueriesUseTheSameBucketExpressionForSelectAndGroupBy() throws Exception {
        assertTrendGrouping("selectEligibleTrend");
        assertTrendGrouping("selectResultTrend");
    }

    @Test
    void rendersEveryAiAdoptionRankingDimension() throws Exception {
        Method method = java.util.Arrays.stream(QualityReportMapper.class.getDeclaredMethods())
            .filter(candidate -> candidate.getName().equals("selectAiAdoptionRanking"))
            .findFirst()
            .orElseThrow();
        Select select = method.getAnnotation(Select.class);
        SqlSource sqlSource = languageDriver.createSqlSource(configuration, String.join(" ", select.value()), Map.class);
        Map<String, Object> parameters = parameters(new QualityDataScopeService.Scope(false, Set.of(), Set.of()));
        for (String dimension : Set.of("AGENT", "REVIEWER", "TEMPLATE")) {
            parameters.put("dimension", dimension);
            BoundSql boundSql = sqlSource.getBoundSql(parameters);
            assertTrue(boundSql.getSql().contains("dimensionName"), dimension);
        }
    }

    private void assertAllQueriesRender(QualityDataScopeService.Scope scope) {
        for (Method method : QualityReportMapper.class.getDeclaredMethods()) {
            Select select = method.getAnnotation(Select.class);
            if (select == null) continue;
            SqlSource sqlSource = languageDriver.createSqlSource(configuration, String.join(" ", select.value()), Map.class);
            BoundSql boundSql = sqlSource.getBoundSql(parameters(scope));
            assertFalse(boundSql.getSql().contains("IN ()"), method.getName());
        }
    }

    private Map<String, Object> parameters(QualityDataScopeService.Scope scope) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("tenantId", "000000");
        parameters.put("startAt", LocalDateTime.of(2026, 9, 1, 0, 0));
        parameters.put("endAt", LocalDateTime.of(2026, 9, 18, 0, 0));
        parameters.put("query", new QualityReportQuery());
        parameters.put("scope", scope);
        parameters.put("_parameter", parameters);
        parameters.put("_databaseId", null);
        return parameters;
    }

    private void assertTrendGrouping(String methodName) throws Exception {
        Method method = java.util.Arrays.stream(QualityReportMapper.class.getDeclaredMethods())
            .filter(candidate -> candidate.getName().equals(methodName))
            .findFirst()
            .orElseThrow();
        String sql = String.join(" ", method.getAnnotation(Select.class).value());
        assertTrue(sql.contains("SELECT DATE_FORMAT(cs.ended_at, '%Y-%m-%d') bucket"));
        assertTrue(sql.contains("GROUP BY DATE_FORMAT(cs.ended_at, '%Y-%m-%d') ORDER BY bucket"));
    }
}
