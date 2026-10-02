package ai.auralis.corpus;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real designs as knowledge: the documentation and representative RTL of
 * open-source hardware repositories. One tree listing per repo (GitHub API),
 * files fetched from raw.githubusercontent.com. Uses GITHUB_TOKEN if set.
 */
@Component
public class GitHubRepoSource {

    public record RepoFile(String repo, String path, String rawUrl, String htmlUrl, long size) {
    }

    private static final List<String> DOC_EXT = List.of(".md", ".rst", ".adoc", ".txt");
    private static final List<String> RTL_EXT = List.of(".sv", ".v", ".svh", ".vhd", ".scala");
    private static final long MAX_DOC = 300_000;
    private static final long MAX_RTL = 80_000;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final JsonMapper json;
    private final String token;

    public GitHubRepoSource(JsonMapper json, @Value("${GITHUB_TOKEN:}") String token) {
        this.json = json;
        this.token = token;
    }

    /** Documentation first, then RTL, skipping vendored/third-party and generated paths. */
    public List<RepoFile> list(String repo, int maxDocs, int maxRtl) throws Exception {
        String branch = json.readTree(get("https://api.github.com/repos/" + repo)).path("default_branch").asString("main");
        JsonNode tree = json.readTree(get("https://api.github.com/repos/" + repo + "/git/trees/" + branch + "?recursive=1"));
        List<RepoFile> docs = new ArrayList<>();
        List<RepoFile> rtl = new ArrayList<>();
        for (JsonNode n : tree.path("tree")) {
            if (!"blob".equals(n.path("type").asString())) {
                continue;
            }
            String path = n.path("path").asString();
            String lower = path.toLowerCase(Locale.ROOT);
            if (lower.contains("vendor/") || lower.contains("third_party") || lower.contains("3rdparty")
                    || lower.contains("/test/") && !lower.contains("doc") || lower.startsWith(".")) {
                continue;
            }
            long size = n.path("size").asLong(0);
            String raw = "https://raw.githubusercontent.com/" + repo + "/" + branch + "/" + path;
            String html = "https://github.com/" + repo + "/blob/" + branch + "/" + path;
            if (DOC_EXT.stream().anyMatch(lower::endsWith) && size > 400 && size < MAX_DOC
                    && !lower.contains("license") && !lower.contains("changelog")) {
                docs.add(new RepoFile(repo, path, raw, html, size));
            } else if (RTL_EXT.stream().anyMatch(lower::endsWith) && size > 800 && size < MAX_RTL
                    && (lower.contains("rtl/") || lower.contains("/src/") || lower.contains("hw/"))) {
                rtl.add(new RepoFile(repo, path, raw, html, size));
            }
        }
        // README and top-level docs first; larger RTL modules first (cores, not glue).
        docs.sort((a, b) -> Integer.compare(depth(a.path()), depth(b.path())));
        rtl.sort((a, b) -> Long.compare(b.size(), a.size()));
        List<RepoFile> out = new ArrayList<>(docs.subList(0, Math.min(maxDocs, docs.size())));
        out.addAll(rtl.subList(0, Math.min(maxRtl, rtl.size())));
        return out;
    }

    public String fetch(String rawUrl) throws Exception {
        return get(rawUrl);
    }

    private String get(String url) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
                .header("User-Agent", "Auralis-Corpus/0.1")
                .header("Accept", "application/vnd.github+json");
        if (!token.isBlank() && url.startsWith("https://api.github.com")) {
            b.header("Authorization", "Bearer " + token);
        }
        var resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("GitHub HTTP " + resp.statusCode() + " for " + url);
        }
        return resp.body();
    }

    private static int depth(String path) {
        int d = path.split("/").length;
        return path.toLowerCase(Locale.ROOT).contains("readme") ? d - 1 : d;
    }
}
