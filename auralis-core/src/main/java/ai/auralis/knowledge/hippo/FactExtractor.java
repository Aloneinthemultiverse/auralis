package ai.auralis.knowledge.hippo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import ai.auralis.knowledge.ingest.Embedder;

/**
 * HippoRAG offline indexing for new knowledge: the local model extracts named entities
 * and (subject, relation, object) facts from each chunk; entities and facts are embedded
 * and stored. New documents are queued automatically; older chunks are backfilled on
 * request or imported in bulk from the Kaggle job (see {@link HippoImporter}).
 */
@Service
public class FactExtractor {

    private static final Logger log = LoggerFactory.getLogger(FactExtractor.class);
    private static final Pattern JSON = Pattern.compile("\\{.*\\}", Pattern.DOTALL);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final ChatClient chat;
    private final Embedder embedder;
    private final HippoStore store;
    private final LinkedBlockingQueue<Map.Entry<UUID, String>> queue = new LinkedBlockingQueue<>();
    private volatile int processed;

    public FactExtractor(ChatClient.Builder builder, Embedder embedder, HippoStore store) {
        this.chat = builder.build();
        this.embedder = embedder;
        this.store = store;
    }

    /** Queues chunks for extraction (called after a document is ingested). */
    public void enqueue(List<Map.Entry<UUID, String>> chunks) {
        queue.addAll(chunks);
    }

    /** Queues up to {@code limit} chunks that have no facts yet. */
    public int backfill(int limit) {
        List<Map.Entry<UUID, String>> todo = store.pending(limit);
        queue.addAll(todo);
        return todo.size();
    }

    public String status() {
        return "%d chunks queued, %d extracted since start".formatted(queue.size(), processed);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startWorker() {
        Thread.ofVirtual().name("fact-extractor").start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    var job = queue.take();
                    extract(job.getKey(), job.getValue());
                    processed++;
                } catch (InterruptedException e) {
                    return;
                } catch (RuntimeException e) {
                    log.warn("fact extraction failed: {}", e.getMessage());
                }
            }
        });
    }

    void extract(UUID chunkId, String text) {
        // Sent as a plain message, not a template: passages (RTL especially) are full of braces.
        String prompt = """
                Extract a knowledge graph from the passage.

                1. List the named entities (components, standards, people, places, organisations,
                   works, numbers that matter).
                2. List factual triples [subject, relation, object]. Each triple must contain at
                   least one of the named entities; use full names (resolve pronouns); keep
                   relations short (e.g. "connects to", "uses", "is part of", "limits").

                Answer with JSON only: {"entities": [...], "triples": [[subject, relation, object], ...]}

                Passage: """ + (text.length() > 6000 ? text.substring(0, 6000) : text) + " /no_think";
        String out = chat.prompt().messages(new UserMessage(prompt)).call().content();
        List<String[]> triples = parse(out == null ? "" : out);
        if (triples.isEmpty()) {
            store.markDone(chunkId);
            return;
        }
        Map<String, float[]> entityEmb = new LinkedHashMap<>();
        for (String[] t : triples) {
            entityEmb.putIfAbsent(t[0], null);
            entityEmb.putIfAbsent(t[2], null);
        }
        List<String> names = new ArrayList<>(entityEmb.keySet());
        List<float[]> ev = embedder.embedDocuments(names);
        for (int i = 0; i < names.size(); i++) {
            entityEmb.put(names.get(i), ev.get(i));
        }
        List<float[]> fv = embedder.embedDocuments(triples.stream().map(t -> t[0] + " " + t[1] + " " + t[2]).toList());
        List<HippoStore.NewFact> facts = new ArrayList<>();
        for (int i = 0; i < triples.size(); i++) {
            String[] t = triples.get(i);
            facts.add(new HippoStore.NewFact(t[0], t[1], t[2], fv.get(i)));
        }
        store.saveFacts(chunkId, facts, entityEmb);
    }

    /** Triples from the model's JSON, names normalised (lower case, single spaces). */
    static List<String[]> parse(String out) {
        List<String[]> triples = new ArrayList<>();
        Matcher m = JSON.matcher(out);
        if (!m.find()) {
            return triples;
        }
        try {
            JsonNode d = MAPPER.readTree(m.group());
            for (JsonNode t : d.path("triples")) {
                if (t.isArray() && t.size() == 3) {
                    String s = norm(t.get(0).asText());
                    String r = norm(t.get(1).asText());
                    String o = norm(t.get(2).asText());
                    if (!s.isEmpty() && !r.isEmpty() && !o.isEmpty()) {
                        triples.add(new String[] {s, r, o});
                    }
                }
            }
        } catch (Exception e) {
            return triples;
        }
        return triples;
    }

    static String norm(String s) {
        return String.join(" ", s.toLowerCase().trim().split("\\s+"));
    }
}
