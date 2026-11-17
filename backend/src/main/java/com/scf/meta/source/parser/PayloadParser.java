package com.scf.meta.source.parser;

import com.scf.meta.common.Json;
import com.scf.meta.identity.ComponentClaim;
import com.scf.meta.source.SourceArtifact;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * 载荷解析器（parser-1.0）：从各类型来源中提取组件声明。
 * 每种生态连接器按该生态坐标与锁文件规则提取；
 * 私有镜像的非标准字段单独映射，无法归一化的字段记入待核项。
 */
@Component
public class PayloadParser {

    public static class ParseResult {
        public final List<ComponentClaim> claims = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();
        /** 签名索引解析出的签名记录载荷：[subjectHash, signer, name, version, ecosystem, publisher, validFrom] */
        public final List<String[]> signatures = new ArrayList<>();
        /** 组件目录载荷：[ecosystem, namespace, name, publisher, aliasesJson] */
        public final List<String[]> catalogEntries = new ArrayList<>();
    }

    private final Json json;

    public PayloadParser(Json json) {
        this.json = json;
    }

    public ParseResult parse(SourceArtifact artifact) {
        ParseResult result = new ParseResult();
        JsonNode root;
        try {
            root = json.read(artifact.payload);
        } catch (IllegalArgumentException e) {
            // 构建日志允许纯文本行格式
            if ("BUILD_LOG".equals(artifact.type)) {
                parseBuildLogLines(artifact, result);
                return result;
            }
            throw e;
        }
        switch (SourceArtifact.Type.valueOf(artifact.type)) {
            case SBOM -> parseSbom(artifact, root, result);
            case LOCKFILE -> parseLockfile(artifact, root, result);
            case BUILD_LOG -> parseBuildLogJson(artifact, root, result);
            case REPO_METADATA -> parseRepoMetadata(artifact, root, result);
            case SOURCE_SCAN -> parseSourceScan(artifact, root, result);
            case SIGNATURE_INDEX -> parseSignatureIndex(artifact, root, result);
            case COMPONENT_CATALOG -> parseCatalog(artifact, root, result);
            case VULN_ADVISORY -> {
                // 漏洞通告仅保全原始载荷，平台不据此做处置
            }
        }
        return result;
    }

    private ComponentClaim newClaim(SourceArtifact artifact, ComponentClaim.Kind kind) {
        ComponentClaim c = new ComponentClaim();
        c.sourceArtifactId = artifact.id;
        c.kind = kind.name();
        c.buildContext = artifact.buildId;
        c.ecosystem = artifact.ecosystem;
        return c;
    }

    /** 逐条提取依赖范围与业务有效区间（缺省继承来源区间，由复演引擎处理）。 */
    private static void applyMeta(ComponentClaim c, JsonNode node) {
        String scope = text(node, "scope");
        if (scope != null) c.scope = scope;
        String validFrom = text(node, "validFrom");
        String validTo = text(node, "validTo");
        try {
            if (validFrom != null) c.validFrom = java.time.Instant.parse(validFrom);
            if (validTo != null) c.validTo = java.time.Instant.parse(validTo);
        } catch (Exception ignored) {
            // 非法时间格式时保留来源级区间
        }
    }

    private static String text(JsonNode node, String... fields) {
        for (String f : fields) {
            JsonNode v = node.get(f);
            if (v != null && v.isValueNode() && !v.asText().isBlank()) return v.asText();
        }
        return null;
    }

