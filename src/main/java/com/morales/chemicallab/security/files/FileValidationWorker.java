package com.morales.chemicallab.security.files;

import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdfparser.PDFParser;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.xslf.usermodel.XMLSlideShow;

import javax.imageio.*;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.xml.stream.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Internal subprocess entry point. No Spring context, network, rendering or archive extraction. */
public final class FileValidationWorker {
    static final int MAX_DIMENSION = 8192;
    static final long MAX_PIXELS = 16_000_000;
    static final int MAX_ENTRIES = 512;
    static final int MAX_ENTRY_BYTES = 16 * 1024 * 1024;
    static final int MAX_EXPANDED_BYTES = 40 * 1024 * 1024;
    static final int MAX_RATIO = 100;
    static final String PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation";
    static final String PPT = "application/vnd.ms-powerpoint";
    private static final Set<String> PDF_FORBIDDEN = Set.of("A", "AA", "OpenAction", "JS", "JavaScript",
            "AcroForm", "XFA", "EmbeddedFiles", "EF", "RichMedia", "RichMediaContent", "3DD",
            "3DA", "Launch", "SubmitForm", "ImportData", "GoToR", "GoToE", "URI", "Movie", "Sound");
    private static final Set<String> PDF_TYPES = Set.of("Filespec", "EmbeddedFile", "RichMedia", "Movie", "Sound", "3D");
    private long documentPixels;

    public static void main(String[] args) {
        try {
            if (args.length != 4) throw FileValidationService.invalid();
            Path input = Path.of(args[0]), output = Path.of(args[1]);
            int limit = Integer.parseInt(args[3]);
            if (limit != FileValidationService.MATERIAL_BYTES && limit != FileValidationService.SNAPSHOT_BYTES) throw FileValidationService.invalid();
            FileValidationService.checkLength(Files.size(input), limit);
            byte[] bytes = Files.readAllBytes(input);
            var result = new FileValidationWorker().validate(bytes, args[2], limit);
            try (var data = new DataOutputStream(Files.newOutputStream(output, StandardOpenOption.CREATE_NEW))) {
                data.writeUTF(result.contentType()); data.write(result.data());
            }
        } catch (Throwable failure) {
            // No parser messages, user bytes, local paths or filenames in output/logs.
            System.exit(2);
        }
    }

    ValidatedFile validate(byte[] bytes, String candidate, int limit) throws Exception {
        String detected = detect(bytes);
        if (!candidate.equals("auto") && !candidate.equals(detected)
                && !(candidate.equals("image") && detected.startsWith("image/"))) throw FileValidationService.invalid();
        byte[] stored = switch (detected) {
            case "image/png", "image/jpeg" -> image(bytes, detected, limit);
            case "application/pdf" -> { pdf(bytes); yield bytes; }
            case PPT -> { ppt(bytes); yield bytes; }
            case PPTX -> { pptx(bytes); yield bytes; }
            default -> throw FileValidationService.invalid();
        };
        return new ValidatedFile(stored, detected, "");
    }

    private String detect(byte[] bytes) {
        if (starts(bytes, new byte[]{(byte)137,80,78,71,13,10,26,10})) return "image/png";
        if (starts(bytes, new byte[]{(byte)255,(byte)216,(byte)255})) return "image/jpeg";
        if (starts(bytes, "%PDF-".getBytes(java.nio.charset.StandardCharsets.US_ASCII))) return "application/pdf";
        if (starts(bytes, new byte[]{(byte)208,(byte)207,17,(byte)224,(byte)161,(byte)177,26,(byte)225})) return PPT;
        if (starts(bytes, new byte[]{80,75,3,4})) return PPTX;
        throw FileValidationService.invalid();
    }
    private boolean starts(byte[] data, byte[] prefix) {
        return data.length >= prefix.length && Arrays.equals(Arrays.copyOf(data, prefix.length), prefix);
    }

