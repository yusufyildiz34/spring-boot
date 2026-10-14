/*
 * Copyright 2012-present the original author or authors.
 */

package org.springframework.boot.loader.zip;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.boot.loader.testsupport.TestJar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Tests for {@link VirtualZipDataBlock}.
 *
 * @author Phillip Webb
 */
@AssertFileChannelDataBlocksClosed
class VirtualZipDataBlockTests {

	@TempDir
	File tempDir;

	private File file;

	@BeforeEach
	void setup() throws Exception {
		this.file = new File(this.tempDir, "test.jar");
		TestJar.create(this.file);
	}

	@Test
	void createContainsValidZipContent() throws IOException {
		FileDataBlock data = new FileDataBlock(this.file.toPath());
		data.open();
		List<ZipCentralDirectoryFileHeaderRecord> centralRecords = new ArrayList<>();
		List<Long> centralRecordPositions = new ArrayList<>();
		ZipEndOfCentralDirectoryRecord eocd = ZipEndOfCentralDirectoryRecord.load(data).endOfCentralDirectoryRecord();
		long pos = eocd.offsetToStartOfCentralDirectory();
		for (int i = 0; i < eocd.totalNumberOfCentralDirectoryEntries(); i++) {
			ZipCentralDirectoryFileHeaderRecord centralRecord = ZipCentralDirectoryFileHeaderRecord.load(data, pos);
			String name = ZipString.readString(data, pos + ZipCentralDirectoryFileHeaderRecord.FILE_NAME_OFFSET,
					centralRecord.fileNameLength());
			if (name.endsWith(".jar")) {
				centralRecords.add(centralRecord);
				centralRecordPositions.add(pos);
			}
			pos += centralRecord.size();
		}
		NameOffsetLookups nameOffsetLookups = new NameOffsetLookups(2, centralRecords.size());
		for (int i = 0; i < centralRecords.size(); i++) {
			nameOffsetLookups.enable(i, true);
		}
		nameOffsetLookups.enable(0, true);
		File outputFile = new File(this.tempDir, "out.jar");
		try (VirtualZipDataBlock block = new VirtualZipDataBlock(data, nameOffsetLookups,
				centralRecords.toArray(ZipCentralDirectoryFileHeaderRecord[]::new),
				centralRecordPositions.stream().mapToLong(Long::longValue).toArray())) {
			try (FileOutputStream out = new FileOutputStream(outputFile)) {
				block.asInputStream().transferTo(out);
			}
		}
		try (FileSystem fileSystem = FileSystems.newFileSystem(outputFile.toPath())) {
			assertThatExceptionOfType(NoSuchFileException.class)
				.isThrownBy(() -> Files.size(fileSystem.getPath("nessted.jar")));
			assertThat(Files.size(fileSystem.getPath("sted.jar"))).isGreaterThan(0);
			assertThat(Files.size(fileSystem.getPath("other-nested.jar"))).isGreaterThan(0);
			assertThat(Files.size(fileSystem.getPath("ace nested.jar"))).isGreaterThan(0);
			assertThat(Files.size(fileSystem.getPath("lti-release.jar"))).isGreaterThan(0);
		}
	}