    private void parseSbom(SourceArtifact artifact, JsonNode root, ParseResult result) {
        // CycloneDX 风格：components[]，含 name/version/publisher/hashes/purl
        JsonNode components = root.get("components");
        if (components == null || !components.isArray()) {
            result.warnings.add("SBOM 未包含 components 数组");
            return;
        }
        for (JsonNode comp : components) {
            ComponentClaim c = newClaim(artifact, ComponentClaim.Kind.RESOLVED);
            c.name = text(comp, "name");
            c.version = text(comp, "version");
            c.publisher = text(comp, "publisher", "supplier", "group", "author");
            c.namespace = text(comp, "namespace", "group");
            String eco = text(comp, "ecosystem");
            if (eco != null) c.ecosystem = eco;
            String purl = text(comp, "purl");
            if (purl != null) applyPurl(c, purl);
            JsonNode hashes = comp.get("hashes");
            if (hashes != null && hashes.isArray() && !hashes.isEmpty()) {
                JsonNode h = hashes.get(0);
                c.hashAlg = text(h, "alg", "algorithm");
                c.hashValue = text(h, "content", "value");
            }
            String integrity = text(comp, "integrity");
            if (c.hashValue == null && integrity != null) applyIntegrity(c, integrity);
            applyMeta(c, comp);
            if (c.name != null) result.claims.add(c);
        }
    }

    private void parseLockfile(SourceArtifact artifact, JsonNode root, ParseResult result) {
        // 归一化锁文件：dependencies[]，含 name/version/resolved/integrity
        JsonNode deps = root.get("dependencies");
        if (deps == null) deps = root.get("packages");
        if (deps == null || !deps.isArray()) {
            result.warnings.add("锁文件未包含 dependencies/packages 数组");
            return;
        }
        for (JsonNode dep : deps) {
            ComponentClaim c = newClaim(artifact, ComponentClaim.Kind.RESOLVED);
            c.name = text(dep, "name");
            c.version = text(dep, "version");
            c.publisher = text(dep, "publisher");
            c.namespace = text(dep, "namespace");
            String eco = text(dep, "ecosystem");
            if (eco != null) c.ecosystem = eco;
            String integrity = text(dep, "integrity", "resolved_integrity");
            if (integrity != null) applyIntegrity(c, integrity);
            String sha = text(dep, "sha256", "sha512", "shasum", "hash");
            if (c.hashValue == null && sha != null) {
                c.hashAlg = sha.length() == 128 ? "SHA-512" : sha.length() == 40 ? "SHA-1" : "SHA-256";
                c.hashValue = sha;
            }
            applyMeta(c, dep);
            if (c.name != null) result.claims.add(c);
        }
    }

    private void parseBuildLogJson(SourceArtifact artifact, JsonNode root, ParseResult result) {
        JsonNode resolved = root.get("resolved");
        if (resolved == null || !resolved.isArray()) {
            result.warnings.add("构建日志 JSON 未包含 resolved 数组");
            return;
        }
        for (JsonNode r : resolved) {
            ComponentClaim c = newClaim(artifact, ComponentClaim.Kind.RESOLVED);
            c.name = text(r, "name", "artifact");
            c.version = text(r, "version");
            c.publisher = text(r, "publisher", "group", "org");
            c.namespace = text(r, "namespace", "group");
            String eco = text(r, "ecosystem", "type");
            if (eco != null) c.ecosystem = eco;
            String hash = text(r, "sha256", "hash", "integrity");
            if (hash != null) {
                if (hash.startsWith("sha256:")) hash = hash.substring(7);
                c.hashAlg = "SHA-256";
                c.hashValue = hash;
            }
            applyMeta(c, r);
            if (c.name != null) result.claims.add(c);
        }
    }

