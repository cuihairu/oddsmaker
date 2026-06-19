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
        public String name;
    }

    public static class CreateKeyReq {
        public String gameId;
        public String environmentId;
        public String name;
    }

    public static class KeyDetailResp {
        public String apiKey;
        public String secret;
        public String gameId;
        public String environmentId;
        public Integer rpm;
        public Integer ipRpm;
        public List<String> propsAllowlist;
        public String piiEmail;  // allow|mask|drop
        public String piiPhone;  // allow|mask|drop
        public String piiIp;     // allow|coarse|drop
        public List<String> denyKeys;
        public List<String> maskKeys;
    }
}
