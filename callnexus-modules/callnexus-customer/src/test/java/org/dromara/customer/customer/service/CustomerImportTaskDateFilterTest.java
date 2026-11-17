package org.dromara.customer.customer.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.customer.customer.domain.CustomerImportTask;
import org.dromara.customer.customer.domain.CustomerImportRow;
import org.dromara.customer.customer.domain.CustomerImportBatch;
import org.dromara.customer.customer.domain.request.CustomerImportTaskQuery;
import org.dromara.customer.customer.mapper.CustomerAssignmentMapper;
import org.dromara.customer.customer.mapper.CustomerImportBatchMapper;
import org.dromara.customer.customer.mapper.CustomerImportRowMapper;
import org.dromara.customer.customer.mapper.CustomerImportTaskMapper;
import org.dromara.customer.customer.service.impl.CustomerImportTaskServiceImpl;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Tag("dev")
@SuppressWarnings({"unchecked", "rawtypes"})
class CustomerImportTaskDateFilterTest {
    @BeforeAll
    static void initializeMapping() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), CustomerImportTask.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), CustomerImportRow.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), CustomerImportBatch.class);
    }

    @Test
    void deletesOnlyUnsharedCustomersCreatedByTheTask() {
        CustomerImportTaskMapper tasks = mock(CustomerImportTaskMapper.class);
        CustomerImportBatchMapper batches = mock(CustomerImportBatchMapper.class);
        CustomerImportRowMapper rows = mock(CustomerImportRowMapper.class);
        CustomerApplicationService customers = mock(CustomerApplicationService.class);
        ObjectProvider<CustomerApplicationService> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(customers);
        when(tasks.selectForUpdate(1L)).thenReturn(new CustomerImportTask());
        CustomerImportRow owned = new CustomerImportRow();
        owned.setCustomerId(10L);
        CustomerImportRow shared = new CustomerImportRow();
        shared.setCustomerId(11L);
        when(rows.selectList(any())).thenReturn(List.of(owned, shared), List.of(shared));

        new CustomerImportTaskServiceImpl(tasks, batches, rows, mock(CustomerAssignmentMapper.class), provider).delete(1L);

        verify(customers).deleteImportedCustomer(10L);
        verifyNoMoreInteractions(customers);
        ArgumentCaptor<LambdaQueryWrapper<CustomerImportRow>> queries = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(rows, times(2)).selectList(queries.capture());
        LambdaQueryWrapper<CustomerImportRow> ownership = queries.getAllValues().get(0);
        assertTrue(ownership.getSqlSegment().contains("batch_id IS NOT NULL"));
        assertTrue(ownership.getSqlSegment().contains("error_message ="));
        assertTrue(ownership.getParamNameValuePairs().containsValue("导入成功"));
        verify(rows).delete(any());
        verify(batches).delete(any());
        verify(tasks).deleteById(1L);
    }

    @Test
    void blocksDeletionWhileImportRunsOrOutboundSourceExists() {
        CustomerImportTaskMapper tasks = mock(CustomerImportTaskMapper.class);
        CustomerImportBatchMapper batches = mock(CustomerImportBatchMapper.class);
        CustomerImportRowMapper rows = mock(CustomerImportRowMapper.class);
        when(tasks.selectForUpdate(1L)).thenReturn(new CustomerImportTask());
        when(batches.selectCount(any())).thenReturn(1L, 0L);
        when(tasks.hasOutboundSource(1L)).thenReturn(true);
        var service = new CustomerImportTaskServiceImpl(tasks, batches, rows, mock(CustomerAssignmentMapper.class), mock(ObjectProvider.class));
        assertThrows(ServiceException.class, () -> service.delete(1L));
        assertThrows(ServiceException.class, () -> service.delete(1L));
        verifyNoInteractions(rows);
        verify(tasks, never()).deleteById(1L);
    }

    @Test
    void includesWholeEndDateAndPreservesOtherFilters() {
        CustomerImportTaskQuery query = new CustomerImportTaskQuery();
        query.setTaskName("回访");
        query.setStatus("ENABLED");
        query.setCreatedDateFrom(LocalDate.of(2026, 10, 1));
        query.setCreatedDateTo(LocalDate.of(2026, 10, 28));
        LambdaQueryWrapper<CustomerImportTask> wrapper = capture(query);
        String sql = wrapper.getSqlSegment();
        assertTrue(sql.contains("create_time >="));
        assertTrue(sql.contains("create_time <"));
        assertTrue(sql.contains("task_name LIKE"));
        assertTrue(sql.contains("status ="));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(LocalDateTime.of(2026, 10, 1, 0, 0)));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(LocalDateTime.of(2026, 10, 29, 0, 0)));
    }

    @Test
    void supportsSingleDayAndOneSidedRanges() {
        CustomerImportTaskQuery query = new CustomerImportTaskQuery();
        query.setCreatedDateFrom(LocalDate.of(2026, 10, 28));
        query.setCreatedDateTo(query.getCreatedDateFrom());
        LambdaQueryWrapper<CustomerImportTask> sameDay = capture(query);
        sameDay.getSqlSegment();
        assertTrue(sameDay.getParamNameValuePairs().containsValue(LocalDateTime.of(2026, 10, 29, 0, 0)));
        query.setCreatedDateFrom(null);
        assertFalse(capture(query).getSqlSegment().contains("create_time >="));
        query.setCreatedDateFrom(query.getCreatedDateTo());
        query.setCreatedDateTo(null);
        assertFalse(capture(query).getSqlSegment().contains("create_time <"));
    }

    @Test
    void clearingRangeRemovesDateConditions() {
        LambdaQueryWrapper<CustomerImportTask> wrapper = capture(null);
        String sql = wrapper.getSqlSegment();
        assertFalse(sql.contains("create_time >="));
        assertFalse(sql.contains("create_time <"));
        assertTrue(sql.contains("ORDER BY create_time DESC"));
    }

    @Test
    void rejectsReversedDatesBeforeQueryingDatabase() {
        CustomerImportTaskMapper mapper = mock(CustomerImportTaskMapper.class);
        CustomerImportTaskQuery query = new CustomerImportTaskQuery();
        query.setCreatedDateFrom(LocalDate.of(2026, 10, 28));
        query.setCreatedDateTo(LocalDate.of(2026, 10, 1));
        assertThrows(ServiceException.class, () -> service(mapper).page(query, new PageQuery(12, 1)));
        verifyNoInteractions(mapper);
    }

    private LambdaQueryWrapper<CustomerImportTask> capture(CustomerImportTaskQuery query) {
        CustomerImportTaskMapper mapper = mock(CustomerImportTaskMapper.class);
        when(mapper.selectPage(any(Page.class), any())).thenReturn(new Page<CustomerImportTask>());
        service(mapper).page(query, new PageQuery(12, 1));
        ArgumentCaptor<LambdaQueryWrapper<CustomerImportTask>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectPage(any(Page.class), captor.capture());
        return captor.getValue();
    }

    private CustomerImportTaskServiceImpl service(CustomerImportTaskMapper mapper) {
        return new CustomerImportTaskServiceImpl(mapper, mock(CustomerImportBatchMapper.class),
            mock(CustomerImportRowMapper.class), mock(CustomerAssignmentMapper.class),
            mock(ObjectProvider.class));
    }
}
