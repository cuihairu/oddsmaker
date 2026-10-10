package io.oddsmaker.gateway.config;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 将已认证 API Key 的策略字段投影为 Gateway 使用的轻量对象。
 * 凭据和策略来自同一内部查询，避免向 Control Service 发起两次不一致的调用。
 */
@Component
public class PolicyService {
    private final AuthService authService;

    public static class Policy {
        public Integer rpm;
        public Integer ipRpm;
        public List<String> propsAllowlist;
        public String piiEmail;
        public String piiPhone;
        public String piiIp;
        public List<String> denyKeys;
        public List<String> maskKeys;
        public String schemaPiiPolicy;
    }

    public PolicyService(AuthService authService) {
        this.authService = authService;
    }

    public Policy getPolicy(String apiKey) {
        AuthService.ApiKeyContext context = authService.getContext(apiKey);
        if (context == null) {
            return null;
        }
        Policy policy = new Policy();
        policy.rpm = context.rpm;
        policy.ipRpm = context.ipRpm;
        policy.propsAllowlist = context.propsAllowlist;
        policy.piiEmail = context.piiEmail;
        policy.piiPhone = context.piiPhone;
        policy.piiIp = context.piiIp;
        policy.denyKeys = context.denyKeys;
        policy.maskKeys = context.maskKeys;
        policy.schemaPiiPolicy = context.schemaPiiPolicy;
        return policy;
    }
}
