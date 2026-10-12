package com.morales.chemicallab.security.files;

import org.apache.pdfbox.pdmodel.*;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;

/** Real, minimal, innocuous formats instead of pretend document/image byte arrays. */
public final class FileFixtures {
    private FileFixtures() {}
    public static byte[] pdf() {
        try (var doc = new PDDocument(); var out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage()); doc.save(out); return out.toByteArray();
        } catch (Exception ex) { throw new AssertionError(ex); }
    }
    public static byte[] ppt() {
        try (var show = new HSLFSlideShow(); var out = new ByteArrayOutputStream()) {
            show.createSlide(); show.write(out); return out.toByteArray();
        } catch (Exception ex) { throw new AssertionError(ex); }
    }
    public static byte[] pptx() {
        try (var show = new XMLSlideShow(); var out = new ByteArrayOutputStream()) {
            // POI's bundled template thumbnail has trailing bytes; replace it with a complete fixture JPEG.
            for (var part : show.getPackage().getParts()) {
                if (part.getPartName().getName().startsWith("/docProps/thumbnail."))
                    try (var image = part.getOutputStream()) { image.write(jpeg()); }
            }
            show.createSlide(); show.write(out); return out.toByteArray();
        } catch (Exception ex) { throw new AssertionError(ex); }
    }
    public static byte[] png() { return image("png"); }
    public static byte[] jpeg() { return image("jpeg"); }
    private static byte[] image(String format) {
        try (var out = new ByteArrayOutputStream()) {
            ImageIO.setUseCache(false);
            ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), format, out);
            return out.toByteArray();
        } catch (Exception ex) { throw new AssertionError(ex); }
    }
}
