package io.openmessaging.demo;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;

public class PersistenceFile {
	public final String path;
	public final String fileName;

	private final RandomAccessFile randomAccessFile;
	private final FileChannel fileChannel;

	public PersistenceFile(String path, String fileName) {
		this.path = path;
		this.fileName = fileName;
		File file = new File(path, fileName);
		try {
			if (!file.exists()) {
				file.createNewFile();
			}
			randomAccessFile = new RandomAccessFile(file, "rw");
			fileChannel = randomAccessFile.getChannel();
		} catch (IOException e) {
			e.printStackTrace();
			throw new ClientOMSException("PersistenceFile create failure", e);
		}
	}

	public FileChannel getFileChannel() {
		return this.fileChannel;
	}

}
