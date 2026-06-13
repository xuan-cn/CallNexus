package org.dromara.common.core.service;

import org.dromara.common.core.domain.dto.OssDTO;

import java.time.Duration;
import java.util.List;

/**
 * 通用 OSS服务
 *
 * @author Lion Li
 */
public interface OssService {

    /**
     * 通过ossId查询对应的url
     *
     * @param ossIds ossId串逗号分隔
     * @return url串逗号分隔
     */
    String selectUrlByIds(String ossIds);

    /**
     * 通过 ossId 获取前端可直接访问的 URL。
     * 私有桶返回指定有效期的预签名 URL，公有桶返回原始 URL。
     *
     * @param ossId ossId
     * @param ttl   私有桶预签名 URL 有效期
     * @return 可访问 URL，文件不存在时返回 null
     */
    String selectUrlById(Long ossId, Duration ttl);

    /**
     * 通过ossId查询列表
     *
     * @param ossIds ossId串逗号分隔
     * @return 列表
     */
    List<OssDTO> selectByIds(String ossIds);
}
