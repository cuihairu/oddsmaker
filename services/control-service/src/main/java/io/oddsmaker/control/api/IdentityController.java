package io.oddsmaker.control.api;

import io.oddsmaker.control.jpa.IdentityEntity;
import io.oddsmaker.control.jpa.IdentityLinkEntity;
import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.IdentityMergeService;
import io.oddsmaker.control.service.IdentityService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 身份查询与显式合并 API。
 * 提供按 device/player/user/identifier 反查统一身份，以及查身份的关联（identity_links）；
 * POST /{gameId}/merge 为操作员显式合并（secondary 并入 primary，privacy:manage 门禁）——
 * 自动写入链路仍由 Flink IdentityMergeJob → Kafka → IdentityConsumer 完成，人工合并补齐
 * 自动链路之外的决定通道（Consumer 对合并墓碑跳过重建，人工决定不被重放复活）。
 * 鉴权走 AccessGuard 行内风格（game:read / privacy:manage，全部 game 级）。历史形态为
 * @PreAuthorize hasAuthority('READ_GAME:'+gameId) 拼接式，而全仓只签发 ROLE_* authority，
 * 方法安全开启后这些注解恒 403——故换成权限种子（V0.9.8）+ AccessGuard 解析。
 */
@RestController
@RequestMapping("/api/identities")
public class IdentityController {

    @Autowired
    private IdentityService identityService;

    @Autowired
    private IdentityMergeService identityMergeService;

    @Autowired
    private AccessGuard accessGuard;

    @GetMapping("/{gameId}/{identityId}")
    public ResponseEntity<IdentityEntity> getIdentity(@PathVariable String gameId, @PathVariable String identityId) {
        accessGuard.requireGamePermission(gameId, "game:read");
        return ResponseEntity.of(identityService.getIdentity(gameId, identityId));
    }

    @GetMapping("/{gameId}/by-device")
    public ResponseEntity<IdentityEntity> findByDevice(@PathVariable String gameId, @RequestParam String deviceId) {
        accessGuard.requireGamePermission(gameId, "game:read");
        return ResponseEntity.of(identityService.findByDevice(gameId, deviceId));
    }

    @GetMapping("/{gameId}/by-player")
    public ResponseEntity<IdentityEntity> findByPlayer(@PathVariable String gameId, @RequestParam String playerId) {
        accessGuard.requireGamePermission(gameId, "game:read");
        return ResponseEntity.of(identityService.findByPlayer(gameId, playerId));
    }

    @GetMapping("/{gameId}/by-user")
    public List<IdentityEntity> findByUser(@PathVariable String gameId, @RequestParam String userId) {
        accessGuard.requireGamePermission(gameId, "game:read");
        return identityService.findByUser(gameId, userId);
    }

    @GetMapping("/{gameId}/by-identifier")
    public List<IdentityEntity> findByIdentifier(
            @PathVariable String gameId,
            @RequestParam String type,
            @RequestParam String value) {
        accessGuard.requireGamePermission(gameId, "game:read");
        return identityService.findByIdentifier(gameId, type, value);
    }

    @GetMapping("/{identityId}/links")
    public List<IdentityLinkEntity> getLinks(@PathVariable String identityId, @RequestParam String gameId) {
        accessGuard.requireGamePermission(gameId, "game:read");
        return identityService.getLinks(identityId);
    }

    /**
     * 显式合并：secondary 并入 primary（幸存）。privacy:manage 门禁——身份图谱写操作
     * 与 GDPR 擦除（PlayerErasureController）同权限域。合并摘要含重挂/吊销 link 计数。
     */
    @PostMapping("/{gameId}/merge")
    public ResponseEntity<Map<String, Object>> merge(@PathVariable String gameId,
                                                     @RequestBody MergeReq request) {
        accessGuard.requireGamePermission(gameId, "privacy:manage");
        String by = request.by != null && !request.by.isBlank() ? request.by : currentOperator();
        return ResponseEntity.ok(identityMergeService.merge(
                gameId, request.primaryId, request.secondaryId, request.reason, by));
    }

    public static class MergeReq {
        public String primaryId;    // 幸存身份
        public String secondaryId;  // 被并入身份（墓碑化）
        public String reason;       // 合并原因（审计）
        public String by;           // 操作人（缺省取当前登录用户）
    }

    private static String currentOperator() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        return auth != null ? auth.getName() : null;
    }
}
