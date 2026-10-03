package io.oddsmaker.control.jpa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UserEntity 的对外 JSON 契约（POST /api/users 与 /api/auth/login 直接收发实体，
 * 无 DTO 隔离层——注解层面的疏漏会直接变成线上行为）：
 *
 * - passwordHash 只写不读：deploy/demo README §5 建号 curl 按契约送已 bcrypt 的口令，
 *   登录端点 passwordEncoder.matches 依赖它落库。原 @JsonIgnore 把反序列化一并禁掉，
 *   口令被静默丢弃、建出来的号登不进（200 但账号不可用）；回吐侧仍不得序列化。
 * - Persistable.isNew 是 JPA 新建态标记（save 走 persist 还是 merge 的分叉依据），
 *   不属于 API 契约，不得外泄到响应体。
 * - global_role 迁移列 NOT NULL DEFAULT 'USER'：Hibernate 会显式绑 NULL 撞约束
 *   （列 DEFAULT 只在省略列时生效），实体必须自带同口径默认值。
 */
@DisplayName("UserEntity JSON 契约：passwordHash 只写不读、isNew 不外泄、globalRole 默认 USER")
class UserEntityContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @Test
    @DisplayName("§5 建号请求体的 passwordHash 能反序列化进实体")
    void passwordHashDeserializes() throws Exception {
        String body = "{\"username\":\"demo\",\"email\":\"demo@oddsmaker.local\","
            + "\"displayName\":\"演示账号\",\"passwordHash\":\"$2a$10$hash\","
            + "\"roles\":[\"VIEWER\"],\"status\":\"ACTIVE\"}";

        UserEntity user = MAPPER.readValue(body, UserEntity.class);

        assertEquals("$2a$10$hash", user.passwordHash);
        assertEquals("demo", user.username);
        assertEquals("demo@oddsmaker.local", user.email);
        assertTrue(user.roles.contains(UserEntity.UserRole.VIEWER));
    }

    @Test
    @DisplayName("序列化不回吐 passwordHash，也不外泄 isNew/new")
    void secretsAndJpaFlagsNotSerialized() throws Exception {
        UserEntity user = new UserEntity();
        user.id = "user_x";
        user.username = "demo";
        user.email = "demo@oddsmaker.local";
        user.passwordHash = "$2a$10$hash";

        JsonNode json = MAPPER.readTree(MAPPER.writeValueAsString(user));

        assertFalse(json.has("passwordHash"), "口令哈希不得出现在响应体");
        assertFalse(json.has("isNew"), "Persistable 新建态标记不得外泄");
        assertFalse(json.has("new"), "isNew() getter 派生名同样不得外泄");
        assertEquals("demo", json.path("username").asText());
    }

    @Test
    @DisplayName("Persistable：新建实例 isNew=true，@PostLoad/@PostPersist 后翻 false 走 update")
    void persistableNewFlagTransitions() {
        UserEntity user = new UserEntity();
        assertTrue(user.isNew(), "新建实例必须走 persist（merge 携带非空 roles 集合会引发幽灵 UPDATE）");
        user.markNotNew();
        assertFalse(user.isNew());
    }

    @Test
    @DisplayName("globalRole 字段初始化器默认 USER（与迁移 DEFAULT 同口径）")
    void globalRoleDefaultsToUser() {
        assertEquals(UserEntity.GlobalRole.USER, new UserEntity().globalRole);
    }
}
