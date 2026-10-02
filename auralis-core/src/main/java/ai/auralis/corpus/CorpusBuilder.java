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

    public CorpusBuilder(ArxivClient arxiv, OpenAlexClient openAlex, DecisionService decisions,
                         IngestionService ingestion, GraphStore graph, LocalDeepResearch localDeep,
                         KaggleDeepResearch kaggleDeep, JsonMapper json) {
        this.arxiv = arxiv;
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
            if (keep.size() < deepCfg.path("minKeptToSkip").asInt(5)
                    && deepCfg.path("enabled").asBoolean(false)) {
                job.phase = "deep research: " + topic;
                List<Candidate> deep = dedup(deepDiscover(job, topic,
                        deepCfg.path("mode").asString("LOCAL"), allowed), seen);
                job.discovered.addAndGet(deep.size());
                keep = new ArrayList<>(keep);
                keep.addAll(layaFilter(job, topic, question, deep, keepN - keep.size()));
            }
            job.kept.addAndGet(keep.size());
            job.note(String.format(Locale.ROOT, "%s: %d new candidates, Laya kept %d",
                    topic, fresh.size(), keep.size()));

            var topicId = graph.upsertNode(ns, "topic:" + topic, "TOPIC", topic, null, null, "{}");
            graph.addEdge(corpusId, topicId, "CONTAINS");
            for (Candidate c : keep) {
                job.phase = "ingesting (" + topic + "): " + c.title();
                UUID node = ingest(job, ns, topicId, c, cacheDir);
                if (node != null) {
                    keptAll.put(c.key(), c);
                    nodeIds.put(c.key(), node);
                }
            }
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
        return scored.entrySet().stream()
                .sorted(Comparator.comparingDouble((Map.Entry<String, Double> e) ->
                        e.getValue() * (1 + Math.log10(1 + byKey.get(e.getKey()).citedBy()))).reversed())
                .limit(max)
                .map(e -> byKey.get(e.getKey()))
                .toList();
    }

    /** Apodex/AgentHarness hunts open-access PDFs for a thin topic; only allow-listed hosts pass. */
    private List<Candidate> deepDiscover(Job job, String topic, String mode, List<String> allowed) {
        DeepResearch deep = "KAGGLE".equalsIgnoreCase(mode) ? kaggleDeep : localDeep;
        String q = "Find freely available, open-access PDF documents (textbooks, lecture notes, theses, "
                + "technical reports, papers) about: " + topic + ". List each with its title and the "
                + "direct PDF URL. Only include documents that are legally free to download.";
        var findings = deep.research(List.of(new DeepResearch.Query("d", q)));
        List<Candidate> out = new ArrayList<>();
        for (var f : findings) {
            if (!f.ok()) {
                job.note("deep research failed for '" + topic + "': " + f.error());
                continue;
            }
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
                out.add(new Candidate(key, name, "Found by deep research for: " + topic, "", null, u, pdf,
                        "open host (verify)", "deep-research", topic, 0, null, List.of()));
            }
            job.note("deep research for '" + topic + "' proposed " + out.size() + " open PDFs");
        }
        return out;
    }

    /** Downloads and ingests one work; returns its graph node, or null if it was skipped. */
    private UUID ingest(Job job, String ns, UUID topicId, Candidate c, Path cacheDir) {
        try {
            Path file = cacheDir.resolve(c.key().replaceAll("[^A-Za-z0-9._-]", "_") + ".pdf");
            if (!Files.exists(file) || Files.size(file) == 0) {
                if (c.source().equals("arxiv") || c.pdfUrl().contains("arxiv.org")) {
                    Thread.sleep(1000); // polite to arXiv
                }
                download(c.pdfUrl(), file);
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
