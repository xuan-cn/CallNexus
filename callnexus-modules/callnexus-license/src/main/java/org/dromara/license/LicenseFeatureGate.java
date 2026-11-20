package org.dromara.license;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Slf4j
@Service
@RequiredArgsConstructor
public class LicenseFeatureGate {
    private final LicenseManagementService management;

    public String denialForOutbound(boolean usesAi) {
        return usesAi ? denial(Feature.AUTO_OUTBOUND, Feature.AI_VOICE) : denial(Feature.AUTO_OUTBOUND);
    }

    public String denialForAiVoice() {
        return denial(Feature.AI_VOICE);
    }

    public void requireOutbound(boolean usesAi) {
        String reason = denialForOutbound(usesAi);
        if (reason != null) throw new ServiceException(reason);
    }

    private String denial(Feature... features) {
        return denial(readStatus(), features);
    }

    LicenseManagementService.Status readStatus() {
        LicenseManagementService.Status status;
        try {
            status = management.status();
        } catch (DataAccessException | TransactionException e) {
            log.warn("无法读取授权状态，拒绝新业务，errorType={}", e.getClass().getSimpleName());
            return null;
        }
        return status;
    }

    static String denial(LicenseManagementService.Status status, Feature... features) {
        if (status == null) return "无法读取系统授权，暂不允许发起新业务";
        if (!"VALID".equals(status.state())) {
            return switch (status.state()) {
                case "EXPIRED" -> "系统授权已过期，请导入续期授权";
                case "NOT_YET_VALID" -> "系统授权尚未生效";
                case "UNLICENSED" -> "系统未导入有效授权，请先导入正式授权";
                default -> "系统授权无效，请检查授权管理";
            };
        }
        for (Feature feature : features) {
            if (!Boolean.TRUE.equals(status.features().get(feature.code))) {
                return "当前授权未启用" + feature.label;
            }
        }
        return null;
    }

    enum Feature {
        AUTO_OUTBOUND("auto_outbound", "自动外呼"), AI_VOICE("ai_voice", "AI 语音机器人");

        private final String code;
        private final String label;

        Feature(String code, String label) {
            this.code = code;
            this.label = label;
        }
    }
}
