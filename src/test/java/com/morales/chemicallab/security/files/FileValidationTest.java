package com.morales.chemicallab.security.files;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.encryption.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import static org.assertj.core.api.Assertions.*;

class FileValidationTest {
    final FileValidationService service = new FileValidationService();
    byte[] bytes(String ext) {
        return switch (ext) {
            case "pdf" -> FileFixtures.pdf(); case "ppt" -> FileFixtures.ppt(); case "pptx" -> FileFixtures.pptx();
            case "png" -> FileFixtures.png(); default -> FileFixtures.jpeg();
        };
    }
    String mime(String ext) {
        return switch (ext) {
            case "pdf" -> "application/pdf"; case "ppt" -> FileValidationWorker.PPT; case "pptx" -> FileValidationWorker.PPTX;
            case "png" -> "image/png"; default -> "image/jpeg";
        };
    }
    MultipartFile file(String ext, byte[] data) { return new MockMultipartFile("file", "ficticio." + ext, mime(ext), data); }

    @ParameterizedTest @ValueSource(strings={"pdf","ppt","pptx","png","jpg","jpeg"})
    void legitimateFormatsAndHistoricalMetadata(String ext) throws Exception {
        new FileValidationWorker().validate(bytes(ext), mime(ext), FileValidationService.MATERIAL_BYTES);
        var result = service.material(file(ext, bytes(ext)));
        assertThat(result.contentType()).isEqualTo(mime(ext));
        assertThat(result.data()).isNotEmpty();
        var historical = service.historical(bytes(ext), "text/html", "../antiguo.html\r\n", false);
        assertThat(historical.contentType()).isEqualTo(mime(ext));
        assertThat(historical.fileName()).endsWith("." + (ext.equals("jpeg") ? "jpg" : ext)).doesNotContain("/", "\r", "\n");
    }
    @ParameterizedTest @ValueSource(strings={"pdf","ppt","pptx","png","jpg"})
    void fictitiousCorruptEmptyAndTruncated(String ext) {
        assertThatThrownBy(() -> service.material(file(ext, "Texto inocuo".getBytes()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.material(file(ext, new byte[0]))).isInstanceOf(IllegalArgumentException.class);
        byte[] valid = bytes(ext);
        assertThatThrownBy(() -> service.material(file(ext, Arrays.copyOf(valid, valid.length / 2)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.material(file(ext, Arrays.copyOf(valid, Math.max(1, valid.length - 16))))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void mismatchesDoNotUseSignaturesAlone() {
        assertThatThrownBy(() -> service.material(file("pdf", FileFixtures.png()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.material(new MockMultipartFile("file", "real.pdf", "image/png", FileFixtures.pdf()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.material(file("pdf", "%PDF-1.7\nTexto ficticio\n%%EOF".getBytes()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.snapshot(file("pdf", FileFixtures.pdf()))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void inputBytesAndReportedLengthHaveIndependentBounds() {
        assertThatCode(() -> FileValidationService.checkLength(FileValidationService.MATERIAL_BYTES, FileValidationService.MATERIAL_BYTES)).doesNotThrowAnyException();
        assertThatCode(() -> FileValidationService.checkLength(FileValidationService.SNAPSHOT_BYTES, FileValidationService.SNAPSHOT_BYTES)).doesNotThrowAnyException();
        assertThatThrownBy(() -> service.material(file("pdf", new byte[FileValidationService.MATERIAL_BYTES + 1]))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.snapshot(file("png", new byte[FileValidationService.SNAPSHOT_BYTES + 1]))).isInstanceOf(IllegalArgumentException.class);
        var dishonest = new MockMultipartFile("file", "guia.pdf", "application/pdf", new byte[FileValidationService.MATERIAL_BYTES + 1]) {
            @Override public long getSize() { return 1; }
        };
        assertThatThrownBy(() -> service.material(dishonest)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void exactMaterialByteLimitAcceptsACompleteDocument() throws Exception {
        try(var document=new PDDocument()) {
            document.addPage(new PDPage());
            var stream=document.getDocument().createCOSStream();
            document.getDocumentCatalog().getCOSObject().setItem(COSName.getPDFName("Fictitious"),stream);
            int payload=FileValidationService.MATERIAL_BYTES-2048;
            byte[] padded=null;
            for(int attempt=0;attempt<5;attempt++) {
                try(var contents=stream.createOutputStream()) { contents.write(new byte[payload]); }
                var out=new ByteArrayOutputStream(); document.save(out); padded=out.toByteArray();
                if(padded.length==FileValidationService.MATERIAL_BYTES)break;
                payload+=FileValidationService.MATERIAL_BYTES-padded.length;
            }
            assertThat(padded).hasSize(FileValidationService.MATERIAL_BYTES);
            assertThat(service.material(file("pdf",padded)).data()).hasSize(FileValidationService.MATERIAL_BYTES);
        }
        byte[] valid=FileFixtures.png();
        byte[] tagged=addPngChunk(valid,"tEXt",new byte[FileValidationService.SNAPSHOT_BYTES-valid.length-12]);
        assertThat(tagged).hasSize(FileValidationService.SNAPSHOT_BYTES);
        assertThat(service.snapshot(file("png",tagged)).data().length).isLessThan(1000);
    }
    @Test void pptMacrosEmbeddedStoragesAndEncryptionAreRejected() throws Exception {
        try (var show=new org.apache.poi.hslf.usermodel.HSLFSlideShow();var out=new ByteArrayOutputStream()) {
            show.createSlide();
            for (var record : show.getSlideShowImpl().getRecords()) enableFictitiousMacro(record);
            show.write(out); byte[] data=out.toByteArray();
            assertThatThrownBy(() -> service.material(file("ppt",data))).isInstanceOf(IllegalArgumentException.class);
        }
        try(var fs=new org.apache.poi.poifs.filesystem.POIFSFileSystem(new ByteArrayInputStream(FileFixtures.ppt()));var out=new ByteArrayOutputStream()) {
            fs.getRoot().createDirectory("ObjectPool").createDocument("Fictitious",new ByteArrayInputStream("inocuo".getBytes()));
            fs.writeFilesystem(out);byte[] data=out.toByteArray();
            assertThatThrownBy(() -> service.material(file("ppt",data))).isInstanceOf(IllegalArgumentException.class);
        }
        org.apache.poi.hssf.record.crypto.Biff8EncryptionKey.setCurrentUserPassword("fictitious");
        try(var show=new org.apache.poi.hslf.usermodel.HSLFSlideShow();var out=new ByteArrayOutputStream()) {
            show.createSlide();show.write(out);byte[] data=out.toByteArray();
            assertThatThrownBy(() -> service.material(file("ppt",data))).isInstanceOf(IllegalArgumentException.class);
        } finally { org.apache.poi.hssf.record.crypto.Biff8EncryptionKey.setCurrentUserPassword(null); }
    }
    void enableFictitiousMacro(org.apache.poi.hslf.record.Record record) {
        if(record instanceof org.apache.poi.hslf.record.VBAInfoAtom atom) atom.setHasMacros(true);
        if(record.getChildRecords()!=null) for(var child:record.getChildRecords()) enableFictitiousMacro(child);
    }
    @Test void dimensionAndPixelBoundariesBeforeDecode() throws Exception {
        assertThatCode(() -> FileValidationWorker.dimensions(8192, 1)).doesNotThrowAnyException();
        assertThatCode(() -> FileValidationWorker.dimensions(4000, 4000)).doesNotThrowAnyException();
        for (int[] size : List.of(new int[]{8193, 1}, new int[]{4000,4001}, new int[]{0,1}, new int[]{Integer.MAX_VALUE,Integer.MAX_VALUE})) {
            assertThatThrownBy(() -> FileValidationWorker.dimensions(size[0],size[1])).isInstanceOf(IllegalArgumentException.class);
            byte[] data = FileFixtures.png();
            java.nio.ByteBuffer.wrap(data, 16, 8).putInt(size[0]).putInt(size[1]);
            CRC32 crc = new CRC32(); crc.update(data, 12, 17);
            java.nio.ByteBuffer.wrap(data,29,4).putInt((int)crc.getValue());
            assertThatThrownBy(() -> service.snapshot(file("png", data))).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void imagesAreReencodedAndAncillaryMetadataIsDropped() throws Exception {
        byte[] png = FileFixtures.png();
        byte[] tagged = addPngChunk(png, "tEXt", "Fictitious\0metadata".getBytes());
        var normalized = service.snapshot(file("png",tagged));
        assertThat(new String(normalized.data(), java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("Fictitious", "metadata");
        assertThat(javax.imageio.ImageIO.read(new ByteArrayInputStream(normalized.data())).getWidth()).isEqualTo(8);
        byte[] corrupt = tagged.clone(); corrupt[45] ^= 1;
        assertThatThrownBy(() -> service.snapshot(file("png",corrupt))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.snapshot(file("png",addPngChunk(png,"acTL",new byte[8])))).isInstanceOf(IllegalArgumentException.class);
    }
    byte[] addPngChunk(byte[] png, String type, byte[] data) throws Exception {
        var out = new ByteArrayOutputStream(); var stream = new DataOutputStream(out);
        stream.write(png,0,33); stream.writeInt(data.length); stream.writeBytes(type); stream.write(data);
        CRC32 crc = new CRC32(); crc.update(type.getBytes()); crc.update(data); stream.writeInt((int)crc.getValue());
        stream.write(png,33,png.length-33); return out.toByteArray();
    }
    @Test void pdfEncryptionActionsAndEmbeddedObjectsAreRejected() throws Exception {
        for (String mode : List.of("encrypted","action","embedded","form")) {
            try (var document = new PDDocument(); var out = new ByteArrayOutputStream()) {
                document.addPage(new PDPage());
                if (mode.equals("encrypted")) document.protect(new StandardProtectionPolicy("owner", "", new AccessPermission()));
                else if (mode.equals("action")) {
                    var action = new COSDictionary(); action.setName(COSName.S,"JavaScript"); action.setString(COSName.JS,"/* fictitious */");
                    document.getDocumentCatalog().getCOSObject().setItem(COSName.OPEN_ACTION,action);
                } else if (mode.equals("embedded")) {
                    var spec = new COSDictionary(); spec.setName(COSName.TYPE,"Filespec");
                    document.getDocumentCatalog().getCOSObject().setItem(COSName.getPDFName("Fictitious"),spec);
                } else document.getDocumentCatalog().getCOSObject().setItem(COSName.ACRO_FORM,new COSDictionary());
                document.save(out); byte[] data = out.toByteArray();
                assertThatThrownBy(() -> service.material(file("pdf",data))).isInstanceOf(IllegalArgumentException.class);
            }
        }
    }
    @Test void pdfDecompressedStreamsHaveBounds() throws Exception {
        try (var document = new PDDocument(); var out = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            var stream = document.getDocument().createCOSStream();
            try(var compressed = stream.createOutputStream(COSName.FLATE_DECODE)) { compressed.write(new byte[FileValidationWorker.MAX_ENTRY_BYTES+1]); }
            document.getDocumentCatalog().getCOSObject().setItem(COSName.getPDFName("Fictitious"),stream);
            document.save(out); byte[] data = out.toByteArray();
            assertThatThrownBy(() -> service.material(file("pdf",data))).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void pptxMustBeRealPresentationAndRejectsExternalRelationshipsDtdAndEmbeddedContent() throws Exception {
        assertThatThrownBy(() -> service.material(file("pptx", zip(Map.of("hello.xml","<hello/>".getBytes()))))).isInstanceOf(IllegalArgumentException.class);
        var entries = unzip(FileFixtures.pptx());
        for (String name : List.of("ppt/embeddings/fictitious.xml","ppt/activeX/fictitious.xml","../outside.xml")) {
            var changed = new LinkedHashMap<>(entries); changed.put(name,"<fictitious/>".getBytes());
            byte[] data = zip(changed);
            assertThatThrownBy(() -> service.material(file("pptx",data))).isInstanceOf(IllegalArgumentException.class);
        }
        entries.put("ppt/_rels/presentation.xml.rels", ("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"+
                "<Relationship Id=\"rIdExternal\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink\" Target=\"http://127.0.0.1:9/never\" TargetMode=\"External\"/></Relationships>").getBytes());
        assertThatThrownBy(() -> service.material(file("pptx",zip(entries)))).isInstanceOf(IllegalArgumentException.class);
        entries.put("ppt/presentation.xml","<!DOCTYPE a [<!ENTITY x SYSTEM 'file:///never'>]><a>&x;</a>".getBytes());
        assertThatThrownBy(() -> service.material(file("pptx",zip(entries)))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void zipEntryCountEntryBytesTotalAndRatioBounds() throws Exception {
        var entries = unzip(FileFixtures.pptx());
        entries.put("ppt/fictitious.xml", new byte[300_000]);
        assertThatThrownBy(() -> service.material(file("pptx",zip(entries)))).isInstanceOf(IllegalArgumentException.class);
        var many = new LinkedHashMap<String,byte[]>();
        for (int i=0; i <= FileValidationWorker.MAX_ENTRIES; i++) many.put("part"+i+".xml","<a/>".getBytes());
        assertThatThrownBy(() -> service.material(file("pptx",zip(many)))).isInstanceOf(IllegalArgumentException.class);
        entries.put("ppt/fictitious.xml",new byte[FileValidationWorker.MAX_ENTRY_BYTES+1]);
        assertThatThrownBy(() -> service.material(file("pptx",zip(entries)))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void zipExpandedByteAndEntryCountBoundariesAcceptValidPackagesAndRejectTheNextByte() throws Exception {
        var entries=unzip(FileFixtures.pptx());
        int original=entries.values().stream().mapToInt(a -> a.length).sum();
        int remaining=FileValidationWorker.MAX_EXPANDED_BYTES-original;
        int index=0;
        while(remaining>0) {
            int size=Math.min(FileValidationWorker.MAX_ENTRY_BYTES,remaining);
            entries.put("ppt/fictitious"+(index++)+".xml",xmlBytes(size)); remaining-=size;
        }
        byte[] maximum=zip(entries);
        assertThat(maximum.length).isLessThan(FileValidationService.MATERIAL_BYTES);
        assertThat(service.material(file("pptx",maximum)).contentType()).isEqualTo(FileValidationWorker.PPTX);
        entries.put("ppt/extra.xml","<f/>".getBytes());
        assertThatThrownBy(() -> service.material(file("pptx",zip(entries)))).isInstanceOf(IllegalArgumentException.class);
        var many=unzip(FileFixtures.pptx());
        while(many.size()<FileValidationWorker.MAX_ENTRIES)many.put("ppt/part"+many.size()+".xml","<f/>".getBytes());
        assertThat(service.material(file("pptx",zip(many))).contentType()).isEqualTo(FileValidationWorker.PPTX);
        many.put("ppt/next.xml","<f/>".getBytes());
        assertThatThrownBy(() -> service.material(file("pptx",zip(many)))).isInstanceOf(IllegalArgumentException.class);
    }
    byte[] xmlBytes(int size) {
        byte[] data=new byte[size];new Random(106).nextBytes(data);
        for(int i=0;i<size;i++)data[i]=(byte)((data[i]&1)==0?'a':'b');
        System.arraycopy("<f>".getBytes(),0,data,0,3);System.arraycopy("</f>".getBytes(),0,data,size-4,4);
        return data;
    }
    static byte[] zip(Map<String,byte[]> entries) throws Exception {
        var out = new ByteArrayOutputStream();
        try(var zip = new ZipOutputStream(out)) {
            for (var entry : entries.entrySet()) { zip.putNextEntry(new ZipEntry(entry.getKey())); zip.write(entry.getValue()); zip.closeEntry(); }
        }
        return out.toByteArray();
    }
    static Map<String,byte[]> unzip(byte[] data) throws Exception {
        var result = new LinkedHashMap<String,byte[]>();
        try(var zip = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry entry; while((entry=zip.getNextEntry())!=null) result.put(entry.getName(),zip.readAllBytes());
        }
        return result;
    }
}
