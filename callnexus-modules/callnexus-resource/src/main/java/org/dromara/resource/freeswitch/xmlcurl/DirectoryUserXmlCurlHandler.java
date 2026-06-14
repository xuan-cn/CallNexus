package org.dromara.resource.freeswitch.xmlcurl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.resource.event.queue.AgentRingSignalEvent;
import org.dromara.resource.freeswitch.xml.FreeSwitchXmlRenderer;
import org.dromara.resource.freeswitch.xml.directory.FreeSwitchDirectoryXmlRenderer;
import org.dromara.resource.node.service.FreeSwitchNodeQueryService;
import org.dromara.resource.sip.domain.response.SipDirectoryAccountResponse;
import org.dromara.resource.sip.service.SipAccountQueryService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class DirectoryUserXmlCurlHandler implements FreeSwitchXmlCurlHandler {
    private final SipAccountQueryService sipAccountQueryService;
    private final FreeSwitchDirectoryXmlRenderer directoryXmlRenderer;
    private final FreeSwitchNodeQueryService nodeQueryService;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public boolean supports(FreeSwitchXmlCurlRequest request) {
        return "directory".equals(request.section()) && StringUtils.isBlank(request.purpose());
    }

    @Override
    public String handle(FreeSwitchXmlCurlRequest request) {
        String domain = request.domain();
        String extension = request.firstValue("user");
        if (StringUtils.isBlank(extension)) extension = request.firstValue("sip_auth_username");
        if (StringUtils.isBlank(extension)) extension = request.firstValue("key_value");
        if (StringUtils.isBlank(domain) || StringUtils.isBlank(extension)) return FreeSwitchXmlRenderer.notFound();

        // 识别 mod_callcenter 队列振铃请求：directory 请求会携带 cc_queue/cc_agent/cc_member_session_uuid。
        // 这是队列向坐席振铃的可靠信号，发布事件供 call 模块记录"坐席振铃"时间线节点。
        publishAgentRingSignalIfFromQueue(request, domain);

        SipDirectoryAccountResponse account = sipAccountQueryService.findDirectoryAccount(request.tenantId(), domain, extension);
        if (account == null) return FreeSwitchXmlRenderer.notFound();
        return directoryXmlRenderer.render(account);
    }

    /**
     * 当 directory 请求来自 mod_callcenter 队列振铃时，发布坐席振铃信号事件。
     *
     * <p>mod_callcenter 给坐席振铃时，会通过 directory xml-curl 查询坐席信息，
     * 请求参数携带 cc_queue、cc_agent、cc_member_session_uuid（= 入站腿 channel uuid）、action=user_call。
     * 这比 ESL CUSTOM 事件更可靠，不受 FreeSWITCH 版本的事件广播机制影响。
     */
    private void publishAgentRingSignalIfFromQueue(FreeSwitchXmlCurlRequest request, String domain) {
        String queueCode = request.firstValue("cc_queue");
        if (StringUtils.isBlank(queueCode)) return;
        String agentIdentity = request.firstValue("cc_agent");
        String memberSessionUuid = request.firstValue("cc_member_session_uuid");
        if (StringUtils.isBlank(agentIdentity) || StringUtils.isBlank(memberSessionUuid)) return;

        Long nodeId = null;
        try {
            String switchIpv4 = request.firstValue("FreeSWITCH-IPv4");
            String hostname = request.firstValue("FreeSWITCH-Hostname");
            if (StringUtils.isNotBlank(switchIpv4) || StringUtils.isNotBlank(hostname)) {
                nodeId = nodeQueryService.resolveEnabledNodeId(request.tenantId(), null, switchIpv4, hostname, null);
            }
        } catch (Exception exception) {
            log.warn("解析队列振铃 directory 请求的节点失败，不影响 directory 返回，domain={}，queueCode={}",
                domain, queueCode, exception);
        }

        try {
            eventPublisher.publishEvent(new AgentRingSignalEvent(
                request.tenantId(),
                memberSessionUuid,
                queueCode,
                agentIdentity,
                request.firstValue("action"),
                nodeId
            ));
        } catch (Exception exception) {
            log.warn("发布坐席振铃信号事件失败，不影响 directory 返回，memberSessionUuid={}，queueCode={}",
                memberSessionUuid, queueCode, exception);
        }
    }
}
