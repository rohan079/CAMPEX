package com.aivoice.lead.service.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class Mp4InspectorTest {

    @TempDir Path dir;

    /** A minimal MP4: ftyp + moov/mvhd with the given duration. */
    public static byte[] mp4(String brand, int timescale, long duration, boolean version1) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(16); out.write("ftyp".getBytes(StandardCharsets.ISO_8859_1)); out.write(brand.getBytes(StandardCharsets.ISO_8859_1)); out.writeInt(0);
        int mvhdSize = version1 ? 8 + 4 + 8 + 8 + 4 + 8 : 8 + 4 + 4 + 4 + 4 + 4;
        out.writeInt(8 + mvhdSize); out.write("moov".getBytes(StandardCharsets.ISO_8859_1));
        out.writeInt(mvhdSize); out.write("mvhd".getBytes(StandardCharsets.ISO_8859_1));
        out.writeByte(version1 ? 1 : 0); out.write(new byte[3]);
        if (version1) { out.writeLong(0); out.writeLong(0); out.writeInt(timescale); out.writeLong(duration); }
        else { out.writeInt(0); out.writeInt(0); out.writeInt(timescale); out.writeInt((int) duration); }
        return bytes.toByteArray();
    }

    private Path write(byte[] data) throws IOException {
        Path p = dir.resolve("v" + System.nanoTime());
        Files.write(p, data);
        return p;
    }

    @Test
    void readsDurationAndContainerType() throws Exception {
        assertThat(Mp4Inspector.inspect(write(mp4("isom", 1000, 30_000, false)))).isEqualTo(new Mp4Inspector.Info("video/mp4", 30_000));
        assertThat(Mp4Inspector.inspect(write(mp4("qt  ", 600, 36_000, true)))).isEqualTo(new Mp4Inspector.Info("video/quicktime", 60_000));
    }

    @Test
    void rejectsFilesThatAreNotVideos() throws Exception {
        assertThatThrownBy(() -> Mp4Inspector.inspect(write("<html>not a video</html>".getBytes()))).isInstanceOf(Mp4Inspector.InvalidVideoException.class);
        byte[] truncated = mp4("isom", 1000, 30_000, false);
        assertThatThrownBy(() -> Mp4Inspector.inspect(write(java.util.Arrays.copyOf(truncated, truncated.length - 10))))
                .isInstanceOf(Mp4Inspector.InvalidVideoException.class);
    }
}
