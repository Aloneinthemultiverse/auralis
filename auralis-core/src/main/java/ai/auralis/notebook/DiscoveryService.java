package ai.auralis.notebook;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import ai.auralis.corpus.ArxivClient;
import ai.auralis.corpus.Candidate;
import ai.auralis.corpus.GitHubRepoSource;
import ai.auralis.corpus.OpenAlexClient;
import ai.auralis.decision.DecisionService;
import ai.auralis.graph.GraphStore;
import ai.auralis.knowledge.ingest.IngestionService;
import ai.auralis.research.deep.DeepResearch;
import ai.auralis.research.deep.KaggleDeepResearch;
import ai.auralis.research.deep.LocalDeepResearch;

/**
 * NotebookLM-style source discovery: give a topic, get a ranked list of sources
 * (papers, web pages, open-source designs) with summaries; Laya scores each
 * one's relevance; the user picks what to import into the notebook. "Deep" mode
 * adds an Apodex/AgentHarness research report and the sources it used.
 */
@Service
public class DiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryService.class);
    private static final Pattern URL = Pattern.compile("https?://[^\\s\"'<>)\\]\\\\]+");
    private static final List<String> ARXIV_CATS = List.of("cs.AR", "cs.LG", "cs.AI", "cs.ET",
            "eess.SY", "eess.SP", "cs.DC", "physics.app-ph");

    /** A source found for a notebook. kind: paper | web | pdf | repo */
    public record Found(String id, String kind, String title, String url, String pdfUrl, String summary,
                        String origin, Integer year, int citedBy, Double relevance) {
    }

    public static final class DiscoverJob {
        public final UUID id = UUID.randomUUID();
        public final String notebook;
        public final String query;
        public final String mode;
        public volatile String status = "RUNNING";
        public volatile String phase = "searching";
        public volatile String report;
        public volatile List<Found> sources = List.of();

        DiscoverJob(String notebook, String query, String mode) {
            this.notebook = notebook;
            this.query = query;
            this.mode = mode;
        }
    }

    public static final class ImportJob {
        public final UUID id = UUID.randomUUID();
        public final String notebook;
        public final int total;
        public volatile String status = "RUNNING";
        public final AtomicInteger done = new AtomicInteger();
        public final AtomicInteger failed = new AtomicInteger();
        public final AtomicInteger chunks = new AtomicInteger();
        public final List<String> log = Collections.synchronizedList(new ArrayList<>());

        ImportJob(String notebook, int total) {
            this.notebook = notebook;
            this.total = total;
        }
    }

    private final ArxivClient arxiv;
    private final OpenAlexClient openAlex;
    private final WebSearchClient web;
    private final GitHubRepoSource github;
    private final DecisionService decisions;
    private final IngestionService ingestion;
    private final GraphStore graph;
    private final LocalDeepResearch localDeep;
    private final KaggleDeepResearch kaggleDeep;
    private final JsonMapper json;
    private final String githubToken;
    private final Path cache = Path.of(System.getProperty("java.io.tmpdir"), "auralis-sources");
    private final Map<UUID, DiscoverJob> discovers = new ConcurrentHashMap<>();
    private final Map<UUID, ImportJob> imports = new ConcurrentHashMap<>();
    private final ExecutorService runner = Executors.newVirtualThreadPerTaskExecutor();
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(20)).build();

    public DiscoveryService(ArxivClient arxiv, OpenAlexClient openAlex, WebSearchClient web,
                            GitHubRepoSource github, DecisionService decisions, IngestionService ingestion,
                            GraphStore graph, LocalDeepResearch localDeep, KaggleDeepResearch kaggleDeep,
                            JsonMapper json, @Value("${GITHUB_TOKEN:}") String githubToken) {
        this.arxiv = arxiv;
        this.openAlex = openAlex;
        this.web = web;
        this.github = github;
        this.decisions = decisions;
        this.ingestion = ingestion;
        this.graph = graph;
        this.localDeep = localDeep;
        this.kaggleDeep = kaggleDeep;
        this.json = json;
        this.githubToken = githubToken;
    }

    // ── notebooks ───────────────────────────────────────────────────

    public String createNotebook(String title) {
        String slug = title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        String ns = "nb-" + (slug.length() > 40 ? slug.substring(0, 40) : slug) + "-"
                + UUID.randomUUID().toString().substring(0, 6);
        graph.upsertNode(ns, "notebook", "NOTEBOOK", title, null, null, "{}");
        return ns;
    }

    // ── discovery ───────────────────────────────────────────────────

    public DiscoverJob discover(String notebook, String query, String mode) {
        DiscoverJob job = new DiscoverJob(notebook, query, mode == null ? "fast" : mode.toLowerCase(Locale.ROOT));
        discovers.put(job.id, job);
        runner.submit(() -> {
            try {
                runDiscover(job);
                job.status = "DONE";
            } catch (Exception ex) {
                log.error("discovery {} failed", job.id, ex);
                job.phase = "failed: " + ex.getMessage();
                job.status = "FAILED";
            }
        });
        return job;
    }

    public Optional<DiscoverJob> discoverJob(UUID id) {
        return Optional.ofNullable(discovers.get(id));
    }

    private void runDiscover(DiscoverJob job) throws Exception {
        job.sources = search(job.query, job.mode, job);
        job.phase = job.sources.size() + " sources found";
    }

    /**
     * Searches literature, web and open-source designs for a query and returns the
     * sources ranked by Laya's relevance score. {@code job} (optional) receives
     * progress and the deep-research report.
     */
    public List<Found> search(String query, String mode, DiscoverJob job) throws Exception {
        String q = query;
        String searchMode = mode == null ? "fast" : mode;
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var fArxiv = CompletableFuture.supplyAsync(() -> safe(() -> arxiv.search(q, ARXIV_CATS, 20)), pool);
            var fOa = CompletableFuture.supplyAsync(() -> safe(() -> openAlex.search(q, 20)), pool);
            var fWeb = CompletableFuture.supplyAsync(() -> web.search(q, 20), pool);
            var fPdf = CompletableFuture.supplyAsync(() -> web.search(q + " filetype:pdf", 15), pool);
            var fRepo = CompletableFuture.supplyAsync(() -> safe(() -> repos(q)), pool);

            Map<String, Found> found = new LinkedHashMap<>();
            Set<String> titles = new LinkedHashSet<>();
            for (Candidate c : fArxiv.join()) {
                add(found, titles, new Found(c.key(), "paper", c.title(), c.landingUrl(), c.pdfUrl(),
                        c.abstractText(), "arXiv", c.year(), 0, null));
            }
            for (Candidate c : fOa.join()) {
                add(found, titles, new Found(c.key(), "paper", c.title(), c.landingUrl(), c.pdfUrl(),
                        c.abstractText(), "OpenAlex", c.year(), c.citedBy(), null));
            }
            for (var h : fPdf.join()) {
                if (h.url().toLowerCase(Locale.ROOT).contains(".pdf")) {
                    add(found, titles, new Found("url:" + h.url(), "pdf", h.title(), h.url(), h.url(),
                            h.snippet(), "web (" + h.engine() + ")", null, 0, null));
                }
            }
            for (var h : fWeb.join()) {
                add(found, titles, new Found("url:" + h.url(), h.url().toLowerCase(Locale.ROOT).endsWith(".pdf")
                        ? "pdf" : "web", h.title(), h.url(), null, h.snippet(), "web (" + h.engine() + ")", null, 0, null));
            }
            for (Found r : fRepo.join()) {
                add(found, titles, r);
            }

            if ("deep".equals(searchMode) || "deep-kaggle".equals(searchMode)) {
                if (job != null) {
                    job.phase = "deep research (Apodex/AgentHarness)…";
                }
                DeepResearch deep = "deep-kaggle".equals(searchMode) ? kaggleDeep : localDeep;
                var res = deep.research(List.of(new DeepResearch.Query("q", q)));
                for (var f : res) {
                    if (f.ok()) {
                        if (job != null) {
                            job.report = f.answer();
                        }
                        Set<String> urls = new LinkedHashSet<>(f.sources());
                        Matcher m = URL.matcher(f.answer());
                        while (m.find()) {
                            urls.add(m.group().replaceAll("[.,;]+$", ""));
                        }
                        for (String u : urls) {
                            boolean pdf = u.toLowerCase(Locale.ROOT).contains(".pdf");
                            add(found, titles, new Found("url:" + u, pdf ? "pdf" : "web", host(u) + " — "
                                    + u.substring(u.lastIndexOf('/') + 1), u, pdf ? u : null,
                                    "Cited by the deep research report.", "deep research", null, 0, null));
                        }
                    } else if (job != null) {
                        job.report = "Deep research failed: " + f.error();
                    }
                }
            }

            if (job != null) {
                job.phase = "Laya is scoring " + found.size() + " sources";
                job.sources = List.copyOf(found.values());
            }
            Map<String, String> items = new LinkedHashMap<>();
            found.values().forEach(f -> items.put(f.id(), f.title() + ". " + trim(f.summary(), 300)));
            Map<String, Double> scores = decisions.selectScored("notebook", null, "source_relevance",
                    "Is this source relevant to the research topic: " + q, items, 0.0, items.size());
            List<Found> ranked = found.values().stream()
                    .map(f -> new Found(f.id(), f.kind(), f.title(), f.url(), f.pdfUrl(), trim(f.summary(), 600),
                            f.origin(), f.year(), f.citedBy(), scores.get(f.id())))
                    .sorted(Comparator.comparingDouble((Found f) -> f.relevance() == null ? 0 : f.relevance())
                            .reversed())
                    .toList();
            return ranked;
        }
    }

    private List<Found> repos(String q) throws Exception {
        String url = "https://api.github.com/search/repositories?per_page=8&sort=stars&q="
                + URLEncoder.encode(q, StandardCharsets.UTF_8);
        var b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("Accept", "application/vnd.github+json").header("User-Agent", "Auralis/0.1");
        if (!githubToken.isBlank()) {
            b.header("Authorization", "Bearer " + githubToken);
        }
        var resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        List<Found> out = new ArrayList<>();
        if (resp.statusCode() != 200) {
            return out;
        }
        for (JsonNode r : json.readTree(resp.body()).path("items")) {
            out.add(new Found("repo:" + r.path("full_name").asString(), "repo", r.path("full_name").asString(),
                    r.path("html_url").asString(), null,
                    r.path("description").asString("") + " (★" + r.path("stargazers_count").asInt() + ")",
                    "GitHub", null, r.path("stargazers_count").asInt(), null));
        }
        return out;
    }

    // ── import ──────────────────────────────────────────────────────

    public ImportJob importSources(String notebook, List<Found> items) {
        ImportJob job = new ImportJob(notebook, items.size());
        imports.put(job.id, job);
        var root = notebookRoot(notebook);
        runner.submit(() -> {
            for (Found f : items) {
                try {
                    int chunks = importOne(notebook, root, f);
                    job.chunks.addAndGet(chunks);
                    job.done.incrementAndGet();
                    job.log.add("✓ " + f.title() + " (" + chunks + " chunks)");
                } catch (Exception ex) {
                    job.failed.incrementAndGet();
                    job.log.add("✗ " + f.title() + ": " + ex.getMessage());
                }
            }
            job.status = "DONE";
        });
        return job;
    }

    public Optional<ImportJob> importJob(UUID id) {
        return Optional.ofNullable(imports.get(id));
    }

    /** The NOTEBOOK node of a notebook (created if missing). */
    public UUID notebookRoot(String notebook) {
        return graph.upsertNode(notebook, "notebook", "NOTEBOOK",
                graph.root(notebook).map(GraphStore.Node::name).orElse(notebook), null, null, "{}");
    }

    /** Downloads/ingests one source into a notebook under {@code root}; returns its chunk count. */
    public int importOne(String ns, UUID root, Found f) throws Exception {
        if ("repo".equals(f.kind())) {
            String repo = f.title();
            var repoNode = graph.upsertNode(ns, "repo:" + repo, "DESIGN", repo, f.url(), null,
                    json.writeValueAsString(Map.of("summary", f.summary() == null ? "" : f.summary())));
            graph.addEdge(root, repoNode, "CONTAINS");
            int chunks = 0;
            for (var file : github.list(repo, 25, 8)) {
                String text = github.fetch(file.rawUrl());
                boolean rtl = !file.path().toLowerCase(Locale.ROOT).matches(".*\\.(md|rst|adoc|txt)$");
                var r = ingestion.ingestText(rtl ? "Source file " + file.path() + " of " + repo + ":\n\n" + text : text,
                        repo + " — " + file.path(), rtl ? "rtl" : "design-doc", file.htmlUrl());
                var n = graph.upsertNode(ns, "file:" + repo + ":" + file.path(), rtl ? "RTL" : "DOC",
                        file.path(), file.htmlUrl(), r.documentId(), "{}");
                graph.addEdge(repoNode, n, "CONTAINS");
                chunks += r.chunks();
            }
            return chunks;
        }
        Files.createDirectories(cache);
        String url = f.pdfUrl() != null ? f.pdfUrl() : f.url();
        Path file = cache.resolve(UUID.nameUUIDFromBytes(url.getBytes(StandardCharsets.UTF_8)) + suffix(url));
        if (!Files.exists(file) || Files.size(file) == 0) {
            fetch(url, file);
        }
        var r = ingestion.ingest(new FileSystemResource(file), f.title(), f.kind().equals("paper") ? "paper" : f.kind(),
                f.url());
        String props = json.writeValueAsString(Map.of("origin", f.origin(), "summary", trim(f.summary(), 500),
                "year", f.year() == null ? "" : f.year().toString()));
        var node = graph.upsertNode(ns, f.id(), "WORK", f.title(), f.url(), r.documentId(), props);
        graph.addEdge(root, node, "CONTAINS");
        return r.chunks();
    }

    private void fetch(String url, Path target) throws Exception {
        var resp = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(3))
                .header("User-Agent", "Mozilla/5.0 (Auralis; research notebook)").build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("HTTP " + resp.statusCode());
        }
        Path tmp = Files.createTempFile(target.getParent(), "dl-", ".part");
        try (InputStream in = resp.body()) {
            Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    // ── helpers ─────────────────────────────────────────────────────

    private static void add(Map<String, Found> found, Set<String> titles, Found f) {
        if (f.url() == null || f.url().isBlank() || f.title() == null || f.title().isBlank()) {
            return;
        }
        String t = Candidate.normTitle(f.title());
        if (found.containsKey(f.id()) || !titles.add(t)) {
            return;
        }
        found.put(f.id(), f);
    }

    private static String suffix(String url) {
        String l = url.toLowerCase(Locale.ROOT);
        return l.contains(".pdf") ? ".pdf" : ".html";
    }

    private static String host(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return "web";
        }
    }

    private static String trim(String s, int n) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\s+", " ").strip();
        return t.length() <= n ? t : t.substring(0, n) + "…";
    }

    interface Call<T> {
        T get() throws Exception;
    }

    private static <T> List<T> safe(Call<List<T>> c) {
        try {
            return c.get();
        } catch (Exception e) {
            return List.of();
        }
    }

    @PreDestroy
    void shutdown() {
        runner.shutdownNow();
    }
}
