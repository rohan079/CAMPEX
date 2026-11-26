package com.aivoice.lead.service.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

public class AdImageFormatterTest {

    public static byte[] png(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.ORANGE);
        g.fillRect(0, 0, w, h);
        g.setColor(Color.BLUE);
        g.fillRect(w / 4, h / 4, w / 2, h / 2);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static BufferedImage read(byte[] bytes) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    @Test
    void squareArtworkBecomesFullHdStoryWithoutCroppingIt() throws Exception {
        AdImageFormatter.Formatted f = AdImageFormatter.format(png(1024, 1024), "9:16");
        BufferedImage out = read(f.bytes());
        assertThat(f.mimeType()).isEqualTo("image/jpeg");
        assertThat(out.getWidth()).isEqualTo(1080);
        assertThat(out.getHeight()).isEqualTo(1920);
        assertThat(f.mode()).isEqualTo("fit");
        // The whole square sits in the middle: its blue centre is at the centre of the story.
        Color centre = new Color(out.getRGB(540, 960));
        assertThat(centre.getBlue()).isGreaterThan(200);
        assertThat(centre.getRed()).isLessThan(60);
    }

    @Test
    void nearlyVerticalArtworkIsCentreCroppedToTheExactSize() throws Exception {
        AdImageFormatter.Formatted f = AdImageFormatter.format(png(1024, 1792), "9:16");
        assertThat(f.mode()).isEqualTo("crop");
        assertThat(read(f.bytes()).getHeight()).isEqualTo(1920);
        AdImageFormatter.Formatted feed = AdImageFormatter.format(png(1200, 1200), "4:5");
        assertThat(read(feed.bytes()).getHeight()).isEqualTo(1350);
    }

    @Test
    void unreadableImagesAndUnknownAspectsAreRejected() {
        assertThatThrownBy(() -> AdImageFormatter.format(new byte[]{1, 2, 3}, "9:16")).hasMessageContaining("PNG or JPEG");
        assertThatThrownBy(() -> AdImageFormatter.format(new byte[]{1}, "16:9")).hasMessageContaining("Aspect");
    }
}
