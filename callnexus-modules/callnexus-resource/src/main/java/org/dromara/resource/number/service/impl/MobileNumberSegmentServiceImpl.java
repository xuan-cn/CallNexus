package org.dromara.resource.number.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.resource.number.domain.MobileNumberSegment;
import org.dromara.resource.number.domain.request.MobileNumberSegmentPageQuery;
import org.dromara.resource.number.domain.request.MobileNumberSegmentRequest;
import org.dromara.resource.number.domain.response.MobileNumberSegmentResponse;
import org.dromara.resource.number.mapper.MobileNumberSegmentMapper;
import org.dromara.resource.number.service.MobileNumberSegmentService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class MobileNumberSegmentServiceImpl implements MobileNumberSegmentService {

    private static final String DEFAULT_COUNTRY_CODE = "86";

    private final MobileNumberSegmentMapper mapper;

    @Override
    public TableDataInfo<MobileNumberSegmentResponse> page(MobileNumberSegmentPageQuery query, PageQuery pageQuery) {
        LambdaQueryWrapper<MobileNumberSegment> wrapper = new LambdaQueryWrapper<MobileNumberSegment>()
            .eq(StringUtils.isNotBlank(query.getCountryCode()), MobileNumberSegment::getCountryCode, normalizeCountryCode(query.getCountryCode()))
            .like(StringUtils.isNotBlank(query.getSegmentPrefix()), MobileNumberSegment::getSegmentPrefix, normalizeSegment(query.getSegmentPrefix()))
            .like(StringUtils.isNotBlank(query.getProvince()), MobileNumberSegment::getProvince, query.getProvince())
            .like(StringUtils.isNotBlank(query.getCity()), MobileNumberSegment::getCity, query.getCity())
            .like(StringUtils.isNotBlank(query.getCarrier()), MobileNumberSegment::getCarrier, query.getCarrier())
            .eq(query.getEnabled() != null, MobileNumberSegment::getEnabled, query.getEnabled())
            .orderByAsc(MobileNumberSegment::getSegmentPrefix);
        Page<MobileNumberSegment> page = mapper.selectPage(pageQuery.build(), wrapper);
        return new TableDataInfo<>(page.getRecords().stream().map(this::toResponse).toList(), page.getTotal());
    }

    @Override
    public MobileNumberSegmentResponse get(Long id) {
        MobileNumberSegment segment = mapper.selectById(id);
        if (segment == null) {
            throw new ServiceException("手机号段不存在");
        }
        return toResponse(segment);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(MobileNumberSegmentRequest request) {
        String countryCode = normalizeCountryCode(request.getCountryCode());
        String segmentPrefix = normalizeSegment(request.getSegmentPrefix());
        ensureSegmentValid(segmentPrefix);
        ensureUnique(countryCode, segmentPrefix, null);

        MobileNumberSegment segment = new MobileNumberSegment();
        segment.setCountryCode(countryCode);
        segment.setSegmentPrefix(segmentPrefix);
        segment.setProvince(request.getProvince().trim());
        segment.setCity(request.getCity().trim());
        segment.setCarrier(request.getCarrier().trim());
        segment.setEnabled(!Boolean.FALSE.equals(request.getEnabled()));
        mapper.insert(segment);
        log.info("新增手机号段，countryCode={}，segmentPrefix={}，province={}，city={}，carrier={}",
            segment.getCountryCode(), segment.getSegmentPrefix(), segment.getProvince(), segment.getCity(), segment.getCarrier());
        return segment.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, MobileNumberSegmentRequest request) {
        MobileNumberSegment segment = mapper.selectById(id);
        if (segment == null) {
            throw new ServiceException("手机号段不存在");
        }
        String countryCode = normalizeCountryCode(request.getCountryCode());
        String segmentPrefix = normalizeSegment(request.getSegmentPrefix());
        ensureSegmentValid(segmentPrefix);
        ensureUnique(countryCode, segmentPrefix, id);

        segment.setCountryCode(countryCode);
        segment.setSegmentPrefix(segmentPrefix);
        segment.setProvince(request.getProvince().trim());
        segment.setCity(request.getCity().trim());
        segment.setCarrier(request.getCarrier().trim());
        segment.setEnabled(!Boolean.FALSE.equals(request.getEnabled()));
        segment.setVersion(request.getVersion());
        if (mapper.updateById(segment) != 1) {
            throw new ServiceException("手机号段已被其他用户修改，请刷新后重试");
        }
        log.info("更新手机号段，id={}，segmentPrefix={}，enabled={}", id, segment.getSegmentPrefix(), segment.getEnabled());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        MobileNumberSegment segment = mapper.selectById(id);
        if (segment == null) {
            throw new ServiceException("手机号段不存在");
        }
        if (mapper.deleteById(id) != 1) {
            throw new ServiceException("手机号段不存在");
        }
        log.info("删除手机号段，id={}，segmentPrefix={}", id, segment.getSegmentPrefix());
    }

    private void ensureUnique(String countryCode, String segmentPrefix, Long excludeId) {
        Long count = mapper.selectCount(new LambdaQueryWrapper<MobileNumberSegment>()
            .eq(MobileNumberSegment::getCountryCode, countryCode)
            .eq(MobileNumberSegment::getSegmentPrefix, segmentPrefix)
            .ne(excludeId != null, MobileNumberSegment::getId, excludeId));
        if (count != null && count > 0) {
            throw new ServiceException("该国家码和手机号段已存在");
        }
    }

    private String normalizeCountryCode(String countryCode) {
        if (StringUtils.isBlank(countryCode)) {
            return DEFAULT_COUNTRY_CODE;
        }
        return countryCode.trim().replace("+", "");
    }

    private String normalizeSegment(String segmentPrefix) {
        if (StringUtils.isBlank(segmentPrefix)) {
            return "";
        }
        return segmentPrefix.trim().replaceAll("\\D", "");
    }

    private void ensureSegmentValid(String segmentPrefix) {
        if (!segmentPrefix.matches("^1[3-9]\\d{1,5}$")) {
            throw new ServiceException("手机号段格式不正确，请填写 3 到 7 位手机号前缀，例如 176 或 1760247");
        }
    }

    private MobileNumberSegmentResponse toResponse(MobileNumberSegment segment) {
        MobileNumberSegmentResponse response = new MobileNumberSegmentResponse();
        response.setId(segment.getId());
        response.setCountryCode(segment.getCountryCode());
        response.setSegmentPrefix(segment.getSegmentPrefix());
        response.setProvince(segment.getProvince());
        response.setCity(segment.getCity());
        response.setCarrier(segment.getCarrier());
        response.setEnabled(segment.getEnabled());
        response.setCreateTime(segment.getCreateTime());
        response.setVersion(segment.getVersion());
        return response;
    }
}
