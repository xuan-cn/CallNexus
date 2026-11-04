package org.dromara.outbound.service;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.outbound.controller.OutboundBlacklistController;
import org.dromara.outbound.controller.OutboundTaskController;
import org.dromara.outbound.domain.OutboundTask;
import org.dromara.outbound.domain.response.OutboundBlacklistTaskOptionResponse;
import org.dromara.outbound.mapper.OutboundTaskMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Tag("dev")
class OutboundBlacklistTaskOptionsTest {
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void optionsSelectOnlyAutomaticTasksAndMinimalFields() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), OutboundTask.class);
        OutboundTaskMapper mapper = mock(OutboundTaskMapper.class);
        OutboundTask task = new OutboundTask();
        task.setId(1L);
        task.setTaskCode("AUTO_TEST");
        task.setTaskName("Test task");
        when(mapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(task));
        var service = new OutboundBlacklistService(null, null, null, mapper, null, null);

        assertEquals(List.of(new OutboundBlacklistTaskOptionResponse(1L, "AUTO_TEST", "Test task")), service.taskOptions());
        ArgumentCaptor<LambdaQueryWrapper<OutboundTask>> query = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectList(query.capture());
        assertEquals("id,task_code,task_name", query.getValue().getSqlSelect());
        assertTrue(query.getValue().getSqlSegment().contains("task_type ="));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue("AUTO"));
    }

    @Test
    void chooserUsesBlacklistPermissionsWithoutOpeningLegacyTaskApi() throws Exception {
        var annotation = OutboundBlacklistController.class.getMethod("taskOptions").getAnnotation(SaCheckPermission.class);
        assertEquals(SaMode.OR, annotation.mode());
        assertEquals(Set.of("callcenter:outbound-blacklist:create", "callcenter:outbound-blacklist:update",
            "callcenter:outbound-blacklist:import"), Set.of(annotation.value()));
        assertArrayEquals(new String[]{"callcenter:outbound-task:list"},
            OutboundTaskController.class.getMethod("list").getAnnotation(SaCheckPermission.class).value());
    }

    @Test
    void controllerReturnsOptionsFromBlacklistService() {
        OutboundBlacklistService service = mock(OutboundBlacklistService.class);
        var options = List.of(new OutboundBlacklistTaskOptionResponse(1L, "AUTO_TEST", "Test task"));
        when(service.taskOptions()).thenReturn(options);
        assertEquals(options, new OutboundBlacklistController(service).taskOptions().getData());
    }
}
