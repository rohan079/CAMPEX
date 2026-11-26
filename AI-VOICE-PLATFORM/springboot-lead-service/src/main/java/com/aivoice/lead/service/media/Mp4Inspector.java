package com.aivoice.lead.service.media;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Minimal ISO-BMFF (MP4 / QuickTime) reader: confirms the container type and reads the movie duration from
 * {@code moov/mvhd}. It never decodes media; it only walks box headers, so a hostile file cannot make it allocate.
 */
public final class Mp4Inspector {

    public record Info(String mimeType, long durationMs) {
    }

    public static class InvalidVideoException extends Exception {
        public InvalidVideoException(String message) {
            super(message);
        }
    }

    private Mp4Inspector() {
    }

    public static Info inspect(Path file) throws InvalidVideoException {
        try (RandomAccessFile in = new RandomAccessFile(file.toFile(), "r")) {
            long length = in.length();
            if (length < 16) throw new InvalidVideoException("The file is too small to be a video.");
            in.seek(4);
            String first = type(in);
            if (!"ftyp".equals(first)) throw new InvalidVideoException("Upload an MP4 or MOV video.");
            in.seek(8);
            String brand = type(in);
            String mime = brand.startsWith("qt") ? "video/quicktime" : "video/mp4";
            long moov = find(in, 0, length, "moov");
            if (moov < 0) throw new InvalidVideoException("The video has no movie header; re-export it as MP4.");
            long[] moovBox = header(in, moov, length);
            long mvhd = find(in, moov + moovBox[1], moov + moovBox[0], "mvhd");
            if (mvhd < 0) throw new InvalidVideoException("The video has no duration information.");
            long[] mvhdBox = header(in, mvhd, length);
            in.seek(mvhd + mvhdBox[1]);
            int version = in.readUnsignedByte();
            in.skipBytes(3);
            long timescale;
            long duration;
            if (version == 1) {
                in.skipBytes(16);
                timescale = Integer.toUnsignedLong(in.readInt());
                duration = in.readLong();
            } else {
                in.skipBytes(8);
                timescale = Integer.toUnsignedLong(in.readInt());
                duration = Integer.toUnsignedLong(in.readInt());
            }
            if (timescale == 0 || duration <= 0) throw new InvalidVideoException("The video duration could not be read.");
            return new Info(mime, duration * 1000 / timescale);
        } catch (IOException e) {
            throw new InvalidVideoException("The video could not be read.");
        }
    }

    /** Offset of the first box of {@code wanted} type between {@code from} and {@code to}, or -1. */
    private static long find(RandomAccessFile in, long from, long to, String wanted) throws IOException, InvalidVideoException {
        long pos = from;
        int guard = 0;
        while (pos + 8 <= to && guard++ < 10_000) {
            long[] h = header(in, pos, to);
            in.seek(pos + 4);
            if (wanted.equals(type(in))) return pos;
            pos += h[0];
        }
        return -1;
    }

    /** {size, headerLength} of the box at {@code pos}. */
    private static long[] header(RandomAccessFile in, long pos, long limit) throws IOException, InvalidVideoException {
        in.seek(pos);
        long size = Integer.toUnsignedLong(in.readInt());
        int headerLength = 8;
        if (size == 1) {
            in.seek(pos + 8);
            size = in.readLong();
            headerLength = 16;
        } else if (size == 0) {
            size = limit - pos;
        }
        if (size < headerLength || pos + size > limit) throw new InvalidVideoException("The video file is damaged or truncated.");
        return new long[]{size, headerLength};
    }

    private static String type(RandomAccessFile in) throws IOException {
        byte[] b = new byte[4];
        in.readFully(b);
        return new String(b, StandardCharsets.ISO_8859_1);
    }
}