    byte[] image(byte[] bytes, String type, int limit) throws Exception {
        ImageIO.setUseCache(false);
        // ImageIO can recover truncated input; require complete PNG chunks/CRC or JPEG EOI as well.
        if (type.equals("image/png")) pngEnvelope(bytes);
        else if (bytes.length < 4 || bytes[bytes.length - 2] != (byte)255 || bytes[bytes.length - 1] != (byte)217)
            throw FileValidationService.invalid();
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw FileValidationService.invalid();
            ImageReader reader = readers.next();
            try {
                // Only the JDK PNG/JPEG codecs; no optional plugins for uploaded types.
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!(type.equals("image/png") && format.equals("png"))
                        && !(type.equals("image/jpeg") && (format.equals("jpeg") || format.equals("jpg")))) throw FileValidationService.invalid();
                reader.setInput(input, true, true);
                reader.addIIOReadWarningListener((source, warning) -> { throw FileValidationService.invalid(); });
                int width = reader.getWidth(0), height = reader.getHeight(0);
                dimensions(width, height);
                documentPixels += (long)width * height;
                if (documentPixels > 32_000_000) throw FileValidationService.invalid();
                BufferedImage decoded = reader.read(0);
                if (decoded == null || decoded.getWidth() != width || decoded.getHeight() != height) throw FileValidationService.invalid();
                // New raster drops profiles, EXIF, comments and other metadata.
                BufferedImage raster = new BufferedImage(width, height,
                        type.equals("image/png") ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
                var graphics = raster.createGraphics();
                try { graphics.drawImage(decoded, 0, 0, null); } finally { graphics.dispose(); }
                var output = new LimitedOutput(limit);
                if (!ImageIO.write(raster, format, output)) throw FileValidationService.invalid();
                return output.toByteArray();
            } finally { reader.dispose(); }
        }
    }

    static void dimensions(int width, int height) {
        if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION
                || (long)width * height > MAX_PIXELS) throw FileValidationService.invalid();
    }
    private void pngEnvelope(byte[] bytes) {
        int pos = 8, chunks = 0; boolean header = false, image = false, ended = false;
        while (pos < bytes.length) {
            if (++chunks > 4096 || bytes.length - pos < 12) throw FileValidationService.invalid();
            long length = Integer.toUnsignedLong(beInt(bytes, pos));
            if (length > bytes.length - pos - 12L) throw FileValidationService.invalid();
            int size = (int)length;
            String kind = new String(bytes, pos + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
            var crc = new CRC32(); crc.update(bytes, pos + 4, size + 4);
            if (crc.getValue() != Integer.toUnsignedLong(beInt(bytes, pos + 8 + size))) throw FileValidationService.invalid();
            if (!header) {
                if (!kind.equals("IHDR") || size != 13) throw FileValidationService.invalid();
                dimensions(beInt(bytes, pos + 8), beInt(bytes, pos + 12)); header = true;
            } else if (kind.equals("IHDR")) throw FileValidationService.invalid();
            if (kind.equals("acTL") || kind.equals("fcTL") || kind.equals("fdAT")) throw FileValidationService.invalid();
            if (kind.equals("IDAT")) image = true;
            pos += size + 12;
            if (kind.equals("IEND")) {
                if (size != 0 || pos != bytes.length || !image) throw FileValidationService.invalid();
                ended = true; break;
            }
        }
        if (!header || !ended) throw FileValidationService.invalid();
    }
    private int beInt(byte[] bytes, int offset) {
        return java.nio.ByteBuffer.wrap(bytes, offset, 4).getInt();
    }

    private void pdf(byte[] bytes) throws Exception {
        String end = new String(bytes, Math.max(0, bytes.length - 128), Math.min(128, bytes.length), java.nio.charset.StandardCharsets.ISO_8859_1);
        if (!end.stripTrailing().endsWith("%%EOF")) throw FileValidationService.invalid();
        var parser = new StrictPdfParser(new RandomAccessReadBuffer(bytes));
        try (PDDocument document = parser.parse()) {
            if (document.isEncrypted() || document.getNumberOfPages() < 1 || document.getNumberOfPages() > 300) throw FileValidationService.invalid();
            Set<COSBase> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            var budget = new long[]{0,0};
            walkPdf(document.getDocument().getTrailer(), visited, budget, 0);
            // Include unreferenced and historical objects; don't approve hidden embedded payloads.
            if (document.getDocument().getXrefTable().size() > 100_000) throw FileValidationService.invalid();
            for (var key : document.getDocument().getXrefTable().keySet())
                walkPdf(document.getDocument().getObjectFromPool(key), visited, budget, 0);
            for (var page : document.getPages()) {
                if (page.getMediaBox().getWidth() <= 0 || page.getMediaBox().getHeight() <= 0) throw FileValidationService.invalid();
                var content = new PDFStreamParser(page);
                int tokens = 0;
                while (content.parseNextToken() != null) if (++tokens > 500_000) throw FileValidationService.invalid();
            }
        }
    }
    private void walkPdf(COSBase node, Set<COSBase> visited, long[] budget, int depth) throws Exception {
        if (node == null || !visited.add(node)) return;
        if (depth > 64 || ++budget[0] > 100_000) throw FileValidationService.invalid();
        if (node instanceof COSObject object) walkPdf(object.getObject(), visited, budget, depth + 1);
        else if (node instanceof COSArray array) for (COSBase value : array) walkPdf(value, visited, budget, depth + 1);
        else if (node instanceof COSDictionary dictionary) {
            if (PDF_TYPES.contains(dictionary.getNameAsString(COSName.TYPE, "")) || PDF_TYPES.contains(dictionary.getNameAsString(COSName.SUBTYPE, ""))) throw FileValidationService.invalid();
            for (COSName key : dictionary.keySet()) {
                if (PDF_FORBIDDEN.contains(key.getName())) throw FileValidationService.invalid();
                walkPdf(dictionary.getItem(key), visited, budget, depth + 1);
            }
            if (dictionary instanceof COSStream stream) {
                if (stream.containsKey(COSName.F) || stream.containsKey(COSName.F_FILTER)) throw FileValidationService.invalid();
                try (InputStream decoded = stream.createInputStream()) {
                    byte[] buffer = new byte[8192]; long entry = 0; int read;
                    while ((read = decoded.read(buffer)) >= 0) {
                        entry += read; budget[1] += read;
                        if (entry > MAX_ENTRY_BYTES || budget[1] > MAX_EXPANDED_BYTES) throw FileValidationService.invalid();
                    }
                }
            }
        }
    }

    private void ppt(byte[] bytes) throws Exception {
        if (bytes.length < 512) throw FileValidationService.invalid();
        int shift = (bytes[30] & 255) | ((bytes[31] & 255) << 8);
        if ((shift != 9 && shift != 12) || bytes.length % (1 << shift) != 0) throw FileValidationService.invalid();
        org.apache.poi.util.IOUtils.setByteArrayMaxOverride(MAX_ENTRY_BYTES);
        HSLFSlideShow.setMaxRecordLength(MAX_ENTRY_BYTES);
        try (var fs = new POIFSFileSystem(new ByteArrayInputStream(bytes))) {
            var root = fs.getRoot();
            if (!root.hasEntry("PowerPoint Document") || !root.hasEntry("Current User")) throw FileValidationService.invalid();
            Set<String> allowed = Set.of("PowerPoint Document", "Current User", "Pictures", "\u0005SummaryInformation", "\u0005DocumentSummaryInformation");
            for (var entry : root) if (!allowed.contains(entry.getName()) || entry.isDirectoryEntry()) throw FileValidationService.invalid();
            try (var show = new HSLFSlideShow(fs)) {
                pptEnvelope(show.getSlideShowImpl().getUnderlyingBytes(), 0,
                        show.getSlideShowImpl().getUnderlyingBytes().length, 0, new int[]{0});
                if (show.getSlideShowImpl().getDocumentEncryptionAtom() != null || show.getEmbeddedObjects().length != 0
                        || show.getSoundData().length != 0) throw FileValidationService.invalid();
                int[] count = {0};
                for (var record : show.getSlideShowImpl().getRecords()) pptRecords(record, 0, count);
                if (show.getSlides().isEmpty() || show.getSlides().size() > 300) throw FileValidationService.invalid();
                for (var slide : show.getSlides()) if (slide.getShapes().size() > 2000) throw FileValidationService.invalid();
                for (var picture : show.getPictureData()) {
                    String mime = picture.getType().contentType;
                    if (!Set.of("image/png", "image/jpeg").contains(mime)) throw FileValidationService.invalid();
                    image(picture.getData(), mime, MAX_ENTRY_BYTES);
                }
            }
        }
    }
    private void pptRecords(org.apache.poi.hslf.record.Record record, int depth, int[] count) {
        if (depth > 64 || ++count[0] > 100_000) throw FileValidationService.invalid();
        String kind = record.getClass().getSimpleName();
        if (record instanceof org.apache.poi.hslf.record.VBAInfoAtom vba && (vba.isHasMacros() || vba.getPersistIdRef() != 0))
            throw FileValidationService.invalid();
        if ((kind.startsWith("Ex") && !Set.of("ExObjList", "ExObjListAtom").contains(kind))
                || (kind.contains("VBA") && !Set.of("VBAInfoContainer", "VBAInfoAtom").contains(kind))
                || kind.contains("Encryption") || kind.equals("InteractiveInfo"))
            throw new IllegalArgumentException("Unsupported PPT record: " + kind);
        var children = record.getChildRecords();
        if (children != null) for (var child : children) pptRecords(child, depth + 1, count);
    }

    private void pptEnvelope(byte[] data, int start, int end, int depth, int[] records) {
        if (depth > 64) throw FileValidationService.invalid();
        int pos = start;
        while (pos < end) {
            if (end - pos < 8 || ++records[0] > 100_000) throw FileValidationService.invalid();
            long size = Integer.toUnsignedLong(java.nio.ByteBuffer.wrap(data, pos + 4, 4)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt());
            if (size > end - pos - 8L) throw FileValidationService.invalid();
            int next = pos + 8 + (int)size;
            if ((data[pos] & 15) == 15) pptEnvelope(data, pos + 8, next, depth + 1, records);
            pos = next;
        }
    }

    private void pptx(byte[] bytes) throws Exception {
        var names = new HashSet<String>(); long total = 0;
        // Require a consistent central directory; a streaming reader alone accepts a missing EOCD.
        var centralNames = new HashSet<String>();
        try (var central = org.apache.commons.compress.archivers.zip.ZipFile.builder()
                .setSeekableByteChannel(new org.apache.commons.compress.utils.SeekableInMemoryByteChannel(bytes)).get()) {
            var entries = central.getEntries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (centralNames.size() >= MAX_ENTRIES || !centralNames.add(entry.getName()) || !safeEntry(entry.getName())
                        || entry.getGeneralPurposeBit().usesEncryption() || entry.getSize() > MAX_ENTRY_BYTES
                        || entry.getSize() < 0 || !central.canReadEntryData(entry)) throw FileValidationService.invalid();
            }
        }
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (names.size() >= MAX_ENTRIES || !names.add(name) || !safeEntry(name) || entry.isDirectory()) throw FileValidationService.invalid();
                var content = new LimitedOutput(MAX_ENTRY_BYTES);
                byte[] buffer = new byte[8192]; int read;
                while ((read = zip.read(buffer)) >= 0) {
                    total += read;
                    if (total > MAX_EXPANDED_BYTES) throw FileValidationService.invalid();
                    content.write(buffer, 0, read);
                }
                zip.closeEntry(); // CRC and actual sizes, including data descriptors.
                if (entry.getCompressedSize() < 0 || content.size() > Math.max(1, entry.getCompressedSize()) * MAX_RATIO) throw FileValidationService.invalid();
                byte[] data = content.toByteArray();
                if (name.endsWith(".xml") || name.endsWith(".rels")) xml(data, name);
                else if ((name.startsWith("ppt/media/") || name.startsWith("docProps/thumbnail."))
                        && (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg"))) {
                    String type = detect(data);
                    image(data, type, MAX_ENTRY_BYTES);
                } else throw new IllegalArgumentException("Unsupported OOXML part");
            }
        }
        if (!names.contains("[Content_Types].xml") || !names.contains("_rels/.rels") || !names.contains("ppt/presentation.xml")) throw FileValidationService.invalid();
        if (!names.equals(centralNames)) throw FileValidationService.invalid();
        ZipSecureFile.setMaxFileCount(MAX_ENTRIES);
        ZipSecureFile.setMaxEntrySize(MAX_ENTRY_BYTES);
        ZipSecureFile.setMinInflateRatio(1.0 / MAX_RATIO);
        org.apache.poi.util.IOUtils.setByteArrayMaxOverride(MAX_ENTRY_BYTES);
        try (var pkg = OPCPackage.open(new ByteArrayInputStream(bytes)); var show = new XMLSlideShow(pkg)) {
            if (!show.getCTPresentation().validate() || show.getSlides().isEmpty() || show.getSlides().size() > 300) throw FileValidationService.invalid();
            if (pkg.getParts().stream().noneMatch(p -> p.getPartName().getName().equals("/ppt/presentation.xml")
                    && p.getContentType().equals("application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"))) throw FileValidationService.invalid();
            for (var part : pkg.getParts()) {
                if (part.isRelationshipPart()) continue;
                for (var relationship : part.getRelationships()) {
                    if (relationship.getTargetMode() == org.apache.poi.openxml4j.opc.TargetMode.EXTERNAL) throw FileValidationService.invalid();
                    if (part.getRelatedPart(relationship) == null) throw FileValidationService.invalid();
                }
            }
            for (var slide : show.getSlides()) if (!slide.getXmlObject().validate() || slide.getShapes().size() > 2000) throw FileValidationService.invalid();
        }
    }

    private boolean safeEntry(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return name.length() <= 240 && !name.startsWith("/") && !name.contains("\\") && !name.contains(":")
                && Arrays.stream(name.split("/", -1)).noneMatch(s -> s.equals("..") || s.equals(".") || s.isEmpty())
                && !lower.contains("embedding") && !lower.contains("activex") && !lower.contains("vba")
                && !lower.contains("oleobject") && !lower.contains("externallink");
    }
    private void xml(byte[] data, String name) throws Exception {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        factory.setXMLResolver((a,b,c,d) -> { throw FileValidationService.invalid(); });
        var reader = factory.createXMLStreamReader(new ByteArrayInputStream(data));
        int depth = 0, elements = 0;
        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE) throw FileValidationService.invalid();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    if (++depth > 64 || ++elements > 100_000 || reader.getAttributeCount() > 64) throw FileValidationService.invalid();
                    String local = reader.getLocalName().toLowerCase(Locale.ROOT);
                    if (Set.of("oleobj", "control", "video", "audio", "hlinkclick", "hlinkhover", "externaldata").contains(local)) throw FileValidationService.invalid();
                    for (int i = 0; i < reader.getAttributeCount(); i++) {
                        String attribute = reader.getAttributeLocalName(i), value = reader.getAttributeValue(i);
                        if (value.length() > 8192) throw FileValidationService.invalid();
                        if (attribute.equals("TargetMode") && !value.equals("Internal")) throw FileValidationService.invalid();
                        if (attribute.equals("ContentType") && (value.toLowerCase(Locale.ROOT).contains("macro")
                                || value.toLowerCase(Locale.ROOT).contains("vba") || value.toLowerCase(Locale.ROOT).contains("oleobject")
                                || value.toLowerCase(Locale.ROOT).contains("activex"))) throw FileValidationService.invalid();
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) depth--;
            }
        } finally { reader.close(); }
    }

    private static final class LimitedOutput extends ByteArrayOutputStream {
        private final int limit;
        LimitedOutput(int limit) { this.limit = limit; }
        @Override public synchronized void write(int value) {
            if (count >= limit) throw FileValidationService.invalid(); super.write(value);
        }
        @Override public synchronized void write(byte[] bytes, int offset, int length) {
            if (length > limit - count) throw FileValidationService.invalid(); super.write(bytes, offset, length);
        }
    }

    private static final class StrictPdfParser extends PDFParser {
        StrictPdfParser(RandomAccessReadBuffer input) throws IOException { super(input); setLenient(false); }
    }
}
