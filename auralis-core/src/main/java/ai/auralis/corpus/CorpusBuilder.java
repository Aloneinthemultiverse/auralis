package ai.auralis.corpus;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import ai.auralis.decision.DecisionService;
import ai.auralis.graph.GraphStore;
import ai.auralis.knowledge.ingest.IngestionService;
import ai.auralis.research.deep.DeepResearch;
import ai.auralis.research.deep.KaggleDeepResearch;
import ai.auralis.research.deep.LocalDeepResearch;

/**
 * Builds a domain research corpus at scale from a manifest of topics:
 * discover (arXiv + OpenAlex, sources chosen from the OSINT catalog's academic
 * search branch) → Laya decides relevance in batches → topics left thin are
 * escalated to Apodex/AgentHarness deep research restricted to open hosts →
 * download open-access PDFs → ingest → graph of TOPIC → WORK nodes with CITES
 * edges between works in the corpus.
 */
@Service
public class CorpusBuilder {

    private static final Logger log = LoggerFactory.getLogger(CorpusBuilder.class);
    private static final Pattern URL = Pattern.compile("https?://[^\\s\"'<>)\\]\\\\]+");
    private static final Pattern ARXIV_ABS = Pattern.compile("arxiv\\.org/(?:abs|pdf)/([0-9]{4}\\.[0-9]{4,5})");

    /** Live progress of one build. */
    public static final class Job {
        public final UUID id = UUID.randomUUID();
        public final String namespace;
        public final Instant started = Instant.now();
        public volatile String status = "RUNNING";
        public volatile String phase = "starting";
        public final AtomicInteger discovered = new AtomicInteger();
        public final AtomicInteger kept = new AtomicInteger();
        public final AtomicInteger ingested = new AtomicInteger();
        public final AtomicInteger failed = new AtomicInteger();
        public final AtomicInteger chunks = new AtomicInteger();
        public final AtomicInteger citationEdges = new AtomicInteger();
        public final List<String> log = Collections.synchronizedList(new ArrayList<>());

        Job(String namespace) {
            this.namespace = namespace;
        }

        void note(String msg) {
            log.add(java.time.LocalTime.now().withNano(0) + " " + msg);
            if (log.size() > 400) {
                log.removeFirst();
            }
        }
    }

