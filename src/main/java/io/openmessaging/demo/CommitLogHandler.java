package io.openmessaging.demo;

import java.util.concurrent.ConcurrentHashMap;

public class CommitLogHandler {
	private static final ConcurrentHashMap<String, CommitLog> handler = new ConcurrentHashMap<>();

	private CommitLogHandler() {
	}

	public static CommitLog getCommitLogByName(String path, String name) {
		if (handler.containsKey(name)) {
			return handler.get(name);
		} else { // TODO ????
			handler.put(name, new CommitLog(path + "/" + name));
		}
		return handler.get(name);
	}
}