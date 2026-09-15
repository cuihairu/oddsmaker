package io.oddsmaker.control.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Keycloak JWT认证转换器
 * 从Keycloak JWT令牌中提取用户信息和角色
 */
public class KeycloakJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final String REALM_ACCESS_CLAIM = "realm_access";
    private static final String RESOURCE_ACCESS_CLAIM = "resource_access";
    private static final String ROLES_CLAIM = "roles";
    private static final String PREFERRED_USERNAME_CLAIM = "preferred_username";
    private static final String EMAIL_CLAIM = "email";
    private static final String NAME_CLAIM = "name";

    private final JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Collection<GrantedAuthority> authorities = Stream.concat(
            authoritiesConverter.convert(jwt).stream(),
            extractKeycloakAuthorities(jwt).stream()
        ).collect(Collectors.toSet());

        String principal = extractPrincipal(jwt);
        
        return new JwtAuthenticationToken(jwt, authorities, principal);
    }

    /**
     * 从JWT令牌中提取Keycloak角色
     */
    private Collection<GrantedAuthority> extractKeycloakAuthorities(Jwt jwt) {
        Collection<GrantedAuthority> authorities = new ArrayList<>();

        // 本地登录签发的自签 JWT：顶层 roles 数组（JwtService.issue 的约定）
        List<String> localRoles = jwt.getClaimAsStringList(ROLES_CLAIM);
        if (localRoles != null) {
            localRoles.stream()
                .filter(role -> role != null && !role.isBlank())
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()))
                .forEach(authorities::add);
        }

        // 从realm_access中提取角色
        Map<String, Object> realmAccess = jwt.getClaimAsMap(REALM_ACCESS_CLAIM);
        if (realmAccess != null && realmAccess.containsKey(ROLES_CLAIM)) {
            @SuppressWarnings("unchecked")
            List<String> realmRoles = (List<String>) realmAccess.get(ROLES_CLAIM);
            realmRoles.stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()))
                .forEach(authorities::add);
        }

        // 从resource_access中提取客户端角色
        Map<String, Object> resourceAccess = jwt.getClaimAsMap(RESOURCE_ACCESS_CLAIM);
        if (resourceAccess != null) {
            resourceAccess.forEach((clientId, clientRolesObj) -> {
                if (clientRolesObj instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> clientRoles = (Map<String, Object>) clientRolesObj;
                    if (clientRoles.containsKey(ROLES_CLAIM)) {
                        @SuppressWarnings("unchecked")
                        List<String> roles = (List<String>) clientRoles.get(ROLES_CLAIM);
                        roles.stream()
                            .map(role -> new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()))
                            .forEach(authorities::add);
                    }
                }
            });
        }

        // 如果没有找到角色，默认添加USER角色
        if (authorities.isEmpty()) {
            authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
        }

        return authorities;
    }

    /**
     * 从JWT令牌中提取主体（用户名）
     */
    private String extractPrincipal(Jwt jwt) {
        // 优先使用preferred_username
        String username = jwt.getClaimAsString(PREFERRED_USERNAME_CLAIM);
        if (username != null && !username.isEmpty()) {
            return username;
        }

        // 其次使用email
        String email = jwt.getClaimAsString(EMAIL_CLAIM);
        if (email != null && !email.isEmpty()) {
            return email;
        }

        // 最后使用name
        String name = jwt.getClaimAsString(NAME_CLAIM);
        if (name != null && !name.isEmpty()) {
            return name;
        }

        // 如果都没有，使用subject
        return jwt.getSubject();
    }
}