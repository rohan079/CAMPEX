package com.aivoice.lead.service.media;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/**
 * Turns any PNG/JPEG into an exact Meta ad size, saved as JPEG (a format Meta always renders).
 *
 * <p>A source close to the target shape is centre-cropped. A source far from it (a square photo going to 9:16) is
 * fitted whole, over a blurred and darkened copy of itself, so nothing important is cut off.
 */
public final class AdImageFormatter {

    /** Meta's recommended sizes: feed square, feed portrait, Stories/Reels full screen. */
    public static final Map<String, int[]> SIZES = Map.of(
            "1:1", new int[]{1080, 1080},
            "4:5", new int[]{1080, 1350},
            "9:16", new int[]{1080, 1920});

    /** Centre-crop when the source ratio is within this fraction of the target; otherwise fit over a blur. */
    static final double CROP_TOLERANCE = 0.12;

    public record Formatted(byte[] bytes, String mimeType, int width, int height, String mode) {
    }

    private AdImageFormatter() {
    }

    public static Formatted format(byte[] source, String aspect) {
        int[] size = SIZES.get(aspect);
        if (size == null) {
            throw new IllegalArgumentException("Aspect must be one of 1:1, 4:5, 9:16");
        }
        BufferedImage src;
        try {
            src = ImageIO.read(new ByteArrayInputStream(source));
        } catch (IOException e) {
            src = null;
        }
        if (src == null) {
            throw new IllegalArgumentException("This image can't be resized. Use a PNG or JPEG.");
        }
        int w = size[0];
        int h = size[1];
        double target = (double) w / h;
        double ratio = (double) src.getWidth() / src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, w, h);
            String mode;
            if (Math.abs(ratio - target) / target <= CROP_TOLERANCE) {
                drawCover(g, src, w, h);
                mode = "crop";
            } else {
                // Blurred background: cover-scale a tiny copy back up, then darken it.
                int sw = Math.max(1, w / 24);
                int sh = Math.max(1, h / 24);
                BufferedImage tiny = new BufferedImage(sw, sh, BufferedImage.TYPE_INT_RGB);
                Graphics2D tg = tiny.createGraphics();
                tg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                drawCover(tg, src, sw, sh);
                tg.dispose();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(tiny, 0, 0, w, h, null);
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.35f));
                g.fillRect(0, 0, w, h);
                g.setComposite(AlphaComposite.SrcOver);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                double scale = Math.min((double) w / src.getWidth(), (double) h / src.getHeight());
                int dw = (int) Math.round(src.getWidth() * scale);
                int dh = (int) Math.round(src.getHeight() * scale);
                g.drawImage(src, (w - dw) / 2, (h - dh) / 2, dw, dh, null);
                mode = "fit";
            }
            return new Formatted(jpeg(out), "image/jpeg", w, h, mode);
        } finally {
            g.dispose();
        }
    }

    private static void drawCover(Graphics2D g, BufferedImage src, int w, int h) {
        double scale = Math.max((double) w / src.getWidth(), (double) h / src.getHeight());
        int dw = (int) Math.ceil(src.getWidth() * scale);
        int dh = (int) Math.ceil(src.getHeight() * scale);
        g.drawImage(src, (w - dw) / 2, (h - dh) / 2, dw, dh, null);
    }

    private static byte[] jpeg(BufferedImage image) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
                writer.setOutput(out);
                ImageWriteParam param = writer.getDefaultWriteParam();
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(0.9f);
                writer.write(null, new IIOImage(image, null, null), param);
            } finally {
                writer.dispose();
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not encode the image", e);
        }
    }
}
