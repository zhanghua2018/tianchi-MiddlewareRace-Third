package io.openmessaging.demo;

import java.nio.ByteBuffer;
import java.util.HashMap;

import io.openmessaging.BytesMessage;
import io.openmessaging.Message;

// 一个 Producer/Consumer 一个，不会有并发
public class MessageStore {

	private HashMap<String, Topic> topicCache = new HashMap<>();

	// test
	private static int ID = 0;
	private int priID;
	{
		priID = ID++;
	}
	private long count0 = 0;
	private long count1 = 0;
	private long count2 = 0;

	// for Producer
	private final ByteBuffer KVToBytesBuffer = ByteBuffer.allocate(1 * 1024 * 1024);

	// for Consumer
	// 存 <bucket name, offsetInIndexFile>
	private final HashMap<String, Long> offsets = new HashMap<>();
	private final ReadBuffer readIndexFileBuffer = new ReadBuffer(Constants.INDEX_TYPE);
	private final ReadBuffer readLogFileBuffer = new ReadBuffer(Constants.LOG_TYPE);
	private int lastMessageOffset = 0;

	public MessageStore() {
	}

	// for Produce
	public void putMessage(String bucket, Message message) {
		Topic topic;
		if ((topic = topicCache.get(bucket)) == null) {
			topic = GlobalResource.getTopicByName(bucket);
			topicCache.put(bucket, topic);
		}
		long start1 = System.nanoTime();
		byte[] messageByte = messageToBytes(message);
		long start2 = System.nanoTime();
		try {
			GlobalResource.putWriteTask(topic.ID, new WriteTask(topic.getWriteBuffer(), messageByte));
			long start3 = System.nanoTime();
			count1 += start2 - start1;
			count2 += start3 - start2;
			count0++;
			if (count0 % 500000 == 0) {
				System.out.println(priID + ":count1=" + (double) count1 / 1000000000);
				System.out.println(priID + ":count2=" + (double) count2 / 1000000000);
			}
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	// for Consumer, 利用自己的 readIndexFileBuffer, readLogFileBuffer 快速消费
	public Message pollMessage(String bucket) {
		Topic topic;
		if ((topic = topicCache.get(bucket)) == null) {
			topic = GlobalResource.getTopicByName(bucket);
			topicCache.put(bucket, topic);
		}
		// Step 1: 读 Index
		long offsetInIndexFile = offsets.getOrDefault(bucket, Long.valueOf(0));
		if (offsetInIndexFile == 0) {
			readIndexFileBuffer.read(topic, 0);
			offsetInIndexFile += Constants.INDEX_SIZE;
		}
		int offset = readIndexFileBuffer.read(topic, offsetInIndexFile);
		if (offset == 0 || offset < lastMessageOffset)
			return null;

		// Step 2: 读 Message
		byte[] messageBytes = readLogFileBuffer.read(topic, lastMessageOffset, offset - lastMessageOffset);
		if (messageBytes == null)
			return null; // ERROR 有 index 无 message

		// Step 3: 更新 Offset
		Message result = bytesToMessage(messageBytes);
		offsets.put(bucket, offsetInIndexFile + Constants.INDEX_SIZE);
		lastMessageOffset = offset;
		return result;
	}

	// for Producer
	/**
	 * message 结构
	 * ------------------------------------------------------------------------
	 * |body.length| body |headers.length|headers|properties.length|properties|
	 * |____int____|byte[]|_____int______|byte[] |_______int_______|__byte[]__|
	 * ------------------------------------------------------------------------
	 */
	public byte[] messageToBytes(Message message) {
		byte[] byteHeaders = defaultKeyValueToBytes((DefaultKeyValue) (message.headers()));
		byte[] byteProperties = defaultKeyValueToBytes((DefaultKeyValue) message.properties());
		byte[] byteBody = ((BytesMessage) message).getBody();
		byte[] result = new byte[3 * 4 + byteBody.length + byteHeaders.length + byteProperties.length];
		int pos = 0;
		Utils.intToByteArray(byteBody.length, result, pos); // byteBody.length
		pos += 4;
		System.arraycopy(byteBody, 0, result, pos, byteBody.length); // byteBody
		pos += byteBody.length;
		Utils.intToByteArray(byteHeaders.length, result, pos); // byteHeaders.length
		pos += 4;
		System.arraycopy(byteHeaders, 0, result, pos, byteHeaders.length); // byteHeaders
		pos += byteHeaders.length;
		Utils.intToByteArray(byteProperties.length, result, pos); // byteProperties.length
		if (byteProperties.length != 0) {
			pos += 4;
			System.arraycopy(byteProperties, 0, result, pos, byteProperties.length); // byteProperties
		}
		// TODO 添加数据压缩
		return result;
	}

//	public byte[] defaultKeyValueToBytes0(DefaultKeyValue kv) {
//		if (kv == null) {
//			return new byte[0];
//		}
//		ByteArrayOutputStream bout = new ByteArrayOutputStream();
//		try (ObjectOutputStream out = new ObjectOutputStream(bout)) {
//			out.writeObject(kv.getKVS());
//			out.flush();
//			byte[] bytes = bout.toByteArray();
//			bout.close();
//			return bytes;
//		} catch (IOException e) {
//			e.printStackTrace();
//		}
//		return null;
//	}

	// for Producer
	public byte[] defaultKeyValueToBytes(DefaultKeyValue kv) {
		if (kv == null) {
			return new byte[0];
		}
		Object value;
		byte[] keyBytes, stringValueBytes;
		KVToBytesBuffer.clear();
		for (String key : kv.keySet()) {
			// key
			keyBytes = key.getBytes();
			KVToBytesBuffer.putInt(keyBytes.length);
			KVToBytesBuffer.put(keyBytes);
			// value
			value = kv.get(key);
			if (value instanceof Integer) {
				KVToBytesBuffer.put((byte) 0);
				KVToBytesBuffer.putInt((Integer) value);
			} else if (value instanceof Long) {
				KVToBytesBuffer.put((byte) 1);
				KVToBytesBuffer.putLong((Long) value);
			} else if (value instanceof Double) {
				KVToBytesBuffer.put((byte) 2);
				KVToBytesBuffer.putDouble((Double) value);
			} else {
				KVToBytesBuffer.put((byte) 3);
				stringValueBytes = ((String) value).getBytes();
				KVToBytesBuffer.putInt(stringValueBytes.length);
				KVToBytesBuffer.put(stringValueBytes);
			}
		}
		KVToBytesBuffer.flip();
		byte[] result = new byte[KVToBytesBuffer.remaining()];
		KVToBytesBuffer.get(result);
		return result;
	}

	// for Consumer
	// message 结构见 messageToBytes
	public Message bytesToMessage(byte[] bytes) {
		// TODO 添加数据解压缩
		int length = Utils.getInt(bytes, 0), pos = 4; // byteBody.length
		byte[] body = new byte[length];
		System.arraycopy(bytes, pos, body, 0, length); // byteBody
		DefaultBytesMessage message = new DefaultBytesMessage(body);
		pos += length;
		length = Utils.getInt(bytes, pos); // byteHeaders.length
		pos += 4;
		bytesToDefaultKeyValue((DefaultKeyValue) (message.headers()), bytes, pos, length); // byteHeaders
		pos += length;
		length = Utils.getInt(bytes, pos); // byteProperties.length
		if (length != 0) { // byteProperties
			pos += 4;
			bytesToDefaultKeyValue((DefaultKeyValue) (message.properties()), bytes, pos, length);
		}
		return message;
	}

	// for Consumer
	public DefaultKeyValue bytesToDefaultKeyValue(DefaultKeyValue kv, byte[] kvBytes, int offset, int length) {
		int end = offset + length;
		int intValue;
		long longValue;
		double doubleValue;
		String key, stringValue;
		while (offset < end) {
			intValue = Utils.getInt(kvBytes, offset);
			offset += 4;
			key = new String(kvBytes, offset, intValue);
			offset += intValue;
			switch (kvBytes[offset++]) {
			case 0: // for int
				intValue = Utils.getInt(kvBytes, offset);
				offset += 4;
				kv.put(key, intValue);
				break;
			case 1: // for long
				longValue = Utils.getLong(kvBytes, offset);
				offset += 8;
				kv.put(key, longValue);
				break;
			case 2: // for double
				doubleValue = Utils.getDouble(kvBytes, offset);
				offset += 8;
				kv.put(key, doubleValue);
				break;
			case 3: // for string
				intValue = Utils.getInt(kvBytes, offset);
				offset += 4;
				stringValue = new String(kvBytes, offset, intValue);
				offset += intValue;
				kv.put(key, stringValue);
				break;
			default:
				System.err.println("ERROR: bytesToDefaultKeyValue");
				break;
			}
		}
		return kv;
	}

	// for Producer
	public void flush() {
		System.out.println(priID + ":count1=" + (double) count1 / 1000000000);
		System.out.println(priID + ":count2=" + (double) count2 / 1000000000);
		long start = System.nanoTime();
		GlobalResource.flush();
		System.out.println(priID + ":count4=" + (double) (System.nanoTime() - start) / 1000000000);
	}
}