    /** 纯文本构建日志行：Resolved <eco>:<ns>/<name>@<version> sha256=<hash> publisher=<p> */
    private void parseBuildLogLines(SourceArtifact artifact, ParseResult result) {
        for (String line : artifact.payload.split("\\R")) {
            String t = line.trim();
            if (!t.startsWith("Resolved ")) continue;
            try {
                String[] parts = t.substring(9).split("\\s+");
                String coord = parts[0];
                ComponentClaim c = newClaim(artifact, ComponentClaim.Kind.RESOLVED);
                int at = coord.lastIndexOf('@');
                c.version = at > 0 ? coord.substring(at + 1) : null;
                String left = at > 0 ? coord.substring(0, at) : coord;
                int colon = left.indexOf(':');
                if (colon > 0) {
                    c.ecosystem = left.substring(0, colon);
                    left = left.substring(colon + 1);
                }
                int slash = left.lastIndexOf('/');
                if (slash > 0) {
                    c.namespace = left.substring(0, slash);
                    c.name = left.substring(slash + 1);
                } else {
                    c.name = left;
                }
                for (int i = 1; i < parts.length; i++) {
                    if (parts[i].startsWith("sha256=")) {
                        c.hashAlg = "SHA-256";
                        c.hashValue = parts[i].substring(7);
                    } else if (parts[i].startsWith("publisher=")) {
                        c.publisher = parts[i].substring(10);
                    }
                }
                if (c.name != null) result.claims.add(c);
            } catch (Exception e) {
                result.warnings.add("无法解析构建日志行: " + t);
            }
        }
    }

    private void parseRepoMetadata(SourceArtifact artifact, JsonNode root, ParseResult result) {
        // 标准格式：packages[]；私有镜像常见变体单独映射
        JsonNode packages = root.get("packages");
        boolean mirror = root.has("mirror") || (artifact.sourceUri != null && artifact.sourceUri.contains("mirror"));
        if (packages == null && root.has("versions")) {
            // npm registry 文档风格：{name, versions:{v:{...}}}
            packages = toPackagesArray(root, result);
        }
        if (packages == null || !packages.isArray()) {
            result.warnings.add("包仓元数据缺少可识别的 packages 结构");
            return;
        }
        for (JsonNode pkg : packages) {
            ComponentClaim c = newClaim(artifact, ComponentClaim.Kind.INFERRED);
            c.name = text(pkg, "name");
            c.version = text(pkg, "version");
            c.publisher = text(pkg, "publisher", "maintainer", "author", "owner");
            c.namespace = text(pkg, "namespace", "scope");
            String eco = text(pkg, "ecosystem", "type");
            if (eco != null) c.ecosystem = eco;
            // 私有镜像字段变体：dist.shasum / dist.integrity / distHash / dist_hash
            JsonNode dist = pkg.get("dist");
            String hash = null;
            if (dist != null) hash = text(dist, "integrity", "shasum", "sha256");
            if (hash == null) hash = text(pkg, "distHash", "dist_hash", "integrity");
            if (hash != null) {
                if (hash.startsWith("sha256-")) {
                    c.hashAlg = "SHA-256";
                    c.hashValue = hash.substring(7);
                } else if (hash.startsWith("sha512-")) {
                    c.hashAlg = "SHA-512";
                    c.hashValue = hash.substring(7);
                } else {
                    c.hashAlg = hash.length() == 40 ? "SHA-1" : "SHA-256";
                    c.hashValue = hash;
                }
            }
            if (mirror) {
                // 私有镜像：坐标字段常缺生态上下文，保留为候选并记待核
                result.warnings.add("私有镜像来源字段需单独维护："
                        + (c.name == null ? "?" : c.name) + " 的镜像字段已按变体映射，生态上下文待核");
            }
            applyMeta(c, pkg);
            if (c.name != null) result.claims.add(c);
        }
    }

    private JsonNode toPackagesArray(JsonNode root, ParseResult result) {
        String name = root.has("name") ? root.get("name").asText() : null;
        JsonNode versions = root.get("versions");
        List<tools.jackson.databind.node.ObjectNode> list = new ArrayList<>();
        if (versions != null && versions.isObject()) {
            versions.properties().forEach(e -> {
                var obj = json.mapper().createObjectNode();
                obj.put("name", name);
                obj.put("version", e.getKey());
                JsonNode v = e.getValue();
                if (v != null && v.isObject()) {
                    JsonNode dist = v.get("dist");
                    if (dist != null) obj.set("dist", dist);
                    JsonNode pub = v.get("publisher");
                    if (pub == null) pub = v.get("_npmUser");
                    if (pub != null) obj.set("publisher", pub);
                }
                list.add(obj);
            });
        }
        return json.mapper().createArrayNode().addAll(list);
    }

