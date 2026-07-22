package org.dromara.resource.number.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.resource.number.domain.AreaCode;
import org.dromara.resource.number.domain.request.AreaCodePageQuery;
import org.dromara.resource.number.domain.request.AreaCodeRequest;
import org.dromara.resource.number.domain.response.AreaCodeResponse;
import org.dromara.resource.number.mapper.AreaCodeMapper;
import org.dromara.resource.number.service.AreaCodeService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AreaCodeServiceImpl implements AreaCodeService {

    private static final String DEFAULT_COUNTRY_CODE = "86";

    private final AreaCodeMapper areaCodeMapper;

    @Override
    public TableDataInfo<AreaCodeResponse> page(AreaCodePageQuery query, PageQuery pageQuery) {
        LambdaQueryWrapper<AreaCode> wrapper = new LambdaQueryWrapper<AreaCode>()
            .eq(StringUtils.isNotBlank(query.getCountryCode()), AreaCode::getCountryCode, normalizeCountryCode(query.getCountryCode()))
            .like(StringUtils.isNotBlank(query.getProvince()), AreaCode::getProvince, query.getProvince())
            .like(StringUtils.isNotBlank(query.getCity()), AreaCode::getCity, query.getCity())
            .eq(StringUtils.isNotBlank(query.getAreaCode()), AreaCode::getAreaCode, normalizeAreaCode(query.getAreaCode()))
            .eq(query.getEnabled() != null, AreaCode::getEnabled, query.getEnabled())
            .orderByAsc(AreaCode::getCountryCode)
            .orderByAsc(AreaCode::getAreaCode);
        Page<AreaCode> page = areaCodeMapper.selectPage(pageQuery.build(), wrapper);
        return new TableDataInfo<>(page.getRecords().stream().map(this::toResponse).toList(), page.getTotal());
    }

    @Override
    public AreaCodeResponse get(Long id) {
        AreaCode areaCode = areaCodeMapper.selectById(id);
        if (areaCode == null) {
            throw new ServiceException("区号不存在");
        }
        return toResponse(areaCode);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(AreaCodeRequest request) {
        String countryCode = normalizeCountryCode(request.getCountryCode());
        String areaCodeValue = normalizeAreaCode(request.getAreaCode());
        ensureAreaCodeValid(areaCodeValue);
        ensureUnique(countryCode, areaCodeValue, null);

        AreaCode areaCode = new AreaCode();
        areaCode.setCountryCode(countryCode);
        areaCode.setProvince(request.getProvince().trim());
        areaCode.setCity(request.getCity().trim());
        areaCode.setAreaCode(areaCodeValue);
        areaCode.setEnabled(!Boolean.FALSE.equals(request.getEnabled()));
        areaCodeMapper.insert(areaCode);
        log.info("新增电话区号，countryCode={}，province={}，city={}，areaCode={}",
            areaCode.getCountryCode(), areaCode.getProvince(), areaCode.getCity(), areaCode.getAreaCode());
        return areaCode.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, AreaCodeRequest request) {
        AreaCode areaCode = areaCodeMapper.selectById(id);
        if (areaCode == null) {
            throw new ServiceException("区号不存在");
        }
        String countryCode = normalizeCountryCode(request.getCountryCode());
        String areaCodeValue = normalizeAreaCode(request.getAreaCode());
        ensureAreaCodeValid(areaCodeValue);
        ensureUnique(countryCode, areaCodeValue, id);

        areaCode.setCountryCode(countryCode);
        areaCode.setProvince(request.getProvince().trim());
        areaCode.setCity(request.getCity().trim());
        areaCode.setAreaCode(areaCodeValue);
        areaCode.setEnabled(!Boolean.FALSE.equals(request.getEnabled()));
        areaCode.setVersion(request.getVersion());
        if (areaCodeMapper.updateById(areaCode) != 1) {
            throw new ServiceException("区号已被其他用户修改，请刷新后重试");
        }
        log.info("更新电话区号，id={}，countryCode={}，areaCode={}，enabled={}",
            id, areaCode.getCountryCode(), areaCode.getAreaCode(), areaCode.getEnabled());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        AreaCode areaCode = areaCodeMapper.selectById(id);
        if (areaCode == null) {
            throw new ServiceException("区号不存在");
        }
        if (areaCodeMapper.deleteById(id) != 1) {
            throw new ServiceException("区号不存在");
        }
        log.info("删除电话区号，id={}，areaCode={}", id, areaCode.getAreaCode());
    }

    private void ensureUnique(String countryCode, String areaCode, Long excludeId) {
        Long count = areaCodeMapper.selectCount(new LambdaQueryWrapper<AreaCode>()
            .eq(AreaCode::getCountryCode, countryCode)
            .eq(AreaCode::getAreaCode, areaCode)
            .ne(excludeId != null, AreaCode::getId, excludeId));
        if (count != null && count > 0) {
            throw new ServiceException("该国家码和区号已存在");
        }
    }

    private String normalizeCountryCode(String countryCode) {
        if (StringUtils.isBlank(countryCode)) {
            return DEFAULT_COUNTRY_CODE;
        }
        return countryCode.trim().replace("+", "");
    }

    private String normalizeAreaCode(String areaCode) {
        if (StringUtils.isBlank(areaCode)) {
            return "";
        }
        String digits = areaCode.trim().replaceAll("\\D", "");
        if (digits.isBlank()) {
            return "";
        }
        return digits.startsWith("0") ? digits : "0" + digits;
    }

    private void ensureAreaCodeValid(String areaCode) {
        if (!areaCode.matches("^0\\d{2,3}$")) {
            throw new ServiceException("区号格式不正确，请填写 010 或 0451 这类国内固话区号");
        }
    }

    private AreaCodeResponse toResponse(AreaCode areaCode) {
        AreaCodeResponse response = new AreaCodeResponse();
        response.setId(areaCode.getId());
        response.setCountryCode(areaCode.getCountryCode());
        response.setProvince(areaCode.getProvince());
        response.setCity(areaCode.getCity());
        response.setAreaCode(areaCode.getAreaCode());
        response.setEnabled(areaCode.getEnabled());
        response.setCreateTime(areaCode.getCreateTime());
        response.setVersion(areaCode.getVersion());
        return response;
    }
}