	@Test // gh-38063
	void createWithDescriptorRecordContainsValidZipContent() throws Exception {
		try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(this.file))) {
			ZipEntry entry = new ZipEntry("META-INF/");
			entry.setMethod(ZipEntry.DEFLATED);
			zip.putNextEntry(entry);
			zip.write(new byte[] { 0, 1, 2, 3, 4, 5, 6, 7, 8 });
			zip.closeEntry();
		}
		byte[] bytes = Files.readAllBytes(this.file.toPath());
		CloseableDataBlock data = new ByteArrayDataBlock(bytes);
		List<ZipCentralDirectoryFileHeaderRecord> centralRecords = new ArrayList<>();
		List<Long> centralRecordPositions = new ArrayList<>();
		ZipEndOfCentralDirectoryRecord eocd = ZipEndOfCentralDirectoryRecord.load(data).endOfCentralDirectoryRecord();
		long pos = eocd.offsetToStartOfCentralDirectory();
		for (int i = 0; i < eocd.totalNumberOfCentralDirectoryEntries(); i++) {
			ZipCentralDirectoryFileHeaderRecord centralRecord = ZipCentralDirectoryFileHeaderRecord.load(data, pos);
			centralRecords.add(centralRecord);
			centralRecordPositions.add(pos);
			pos += centralRecord.size();
		}
		NameOffsetLookups nameOffsetLookups = new NameOffsetLookups(0, centralRecords.size());
		for (int i = 0; i < centralRecords.size(); i++) {
			nameOffsetLookups.enable(i, true);
		}
		nameOffsetLookups.enable(0, true);
		File outputFile = new File(this.tempDir, "out.jar");
		try (VirtualZipDataBlock block = new VirtualZipDataBlock(data, nameOffsetLookups,
				centralRecords.toArray(ZipCentralDirectoryFileHeaderRecord[]::new),
				centralRecordPositions.stream().mapToLong(Long::longValue).toArray())) {
			try (FileOutputStream out = new FileOutputStream(outputFile)) {
				block.asInputStream().transferTo(out);
			}
		}
		byte[] virtualBytes = Files.readAllBytes(outputFile.toPath());
		assertThat(bytes).isEqualTo(virtualBytes);
	}

	@Test
	void createWhenContentIsLargerThanMaxIntContainsValidZipContent() throws IOException {
		long contentSize = 3L * 1024 * 1024 * 1024;
		byte[] name = "large.dat".getBytes(StandardCharsets.UTF_8);
		ZipLocalFileHeaderRecord localRecord = new ZipLocalFileHeaderRecord((short) 10, (short) 0,
				(short) ZipEntry.STORED, (short) 0, (short) 0, 0, (int) contentSize, (int) contentSize,
				(short) name.length, (short) 0);
		ZipCentralDirectoryFileHeaderRecord centralRecord = new ZipCentralDirectoryFileHeaderRecord((short) 10,
				(short) 10, (short) 0, (short) ZipEntry.STORED, (short) 0, (short) 0, 0, (int) contentSize,
				(int) contentSize, (short) name.length, (short) 0, (short) 0, (short) 0, (short) 0, 0, 0);
		long centralRecordPos = localRecord.size() + contentSize;
		SparseDataBlock data = new SparseDataBlock(centralRecordPos + centralRecord.size());
		data.write(0, localRecord.asByteArray());
		data.write(localRecord.size() - name.length, name);
		data.write(centralRecordPos, centralRecord.asByteArray());
		data.write(centralRecordPos + ZipCentralDirectoryFileHeaderRecord.FILE_NAME_OFFSET, name);
		try (VirtualZipDataBlock block = new VirtualZipDataBlock(data, NameOffsetLookups.NONE,
				new ZipCentralDirectoryFileHeaderRecord[] { centralRecord }, new long[] { centralRecordPos })) {
			ZipEndOfCentralDirectoryRecord eocd = new ZipEndOfCentralDirectoryRecord((short) 1, 0, 0);
			assertThat(block.size()).isEqualTo(data.size() + eocd.size());
			ByteBuffer content = ByteBuffer.allocate(16);
			assertThat(block.read(content, localRecord.size())).isEqualTo(16);
			ZipEndOfCentralDirectoryRecord virtualEocd = ZipEndOfCentralDirectoryRecord.load(block)
				.endOfCentralDirectoryRecord();
			assertThat(Integer.toUnsignedLong(virtualEocd.offsetToStartOfCentralDirectory()))
				.isEqualTo(centralRecordPos);
			assertThat(ZipCentralDirectoryFileHeaderRecord.load(block, centralRecordPos)).isEqualTo(centralRecord);
			assertThat(ZipString.readString(block,
					centralRecordPos + ZipCentralDirectoryFileHeaderRecord.FILE_NAME_OFFSET, name.length))
				.isEqualTo("large.dat");
		}
	}

	@Test
	void dataPartReadWhenSizeIsLargerThanMaxUnsignedIntReadsData() throws IOException {
		SparseDataBlock data = new SparseDataBlock(0x100000000L + 32);
		try (VirtualZipDataBlock block = new VirtualZipDataBlock(data, NameOffsetLookups.NONE,
				new ZipCentralDirectoryFileHeaderRecord[0], new long[0])) {
			DataBlock part = block.new DataPart(0, data.size());
			assertThat(part.read(ByteBuffer.allocate(64), 0)).isEqualTo(64);
			assertThat(part.read(ByteBuffer.allocate(64), data.size() - 8)).isEqualTo(8);
			assertThat(part.read(ByteBuffer.allocate(64), data.size())).isEqualTo(-1);
		}
	}

	/**
	 * {@link CloseableDataBlock} that contains zeros apart from explicitly written bytes,
	 * allowing large blocks to be tested without allocating their content.
	 */
	static class SparseDataBlock implements CloseableDataBlock {

		private final long size;

		private final NavigableMap<Long, byte[]> written = new TreeMap<>();

		SparseDataBlock(long size) {
			this.size = size;
		}

		void write(long pos, byte[] bytes) {
			this.written.put(pos, bytes);
		}

		@Override
		public long size() {
			return this.size;
		}

		@Override
		public int read(ByteBuffer dst, long pos) {
			if (pos >= this.size) {
				return -1;
			}
			int count = (int) Math.min(dst.remaining(), this.size - pos);
			for (int i = 0; i < count; i++) {
				dst.put(get(pos + i));
			}
			return count;
		}

		private byte get(long pos) {
			Map.Entry<Long, byte[]> entry = this.written.floorEntry(pos);
			if (entry != null && pos < entry.getKey() + entry.getValue().length) {
				return entry.getValue()[(int) (pos - entry.getKey())];
			}
			return 0;
		}

		@Override
		public void close() {
		}

	}

}
