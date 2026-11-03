package org.dromara.customer.customer.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.customer.customer.domain.CustomerImportTask;
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

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Tag("dev")
@SuppressWarnings({"unchecked", "rawtypes"})
class CustomerImportTaskDateFilterTest {
    @BeforeAll
    static void initializeMapping() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), CustomerImportTask.class);
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
            mock(CustomerImportRowMapper.class), mock(CustomerAssignmentMapper.class));
    }
}
