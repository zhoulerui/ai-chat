package com.example.aichat.util;

import java.io.IOException;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * 网页抓取工具(URL 一键入库):
 *  - SSRF 防护:仅 http/https,拒绝内网/回环/链路本地地址;
 *  - 10s 超时、正文上限 5MB;
 *  - 提取标题(og:title / &lt;title&gt; / host)与正文(优先 article/main,按段落拼接)。
 */
public final class UrlImporter {

    private static final int TIMEOUT_MS = 10_000;
    private static final int MAX_BODY = 5 * 1024 * 1024;

    private UrlImporter() {
    }

    /** 抓取结果 */
    public record Result(String title, String text) {
    }

    public static Result importUrl(String url) throws IOException {
        validate(url);
        Document doc = Jsoup.connect(url)
                .timeout(TIMEOUT_MS)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/126 Safari/537.36")
                .followRedirects(true)
                .maxBodySize(MAX_BODY)
                .get();
        String title = extractTitle(doc, url);
        String text = extractText(doc);
        if (text.isBlank()) {
            throw new IllegalArgumentException("未能从该网页提取到正文(可能是动态渲染页面)");
        }
        return new Result(title, text);
    }

    // ---------- SSRF 防护 ----------

    static void validate(String url) {
        URL u;
        try {
            u = new URL(url.trim());
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("无效的网址,请检查格式(需带 http:// 或 https://)");
        }
        String proto = u.getProtocol().toLowerCase();
        if (!"http".equals(proto) && !"https".equals(proto)) {
            throw new IllegalArgumentException("仅支持 http / https 网址");
        }
        String host = u.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("无效的网址");
        }
        if (isPrivateAddress(host)) {
            throw new IllegalArgumentException("禁止抓取内网 / 局域网地址,仅支持公网网页");
        }
    }

    static boolean isPrivateAddress(String host) {
        if ("localhost".equalsIgnoreCase(host)) {
            return true;
        }
        try {
            for (InetAddress a : InetAddress.getAllByName(host)) {
                if (a.isSiteLocalAddress() || a.isLoopbackAddress()
                        || a.isLinkLocalAddress() || a.isAnyLocalAddress()) {
                    return true;
                }
            }
        } catch (Exception e) {
            return true;   // DNS 解析失败按拒绝处理
        }
        return false;
    }

    // ---------- 提取 ----------

    static String extractTitle(Document doc, String url) {
        String t = doc.select("meta[property=og:title]").attr("content");
        if (t.isBlank()) {
            t = doc.title();
        }
        if (t.isBlank()) {
            try {
                t = new URL(url).getHost();
            } catch (Exception ignored) {
                t = "网页导入";
            }
        }
        String cleaned = t.trim().replaceAll("\\s+", " ");
        return cleaned.length() > 100 ? cleaned.substring(0, 100) : cleaned;
    }

    static String extractText(Document doc) {
        doc.select("script, style, noscript, svg, iframe, form, button, nav, header, footer, aside").remove();
        Element main = doc.selectFirst("article, main, [role=main]");
        Element body = main != null ? main : doc.body();
        if (body == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Element el : body.select("p, h1, h2, h3, h4, h5, li, pre, blockquote, td")) {
            String t = el.text().trim();
            if (!t.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(t);
            }
        }
        return sb.toString();
    }
}