    private final ArxivClient arxiv;
    private final GitHubRepoSource github;
    private final HarvestSources harvestSources;
    private final OpenAlexClient openAlex;
    private final DecisionService decisions;
    private final IngestionService ingestion;
    private final GraphStore graph;
    private final LocalDeepResearch localDeep;
    private final KaggleDeepResearch kaggleDeep;
    private final JsonMapper json;
    private final Map<UUID, Job> jobs = new ConcurrentHashMap<>();
    private final ExecutorService runner = Executors.newVirtualThreadPerTaskExecutor();
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(20)).build();

    public CorpusBuilder(ArxivClient arxiv, GitHubRepoSource github, HarvestSources harvestSources, OpenAlexClient openAlex, DecisionService decisions,
                         IngestionService ingestion, GraphStore graph, LocalDeepResearch localDeep,
                         KaggleDeepResearch kaggleDeep, JsonMapper json) {
        this.arxiv = arxiv;
        this.github = github;
        this.harvestSources = harvestSources;
        this.openAlex = openAlex;
        this.decisions = decisions;
        this.ingestion = ingestion;
        this.graph = graph;
        this.localDeep = localDeep;
        this.kaggleDeep = kaggleDeep;
        this.json = json;
    }

    public Job start(Path manifest, Path cacheDir, Integer topicLimit) {
        JsonNode m = json.readTree(manifest.toFile());
        Job job = new Job(m.path("namespace").asString());
        jobs.put(job.id, job);
        runner.submit(() -> {
            try {
                build(job, m, cacheDir, topicLimit == null ? Integer.MAX_VALUE : topicLimit);
                job.status = "DONE";
            } catch (Exception ex) {
                log.error("corpus build {} failed", job.id, ex);
                job.note("FAILED: " + ex);
                job.status = "FAILED";
            }
            job.phase = "finished";
        });
        return job;
    }

    public Optional<Job> job(UUID id) {
        return Optional.ofNullable(jobs.get(id));
    }

    public List<Job> jobs() {
        return List.copyOf(jobs.values());
    }

    private void build(Job job, JsonNode m, Path cacheDir, int topicLimit) throws Exception {
        String ns = job.namespace;
        Files.createDirectories(cacheDir);
        String question = m.path("relevanceQuestion").asString();
        JsonNode src = m.path("sources");
        List<String> cats = new ArrayList<>();
        src.path("arxiv").path("categories").forEach(c -> cats.add(c.asString()));
        int arxivN = src.path("arxiv").path("perTopic").asInt(20);
        int oaN = src.path("openalex").path("perTopic").asInt(20);
        int keepN = m.path("keepPerTopic").asInt(25);
        JsonNode deepCfg = m.path("deepResearch");
        List<String> allowed = new ArrayList<>();
        deepCfg.path("allowedHosts").forEach(h -> allowed.add(h.asString()));

        var corpusId = graph.upsertNode(ns, "corpus", "CORPUS", m.path("title").asString(ns), null, null,
                json.writeValueAsString(Map.of("domain", m.path("domain").asString(""))));
        Set<String> seen = new HashSet<>();
        Map<String, Candidate> keptAll = new LinkedHashMap<>();
        Map<String, UUID> nodeIds = new LinkedHashMap<>();

        List<String> allTopics = new ArrayList<>();
        m.path("topics").forEach(t -> allTopics.add(t.asString()));
        List<String> topics = allTopics.subList(0, Math.min(topicLimit, allTopics.size()));
        Map<String, Integer> thinTopics = new LinkedHashMap<>();
        Map<String, UUID> topicNodes = new LinkedHashMap<>();

        harvest(job, ns, corpusId, m.path("harvest"), question, cacheDir, seen, keptAll, nodeIds);
        if (m.path("reposFirst").asBoolean(true)) {
            ingestRepos(job, ns, corpusId, m.path("repos"));
        }

        for (String topic : topics) {
            job.phase = "discovering: " + topic;
            List<Candidate> found = new ArrayList<>();
            try {
                found.addAll(arxiv.search(topic, cats, arxivN));
            } catch (Exception e) {
                job.note("arXiv failed for '" + topic + "': " + e.getMessage());
            }
            try {
                found.addAll(openAlex.search(topic, oaN));
            } catch (Exception e) {
                job.note("OpenAlex failed for '" + topic + "': " + e.getMessage());
            }
            List<Candidate> fresh = dedup(found, seen);
            job.discovered.addAndGet(fresh.size());

            List<Candidate> keep = layaFilter(job, topic, question, fresh, keepN);
            if (keep.size() < deepCfg.path("minKeptToSkip").asInt(5)) {
                thinTopics.put(topic, keepN - keep.size());
            }
            job.kept.addAndGet(keep.size());
            job.note(String.format(Locale.ROOT, "%s: %d new candidates, Laya kept %d",
                    topic, fresh.size(), keep.size()));

            var topicId = graph.upsertNode(ns, "topic:" + topic, "TOPIC", topic, null, null, "{}");
            graph.addEdge(corpusId, topicId, "CONTAINS");
            topicNodes.put(topic, topicId);
            for (Candidate c : keep) {
                job.phase = "ingesting (" + topic + "): " + c.title();
                UUID node = ingest(job, ns, topicId, c, cacheDir);
                if (node != null) {
                    keptAll.put(c.key(), c);
                    nodeIds.put(c.key(), node);
                }
            }
        }

        if (!thinTopics.isEmpty() && deepCfg.path("enabled").asBoolean(false)) {
            String mode = deepCfg.path("mode").asString("LOCAL");
            job.phase = "deep research (" + mode + ", one batch of " + thinTopics.size() + " thin topics)";
            job.note("escalating " + thinTopics.size() + " thin topics to " + mode + " deep research in one batch");
            Map<String, List<Candidate>> found = deepDiscover(job, List.copyOf(thinTopics.keySet()), mode, allowed);
            for (var e : found.entrySet()) {
                List<Candidate> fresh = dedup(e.getValue(), seen);
                job.discovered.addAndGet(fresh.size());
                List<Candidate> keep = layaFilter(job, e.getKey(), question, fresh, thinTopics.get(e.getKey()));
                job.kept.addAndGet(keep.size());
                for (Candidate c : keep) {
                    UUID node = ingest(job, ns, topicNodes.get(e.getKey()), c, cacheDir);
                    if (node != null) {
                        keptAll.put(c.key(), c);
                        nodeIds.put(c.key(), node);
                    }
                }
            }
        }

        if (!m.path("reposFirst").asBoolean(true)) {
            ingestRepos(job, ns, corpusId, m.path("repos"));
        }

        job.phase = "linking citations";
        Map<String, UUID> byOpenAlex = new LinkedHashMap<>();
        keptAll.values().stream().filter(c -> c.openAlexId() != null).forEach(c ->
                byOpenAlex.put(c.openAlexId(), nodeIds.get(c.key())));
        for (Candidate c : keptAll.values()) {
            for (String ref : c.references()) {
                UUID target = byOpenAlex.get(ref);
                if (target != null) {
                    graph.addEdge(nodeIds.get(c.key()), target, "CITES");
                    job.citationEdges.incrementAndGet();
                }
            }
        }
        job.note("done: " + job.ingested + " works ingested, " + job.chunks + " chunks, "
                + job.citationEdges + " citation links");
    }

    /** Laya judges relevance for the whole batch; the best are kept, ranked by p × citations. */
    private List<Candidate> layaFilter(Job job, String topic, String question, List<Candidate> cands,
                                       int max) {
        if (cands.isEmpty() || max <= 0) {
            return List.of();
        }
        Map<String, String> items = new LinkedHashMap<>();
        Map<String, Candidate> byKey = new LinkedHashMap<>();
        for (Candidate c : cands) {
            items.put(c.key(), c.brief());
            byKey.put(c.key(), c);
        }
        Map<String, Double> scored = decisions.selectScored("corpus", job.id, "corpus_relevance",
                question, items, 0.5, 0);
        int unjudged = decisions.lastUnavailable();
        if (unjudged > 0) {
            job.note(topic + ": Laya could not judge " + unjudged + " of " + cands.size()
                    + " candidates (kept at neutral 0.5)");
        }
        return scored.entrySet().stream()
                .sorted(Comparator.comparingDouble((Map.Entry<String, Double> e) ->
                        e.getValue() * (1 + Math.log10(1 + byKey.get(e.getKey()).citedBy()))).reversed())
                .limit(max)
                .map(e -> byKey.get(e.getKey()))
                .toList();
    }

    /** One Apodex/AgentHarness batch hunts open-access PDFs for all thin topics; only allow-listed hosts pass. */
    private Map<String, List<Candidate>> deepDiscover(Job job, List<String> topics, String mode,
                                                      List<String> allowed) {
        DeepResearch deep = "KAGGLE".equalsIgnoreCase(mode) ? kaggleDeep : localDeep;
        List<DeepResearch.Query> queries = new ArrayList<>();
        for (int i = 0; i < topics.size(); i++) {
            queries.add(new DeepResearch.Query("t" + i, "Find freely available, open-access PDF documents "
                    + "(textbooks, lecture notes, theses, technical reports, papers) about: " + topics.get(i)
                    + ". List each with its title and the direct PDF URL. Only include documents that are "
                    + "legally free to download."));
        }
        Map<String, List<Candidate>> out = new LinkedHashMap<>();
        for (var f : deep.research(queries)) {
            String topic = topics.get(Integer.parseInt(f.id().substring(1)));
            if (!f.ok()) {
                job.note("deep research failed for '" + topic + "': " + f.error());
                continue;
            }
            List<Candidate> cands = new ArrayList<>();
            Set<String> urls = new java.util.LinkedHashSet<>(f.sources());
            Matcher mm = URL.matcher(f.answer());
            while (mm.find()) {
                urls.add(mm.group().replaceAll("[.,;]+$", ""));
            }
            for (String u : urls) {
                Matcher ax = ARXIV_ABS.matcher(u);
                String pdf;
                String key;
                if (ax.find()) {
                    pdf = "https://arxiv.org/pdf/" + ax.group(1);
                    key = "arxiv:" + ax.group(1);
                } else if (u.toLowerCase(Locale.ROOT).endsWith(".pdf") && hostAllowed(u, allowed)) {
                    pdf = u;
                    key = "url:" + u;
                } else {
                    continue;
                }
                String name = u.substring(u.lastIndexOf('/') + 1).replace(".pdf", "").replace('_', ' ');
                cands.add(new Candidate(key, name, "Found by deep research for: " + topic, "", null, u, pdf,
                        "open host (verify)", "deep-research", topic, 0, null, List.of(), List.of()));
            }
            job.note("deep research for '" + topic + "' proposed " + cands.size() + " open PDFs");
            out.put(topic, cands);
        }
        return out;
    }

    /** Open-source chip designs: Laya picks which docs/RTL files of each repo are worth knowing. */
    private void ingestRepos(Job job, String ns, UUID corpusId, JsonNode repos) {
        if (repos == null || !repos.isArray() || repos.isEmpty()) {
            return;
        }
        var designsId = graph.upsertNode(ns, "designs", "CATEGORY", "Open-source chip designs", null, null, "{}");
        graph.addEdge(corpusId, designsId, "CONTAINS");
        for (JsonNode r : repos) {
            String repo = r.path("repo").asString();
            job.phase = "design repo: " + repo;
            try {
                var files = github.list(repo, r.path("maxDocs").asInt(60), r.path("maxRtl").asInt(20));
                Map<String, String> items = new LinkedHashMap<>();
                files.forEach(f -> items.put(f.path(), repo + " " + f.path()));
                Map<String, Double> keep = decisions.selectScored("corpus", job.id, "design_file_relevance",
                        "Is this file about how the hardware design works?",
                        items, 0.4, Math.min(10, items.size()));
                var repoId = graph.upsertNode(ns, "repo:" + repo, "DESIGN", repo, "https://github.com/" + repo,
                        null, json.writeValueAsString(Map.of("description", r.path("description").asString(""))));
                graph.addEdge(designsId, repoId, "CONTAINS");
                int n = 0;
                for (var f : files) {
                    if (!keep.containsKey(f.path())) {
                        continue;
                    }
                    try {
                        String text = github.fetch(f.rawUrl());
                        boolean rtl = !f.path().toLowerCase(Locale.ROOT).matches(".*\\.(md|rst|adoc|txt)$");
                        var res = ingestion.ingestText(
                                rtl ? "Source file " + f.path() + " of " + repo + ":\n\n" + text : text,
                                repo + " — " + f.path(), rtl ? "rtl" : "design-doc", f.htmlUrl());
                        var fileId = graph.upsertNode(ns, "file:" + repo + ":" + f.path(), rtl ? "RTL" : "DOC",
                                f.path(), f.htmlUrl(), res.documentId(), "{}");
                        graph.addEdge(repoId, fileId, "CONTAINS");
                        job.ingested.incrementAndGet();
                        job.chunks.addAndGet(res.chunks());
                        n++;
                    } catch (Exception ex) {
                        job.failed.incrementAndGet();
                    }
                }
                job.note(repo + ": " + files.size() + " candidate files, Laya kept " + keep.size()
                        + ", ingested " + n);
            } catch (Exception ex) {
                job.note("repo " + repo + " failed: " + ex.getMessage());
            }
        }
    }

    /**
     * Bulk phase: whole arXiv categories, Berkeley EECS reports/theses, open Internet
     * Archive books and Wikibooks. Laya judges every candidate in batches; kept ones are
     * ingested as they are found, so progress is visible and an interrupted run keeps
     * everything already ingested.
     */
    private void harvest(Job job, String ns, UUID corpusId, JsonNode h, String question, Path cacheDir,
                         Set<String> seen, Map<String, Candidate> keptAll, Map<String, UUID> nodeIds) {
        if (h == null || h.isMissingNode() || h.isNull()) {
            return;
        }
        for (JsonNode a : h.path("arxiv")) {
            String cat = a.path("category").asString();
            int max = a.path("max").asInt(2000);
            UUID node = category(ns, corpusId, "arXiv " + cat);
            for (int start = 0; start < max; start += 200) {
                job.phase = "harvest arXiv " + cat + " (" + start + "/" + max + ")";
                List<Candidate> page;
                try {
                    page = harvestSources.arxivCategory(cat, start, 200);
                } catch (Exception e) {
                    job.note("arXiv " + cat + " page " + start + " failed: " + e.getMessage());
                    continue;
                }
                if (page.isEmpty()) {
                    break;
                }
                keepAndIngest(job, ns, node, "arXiv " + cat, question, page, cacheDir, seen, keptAll, nodeIds);
            }
        }
        JsonNode b = h.path("berkeley");
        if (!b.isMissingNode()) {
            UUID node = category(ns, corpusId, "UC Berkeley EECS theses and reports");
            for (int y = b.path("toYear").asInt(2025); y >= b.path("fromYear").asInt(2005); y--) {
                job.phase = "harvest Berkeley EECS " + y;
                try {
                    keepAndIngest(job, ns, node, "Berkeley " + y, question, harvestSources.berkeleyYear(y),
                            cacheDir, seen, keptAll, nodeIds);
                } catch (Exception e) {
                    job.note("Berkeley " + y + " failed: " + e.getMessage());
                }
            }
        }
        for (JsonNode q : h.path("internetArchive")) {
            UUID node = category(ns, corpusId, "Open books (Internet Archive)");
            for (int p = 1; p <= h.path("internetArchivePages").asInt(3); p++) {
                job.phase = "harvest Internet Archive: " + q.asString() + " p" + p;
                try {
                    List<Candidate> page = harvestSources.internetArchive(q.asString(), 100, p);
                    if (page.isEmpty()) {
                        break;
                    }
                    keepAndIngest(job, ns, node, "Internet Archive", question, page, cacheDir, seen, keptAll, nodeIds);
                } catch (Exception e) {
                    job.note("Internet Archive '" + q.asString() + "' failed: " + e.getMessage());
                    break;
                }
            }
        }
        List<Candidate> books = new ArrayList<>();
        for (JsonNode w : h.path("books")) {
            books.add(new Candidate("url:" + w.path("url").asString(), w.path("title").asString(),
                    w.path("about").asString(""), w.path("authors").asString(""), null, w.path("url").asString(),
                    w.path("url").asString(), w.path("license").asString("open"), "open-book", "books", 0, null,
                    List.of(), List.of()));
        }
        if (!books.isEmpty()) {
            job.phase = "open books";
            keepAndIngest(job, ns, category(ns, corpusId, "Open textbooks"), "open textbooks", question, books,
                    cacheDir, seen, keptAll, nodeIds);
        }
    }

    private UUID category(String ns, UUID corpusId, String name) {
        UUID id = graph.upsertNode(ns, "source:" + name, "CATEGORY", name, null, null, "{}");
        graph.addEdge(corpusId, id, "CONTAINS");
        return id;
    }

    private void keepAndIngest(Job job, String ns, UUID parent, String label, String question,
                               List<Candidate> cands, Path cacheDir, Set<String> seen,
                               Map<String, Candidate> keptAll, Map<String, UUID> nodeIds) {
        List<Candidate> fresh = dedup(cands, seen);
        job.discovered.addAndGet(fresh.size());
        List<Candidate> keep = layaFilter(job, label, question, fresh, Integer.MAX_VALUE);
        job.kept.addAndGet(keep.size());
        job.note(label + ": " + fresh.size() + " new, Laya kept " + keep.size());
        for (Candidate c : keep) {
            job.phase = "ingesting (" + label + "): " + c.title();
            UUID node = ingest(job, ns, parent, c, cacheDir);
            if (node != null) {
                keptAll.put(c.key(), c);
                nodeIds.put(c.key(), node);
            }
        }
    }

    /** Downloads and ingests one work; returns its graph node, or null if it was skipped. */
    private UUID ingest(Job job, String ns, UUID topicId, Candidate c, Path cacheDir) {
        try {
            Path file = cacheDir.resolve(c.key().replaceAll("[^A-Za-z0-9._-]", "_") + ".pdf");
            if (!Files.exists(file) || Files.size(file) == 0) {
                Exception last = null;
                for (String rawUrl : c.pdfUrls()) {
                    try {
                        String url = rawUrl.startsWith("ia-pdf:")
                                ? harvestSources.resolveInternetArchivePdf(rawUrl.substring(7)) : rawUrl;
                        if (url.contains("arxiv.org")) {
                            Thread.sleep(1000); // polite to arXiv
                        }
                        download(url, file);
                        last = null;
                        break;
                    } catch (Exception e) {
                        last = e;
                    }
                }
                if (last != null) {
                    throw new IllegalStateException(last.getMessage() + " (tried "
                            + c.pdfUrls().size() + " open-access copies)");
                }
            }
            var r = ingestion.ingest(new FileSystemResource(file), c.title(), "paper", c.landingUrl());
            ObjectNode props = json.createObjectNode();
            props.put("authors", c.authors());
            if (c.year() != null) {
                props.put("year", c.year());
            }
            props.put("license", c.license());
            props.put("source", c.source());
            props.put("citedBy", c.citedBy());
            props.put("pdf", c.pdfUrl());
            UUID nodeId = graph.upsertNode(ns, c.key(), "WORK", c.title(), c.landingUrl(), r.documentId(),
                    json.writeValueAsString(props));
            graph.addEdge(topicId, nodeId, "CONTAINS");
            job.ingested.incrementAndGet();
            job.chunks.addAndGet(r.chunks());
            return nodeId;
        } catch (Exception ex) {
            job.failed.incrementAndGet();
            job.note("skip '" + c.title() + "': " + ex.getMessage());
            return null;
        }
    }

    private void download(String url, Path target) throws Exception {
        var resp = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(3))
                .header("User-Agent", "Mozilla/5.0 (Auralis-Corpus; open-access research)").build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("HTTP " + resp.statusCode());
        }
        String type = resp.headers().firstValue("content-type").orElse("");
        Path tmp = Files.createTempFile(target.getParent(), "dl-", ".part");
        try (InputStream in = resp.body()) {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        }
        byte[] head = new byte[5];
        try (var in = Files.newInputStream(tmp)) {
            in.read(head);
        }
        if (!new String(head).startsWith("%PDF")) {
            Files.deleteIfExists(tmp);
            throw new IllegalStateException("not a PDF (" + type + "), likely a paywall or landing page");
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static List<Candidate> dedup(List<Candidate> cands, Set<String> seen) {
        List<Candidate> out = new ArrayList<>();
        for (Candidate c : cands) {
            String t = "t:" + Candidate.normTitle(c.title());
            if (c.title() == null || c.title().isBlank() || !seen.add(c.key()) || !seen.add(t)) {
                continue;
            }
            out.add(c);
        }
        return out;
    }

    private static boolean hostAllowed(String url, List<String> allowed) {
        String host = URI.create(url).getHost();
        if (host == null) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        return allowed.stream().anyMatch(a -> a.startsWith(".") ? h.endsWith(a) : h.equals(a) || h.endsWith("." + a));
    }

    @PreDestroy
    void shutdown() {
        runner.shutdownNow();
    }
}
