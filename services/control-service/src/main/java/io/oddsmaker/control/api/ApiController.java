package io.oddsmaker.control.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * API Controller - 单公司多游戏模型
 * 对外仅暴露 Game → Environment → ApiKey。
 */
@RestController
@RequestMapping("/api")
public class ApiController {
    private final ControlService svc;
    public ApiController(ControlService svc) { this.svc = svc; }

    @PostMapping("/keys")
    public Models.ApiKeyResp createKey(@RequestBody Models.CreateKeyReq req) {
        return svc.createKey(req.gameId, req.environmentId, req.name);
    }

    @GetMapping("/keys/{apiKey}")
    public ResponseEntity<Models.KeyDetailResp> getKey(@PathVariable String apiKey) {
        var r = svc.getKey(apiKey);
        if (r == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(r);
    }

    @GetMapping("/keys")
    public Object listKeys(@RequestParam(value = "q", required = false) String q,
                           @RequestParam(value = "gameId", required = false) String gameId,
                           @RequestParam(value = "environmentId", required = false) String environmentId,
                           @RequestParam(value = "page", required = false) Integer page,
                           @RequestParam(value = "size", required = false) Integer size) {
        if (page == null && size == null && q == null && gameId == null && environmentId == null) {
            return svc.listKeys();
        }
        int p = page == null ? 0 : Math.max(0, page);
        int s = size == null ? 50 : Math.max(1, size);
        return svc.searchKeys(gameId, environmentId, q, p, s);
    }

    public static class UpdatePolicyReq {
        public Integer rpm; public Integer ipRpm; public List<String> propsAllowlist;
        public String piiEmail; public String piiPhone; public String piiIp;
        public List<String> denyKeys; public List<String> maskKeys;
    }

    public static class BatchDeleteReq { public List<String> apiKeys; }

    @PutMapping("/keys/{apiKey}/policy")
    public ResponseEntity<Models.KeyDetailResp> updatePolicy(@PathVariable String apiKey, @RequestBody UpdatePolicyReq req) {
        var r = new Models.KeyDetailResp();
        r.rpm = req.rpm; r.ipRpm = req.ipRpm; r.propsAllowlist = req.propsAllowlist; r.piiEmail = req.piiEmail; r.piiPhone = req.piiPhone; r.piiIp = req.piiIp; r.denyKeys = req.denyKeys; r.maskKeys = req.maskKeys;
        var out = svc.updatePolicy(apiKey, r);
        if (out == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(out);
    }

    @DeleteMapping("/keys/{apiKey}")
    public ResponseEntity<Map<String,Object>> deleteKey(@PathVariable String apiKey) {
        boolean ok = svc.deleteKey(apiKey);
        return ok ? ResponseEntity.ok(Map.of("deleted", true)) : ResponseEntity.notFound().build();
    }

    @PostMapping("/keys/batch-delete")
    public ResponseEntity<Map<String,Object>> deleteKeys(@RequestBody BatchDeleteReq req) {
        long n = svc.deleteKeys(req.apiKeys);
        return ResponseEntity.ok(Map.of("deleted", n));
    }

    @GetMapping("/storage-profiles")
    public List<Models.StorageProfileResp> listStorageProfiles() {
        return svc.listStorageProfiles();
    }

    @GetMapping("/storage-profiles/{profileId}")
    public ResponseEntity<Models.StorageProfileResp> getStorageProfile(@PathVariable String profileId) {
        var profile = svc.getStorageProfile(profileId);
        if (profile == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(profile);
    }

    @PostMapping("/storage-profiles")
    public Models.StorageProfileResp createStorageProfile(@RequestBody Models.CreateStorageProfileReq req) {
        return svc.createStorageProfile(req);
    }

    @PutMapping("/storage-profiles/{profileId}")
    public ResponseEntity<Models.StorageProfileResp> updateStorageProfile(@PathVariable String profileId,
                                                                          @RequestBody Models.CreateStorageProfileReq req) {
        try {
            return ResponseEntity.ok(svc.updateStorageProfile(profileId, req));
        } catch (IllegalArgumentException ex) {
            if (ex.getMessage() != null && ex.getMessage().startsWith("Storage profile not found")) {
                return ResponseEntity.notFound().build();
            }
            throw ex;
        }
    }
}
