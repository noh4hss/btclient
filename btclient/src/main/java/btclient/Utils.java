package btclient;

import java.nio.ByteBuffer;

public class Utils {
	public static byte[] urlencode(byte[] b)
	{
		byte digits[] = "0123456789abcdef".getBytes();
		
		ByteBuffer buffer = ByteBuffer.allocate(3*b.length);
		for(int i = 0; i < b.length; ++i) {
			char c = (char)(b[i] & 0xFF);
			if(Character.isDigit(c) || ('a' <= c && c <= 'z') || ('A' <= c && c <= 'Z') || c == '.' || c == '-' || c == '_' || c == '~') {
				buffer.put((byte)c);
			} else if(c == ' ') {
				buffer.put((byte)'+');
			} else {
				buffer.put((byte)'%');
				int x = b[i] & 0xFF;
				buffer.put(digits[(x&0xF0)>>>4]);
				buffer.put(digits[x&0xF]);
			}
		}
		
		buffer.flip();
		byte[] ret = new byte[buffer.limit()];
		buffer.get(ret);
		return ret;
	}
}
