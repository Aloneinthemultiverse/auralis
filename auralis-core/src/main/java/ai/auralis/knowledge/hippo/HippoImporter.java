package ai.auralis.knowledge.hippo;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

/**
 * Imports facts extracted in bulk on Kaggle (turbograph-research, kaggle/openie + kaggle/embed):
 * {@code <name>.corpus.tsv} maps row index to chunk id; {@code <name>.entities.txt/.f16} and
 * {@code <name>.facts.tsv/.f16} hold the entities, facts and their float16 embeddings.
 */
@Service
public class HippoImporter {

    private final HippoStore store;

    public HippoImporter(HippoStore store) {
        this.store = store;
    }

    public String importDir(Path corpusTsv, Path embDir, String name) throws Exception {
        long t0 = System.currentTimeMillis();
        Map<Integer, UUID> rowToChunk = new HashMap<>();
        for (String line : Files.readAllLines(corpusTsv)) {
            String[] f = line.split("\t", -1);
            if (f.length >= 4) {
                rowToChunk.put(Integer.parseInt(f[0]), UUID.fromString(f[3]));
            }
        }
        List<String> names = Files.readAllLines(embDir.resolve(name + ".entities.txt")).stream()
                .filter(s -> !s.isEmpty()).toList();
        ShortBuffer ent = f16(embDir.resolve(name + ".entities.f16"));
        Map<String, Long> entityId = new HashMap<>();
        for (String n : names) {
            entityId.put(n, store.upsertEntity(n, row(ent)));
        }
        List<String> facts = Files.readAllLines(embDir.resolve(name + ".facts.tsv")).stream()
                .filter(s -> !s.isEmpty()).toList();
        ShortBuffer fac = f16(embDir.resolve(name + ".facts.f16"));
        int saved = 0;
        for (String line : facts) {
            float[] v = row(fac);
            String[] t = line.split("\t", -1);
            UUID chunk = rowToChunk.get(Integer.parseInt(t[0]));
            Long s = entityId.get(t[1]);
            Long o = entityId.get(t[3]);
            if (chunk != null && s != null && o != null) {
                store.insertFact(chunk, s, t[2], o, v);
                saved++;
            }
        }
        rowToChunk.values().forEach(store::markDone);
        return "imported %d entities and %d facts for %d chunks in %d s"
                .formatted(names.size(), saved, rowToChunk.size(), (System.currentTimeMillis() - t0) / 1000);
    }

    private static ShortBuffer f16(Path p) throws Exception {
        return ByteBuffer.wrap(Files.readAllBytes(p)).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
    }

    private static float[] row(ShortBuffer b) {
        float[] v = new float[768];
        for (int i = 0; i < 768; i++) {
            v[i] = Float.float16ToFloat(b.get());
        }
        return v;
    }
}
