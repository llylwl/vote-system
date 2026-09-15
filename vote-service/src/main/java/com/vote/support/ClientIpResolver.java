package com.vote.support;

import com.vote.common.config.SecurityProperties;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 客户端真实 IP 解析器
 * <p>
 * <b>为什么不能直接读 X-Forwarded-For：</b>
 * 该请求头由客户端完全控制。无脑采信它的第一个值，意味着攻击者只要伪造
 * {@code X-Forwarded-For: <随机IP>}，就能同时绕过 IP 限流和 IP 黑名单 ——
 * 对防刷系统而言这是致命缺陷（真实 IP 永远不会进入黑名单）。
 * <p>
 * 正确做法是维护一份<b>可信代理白名单</b>：从 {@code X-Forwarded-For} 的<b>最右侧</b>
 * 开始向左跳过所有可信代理，第一个不可信地址才是客户端真实 IP。若请求压根不来自
 * 可信代理，则完全不采信该头，直接使用 TCP 对端地址。
 * <p>
 * <b>注意：</b>部署在 Nginx/SLB 之后时，必须把代理地址配置进
 * {@code app.security.trusted-proxies}，否则所有请求会被识别为同一个代理 IP，
 * 导致限流误把全站用户算作一个人（20 次/秒变成全站总配额）。
 *
 * @author hzp
 * @since 2026-9-15
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClientIpResolver {

    private static final String HEADER_XFF = "X-Forwarded-For";
    private static final String HEADER_X_REAL_IP = "X-Real-IP";
    private static final String UNKNOWN = "unknown";

    private final SecurityProperties securityProperties;

    /**
     * 解析客户端真实 IP
     *
     * @param request 当前请求
     * @return 客户端 IP；无法判定时返回 TCP 对端地址
     */
    public String resolve(HttpServletRequest request) {
        if (request == null) {
            return UNKNOWN;
        }
        String remoteAddr = request.getRemoteAddr();

        // 请求不是来自可信代理：完全不采信任何转发头，避免被伪造
        if (!isTrustedProxy(remoteAddr)) {
            return remoteAddr;
        }

        // 来自可信代理：从 XFF 最右侧向左找第一个不可信地址
        String xff = request.getHeader(HEADER_XFF);
        if (xff != null && !xff.isBlank()) {
            String[] chain = xff.split(",");
            for (int i = chain.length - 1; i >= 0; i--) {
                String candidate = chain[i].trim();
                if (candidate.isEmpty() || UNKNOWN.equalsIgnoreCase(candidate)) {
                    continue;
                }
                if (!isTrustedProxy(candidate)) {
                    return candidate;
                }
            }
            // 整条链都是可信代理：取最左侧作为原始客户端
            String leftmost = chain[0].trim();
            if (!leftmost.isEmpty() && !UNKNOWN.equalsIgnoreCase(leftmost)) {
                return leftmost;
            }
        }

        String realIp = request.getHeader(HEADER_X_REAL_IP);
        if (realIp != null && !realIp.isBlank() && !UNKNOWN.equalsIgnoreCase(realIp)) {
            return realIp.trim();
        }
        return remoteAddr;
    }

    /** 判断地址是否属于配置的可信代理 */
    public boolean isTrustedProxy(String ip) {
        List<String> trusted = securityProperties.getTrustedProxies();
        if (trusted == null || trusted.isEmpty() || ip == null || ip.isEmpty()) {
            return false;
        }
        for (String pattern : trusted) {
            if (pattern == null || pattern.isBlank()) {
                continue;
            }
            String p = pattern.trim();
            if (p.contains("/")) {
                if (matchesIpv4Cidr(ip, p)) {
                    return true;
                }
            } else if (p.equalsIgnoreCase(ip)) {
                return true;
            }
        }
        return false;
    }

    /** 判断请求是否经由可信代理转发，供调用方决定是否可以采信其它转发头 */
    public boolean isFromTrustedProxy(HttpServletRequest request) {
        if (request == null) {
            return false;
        }
        return isTrustedProxy(request.getRemoteAddr());
    }

    /**
     * IPv4 CIDR 匹配
     * <p>
     * 仅支持 IPv4 网段；IPv6 请使用完整地址精确匹配（当前部署形态下足够，
     * 若将来需要 IPv6 网段可引入成熟库如 {@code commons-net} 的 SubnetUtils）。
     */
    private boolean matchesIpv4Cidr(String ip, String cidr) {
        int slash = cidr.indexOf('/');
        String network = cidr.substring(0, slash);
        int prefixLen;
        try {
            prefixLen = Integer.parseInt(cidr.substring(slash + 1));
        } catch (NumberFormatException e) {
            log.warn("可信代理配置中的 CIDR 前缀长度非法，已忽略: {}", cidr);
            return false;
        }
        if (prefixLen < 0 || prefixLen > 32) {
            log.warn("可信代理配置中的 CIDR 前缀长度越界，已忽略: {}", cidr);
            return false;
        }

        Long ipValue = ipv4ToLong(ip);
        Long networkValue = ipv4ToLong(network);
        if (ipValue == null || networkValue == null) {
            // 含 IPv6 或非法格式，无法按 IPv4 网段匹配
            return false;
        }
        long mask = prefixLen == 0 ? 0L : (0xFFFFFFFFL << (32 - prefixLen)) & 0xFFFFFFFFL;
        return (ipValue & mask) == (networkValue & mask);
    }

    /** IPv4 点分十进制转 long；非 IPv4 返回 null */
    private Long ipv4ToLong(String ip) {
        if (ip == null || ip.isEmpty()) {
            return null;
        }
        String[] parts = ip.split("\\.");
        if (parts.length != 4) {
            return null;
        }
        long result = 0L;
        for (String part : parts) {
            int octet;
            try {
                octet = Integer.parseInt(part);
            } catch (NumberFormatException e) {
                return null;
            }
            if (octet < 0 || octet > 255) {
                return null;
            }
            result = (result << 8) | octet;
        }
        return result;
    }

    /** 启动时提示一次可信代理配置情况，避免部署到反代之后忘记配置 */
    @PostConstruct
    void logTrustedProxyConfig() {
        List<String> trusted = securityProperties.getTrustedProxies();
        if (trusted == null || trusted.isEmpty()) {
            log.info("未配置 app.security.trusted-proxies：不采信任何 X-Forwarded-For，"
                    + "统一使用 TCP 对端地址。若部署在 Nginx/SLB 之后，请务必配置可信代理地址，"
                    + "否则所有请求会被识别为同一个 IP，限流会误伤全站用户。");
        } else {
            log.info("可信反向代理已配置: {}", trusted);
        }
    }
}
