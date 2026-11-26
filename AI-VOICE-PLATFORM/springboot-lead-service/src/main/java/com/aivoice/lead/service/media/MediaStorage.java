package com.aivoice.lead.service.media;

import com.aivoice.lead.config.MediaProperties;
import com.aivoice.lead.service.Exceptions.ConflictException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Stores creative videos on a persistent directory ({@code campex.media.dir}). Keys are generated server-side
 * ({@code clientId/uuid.ext}); client input never becomes part of a path.
 */
@Component
public class MediaStorage {

    public record Stored(String key, long size, String sha256, Path path) {
    }

    private final MediaProperties props;

    public MediaStorage(MediaProperties props) {
        this.props = props;
    }

    public boolean enabled() {
        return props.videoEnabled();
    }

    /** Streams {@code in} to a temp file in the media directory, enforcing the size limit while copying. */
    public Stored storeTemp(UUID clientId, InputStream in, String extension) {
        requireEnabled();
        try {
            Path dir = root().resolve(clientId.toString());
            Files.createDirectories(dir);
            Path temp = Files.createTempFile(dir, "upload-", ".part");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long max = (long) props.maxVideoMb() * 1024 * 1024;
            long size = 0;
            try (DigestInputStream src = new DigestInputStream(in, digest); var out = Files.newOutputStream(temp)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = src.read(buf)) > 0) {
                    size += n;
                    if (size > max) {
                        throw new IllegalArgumentException("Videos must be " + props.maxVideoMb() + " MB or smaller");
                    }
                    out.write(buf, 0, n);
                }
            } catch (RuntimeException | IOException e) {
                Files.deleteIfExists(temp);
                throw e;
            }
            String key = clientId + "/" + UUID.randomUUID() + "." + extension;
            return new Stored(key, size, HexFormat.of().formatHex(digest.digest()), temp);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store the video", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Moves a validated temp upload to its final key. */
    public Path commit(Stored stored) {
        try {
            Path target = path(stored.key());
            Files.move(stored.path(), target, StandardCopyOption.ATOMIC_MOVE);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store the video", e);
        }
    }

    public void discard(Stored stored) {
        try {
            Files.deleteIfExists(stored.path());
        } catch (IOException ignored) {
            // best effort
        }
    }

    public Path path(String key) {
        requireEnabled();
        Path p = root().resolve(key).normalize();
        if (!p.startsWith(root())) throw new IllegalArgumentException("Invalid media key");
        return p;
    }

    private Path root() {
        return Path.of(props.dir()).toAbsolutePath().normalize();
    }

    private void requireEnabled() {
        if (!enabled()) {
            throw new ConflictException("Video upload is not configured on this server (set CAMPEX_MEDIA_DIR to a persistent volume)");
        }
    }

    public int maxVideoSeconds() {
        return props.maxVideoSeconds();
    }

    public int maxVideoMb() {
        return props.maxVideoMb();
    }
}
