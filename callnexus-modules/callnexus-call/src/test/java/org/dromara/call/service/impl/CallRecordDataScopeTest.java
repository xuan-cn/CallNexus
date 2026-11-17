package org.dromara.call.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.agent.service.AgentDataScopeService;
import org.dromara.call.domain.CallSession;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

@Tag("dev")
class CallRecordDataScopeTest {
    @BeforeAll
    static void initializeTableMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), CallSession.class);
    }

    private String sql(AgentDataScopeService.Scope scope) {
        LambdaQueryWrapper<CallSession> wrapper = new LambdaQueryWrapper<>();
        CallRecordApplicationServiceImpl service = mock(CallRecordApplicationServiceImpl.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.invokeMethod(service, "applyDataScope", wrapper, scope);
        return wrapper.getSqlSegment();
    }

    @Test
    void combinesOwnedCallsAndUnassignedQueueCallsWithOr() {
        String sql = sql(new AgentDataScopeService.Scope(true, Set.of(1002L), Set.of(), Set.of(10L)));
        assertTrue(sql.contains("agent_id IN"), sql);
        assertTrue(sql.contains(" OR (agent_id IS NULL AND handling_queue_id IN"), sql);
        assertFalse(sql.contains("agent_id IN (#{ew.paramNameValuePairs.MPGENVAL1}) AND"), sql);
    }

    @Test
    void supportsAgentOnlyScope() {
        String sql = sql(new AgentDataScopeService.Scope(true, Set.of(1002L), Set.of(), Set.of()));
        assertTrue(sql.contains("agent_id IN"), sql);
        assertFalse(sql.contains("handling_queue_id"), sql);
    }

    @Test
    void supportsQueueOnlyScopeWithoutLeadingOr() {
        String sql = sql(new AgentDataScopeService.Scope(true, Set.of(), Set.of(), Set.of(10L)));
        assertTrue(sql.contains("agent_id IS NULL AND handling_queue_id IN"), sql);
        assertFalse(sql.contains("OR"), sql);
    }

    @Test
    void deniesEmptyRestrictedScope() {
        assertTrue(sql(new AgentDataScopeService.Scope(true, Set.of(), Set.of(), Set.of())).contains("1 = 0"));
    }

    @Test
    void doesNotRestrictAdministratorScope() {
        assertEquals("", sql(AgentDataScopeService.Scope.unrestricted()));
    }
}
