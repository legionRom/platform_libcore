/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package libcore.java.util.zip;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import junit.framework.TestCase;

import tests.support.resource.Support_Resources;

public final class ZipFileTest extends TestCase {
    /**
     * Exercise Inflater's ability to refill the zlib's input buffer. As of this
     * writing, this buffer's max size is 64KiB compressed bytes. We'll write a
     * full megabyte of uncompressed data, which should be sufficient to exhaust
     * the buffer. http://b/issue?id=2734751
     */
    public void testInflatingFilesRequiringZipRefill() throws IOException {
        int originalSize = 1024 * 1024;
        byte[] readBuffer = new byte[8192];
        ZipFile zipFile = new ZipFile(createZipFile(1, originalSize));
        for (Enumeration<? extends ZipEntry> e = zipFile.entries(); e.hasMoreElements(); ) {
            ZipEntry zipEntry = e.nextElement();
            assertTrue("This test needs >64 KiB of compressed data to exercise Inflater",
                    zipEntry.getCompressedSize() > (64 * 1024));
            InputStream is = zipFile.getInputStream(zipEntry);
            while (is.read(readBuffer, 0, readBuffer.length) != -1) {}
            is.close();
        }
        zipFile.close();
    }

    private static void replaceBytes(byte[] buffer, byte[] original, byte[] replacement) {
        // Gotcha here: original and replacement must be the same length
        assertEquals(original.length, replacement.length);
        boolean found;
        for(int i=0; i < buffer.length - original.length; i++) {
            found = false;
            if (buffer[i] == original[0]) {
                found = true;
                for (int j=0; j < original.length; j++) {
                    if (buffer[i+j] != original[j]) {
                        found = false;
                        break;
                    }
                }
            }
            if (found) {
                for (int j=0; j < original.length; j++) {
                    buffer[i+j] = replacement[j];
                }
            }
        }
    }

    private static void writeBytes(File f, byte[] bytes) throws IOException {
        FileOutputStream out = new FileOutputStream(f);
        out.write(bytes);
        out.close();
    }

    /**
     * Make sure we don't fail silently for duplicate entries.
     * b/8219321
     */
    public void testDuplicateEntries() throws Exception {
        String name1 = "test_file_name1";
        String name2 = "test_file_name2";

        // Create the good zip file.
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ZipOutputStream out = new ZipOutputStream(baos);
        out.putNextEntry(new ZipEntry(name2));
        out.closeEntry();
        out.putNextEntry(new ZipEntry(name1));
        out.closeEntry();
        out.close();

        // Rewrite one of the filenames.
        byte[] buffer = baos.toByteArray();
        replaceBytes(buffer, name2.getBytes(), name1.getBytes());

        // Write the result to a file.
        File badZip = createTemporaryZipFile();
        writeBytes(badZip, buffer);

        // Check that we refuse to load the modified file.
        try {
            ZipFile bad = new ZipFile(badZip);
            fail();
        } catch (ZipException expected) {
        }
    }

    /**
     * Make sure the size used for stored zip entires is the uncompressed size.
     * b/10227498
     */
    public void testStoredEntrySize() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ZipOutputStream out = new ZipOutputStream(baos);

        // Set up a single stored entry.
        String name = "test_file";
        int expectedLength = 5;
        ZipEntry outEntry = new ZipEntry(name);
        byte[] buffer = new byte[expectedLength];
        outEntry.setMethod(ZipEntry.STORED);
        CRC32 crc = new CRC32();
        crc.update(buffer);
        outEntry.setCrc(crc.getValue());
        outEntry.setSize(buffer.length);

        out.putNextEntry(outEntry);
        out.write(buffer);
        out.closeEntry();
        out.close();

        // Write the result to a file.
        byte[] outBuffer = baos.toByteArray();
        File zipFile = createTemporaryZipFile();
        writeBytes(zipFile, outBuffer);

        ZipFile zip = new ZipFile(zipFile);
        // Set up the zip entry to have different compressed/uncompressed sizes.
        ZipEntry ze = zip.getEntry(name);
        ze.setCompressedSize(expectedLength - 1);
        // Read the contents of the stream and verify uncompressed size was used.
        InputStream stream = zip.getInputStream(ze);
        int count = 0;
        int read;
        while ((read = stream.read(buffer)) != -1) {
            count += read;
        }

