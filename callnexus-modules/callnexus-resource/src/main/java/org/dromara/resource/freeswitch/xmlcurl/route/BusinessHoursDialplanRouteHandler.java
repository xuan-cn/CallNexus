package org.dromara.resource.freeswitch.xmlcurl.route;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.resource.businesshours.domain.PhoneBusinessHoursRoute;
import org.dromara.resource.businesshours.domain.response.BusinessHoursEvaluation;
import org.dromara.resource.businesshours.service.BusinessHoursQueryService;
import org.dromara.resource.businesshours.service.PhoneBusinessHoursRouteService;
import org.dromara.resource.freeswitch.xml.FreeSwitchXmlRenderer;
import org.dromara.resource.event.businesshours.BusinessHoursRouteEvaluatedEvent;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.resource.phone.domain.response.PhoneNumberDialplanRouteResponse;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@RequiredArgsConstructor
@Slf4j
public class BusinessHoursDialplanRouteHandler implements DialplanRouteHandler {
    private final PhoneBusinessHoursRouteService routeService;
    private final BusinessHoursQueryService queryService;
    private final ExtensionDialplanRouteHandler extensionHandler;
    private final IvrDialplanRouteHandler ivrHandler;
    private final QueueDialplanRouteHandler queueHandler;
    private final VoiceMailDialplanRouteHandler voiceMailHandler;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public String routeType() {
        return "BUSINESS_HOURS";
    }

    @Override
    public String render(DialplanRouteContext context) {
        try {
            PhoneBusinessHoursRoute config = routeService.require(Long.valueOf(context.route().getRouteTarget()));
            BusinessHoursEvaluation evaluation = queryService.evaluate(
                context.request().tenantId(), config.getPlanId(), Instant.now());
            String targetType = evaluation.isInBusinessHours() ? config.getInHoursTargetType() : config.getOutHoursTargetType();
            String target = evaluation.isInBusinessHours() ? config.getInHoursTarget() : config.getOutHoursTarget();
            log.info("工作时间路由判断完成，number={}，planId={}，inBusinessHours={}，reason={}，targetType={}，target={}",
                context.route().getNumber(), config.getPlanId(), evaluation.isInBusinessHours(), evaluation.getReason(), targetType, target);
            publishEvaluation(context, config, evaluation, targetType, target);
            if ("HANGUP".equals(targetType)) {
                return renderHangup(context);
            }
            DialplanRouteContext delegated = new DialplanRouteContext(
                context.request(), copyRoute(context.route(), targetType, target), context.dialplanContext(), context.callerNumber());
            return switch (targetType) {
                case "EXTENSION" -> extensionHandler.render(delegated);
                case "IVR" -> ivrHandler.render(delegated);
                case "QUEUE" -> queueHandler.render(delegated);
                case "VOICEMAIL" -> voiceMailHandler.render(delegated);
                default -> FreeSwitchXmlRenderer.notFound();
            };
        } catch (Exception exception) {
            log.warn("生成工作时间号码路由失败，number={}，routeTarget={}，原因={}",
                context.route().getNumber(), context.route().getRouteTarget(), exception.getMessage());
            return FreeSwitchXmlRenderer.notFound();
        }
    }

    private void publishEvaluation(DialplanRouteContext context, PhoneBusinessHoursRoute config,
                                   BusinessHoursEvaluation evaluation, String targetType, String target) {
        String businessCallId = context.request().firstValue("variable_callnexus_business_call_id");
        String channelUuid = context.request().firstValue("variable_uuid");
        if (StringUtils.isBlank(businessCallId)) businessCallId = context.request().firstValue("Unique-ID");
        if (StringUtils.isBlank(businessCallId) || StringUtils.isBlank(channelUuid)) return;
        eventPublisher.publishEvent(new BusinessHoursRouteEvaluatedEvent(
            context.request().tenantId(), businessCallId, channelUuid, config.getPlanId(),
            evaluation.isInBusinessHours(), evaluation.getReason(), evaluation.getTimezone(), targetType, target
        ));
    }

    private PhoneNumberDialplanRouteResponse copyRoute(PhoneNumberDialplanRouteResponse source, String type, String target) {
        PhoneNumberDialplanRouteResponse route = new PhoneNumberDialplanRouteResponse();
        route.setId(source.getId());
        route.setNumber(source.getNumber());
        route.setNodeId(source.getNodeId());
        route.setSipDomain(source.getSipDomain());
        route.setRouteType(type);
        route.setRouteTarget(target);
        return route;
    }

    private String renderHangup(DialplanRouteContext context) {
        String number = FreeSwitchXmlRenderer.escape(context.route().getNumber());
        String dialplanContext = FreeSwitchXmlRenderer.escape(
            context.dialplanContext() == null || context.dialplanContext().isBlank() ? "public" : context.dialplanContext());
        return """
            <document type="freeswitch/xml">
              <section name="dialplan" description="CallNexus Business Hours Dialplan">
                <context name="%s">
                  <extension name="callnexus_business_hours_hangup_%s" continue="false">
                    <condition field="destination_number" expression="^%s$">
                      <action application="set" data="callnexus_route_type=BUSINESS_HOURS"/>
                      <action application="hangup" data="NORMAL_CLEARING"/>
                    </condition>
                  </extension>
                </context>
              </section>
            </document>
            """.formatted(dialplanContext, number, number);
    }
}
