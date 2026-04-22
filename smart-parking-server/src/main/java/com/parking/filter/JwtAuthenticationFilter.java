package com.parking.filter;

import com.parking.repository.InMemoryIdentityStore;
import com.parking.security.JwtTokenProvider;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final InMemoryIdentityStore identityStore;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider, InMemoryIdentityStore identityStore) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.identityStore = identityStore;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                Claims claims = jwtTokenProvider.parseClaims(token);
                String username = claims.getSubject();
                List<String> permissions = resolvePermissions(username, claims);
                List<SimpleGrantedAuthority> authorities = permissions == null
                        ? Collections.emptyList()
                        : permissions.stream().map(SimpleGrantedAuthority::new).collect(Collectors.toList());
                UsernamePasswordAuthenticationToken authenticationToken =
                        new UsernamePasswordAuthenticationToken(username, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authenticationToken);
            } catch (JwtException ignored) {
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }

    private List<String> resolvePermissions(String username, Claims claims) {
        try {
            InMemoryIdentityStore.UserAccount user = identityStore.getByUsername(username);
            if (user != null) {
                return identityStore.getPermissions(user.roleCode());
            }
        } catch (Exception ignored) {
            // If identity tables are not ready, fallback to token permissions.
        }

        List<?> tokenPerms = claims.get("perms", List.class);
        if (tokenPerms == null) {
            return List.of();
        }
        return tokenPerms.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
    }
}
