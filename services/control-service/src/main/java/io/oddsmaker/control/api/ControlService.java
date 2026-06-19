package io.oddsmaker.control.api;

import io.oddsmaker.control.jpa.*;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Control Service - 单公司多游戏模型
 * ApiKey 绑定到 Game + Environment。
 */
@Service
public class ControlService {
    private final ApiKeyRepo keyRepo;
    private final GameRepo gameRepo;
    private final GameEnvironmentRepo envRepo;

    public ControlService(ApiKeyRepo keyRepo,
                          GameRepo gameRepo,
                          GameEnvironmentRepo envRepo) {
        this.keyRepo = keyRepo;
        this.gameRepo = gameRepo;
        this.envRepo = envRepo;
    }

    public Models.ApiKeyResp createKey(String gameId, String environmentId, String name) {
        GameEntity game = gameRepo.findById(gameId)
            .filter(entity -> entity.deletedAt == null)
            .orElseThrow(() -> new IllegalArgumentException("Game not found: " + gameId));

        GameEnvironmentEntity environment = envRepo.findById(environmentId)
            .filter(entity -> entity.deletedAt == null)
            .orElseThrow(() -> new IllegalArgumentException("Environment not found: " + environmentId));

        if (!Objects.equals(environment.gameId, game.id)) {
            throw new IllegalArgumentException("Environment does not belong to game: " + environmentId);
        }

        ApiKeyEntity e = new ApiKeyEntity();
        e.apiKey = gen("pk_"); e.secret = gen("sk_");
        e.gameId = gameId;
        e.environmentId = environmentId;
        e.name = name; e.rpm = 600; e.ipRpm = 300;
        keyRepo.save(e);
        return toResp(e);
    }

    public Models.KeyDetailResp getKey(String apiKey) {
        return keyRepo.findById(apiKey).map(this::toDetail).orElse(null);
    }

    public List<Models.KeyDetailResp> listKeys() {
        return keyRepo.findAll().stream().map(this::toDetail).collect(Collectors.toList());
    }

    public Paged<Models.KeyDetailResp> searchKeys(String gameId, String environmentId, String q, int page, int size) {
        var pageable = org.springframework.data.domain.PageRequest.of(page, size,
                org.springframework.data.domain.Sort.by("apiKey").ascending());
        var pg = keyRepo.searchApiKeysByScope(
                gameId == null ? "" : gameId,
                environmentId == null ? "" : environmentId,
                q == null ? "" : q,
                pageable
        );
        var items = pg.getContent().stream().map(this::toDetail).collect(Collectors.toList());
        return new Paged<>(items, pg.getTotalElements());
    }

    public boolean deleteKey(String apiKey) {
        if (!keyRepo.existsById(apiKey)) return false;
        keyRepo.deleteById(apiKey);
        return true;
    }

    public long deleteKeys(java.util.List<String> apiKeys) {
        if (apiKeys == null || apiKeys.isEmpty()) return 0L;
        keyRepo.deleteAllById(apiKeys);
        return apiKeys.size();
    }

    public static class Paged<T> {
        public java.util.List<T> items; public long total;
        public Paged(java.util.List<T> items, long total){ this.items=items; this.total=total; }
    }

    public Models.KeyDetailResp updatePolicy(String apiKey, Models.KeyDetailResp req) {
        return keyRepo.findById(apiKey).map(e -> {
            if (req.rpm != null) e.rpm = req.rpm;
            if (req.ipRpm != null) e.ipRpm = req.ipRpm;
            if (req.propsAllowlist != null) e.propsAllowlist = String.join(",", req.propsAllowlist);
            if (req.piiEmail != null) e.piiEmail = req.piiEmail;
            if (req.piiPhone != null) e.piiPhone = req.piiPhone;
            if (req.piiIp != null) e.piiIp = req.piiIp;
            if (req.denyKeys != null) e.denyKeys = String.join(",", req.denyKeys);
            if (req.maskKeys != null) e.maskKeys = String.join(",", req.maskKeys);
            keyRepo.save(e);
            return toDetail(e);
        }).orElse(null);
    }

    private Models.ApiKeyResp toResp(ApiKeyEntity e) {
        Models.ApiKeyResp out = new Models.ApiKeyResp();
        out.apiKey = e.apiKey; out.secret = e.secret;
        out.gameId = e.gameId; out.environmentId = e.environmentId;
        out.name = e.name;
        return out;
    }

    private Models.KeyDetailResp toDetail(ApiKeyEntity e) {
        Models.KeyDetailResp r = new Models.KeyDetailResp();
        r.apiKey = e.apiKey; r.secret = e.secret;
        r.gameId = e.gameId; r.environmentId = e.environmentId;
        r.rpm = e.rpm; r.ipRpm = e.ipRpm;
        r.propsAllowlist = split(e.propsAllowlist);
        r.piiEmail = e.piiEmail; r.piiPhone = e.piiPhone; r.piiIp = e.piiIp;
        r.denyKeys = split(e.denyKeys); r.maskKeys = split(e.maskKeys);
        return r;
    }

    private static List<String> split(String s) {
        if (s == null || s.isBlank()) return null;
        String[] parts = s.split(",");
        List<String> out = new ArrayList<>();
        for (String p : parts) { String t = p.trim(); if (!t.isEmpty()) out.add(t); }
        return out;
    }

    private static String gen(String prefix) {
        byte[] b = new byte[12]; new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder(prefix);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
