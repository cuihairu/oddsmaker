package io.oddsmaker.control.api;

import java.util.List;

/**
 * API DTO - 单公司多游戏模型
 * 对外仅暴露 Game + Environment。
 */
public class Models {

    public static class ApiKeyResp {
        public String apiKey; // public key
        public String secret; // private secret
        public String gameId;
        public String environmentId;
        public String storageProfileId;
        public String name;
        public String keyRole; // client|server|admin
    }

    public static class CreateKeyReq {
        public String gameId;
        public String environmentId;
        public String name;
        public String keyRole; // client|server|admin，默认 client
    }

    public static class KeyDetailResp {
        public String apiKey;
        public String gameId;
        public String environmentId;
        public String storageProfileId;
        public String name;
        public String keyRole; // client|server|admin
        public String status;  // active|inactive|revoked
        public String createdAt; // ISO-8601
        public Integer rpm;
        public Integer ipRpm;
        public List<String> propsAllowlist;
        public String piiEmail;  // allow|mask|drop
        public String piiPhone;  // allow|mask|drop
        public String piiIp;     // allow|coarse|drop
        public List<String> denyKeys;
        public List<String> maskKeys;
    }

    /**
     * Gateway 专用的内部凭据视图。
     * 仅允许通过受服务间令牌保护的 /internal 接口读取，绝不能由管理 API 返回。
     */
    public static class InternalApiKeyResp {
        public String apiKey;
        public String secret;
        public String gameId;
        public String environment;
        public String keyRole; // client|server|admin
        public String envStatus; // active|inactive|maintenance
        public Boolean envEnableSampling;
        public Double envSampleRate;
        public Boolean canWrite;
        public Boolean requireHmac;
        public Integer rpm;
        public Integer ipRpm;
        public List<String> propsAllowlist;
        public String piiEmail;
        public String piiPhone;
        public String piiIp;
        public List<String> denyKeys;
        public List<String> maskKeys;
    }

    public static class StorageProfileResp {
        public String id;
        public String name;
        public String displayName;
        public String description;
        public String isolationStrategy;
        public String kafkaCluster;
        public String clickhouseCluster;
        public String redisCluster;
        public String archiveBucket;
        public Boolean active;
    }

    public static class CreateStorageProfileReq {
        public String id;
        public String name;
        public String displayName;
        public String description;
        public String isolationStrategy;
        public String kafkaCluster;
        public String clickhouseCluster;
        public String redisCluster;
        public String archiveBucket;
        public Boolean active;
    }
}
