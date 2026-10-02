package ai.auralis.knowledge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Stops knowledge collection before the machine runs out of room: requires a
 * minimum of free disk space and caps the knowledge database's size. Bulk
 * harvests, discovery imports and problem collection all call {@link #check}
 * before each download.
 */
@Component
public class StorageGuard {

    /** Thrown when collection must pause for lack of storage. */
    public static final class StorageFullException extends IllegalStateException {
        public StorageFullException(String message) {
            super(message);
        }
    }

    private final JdbcClient jdbc;
    private final long minFreeBytes;
    private final long maxDbBytes;
    private final Path probe = Path.of(System.getProperty("java.io.tmpdir"));

    public StorageGuard(JdbcClient jdbc,
                        @Value("${auralis.storage.min-free-gb:10}") double minFreeGb,
                        @Value("${auralis.storage.max-db-gb:15}") double maxDbGb) {
        this.jdbc = jdbc;
        this.minFreeBytes = (long) (minFreeGb * (1L << 30));
        this.maxDbBytes = (long) (maxDbGb * (1L << 30));
    }

    public record Usage(double freeGb, double dbGb, double minFreeGb, double maxDbGb) {
    }

    public Usage usage() {
        long free;
        try {
            free = Files.getFileStore(probe).getUsableSpace();
        } catch (IOException e) {
            free = Long.MAX_VALUE;
        }
        long db = jdbc.sql("SELECT pg_database_size(current_database())").query(Long.class).single();
        return new Usage(free / (double) (1L << 30), db / (double) (1L << 30),
                minFreeBytes / (double) (1L << 30), maxDbBytes / (double) (1L << 30));
    }

    /** @throws StorageFullException when free space or the database cap would be exceeded */
    public void check() {
        Usage u = usage();
        if (u.freeGb() * (1L << 30) < minFreeBytes) {
            throw new StorageFullException(String.format(
                    "paused: only %.1f GB free on disk (minimum %.0f GB) — free space or raise auralis.storage.min-free-gb",
                    u.freeGb(), u.minFreeGb()));
        }
        if (u.dbGb() * (1L << 30) > maxDbBytes) {
            throw new StorageFullException(String.format(
                    "paused: knowledge base is %.1f GB (cap %.0f GB) — raise auralis.storage.max-db-gb to grow further",
                    u.dbGb(), u.maxDbGb()));
        }
    }
}
