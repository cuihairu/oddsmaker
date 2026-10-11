package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.jpa.DimensionAgentConfigEntity;
import io.oddsmaker.control.jpa.DimensionAgentConfigRepo;
import io.oddsmaker.control.jpa.GameEnvironmentRepo;
import io.oddsmaker.control.jpa.GameRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Sync Agent 属性面托管服务（dimension-sync.md「Agent 侧 agent.properties 的 Control 下发迁移」
 * Control 侧半边）。props 非密钥文本可见可改；secrets 整体 AES-GCM 密文托管，管理面响应只回显
 * 键名清单；{@link #resolve} 是 Agent 取配置口（同 dimension:read 门控），注入 game.id /
 * game.environment / status.source-key 并解密合并为一份 Properties 文本，Agent 端 load 即用。
 */
@Service
public class DimensionAgentConfigService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DimensionAgentConfigRepo repo;
    private final GameRepo gameRepo;
    private final GameEnvironmentRepo envRepo;
    private final DimensionCredentialCipher cipher;

    public DimensionAgentConfigService(DimensionAgentConfigRepo repo,
                                       GameRepo gameRepo,
                                       GameEnvironmentRepo envRepo,
                                       DimensionCredentialCipher cipher) {
        this.repo = repo;
        this.gameRepo = gameRepo;
        this.envRepo = envRepo;
        this.cipher = cipher;
    }

    // ===== 请求/响应体 =====

    /** 配置请求体：environment/agentKey/props 必填；secrets 建档必填（更新缺省=保留既有密钥）。 */
    public static final class AgentConfigUpsert {
        public String environment;
        public String agentKey;
        public String props;
        public Map<String, String> secrets;
        public Boolean enabled;
    }

    /** 配置响应：密钥只回显键名（secretsEncrypted 密文永不回显）。 */
    public Map<String, Object> toView(DimensionAgentConfigEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id);
        m.put("gameId", e.gameId);
        m.put("environment", e.environment);
        m.put("agentKey", e.agentKey);
        m.put("enabled", e.enabled);
        m.put("props", e.props);
        m.put("secretKeys", secretKeyNames(e.secretsEncrypted));
        m.put("createdAt", e.createdAt);
        m.put("updatedAt", e.updatedAt);
        return m;
    }

    // ===== CRUD =====

    @Transactional
    public Map<String, Object> create(String gameId, AgentConfigUpsert req) {
        requireGame(gameId);
        if (req.environment == null || req.environment.isBlank()) {
            throw new IllegalArgumentException("environment 不能为空");
        }
        if (req.agentKey == null || req.agentKey.isBlank()) {
            throw new IllegalArgumentException("agentKey 不能为空");
        }
        if (req.props == null || req.props.isBlank()) {
            throw new IllegalArgumentException("props 不能为空");
        }
        DimensionAgentConfigEntity e = new DimensionAgentConfigEntity();
        e.id = "dac_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        e.gameId = gameId;
        e.environment = requireEnvironment(gameId, req.environment);
        e.agentKey = req.agentKey.trim();
        requireAgentKeyUnique(e);
        TreeMap<String, String> props = AgentConfigSpec.parseProps(req.props);
        TreeMap<String, String> secrets = req.secrets == null ? new TreeMap<>() : new TreeMap<>(req.secrets);
        AgentConfigSpec.validate(props, secrets);
        e.props = renderProps(props);
        e.secretsEncrypted = encryptSecrets(secrets);
        e.enabled = req.enabled == null ? Boolean.TRUE : req.enabled;
        e.createdAt = LocalDateTime.now();
        e.updatedAt = e.createdAt;
        return toView(repo.save(e));
    }

    @Transactional
    public Map<String, Object> update(String gameId, String id, AgentConfigUpsert req) {
        DimensionAgentConfigEntity e = findOwned(gameId, id);
        String oldEnv = e.environment;
        String oldKey = e.agentKey;
        if (req.environment != null && !req.environment.isBlank()) {
            e.environment = requireEnvironment(gameId, req.environment);
        }
        if (req.agentKey != null && !req.agentKey.isBlank()) {
            e.agentKey = req.agentKey.trim();
        }
        if (!e.environment.equals(oldEnv) || !e.agentKey.equals(oldKey)) {
            requireAgentKeyUnique(e);
        }
        TreeMap<String, String> props = null;
        if (req.props != null && !req.props.isBlank()) {
            // props 全量替换；密钥侧不动，只校验 props 侧键集/必填/形状
            props = AgentConfigSpec.parseProps(req.props);
            AgentConfigSpec.validateProps(props);
            e.props = renderProps(props);
        }
        if (req.secrets != null) {
            // secrets 缺省（null）= 保留既有密钥；显式给 map = 整体轮换（validate 全量口径，
            // 缺 gateway.api-key 即拒——安全要求与建档一致）
            TreeMap<String, String> propsSide = props != null ? props : AgentConfigSpec.parseProps(e.props);
            TreeMap<String, String> secrets = new TreeMap<>(req.secrets);
            AgentConfigSpec.validate(propsSide, secrets);
            e.secretsEncrypted = encryptSecrets(secrets);
        }
        if (req.enabled != null) {
            e.enabled = req.enabled;
        }
        e.updatedAt = LocalDateTime.now();
        return toView(repo.save(e));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String gameId) {
        requireGame(gameId);
        return repo.findByGameId(gameId).stream()
                .map(this::toView)
                .collect(Collectors.toList());
    }

    @Transactional
    public void delete(String gameId, String id) {
        repo.delete(findOwned(gameId, id));
    }

    // ===== 下发 =====

    /**
     * Agent 取配置：返回可直接 Properties.load 的完整文本（注入行 + props 行 + 解密后的
     * secrets 行）。停用档拒绝下发（Agent 端任何非 200 即走本地文件兜底）。
     */
    @Transactional(readOnly = true)
    public String resolve(String gameId, String environment, String agentKey) {
        DimensionAgentConfigEntity e = repo.findByGameIdAndEnvironmentAndAgentKey(gameId, environment, agentKey)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Agent config not found: " + gameId + "/" + environment + "/" + agentKey));
        if (!Boolean.TRUE.equals(e.enabled)) {
            throw new IllegalArgumentException("Agent config 已停用: " + e.agentKey);
        }
        TreeMap<String, String> props = AgentConfigSpec.parseProps(e.props);
        TreeMap<String, String> secrets = decryptSecrets(e.secretsEncrypted);
        return AgentConfigSpec.renderResolved(e.gameId, e.environment, e.agentKey, props, secrets);
    }

    // ===== 内部 =====

    /** 软删游戏视同不存在（与 DimensionPullService.requireGame 同口径）。 */
    private void requireGame(String gameId) {
        gameRepo.findById(gameId)
                .filter(game -> game.deletedAt == null)
                .orElseThrow(() -> new IllegalArgumentException("Game not found: " + gameId));
    }

    /** 环境名必须属于该游戏（防跨游戏环境串写）。 */
    private String requireEnvironment(String gameId, String environment) {
        String name = environment.trim();
        envRepo.findByGameIdAndNameAndDeletedAtIsNull(gameId, name).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Environment not found: " + name));
        return name;
    }

    private void requireAgentKeyUnique(DimensionAgentConfigEntity e) {
        repo.findByGameIdAndEnvironmentAndAgentKey(e.gameId, e.environment, e.agentKey)
                .ifPresent(other -> {
                    if (!other.id.equals(e.id)) {
                        throw new IllegalArgumentException("同游戏同环境已存在同名 agentKey: " + e.agentKey);
                    }
                });
    }

    private DimensionAgentConfigEntity findOwned(String gameId, String id) {
        DimensionAgentConfigEntity e = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Agent config not found: " + id));
        if (!gameId.equals(e.gameId)) {
            throw new IllegalArgumentException("Agent config not found: " + id);
        }
        return e;
    }

    /** props 规范化存储：TreeMap 序 key=value 行（renderValue 保证 load 往返）。 */
    private static String renderProps(TreeMap<String, String> props) {
        return props.entrySet().stream()
                .map(en -> en.getKey() + "=" + AgentConfigSpec.renderValue(en.getValue()))
                .collect(Collectors.joining("\n", "", "\n"));
    }

    private String encryptSecrets(TreeMap<String, String> secrets) {
        try {
            return cipher.encrypt(MAPPER.writeValueAsString(secrets));
        } catch (Exception e) {
            throw new IllegalArgumentException("secrets 序列化失败: " + e.getMessage());
        }
    }

    private TreeMap<String, String> decryptSecrets(String stored) {
        try {
            return MAPPER.readValue(cipher.decrypt(stored),
                    MAPPER.getTypeFactory().constructMapType(TreeMap.class, String.class, String.class));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("secrets 密文损坏: " + e.getMessage());
        }
    }

    /** 密文解开后只取键名清单（管理面可见性边界：值永不出密文）。 */
    private List<String> secretKeyNames(String stored) {
        try {
            Map<?, ?> raw = MAPPER.readValue(cipher.decrypt(stored), Map.class);
            return raw.keySet().stream().map(Object::toString).sorted().collect(Collectors.toList());
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("secrets 密文损坏: " + e.getMessage());
        }
    }
}
