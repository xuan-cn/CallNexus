package org.dromara.license;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.redis.utils.RedisUtils;
import org.redisson.api.RScript;
import org.redisson.client.RedisException;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class LicenseAiCallQuota {
    private static final long LEASE_MS = 60_000;
    public static final long RENEW_MS = 10_000;
    private static final String SCRIPT = """
        local action, call, owner = ARGV[1], ARGV[2], ARGV[3]
        local time = redis.call('TIME')
        local now = time[1] * 1000 + math.floor(time[2] / 1000)
        for _, expired in ipairs(redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', now)) do
            redis.call('HDEL', KEYS[2], expired)
        end
        redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now)
        local current = redis.call('HGET', KEYS[2], call)
        if action == 'acquire' then
            if current then return -1 end
            local limit = tonumber(ARGV[5])
            if limit >= 0 and redis.call('ZCARD', KEYS[1]) >= limit then return 0 end
        elseif current ~= owner then
            return 0
        elseif action == 'release' then
            redis.call('ZREM', KEYS[1], call)
            redis.call('HDEL', KEYS[2], call)
            return 1
        end
        local ttl = tonumber(ARGV[4])
        redis.call('ZADD', KEYS[1], now + ttl, call)
        redis.call('HSET', KEYS[2], call, owner)
        redis.call('PEXPIRE', KEYS[1], ttl)
        redis.call('PEXPIRE', KEYS[2], ttl)
        return 1
        """;

    private final LicenseFeatureGate gate;

    public Lease acquire(String tenantId, String businessCallId) {
        var status = gate.readStatus();
        String denial = LicenseFeatureGate.denial(status, LicenseFeatureGate.Feature.AI_VOICE);
        if (denial != null) throw new ServiceException(denial);
        String key = "callnexus:license:ai:{" + status.deploymentId() + "}:";
        Lease lease = new Lease(key, tenantId + ":" + businessCallId);
        long result;
        try {
            result = lease.eval("acquire", status.limits().getOrDefault("ai_concurrent_calls", -1));
        } catch (RedisException e) {
            log.warn("AI call license Redis admission unavailable", e);
            throw new ServiceException("无法确认 AI 通话并发额度，暂不接入新通话");
        }
        if (result < 0) throw new DuplicateCallException();
        if (result == 0) throw new ServiceException("AI 通话并发已达授权上限");
        return lease;
    }

    public static final class DuplicateCallException extends RuntimeException {
        private DuplicateCallException() {
            super("当前通话已接入 AI");
        }
    }

    public static final class Lease {
        private final List<Object> keys;
        private final String call;
        private final String owner = UUID.randomUUID().toString();
        private long nextRenew = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(RENEW_MS);
        private boolean closed;

        private Lease(String key, String call) {
            this.keys = List.of(key + "expiry", key + "owner");
            this.call = call;
        }

        public synchronized boolean renewIfDue() {
            if (closed) return false;
            if (System.nanoTime() - nextRenew < 0) return true;
            try {
                if (eval("renew", -1) != 1) return false;
                nextRenew = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(RENEW_MS);
                return true;
            } catch (RedisException e) {
                log.warn("AI call license renewal unavailable", e);
                return false;
            }
        }

        public synchronized void release() {
            if (closed) return;
            closed = true;
            try {
                eval("release", -1);
            } catch (RedisException e) {
                log.warn("AI call license release unavailable; lease will expire", e);
            }
        }

        private long eval(String action, int limit) {
            return RedisUtils.getClient().getScript(StringCodec.INSTANCE).eval(
                RScript.Mode.READ_WRITE, SCRIPT, RScript.ReturnType.INTEGER, keys,
                action, call, owner, Long.toString(LEASE_MS), Integer.toString(limit));
        }
    }
}
