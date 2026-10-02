package ai.auralis.knowledge.ingest;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

import ai.auralis.config.AuralisProperties;

/**
 * Wraps the embedding model with nomic-embed-text's task prefixes: documents
 * and queries are embedded differently, which noticeably improves recall.
 */
@Component
public class Embedder {

    private static final String DOC_PREFIX = "search_document: ";
    private static final String QUERY_PREFIX = "search_query: ";

    private final EmbeddingModel model;
    private final int batchSize;
    private final int dimensions;

    public Embedder(EmbeddingModel model, AuralisProperties props) {
        this.model = model;
        this.batchSize = props.embedding().batchSize();
        this.dimensions = props.embedding().dimensions();
    }

    public List<float[]> embedDocuments(List<String> texts) {
        List<float[]> out = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += batchSize) {
            List<String> batch = texts.subList(i, Math.min(texts.size(), i + batchSize))
                    .stream().map(t -> DOC_PREFIX + t).toList();
            model.embed(batch).forEach(v -> out.add(checked(v)));
        }
        return out;
    }

    public float[] embedQuery(String query) {
        return checked(model.embed(QUERY_PREFIX + query));
    }

    private float[] checked(float[] v) {
        if (v.length != dimensions) {
            throw new IllegalStateException(
                    "embedding has " + v.length + " dims, schema expects " + dimensions);
        }
        return v;
    }
}
