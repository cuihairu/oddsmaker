package io.oddsmaker.control.api;

import io.oddsmaker.control.jpa.IdentityEntity;
import io.oddsmaker.control.jpa.IdentityLinkEntity;
import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.IdentityService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 身份查询 API。
 * 提供按 device/player/user/identifier 反查统一身份，以及查身份的关联（identity_links）。
 * 写入由 Flink IdentityMergeJob → Kafka → IdentityConsumer 自动完成，本接口只读。
 * 鉴权走 AccessGuard 行内风格（game:read，全部 game 级）。历史形态为
 * @PreAuthorize hasAuthority('READ_GAME:'+gameId) 拼接式，而全仓只签发 ROLE_* authority，
 * 方法安全开启后这些注解恒 403——故换成权限种子（V0.9.8）+ AccessGuard 解析。
 */
@RestController
@RequestMapping("/api/identities")
public class IdentityController {

    @Autowired
    private IdentityService identityService;

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
}
