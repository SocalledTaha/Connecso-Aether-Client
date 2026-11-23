package com.whitedns.whiteaesther.core

import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.util.Base64

/** Endpoint data retained from the same provider files Mihomo loaded. */
data class NodeEndpoint(val host: String, val port: Int, val datagram: Boolean = false)

/**
 * Resolves a visible Mihomo node name to its server socket address.
 *
 * The destination screen needs the same signal the Windows client uses: a
 * fresh TCP connect to the selected node's own endpoint, carried through the
 * active Aether SOCKS listener. Mihomo's URL delay API instead times a public
 * HTTP request through the exit node, which can be several hundred ms higher
 * and is not the node endpoint RTT.
 */
internal object NodeEndpointCatalog {
    private var cachedStamp: String? = null
    private var cached: Map<String, NodeEndpoint> = emptyMap()

    @Synchronized
    fun read(home: File): Map<String, NodeEndpoint> {
        val providers = File(home, "providers")
        val files = providers.listFiles()?.filter(File::isFile)?.sortedBy(File::getName).orEmpty()
        val stamp = files.joinToString("|") { "${it.name}:${it.length()}:${it.lastModified()}" }
        if (stamp == cachedStamp) return cached
        cached = files.flatMap { file -> parse(runCatching { file.readText() }.getOrDefault("")) }
            .toMap()
        cachedStamp = stamp
        return cached
    }

    private fun parse(raw: String): List<Pair<String, NodeEndpoint>> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyList()
        val block = parseYaml(trimmed)
        val links = decodeLinks(trimmed).lineSequence().mapNotNull(::parseUri).toList()
        return (block + links).distinctBy { it.first }
    }

    private fun parseYaml(text: String): List<Pair<String, NodeEndpoint>> {
        val rows = mutableListOf<Pair<String, NodeEndpoint>>()
        var fields = linkedMapOf<String, String>()
        var indent = -1
        fun flush() {
            val name = fields["name"]?.trimQuotes()?.takeIf(String::isNotBlank) ?: return
            val host = fields["server"]?.trimQuotes()?.takeIf(String::isNotBlank) ?: return
            val port = fields["port"]?.trimQuotes()?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return
            rows += name to NodeEndpoint(host, port, fields["type"].orEmpty().contains("hysteria", true) || fields["type"].orEmpty().contains("tuic", true))
        }
        for (line in text.lineSequence()) {
            val content = line.substringBefore('#').trimEnd()
            if (content.isBlank()) continue
            val currentIndent = content.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
            val value = content.trim()
            if (value.startsWith("- ")) {
                if (indent >= 0) flush()
                fields = linkedMapOf()
                indent = currentIndent
                val inline = value.removePrefix("- ").trim()
                if (inline.startsWith("{")) {
                    Regex("(?:^|[,{])\\s*([A-Za-z0-9_-]+)\\s*:\\s*(\"[^\"]*\"|'[^']*'|[^,}]+)")
                        .findAll(inline)
                        .forEach { fields[it.groupValues[1].lowercase()] = it.groupValues[2].trim() }
                    flush()
                    indent = -1
                } else if (inline.contains(':')) {
                    val key = inline.substringBefore(':').trim().lowercase()
                    val item = inline.substringAfter(':').trim()
                    if (key in setOf("name", "server", "port", "type")) fields[key] = item
                }
                continue
            }
            if (indent < 0 || currentIndent <= indent || !value.contains(':')) continue
            val key = value.substringBefore(':').trim().lowercase()
            if (key in setOf("name", "server", "port", "type")) fields[key] = value.substringAfter(':').trim()
        }
        if (indent >= 0) flush()
        return rows
    }

    private fun decodeLinks(raw: String): String {
        if (raw.contains("://")) return raw
        val compact = raw.filterNot(Char::isWhitespace)
        for (decoder in listOf(Base64.getMimeDecoder(), Base64.getUrlDecoder())) {
            val padded = compact + "=".repeat((4 - compact.length % 4) % 4)
            val decoded = runCatching { String(decoder.decode(padded)) }.getOrNull()
            if (decoded?.contains("://") == true) return decoded
        }
        return ""
    }

    private fun parseUri(line: String): Pair<String, NodeEndpoint>? = runCatching {
        val uri = URI(line.trim())
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme == "vmess") {
            val encoded = uri.schemeSpecificPart.substringBefore('#')
            val json = String(Base64.getUrlDecoder().decode(encoded.padEnd((encoded.length + 3) / 4 * 4, '=')))
            val obj = org.json.JSONObject(json)
            val name = obj.optString("ps").takeIf(String::isNotBlank) ?: return null
            val host = obj.optString("add").takeIf(String::isNotBlank) ?: return null
            val port = obj.optString("port").toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            return name to NodeEndpoint(host, port)
        }
        val authority = uri.rawAuthority ?: return null
        val hostPort = authority.substringAfterLast('@').removePrefix("[")
        val host: String
        val port: Int
        if (hostPort.startsWith("[") || authority.substringAfterLast('@').startsWith("[")) {
            val bracketed = authority.substringAfterLast('@')
            host = bracketed.substringAfter('[').substringBefore(']')
            port = bracketed.substringAfter("]:").toIntOrNull() ?: return null
        } else {
            host = hostPort.substringBeforeLast(':')
            port = hostPort.substringAfterLast(':').toIntOrNull() ?: return null
        }
        if (host.isBlank() || port !in 1..65535) return null
        val name = uri.rawFragment?.let { URLDecoder.decode(it, "UTF-8") }?.takeIf(String::isNotBlank) ?: host
        name to NodeEndpoint(host, port, scheme in setOf("hysteria", "hysteria2", "hy2", "tuic"))
    }.getOrNull()

    private fun String.trimQuotes(): String = trim().trim('"', '\'')
}
