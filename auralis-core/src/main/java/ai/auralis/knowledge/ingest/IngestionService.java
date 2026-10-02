package ai.auralis.knowledge.ingest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import ai.auralis.knowledge.store.KnowledgeStore;
import ai.auralis.knowledge.store.KnowledgeStore.NewChunk;

/**
 * Turns an evidence source (PDF, DOCX, HTML, text, ...) into searchable chunks.
 * Idempotent on content: re-ingesting identical text returns the existing id.
 */
@Service
public class IngestionService {

    public record IngestResult(UUID documentId, int chunks, boolean alreadyPresent) {
    }

    private final TextChunker chunker;
    private final Embedder embedder;
    private final KnowledgeStore store;

    public IngestionService(TextChunker chunker, Embedder embedder, KnowledgeStore store) {
        this.chunker = chunker;
        this.embedder = embedder;
        this.store = store;
    }

    public IngestResult ingest(Resource resource, String title, String sourceType, String uri) {
        String text = new TikaDocumentReader(resource).get().stream()
                .map(Document::getText)
                .reduce("", (a, b) -> a + "\n\n" + b)
                .strip();
        return ingestText(text, title, sourceType, uri);
    }

    public IngestResult ingestText(String text, String title, String sourceType, String uri) {
        if (text.isBlank()) {
            throw new IllegalArgumentException("no extractable text in '" + title + "'");
        }
        String hash = sha256(text);
        var existing = store.findDocumentByHash(hash);
        if (existing.isPresent()) {
            return new IngestResult(existing.get(), 0, true);
        }
        List<String> pieces = chunker.chunk(text);
        List<float[]> vectors = embedder.embedDocuments(pieces);
        List<NewChunk> chunks = IntStream.range(0, pieces.size())
                .mapToObj(i -> new NewChunk(pieces.get(i), vectors.get(i)))
                .toList();
        UUID id = store.saveDocument(title, sourceType, uri, hash, chunks);
        return new IngestResult(id, chunks.size(), false);
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
