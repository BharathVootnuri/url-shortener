package com.bharath.projects.urlshortener.util;

import com.bharath.projects.urlshortener.config.AppProperties;
import com.bharath.projects.urlshortener.exception.InvalidUrlException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Validates and normalizes user-supplied URLs (requirements D3).
 *
 * <p>Accepted: absolute http/https URLs, pure ASCII, no whitespace/control characters, no
 * credentials, valid host and port, within the configured maximum length.
 * Rejected unless {@code app.url-validation.allow-private-hosts=true} (local profile only):
 * localhost, single-label hosts, and loopback / private / link-local / reserved IP literals.
 *
 * <p>The server never fetches the URL, so this is about abuse prevention, not SSRF defence in depth.
 * Host names are never resolved (no DNS), so validation is fast and has no network side effects.
 *
 * <p>Normalization: trimmed; scheme and host lower-cased; path, query and fragment are kept exactly.
 */
@Component
public class UrlValidator {

    private final int maxLength;
    private final boolean allowPrivateHosts;

    @Autowired
    public UrlValidator(AppProperties props) {
        this(props.urlValidation().maxLength(), props.urlValidation().allowPrivateHosts());
    }

    UrlValidator(int maxLength, boolean allowPrivateHosts) {
        this.maxLength = maxLength;
        this.allowPrivateHosts = allowPrivateHosts;
    }

    /**
     * @return the normalized URL
     * @throws InvalidUrlException if the URL is not acceptable
     */
    public String validateAndNormalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw invalid("URL is required");
        }
        String url = raw.strip();
        if (url.length() > maxLength) {
            throw invalid("URL must be at most " + maxLength + " characters");
        }
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c <= 0x20 || c >= 0x7F) {
                throw invalid("URL must not contain spaces, control characters or non-ASCII characters; "
                        + "percent-encode them");
            }
        }

        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw invalid("URL is not well-formed");
        }

        String scheme = uri.getScheme();
        if (scheme == null) {
            throw invalid("URL must be absolute and start with http:// or https://");
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw invalid("Only http and https URLs are allowed");
        }
        if (uri.getRawUserInfo() != null) {
            throw invalid("URLs containing credentials are not allowed");
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            throw invalid("URL must contain a valid host name");
        }
        int port = uri.getPort();
        if (port != -1 && (port < 1 || port > 65535)) {
            throw invalid("URL port is out of range");
        }

        host = host.toLowerCase(Locale.ROOT);
        checkHost(host);

        StringBuilder normalized = new StringBuilder(url.length())
                .append(scheme).append("://").append(host);
        if (port != -1) {
            normalized.append(':').append(port);
        }
        if (uri.getRawPath() != null) {
            normalized.append(uri.getRawPath());
        }
        if (uri.getRawQuery() != null) {
            normalized.append('?').append(uri.getRawQuery());
        }
        if (uri.getRawFragment() != null) {
            normalized.append('#').append(uri.getRawFragment());
        }
        return normalized.toString();
    }

    private void checkHost(String host) {
        if (host.startsWith("[")) {
            checkIpv6(host);
            return;
        }
        String name = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
        String lastLabel = name.substring(name.lastIndexOf('.') + 1);

        // A host whose last label is numeric is an IPv4 address to browsers, including odd forms such
        // as 2130706433 or 0x7f.1 (both = 127.0.0.1). Only plain dotted-decimal is accepted.
        if (isNumericLabel(lastLabel)) {
            byte[] ipv4 = parseDottedDecimal(name);
            if (ipv4 == null) {
                throw invalid("IP addresses must be written in dotted-decimal form (e.g. 203.0.113.5)");
            }
            requirePublic(ipv4);
            return;
        }

        if (!allowPrivateHosts && (name.equals("localhost")
                || name.endsWith(".localhost")
                || name.endsWith(".local")
                || name.endsWith(".internal")
                || !name.contains("."))) {
            throw invalid("Local and internal hosts are not allowed");
        }
    }

    private void checkIpv6(String host) {
        String literal = host.substring(1, host.length() - 1);
        if (!literal.contains(":") || literal.contains("%")) {
            throw invalid("URL contains an invalid IPv6 address");
        }
        try {
            // Contains ':' so this is parsed as a literal; no DNS lookup happens.
            InetAddress address = InetAddress.getByName(literal);
            requirePublic(address.getAddress());
        } catch (UnknownHostException e) {
            throw invalid("URL contains an invalid IPv6 address");
        }
    }

    /** Rejects non-public addresses unless private hosts are allowed. Accepts 4 or 16 byte addresses. */
    private void requirePublic(byte[] address) {
        if (allowPrivateHosts) {
            return;
        }
        if (isNonPublic(address)) {
            throw invalid("Private, loopback and reserved IP addresses are not allowed");
        }
    }

    static boolean isNonPublic(byte[] a) {
        if (a.length == 4) {
            int b0 = a[0] & 0xFF;
            int b1 = a[1] & 0xFF;
            return b0 == 0                                 // 0.0.0.0/8 "this network"
                    || b0 == 10                            // 10.0.0.0/8 private
                    || b0 == 100 && (b1 & 0xC0) == 64      // 100.64.0.0/10 carrier-grade NAT
                    || b0 == 127                           // loopback
                    || b0 == 169 && b1 == 254              // link-local
                    || b0 == 172 && (b1 & 0xF0) == 16      // 172.16.0.0/12 private
                    || b0 == 192 && b1 == 168              // 192.168.0.0/16 private
                    || b0 == 198 && (b1 & 0xFE) == 18      // 198.18.0.0/15 benchmarking
                    || b0 >= 224;                          // multicast, reserved, broadcast
        }
        if (a.length == 16) {
            boolean unspecified = true;
            for (int i = 0; i < 15; i++) {
                if (a[i] != 0) {
                    unspecified = false;
                    break;
                }
            }
            int last = a[15] & 0xFF;
            int first = a[0] & 0xFF;
            return (unspecified && last <= 1)              // :: and ::1
                    || (first & 0xFE) == 0xFC              // fc00::/7 unique local
                    || first == 0xFE && (a[1] & 0xC0) == 0x80  // fe80::/10 link-local
                    || first == 0xFF;                      // multicast
        }
        return true;
    }

    private static boolean isNumericLabel(String label) {
        if (label.isEmpty()) {
            return false;
        }
        if (label.length() > 1 && label.charAt(0) == '0' && (label.charAt(1) == 'x' || label.charAt(1) == 'X')) {
            return label.substring(2).chars().allMatch(c -> Character.digit(c, 16) >= 0);
        }
        return label.chars().allMatch(c -> c >= '0' && c <= '9');
    }

    /** Parses strict a.b.c.d (decimal, 0-255, no leading zeros). Returns null for anything else. */
    private static byte[] parseDottedDecimal(String s) {
        String[] parts = s.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        byte[] out = new byte[4];
        for (int i = 0; i < 4; i++) {
            String p = parts[i];
            if (p.isEmpty() || p.length() > 3 || (p.length() > 1 && p.charAt(0) == '0')) {
                return null;
            }
            int value = 0;
            for (int j = 0; j < p.length(); j++) {
                char c = p.charAt(j);
                if (c < '0' || c > '9') {
                    return null;
                }
                value = value * 10 + (c - '0');
            }
            if (value > 255) {
                return null;
            }
            out[i] = (byte) value;
        }
        return out;
    }

    private static InvalidUrlException invalid(String message) {
        return new InvalidUrlException(message);
    }
}
