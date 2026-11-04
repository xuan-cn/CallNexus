package org.dromara.customer.sms.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.customer.customer.domain.response.CustomerResponse;
import org.dromara.customer.customer.service.CustomerApplicationService;
import org.dromara.customer.sms.domain.*;
import org.dromara.customer.sms.mapper.*;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class BusinessSmsService {
    private final SmsChannelMapper channels;
    private final SmsTemplateMapper templates;
    private final SmsSendRecordMapper records;
    private final CustomerApplicationService customers;
    private final RedissonClient redis;
    private final TransactionTemplate transaction;

    public record ChannelView(Long id, String channelName, String supplier, String sdkAppId, String signature,
                              Boolean enabled, Integer minuteLimit, Integer dailyLimit, Integer version,
                              boolean keyConfigured, boolean secretConfigured) { }
    public record TemplateView(Long id, Long channelId, String templateName, String providerTemplateId, String content,
                               List<SmsRequests.Variable> variables, Boolean enabled, Integer version) { }
    public record Preview(Long templateId, String phone, String content, Map<String,String> parameters) { }
    public record RecordView(Long id, Long customerId, String phone, String channelName, String templateName,
                             String content, String source, String operatorName, String status, String resultCode,
                             String providerReference, Date createTime, LocalDateTime submittedAt) { }

    public List<ChannelView> channels() {
        return channels.selectList(new LambdaQueryWrapper<SmsChannel>().orderByDesc(SmsChannel::getCreateTime)).stream()
            .map(c -> new ChannelView(c.getId(), c.getChannelName(), c.getSupplier(), c.getSdkAppId(), c.getSignature(),
                c.getEnabled(), c.getMinuteLimit(), c.getDailyLimit(), c.getVersion(), has(c.getAccessKeyId()), has(c.getAccessKeySecret()))).toList();
    }

    public Long saveChannel(Long id, SmsRequests.Channel request) {
        SmsChannel channel = id == null ? new SmsChannel() : channel(id);
        if (id != null && request.version() == null) throw new ServiceException("请刷新渠道配置后修改");
        if (id != null && !channel.getSupplier().equals(request.supplier()) && templates.selectCount(
            new LambdaQueryWrapper<SmsTemplate>().eq(SmsTemplate::getChannelId, id)) > 0) {
            throw new ServiceException("该渠道已绑定模板，不能直接更换服务商，请新建渠道");
        }
        channel.setChannelName(request.channelName().trim());
        channel.setSupplier(request.supplier());
        channel.setSignature(request.signature().trim());
        channel.setSdkAppId(request.sdkAppId() == null ? "" : request.sdkAppId().trim());
        if (has(request.accessKeyId())) channel.setAccessKeyId(request.accessKeyId().trim());
        if (has(request.accessKeySecret())) channel.setAccessKeySecret(request.accessKeySecret().trim());
        if (!has(channel.getAccessKeyId()) || !has(channel.getAccessKeySecret())) throw new ServiceException("请填写渠道账号密钥");
        if ("TENCENT".equals(channel.getSupplier()) && !has(channel.getSdkAppId())) throw new ServiceException("腾讯云需要填写 SDK App ID");
        channel.setEnabled(request.enabled());
        channel.setMinuteLimit(request.minuteLimit());
        channel.setDailyLimit(request.dailyLimit());
        channel.setVersion(request.version());
        if (id == null) channels.insert(channel);
        else if (channels.updateById(channel) != 1) throw new ServiceException("配置已被修改，请刷新后重试");
        return channel.getId();
    }

    public List<TemplateView> templates(boolean enabledOnly) {
        return templates.selectList(new LambdaQueryWrapper<SmsTemplate>()
            .eq(enabledOnly, SmsTemplate::getEnabled, true).orderByDesc(SmsTemplate::getCreateTime)).stream()
            .filter(t -> !enabledOnly || Boolean.TRUE.equals(channel(t.getChannelId()).getEnabled())).map(this::templateView).toList();
    }

    public Long saveTemplate(Long id, SmsRequests.Template request) {
        SmsChannel channel = channel(request.channelId());
        SmsTemplateRenderer.validate(request.content(), request.variables(), "TENCENT".equals(channel.getSupplier()));
        SmsTemplate template = id == null ? new SmsTemplate() : template(id);
        if (id != null && request.version() == null) throw new ServiceException("请刷新模板后修改");
        template.setChannelId(request.channelId());
        template.setTemplateName(request.templateName().trim());
        template.setProviderTemplateId(request.providerTemplateId().trim());
        template.setContent(request.content());
        template.setVariablesJson(JsonUtils.toJsonString(request.variables()));
        template.setEnabled(request.enabled());
        template.setVersion(request.version());
        if (id == null) templates.insert(template);
        else if (templates.updateById(template) != 1) throw new ServiceException("模板已被修改，请刷新后重试");
        return template.getId();
    }

    public Map<String,Object> customerContext(Long customerId) {
        CustomerResponse customer = customers.get(customerId);
        return Map.of("customerName", customer.getCustomerName() == null ? "" : customer.getCustomerName(),
            "primaryPhone", customer.getPrimaryPhone() == null ? "" : customer.getPrimaryPhone(),
            "phones", customer.getPhones() == null ? List.of() : customer.getPhones(), "templates", templates(true));
    }

    public Preview preview(Long customerId, SmsRequests.Send request) {
        CustomerResponse customer = customerId == null ? null : customers.get(customerId);
        if (customer != null) requireCustomerPhone(customer, request.phone());
        SmsTemplate template = template(request.templateId());
        SmsChannel channel = channel(template.getChannelId());
        if (!Boolean.TRUE.equals(template.getEnabled()) || !Boolean.TRUE.equals(channel.getEnabled())) throw new ServiceException("短信模板或渠道已停用");
        List<SmsRequests.Variable> variables = JsonUtils.parseArray(template.getVariablesJson(), SmsRequests.Variable.class);
        SmsTemplateRenderer.validate(template.getContent(), variables, "TENCENT".equals(channel.getSupplier()));
        var resolved = SmsTemplateRenderer.resolve(variables, request.parameters(), customer);
        String content = "【" + channel.getSignature() + "】" + SmsTemplateRenderer.render(template.getContent(), resolved);
        if (content.length() > 4000) throw new ServiceException("替换变量后的短信内容过长，请缩短模板或变量");
        return new Preview(template.getId(), request.phone(), content, resolved);
    }

    public Preview testPreview(Long channelId, SmsRequests.Send request) {
        if (!template(request.templateId()).getChannelId().equals(channelId)) throw new ServiceException("测试模板不属于当前渠道");
        return preview(null, request);
    }

    public Long enqueue(Long customerId, Long testChannelId, SmsRequests.Send request) {
        if (request.requestId() == null || request.requestId().isBlank()) throw new ServiceException("缺少发送请求标识，请重新打开发送窗口");
        Preview preview = preview(customerId, request);
        SmsTemplate template = template(request.templateId());
        SmsChannel channel = channel(template.getChannelId());
        if (testChannelId != null && !channel.getId().equals(testChannelId)) throw new ServiceException("测试模板不属于当前渠道");
        String tenant = Objects.requireNonNullElse(TenantHelper.getTenantId(), "000000");
        var lock = redis.getLock("callnexus:business-sms:" + tenant + ":" + request.phone());
        try {
            if (!lock.tryLock(2, TimeUnit.SECONDS)) throw new ServiceException("正在处理该号码的发送请求，请稍后再试");
            // Commit the rate-limit reservation before releasing the distributed recipient lock.
            return transaction.execute(ignored -> reserve(customerId, request, preview, template, channel, tenant));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ServiceException("短信请求已中断，请查询发送记录");
        } finally {
            if (lock.isHeldByCurrentThread()) lock.unlock();
        }
    }

    private Long reserve(Long customerId, SmsRequests.Send request, Preview preview, SmsTemplate template, SmsChannel channel, String tenant) {
        String hash = fingerprint(customerId, preview, channel.getId());
        SmsSendRecord existing = records.selectOne(new LambdaQueryWrapper<SmsSendRecord>().eq(SmsSendRecord::getRequestId, request.requestId()));
        if (existing != null) {
            if (!hash.equals(existing.getRequestHash())) throw new ServiceException("同一请求标识不能用于不同短信");
            return existing.getId();
        }
        LocalDateTime now = LocalDateTime.now();
        long minuteCount = records.selectCount(new LambdaQueryWrapper<SmsSendRecord>().eq(SmsSendRecord::getPhone, request.phone())
            .ge(SmsSendRecord::getCreateTime, now.minusMinutes(1)));
        long dailyCount = records.selectCount(new LambdaQueryWrapper<SmsSendRecord>().eq(SmsSendRecord::getPhone, request.phone())
            .ge(SmsSendRecord::getCreateTime, LocalDate.now().atStartOfDay()));
        if (minuteCount >= channel.getMinuteLimit() || dailyCount >= channel.getDailyLimit()) throw new ServiceException("该号码已达到短信发送频率限制");
        SmsSendRecord record = new SmsSendRecord();
        record.setTenantId(tenant);
        record.setCustomerId(customerId);
        record.setChannelId(channel.getId());
        record.setTemplateId(template.getId());
        record.setChannelName(channel.getChannelName());
        record.setTemplateName(template.getTemplateName());
        record.setSupplier(channel.getSupplier());
        record.setSignature(channel.getSignature());
        record.setProviderTemplateId(template.getProviderTemplateId());
        record.setPhone(request.phone());
        record.setContent(preview.content());
        record.setParametersJson(JsonUtils.toJsonString(preview.parameters()));
        record.setSource(customerId == null ? "TEST" : "CUSTOMER_MANUAL");
        record.setOperatorName(LoginHelper.getUsername());
        record.setRequestId(request.requestId());
        record.setRequestHash(hash);
        record.setStatus("QUEUED");
        records.insert(record);
        return record.getId();
    }

    public TableDataInfo<RecordView> records(Long customerId, String phone, String status, PageQuery pageQuery) {
        if (customerId != null) customers.get(customerId);
        var page = records.selectPage(pageQuery.build(), new LambdaQueryWrapper<SmsSendRecord>()
            .eq(customerId != null, SmsSendRecord::getCustomerId, customerId)
            .eq(has(phone), SmsSendRecord::getPhone, phone).eq(has(status), SmsSendRecord::getStatus, status)
            .orderByDesc(SmsSendRecord::getCreateTime).orderByDesc(SmsSendRecord::getId));
        return new TableDataInfo<>(page.getRecords().stream().map(r -> new RecordView(r.getId(), r.getCustomerId(), r.getPhone(),
            r.getChannelName(), r.getTemplateName(), r.getContent(), r.getSource(), r.getOperatorName(), r.getStatus(), r.getResultCode(),
            r.getProviderReference(), r.getCreateTime(), r.getSubmittedAt())).toList(), page.getTotal());
    }

    public SmsChannel channel(Long id) {
        SmsChannel channel = channels.selectById(id);
        if (channel == null) throw new ServiceException("短信渠道不存在或无权访问");
        return channel;
    }

    private SmsTemplate template(Long id) {
        SmsTemplate template = templates.selectById(id);
        if (template == null) throw new ServiceException("短信模板不存在或无权访问");
        return template;
    }

    private TemplateView templateView(SmsTemplate t) {
        return new TemplateView(t.getId(), t.getChannelId(), t.getTemplateName(), t.getProviderTemplateId(), t.getContent(),
            JsonUtils.parseArray(t.getVariablesJson(), SmsRequests.Variable.class), t.getEnabled(), t.getVersion());
    }

    static void requireCustomerPhone(CustomerResponse customer, String phone) {
        boolean permitted = customer.getPhones() != null && customer.getPhones().stream()
            .anyMatch(p -> Boolean.TRUE.equals(p.getEnabled()) && (phone.equals(p.getNormalizedPhone()) || phone.equals(p.getPhoneNumber())));
        if (!permitted && (customer.getPhones() == null || customer.getPhones().isEmpty())) permitted = phone.equals(customer.getPrimaryPhone());
        if (!permitted) throw new ServiceException("只能向该客户已启用的联系电话发送短信");
    }

    static String fingerprint(Long customerId, Preview preview, Long channelId) {
        try {
            String body = JsonUtils.toJsonString(Arrays.asList(customerId, channelId, preview.templateId(), preview.phone(), preview.content(), new TreeMap<>(preview.parameters())));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) { throw new IllegalStateException("Unable to calculate SMS fingerprint"); }
    }

    private static boolean has(String value) { return value != null && !value.isBlank(); }
}