        assertEquals(expectedLength, count);
        zip.close();
    }

    public void testInflatingStreamsRequiringZipRefill() throws IOException {
        int originalSize = 1024 * 1024;
        byte[] readBuffer = new byte[8192];
        ZipInputStream in = new ZipInputStream(new FileInputStream(createZipFile(1, originalSize)));
        while (in.getNextEntry() != null) {
            while (in.read(readBuffer, 0, readBuffer.length) != -1) {}
        }
        in.close();
    }

    public void testZipFileWithLotsOfEntries() throws IOException {
        int expectedEntryCount = 64*1024 - 1;
        File f = createZipFile(expectedEntryCount, 0);
        ZipFile zipFile = new ZipFile(f);
        int entryCount = 0;
        for (Enumeration<? extends ZipEntry> e = zipFile.entries(); e.hasMoreElements(); ) {
            ZipEntry zipEntry = e.nextElement();
            ++entryCount;
        }
        assertEquals(expectedEntryCount, entryCount);
        zipFile.close();
    }

    // http://code.google.com/p/android/issues/detail?id=36187
    public void testZipFileLargerThan2GiB() throws IOException {
        if (false) { // TODO: this test requires too much time and too much disk space!
            File f = createZipFile(1024, 3*1024*1024);
            ZipFile zipFile = new ZipFile(f);
            int entryCount = 0;
            for (Enumeration<? extends ZipEntry> e = zipFile.entries(); e.hasMoreElements(); ) {
                ZipEntry zipEntry = e.nextElement();
                ++entryCount;
            }
            assertEquals(1024, entryCount);
            zipFile.close();
        }
    }

    public void testZip64Support() throws IOException {
        try {
            createZipFile(64*1024, 0);
            fail(); // Make this test more like testHugeZipFile when we have Zip64 support.
        } catch (ZipException expected) {
        }
    }

    public void testNullCharset() throws IOException {
        try {
            new ZipFile(createTemporaryZipFile(), null);
            fail();
        } catch (NullPointerException expected) {
        }
    }

    // Tests that non-UTF8 encoded zip files can be interpreted. Relies on ZipOutputStream.
    public void testNonUtf8Encoding() throws IOException {
        Charset charset = Charset.forName("Cp437");
        String encodingDependentString = "\u00FB";
        assertEncodingDiffers(encodingDependentString, charset, StandardCharsets.US_ASCII,
                StandardCharsets.UTF_8);
        String name = "name" + encodingDependentString;
        String comment = "comment" + encodingDependentString;

        File result = createTemporaryZipFile();
        OutputStream os = new BufferedOutputStream(new FileOutputStream(result));
        ZipOutputStream out = new ZipOutputStream(os, charset);
        out.setComment(comment);
        ZipEntry writeEntry = new ZipEntry(name);
        writeEntry.setComment(comment);
        out.putNextEntry(writeEntry);
        out.write("FileContentsIrrelevant".getBytes());
        out.closeEntry();
        out.close();

        ZipFile zipFile = new ZipFile(result, StandardCharsets.US_ASCII);
        assertNull(zipFile.getEntry(name));
        assertFalse(zipFile.getComment().equals(comment));
        zipFile.close();

        zipFile = new ZipFile(result, charset);
        ZipEntry readEntry = zipFile.getEntry(name);
        assertNotNull(readEntry);
        assertEquals(name, readEntry.getName());
        assertEquals(comment, readEntry.getComment());
        assertEquals(comment, zipFile.getComment());
        zipFile.close();
    }

    // Tests that UTF8 encoded zip files can be interpreted when the constructor is provided with a
    // non-UTF-8 encoding. Relies on ZipOutputStream.
    public void testUtf8EncodingOverridesConstructor() throws IOException {
        Charset charset = Charset.forName("Cp437");
        String encodingDependentString = "\u00FB";
        assertEncodingDiffers(encodingDependentString, charset, StandardCharsets.UTF_8);
        String name = "name" + encodingDependentString;
        String comment = "comment" + encodingDependentString;

        File result = createTemporaryZipFile();
        OutputStream os = new BufferedOutputStream(new FileOutputStream(result));
        ZipOutputStream out = new ZipOutputStream(os, StandardCharsets.UTF_8);
        // The file comment does not get meta-data about the character encoding.
        out.setComment(comment);
        // The entry will be tagged as being UTF-8 encoded.
        ZipEntry writeEntry = new ZipEntry(name);
        writeEntry.setComment(comment);
        out.putNextEntry(writeEntry);
        out.write("FileContentsIrrelevant".getBytes());
        out.closeEntry();
        out.close();

        ZipFile zipFile = new ZipFile(result, charset);
        // The entry should be found, because it should be tagged as being UTF-8 encoded.
        ZipEntry readEntry = zipFile.getEntry(name);
        assertNotNull(readEntry);
        assertEquals(name, readEntry.getName());
        assertEquals(comment, readEntry.getComment());
        // We expect the comment to be mangled because it is not tagged.
        assertFalse(zipFile.getComment().equals(comment));
        zipFile.close();
    }

    /**
     * Asserts the byte encoding for the string is different for all the supplied character
     * sets.
     */
    private void assertEncodingDiffers(String string, Charset... charsets) {
        Set<List<Byte>> encodings = new HashSet<List<Byte>>();
        for (int i = 0; i < charsets.length; i++) {
            List<Byte> byteList = new ArrayList<Byte>();
            for (byte b : string.getBytes(charsets[i])) {
                byteList.add(b);
            }
            assertTrue("Encoding has been seen before", encodings.add(byteList));
        }
    }

    /**
     * Compresses the given number of files, each of the given size, into a .zip archive.
     */
    private File createZipFile(int entryCount, int entrySize) throws IOException {
        File result = createTemporaryZipFile();

        byte[] writeBuffer = new byte[8192];
        Random random = new Random();

        ZipOutputStream out = createZipOutputStream(result);
        try {
            for (int entry = 0; entry < entryCount; ++entry) {
                ZipEntry ze = new ZipEntry(Integer.toHexString(entry));
                out.putNextEntry(ze);

                for (int i = 0; i < entrySize; i += writeBuffer.length) {
                    random.nextBytes(writeBuffer);
                    int byteCount = Math.min(writeBuffer.length, entrySize - i);
                    out.write(writeBuffer, 0, byteCount);
                }

                out.closeEntry();
            }
        } finally {
            out.close();
        }
        return result;
    }

    private File createTemporaryZipFile() throws IOException {
        File result = File.createTempFile("ZipFileTest", "zip");
        result.deleteOnExit();
        return result;
    }

    private ZipOutputStream createZipOutputStream(File f) throws IOException {
        return new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(f)));
    }

    public void testSTORED() throws IOException {
        ZipOutputStream out = createZipOutputStream(createTemporaryZipFile());
        CRC32 crc = new CRC32();

        // Missing CRC, size, and compressed size => failure.
        try {
            ZipEntry ze = new ZipEntry("a");
            ze.setMethod(ZipEntry.STORED);
            out.putNextEntry(ze);
            fail();
        } catch (ZipException expected) {
        }

        // Missing CRC and compressed size => failure.
        try {
            ZipEntry ze = new ZipEntry("a");
            ze.setMethod(ZipEntry.STORED);
            ze.setSize(0);
            out.putNextEntry(ze);
            fail();
        } catch (ZipException expected) {
        }

        // Missing CRC and size => failure.
        try {
            ZipEntry ze = new ZipEntry("a");
            ze.setMethod(ZipEntry.STORED);
            ze.setSize(0);
            ze.setCompressedSize(0);
            out.putNextEntry(ze);
            fail();
        } catch (ZipException expected) {
        }

        // Missing size and compressed size => failure.
        try {
            ZipEntry ze = new ZipEntry("a");
            ze.setMethod(ZipEntry.STORED);
            ze.setCrc(crc.getValue());
            out.putNextEntry(ze);
            fail();
        } catch (ZipException expected) {
        }

        // Missing size is copied from compressed size.
        {
            ZipEntry ze = new ZipEntry("okay1");
            ze.setMethod(ZipEntry.STORED);
            ze.setCrc(crc.getValue());

            assertEquals(-1, ze.getSize());
            assertEquals(-1, ze.getCompressedSize());

            ze.setCompressedSize(0);

            assertEquals(-1, ze.getSize());
            assertEquals(0, ze.getCompressedSize());

            out.putNextEntry(ze);

            assertEquals(0, ze.getSize());
            assertEquals(0, ze.getCompressedSize());
        }

        // Missing compressed size is copied from size.
        {
            ZipEntry ze = new ZipEntry("okay2");
            ze.setMethod(ZipEntry.STORED);
            ze.setCrc(crc.getValue());

            assertEquals(-1, ze.getSize());
            assertEquals(-1, ze.getCompressedSize());

            ze.setSize(0);

            assertEquals(0, ze.getSize());
            assertEquals(-1, ze.getCompressedSize());

            out.putNextEntry(ze);

            assertEquals(0, ze.getSize());
            assertEquals(0, ze.getCompressedSize());
        }

        // Mismatched size and compressed size => failure.
        try {
            ZipEntry ze = new ZipEntry("a");
            ze.setMethod(ZipEntry.STORED);
            ze.setCrc(crc.getValue());
            ze.setCompressedSize(1);
            ze.setSize(0);
            out.putNextEntry(ze);
            fail();
        } catch (ZipException expected) {
        }

        // Everything present => success.
        ZipEntry ze = new ZipEntry("okay");
        ze.setMethod(ZipEntry.STORED);
        ze.setCrc(crc.getValue());
        ze.setSize(0);
        ze.setCompressedSize(0);
        out.putNextEntry(ze);

        out.close();
    }

    private String makeString(int count, String ch) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; ++i) {
            sb.append(ch);
        }
        return sb.toString();
    }

    public void testComments() throws Exception {
        String expectedFileComment = "1 \u0666 2";
        String expectedEntryComment = "a \u0666 b";

        File file = createTemporaryZipFile();
        ZipOutputStream out = createZipOutputStream(file);

        // Is file comment length checking done on bytes or characters? (Should be bytes.)
        out.setComment(null);
        out.setComment(makeString(0xffff, "a"));
        try {
            out.setComment(makeString(0xffff + 1, "a"));
            fail();
        } catch (IllegalArgumentException expected) {
        }
        try {
            out.setComment(makeString(0xffff, "\u0666"));
            fail();
        } catch (IllegalArgumentException expected) {
        }

        ZipEntry ze = new ZipEntry("a");

        // Is entry comment length checking done on bytes or characters? (Should be bytes.)
        ze.setComment(null);
        ze.setComment(makeString(0xffff, "a"));
        try {
            ze.setComment(makeString(0xffff + 1, "a"));
            fail();
        } catch (IllegalArgumentException expected) {
        }
        try {
            ze.setComment(makeString(0xffff, "\u0666"));
            fail();
        } catch (IllegalArgumentException expected) {
        }

        ze.setComment(expectedEntryComment);
        out.putNextEntry(ze);
        out.closeEntry();

        out.setComment(expectedFileComment);
        out.close();

        ZipFile zipFile = new ZipFile(file);
        assertEquals(expectedFileComment, zipFile.getComment());
        assertEquals(expectedEntryComment, zipFile.getEntry("a").getComment());
        zipFile.close();
    }

    public void test_getComment_unset() throws Exception {
        File file = createTemporaryZipFile();
        ZipOutputStream out = createZipOutputStream(file);
        ZipEntry ze = new ZipEntry("test entry");
        ze.setComment("per-entry comment");
        out.putNextEntry(ze);
        out.close();

        ZipFile zipFile = new ZipFile(file);
        assertEquals(null, zipFile.getComment());
    }

    // https://code.google.com/p/android/issues/detail?id=58465
    public void test_NUL_in_filename() throws Exception {
        File file = createTemporaryZipFile();

        // We allow creation of a ZipEntry whose name contains a NUL byte,
        // mainly because it's not likely to happen by accident and it's useful for testing.
        ZipOutputStream out = createZipOutputStream(file);
        out.putNextEntry(new ZipEntry("hello"));
        out.putNextEntry(new ZipEntry("hello\u0000"));
        out.close();

        // But you can't open a ZIP file containing such an entry, because we reject it
        // when we find it in the central directory.
        try {
            ZipFile zipFile = new ZipFile(file);
            fail();
        } catch (ZipException expected) {
        }
    }

    public void testCrc() throws IOException {
        ZipEntry ze = new ZipEntry("test");
        ze.setMethod(ZipEntry.STORED);
        ze.setSize(4);

        // setCrc takes a long, not an int, so -1 isn't a valid CRC32 (because it's 64 bits).
        try {
            ze.setCrc(-1);
        } catch (IllegalArgumentException expected) {
        }

        // You can set the CRC32 to 0xffffffff if you're slightly more careful though...
        ze.setCrc(0xffffffffL);
        assertEquals(0xffffffffL, ze.getCrc());

        // And it actually works, even though we use -1L to mean "no CRC set"...
        ZipOutputStream out = createZipOutputStream(createTemporaryZipFile());
        out.putNextEntry(ze);
        out.write(-1);
        out.write(-1);
        out.write(-1);
        out.write(-1);
        out.closeEntry();
        out.close();
    }

    /**
     * RI does not allow reading of an empty zip using a {@link ZipFile}.
     */
    public void testConstructorFailsWhenReadingEmptyZipArchive() throws IOException {

        File resources = Support_Resources.createTempFolder();
        File emptyZip = Support_Resources.copyFile(
                resources, "java/util/zip", "EmptyArchive.zip");

        try {
            // The following should fail with an exception but if it doesn't then we need to clean
            // up the resource so we need a reference to it.
            ZipFile zipFile = new ZipFile(emptyZip);

            // Clean up the resource.
            try {
                zipFile.close();
            } catch (Exception e) {
                // Ignore
            }
            fail();
        } catch (ZipException expected) {
            // expected
        }
    }
}