    private void parseSourceScan(SourceArtifact artifact, JsonNode root, ParseResult result) {
        JsonNode detected = root.get("detected");
        if (detected == null || !detected.isArray()) {
            result.warnings.add("源码扫描未包含 detected 数组");
            return;
        }
        for (JsonNode d : detected) {
            ComponentClaim c = newClaim(artifact, ComponentClaim.Kind.INFERRED);
            c.name = text(d, "name", "package");
            c.version = text(d, "version");
            String eco = text(d, "ecosystem", "language");
            if (eco != null) c.ecosystem = eco;
            c.publisher = text(d, "publisher");
            applyMeta(c, d);
            if (c.name != null) result.claims.add(c);
        }
    }

    private void parseSignatureIndex(SourceArtifact artifact, JsonNode root, ParseResult result) {
        JsonNode sigs = root.get("signatures");
        if (sigs == null || !sigs.isArray()) {
            result.warnings.add("签名索引未包含 signatures 数组");
            return;
        }
        for (JsonNode s : sigs) {
            String subjectHash = text(s, "subjectHash", "subject", "hash");
            String signer = text(s, "signer", "keyId");
            JsonNode comp = s.get("component");
            String name = null, version = null, eco = null, publisher = null;
            if (comp != null) {
                name = text(comp, "name");
                version = text(comp, "version");
                eco = text(comp, "ecosystem");
                publisher = text(comp, "publisher");
            }
            if (subjectHash != null) {
                String validFrom = text(s, "validFrom");
                result.signatures.add(new String[]{subjectHash, signer, name, version, eco, publisher, validFrom});
                if (name != null) {
                    ComponentClaim c = newClaim(artifact, ComponentClaim.Kind.SIGNATURE);
                    c.name = name;
                    c.version = version;
                    if (eco != null) c.ecosystem = eco;
                    c.publisher = publisher;
                    c.hashAlg = "SHA-256";
                    c.hashValue = subjectHash;
                    applyMeta(c, s);
                    result.claims.add(c);
                }
            }
        }
    }

    private void parseCatalog(SourceArtifact artifact, JsonNode root, ParseResult result) {
        JsonNode entries = root.get("entries");
        if (entries == null || !entries.isArray()) {
            result.warnings.add("组件目录未包含 entries 数组");
            return;
        }
        for (JsonNode e : entries) {
            String name = text(e, "name");
            if (name == null) continue;
            String aliases = e.has("aliases") && e.get("aliases").isArray()
                    ? json.write(e.get("aliases")) : "[]";
            result.catalogEntries.add(new String[]{
                    text(e, "ecosystem"), text(e, "namespace"), name,
                    text(e, "publisher"), aliases});
        }
    }

    private static void applyIntegrity(ComponentClaim c, String integrity) {
        if (integrity.startsWith("sha256-")) {
            c.hashAlg = "SHA-256";
            c.hashValue = integrity.substring(7);
        } else if (integrity.startsWith("sha512-")) {
            c.hashAlg = "SHA-512";
            c.hashValue = integrity.substring(7);
        } else {
            c.hashAlg = "SHA-256";
            c.hashValue = integrity;
        }
    }

    private static void applyPurl(ComponentClaim c, String purl) {
        // pkg:<type>/<ns>/<name>@<version>
        try {
            String rest = purl.substring("pkg:".length());
            int slash = rest.indexOf('/');
            c.ecosystem = rest.substring(0, slash);
            String tail = rest.substring(slash + 1);
            int at = tail.lastIndexOf('@');
            if (at > 0) {
                c.version = tail.substring(at + 1);
                tail = tail.substring(0, at);
            }
            int ns = tail.lastIndexOf('/');
            if (ns > 0) {
                c.namespace = tail.substring(0, ns);
                c.name = tail.substring(ns + 1);
            } else {
                c.name = tail;
            }
        } catch (Exception ignored) {
            // purl 不规范时保留已提取字段
        }
    }
}
