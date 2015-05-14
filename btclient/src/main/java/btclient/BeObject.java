package btclient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

public class BeObject {
	private Object o;
	
	// if we were parsing from a byte array we record 
	// where in the array this object is stored
	private int startPos;
	private int endPos;
	
	private BeObject()
	{
		
	}
	
	private BeObject(Object o)
	{
		this.o = o;
	}
	
	private BeObject(Object o, int startPos, int endPos)
	{
		this.o = o;
		this.startPos = startPos;
		this.endPos = endPos;
	}
	
	static class ParsingError extends Error {
		
	}
	
	private static BeObject parseLong(byte[] b, int[] pos)
	{
		int startPos = pos[0];
		if(b[pos[0]] != 'i')
			throw new ParsingError();
		++pos[0];
		
		boolean minus = false;
		if(b[pos[0]] == '-') {
			minus = true;
			++pos[0];
		}
		
		long x = 0;
		while(b[pos[0]] != 'e') {
			if(!Character.isDigit(b[pos[0]]))
				throw new ParsingError();
			x = x*10 + b[pos[0]]-'0';
			++pos[0];
		}
		++pos[0];
		
		if(minus)
			x = -x;
	
		return new BeObject(x, startPos, pos[0]);
	}
	
	private static BeObject parseList(byte[] b, int[] pos)
	{
		int startPos = pos[0];
		if(b[pos[0]] != 'l')
			throw new ParsingError();
		++pos[0];
		
		ArrayList<BeObject> l = new ArrayList<>();
		while(b[pos[0]] != 'e') {
			l.add(parse(b, pos));
		}
		++pos[0];
		
		return new BeObject(l, startPos, pos[0]);
	}
	
	private static BeObject parseDictionary(byte[] b, int[] pos)
	{
		int startPos = pos[0];
		if(b[pos[0]] != 'd')
			throw new ParsingError();
		++pos[0];
		
		HashMap<String, BeObject> m = new HashMap<>();
		while(b[pos[0]] != 'e') {
			m.put(parseString(b, pos).getString(), parse(b, pos));
		}
		++pos[0];
		
		return new BeObject(m, startPos, pos[0]);
	}
	
	private static BeObject parseString(byte[] b, int[] pos)
	{
		int startPos = pos[0];
		int len = 0;
		while(b[pos[0]] != ':') {
			if(!Character.isDigit(b[pos[0]]))
				throw new ParsingError();
			len = len*10 + b[pos[0]]-'0';
			++pos[0];
		}
		++pos[0];
		
		byte[] s = Arrays.copyOfRange(b, pos[0], pos[0]+len);
		pos[0] += len;
		return new BeObject(s, startPos, pos[0]);
	}
	
	private static BeObject parse(byte[] b, int[] pos)
	{
		BeObject bo = new BeObject();
		if(b[pos[0]] == 'i')
			return parseLong(b, pos);
		if(b[pos[0]] == 'l')
			return parseList(b, pos);
		if(b[pos[0]] == 'd')
			return parseDictionary(b, pos);
		return parseString(b, pos);
	}
	
	
	
	public static BeObject parse(byte[] b) throws ParsingError
	{
		return parse(b, new int[] { 0 });
	}
	
	public int getStartPosition()
	{
		return startPos;
	}
	
	public int getEndPosition()
	{
		return endPos;
	}
	
	
	public long getLong()
	{
		return (long)o;
	}
	
	public byte[] getBytes()
	{
		return (byte[])o;
	}
	
	public String getString()
	{
		return new String((byte[])o);
	}
	
	public List<BeObject> getList()
	{
		return (List<BeObject>)o;
	}
	
	public Map<String, BeObject> getMap()
	{
		return (Map<String, BeObject>)o;
	}
	
	private static byte[] encodeBytes(byte[] b)
	{
		String len = Integer.toString(b.length);
		byte[] ret = new byte[len.length() + 1 + b.length];
		System.arraycopy(len.getBytes(), 0, ret, 0, len.length());
		ret[len.length()] = ':';
		System.arraycopy(b, 0, ret, len.length()+1, b.length);
		return ret;
	}
	
	private static byte[] encodeLong(long x) 
	{
		String s = Long.toString(x);
		byte[] ret = new byte[2+s.length()];
		ret[0] = 'i';
		System.arraycopy(s.getBytes(), 0, ret, 1, s.length());
		ret[1+s.length()] = 'e';
		return ret;
	}
	
	private static byte[] encodeString(String s)
	{
		return encodeBytes(s.getBytes());
	}
	
	private static byte[] encodeList(List<Object> l)
	{
		List<byte[]> lb = new ArrayList<>(l.size());
		int len = 2;
		for(Object o : l) {
			byte[] b = encode(o);
			lb.add(b);
			len += b.length;
		}
		
		byte[] ret = new byte[len];
		ret[0] = 'l';
		len = 1;
		for(byte[] b : lb) {
			System.arraycopy(b, 0, ret, len, b.length);
			len += b.length;
		}
		ret[len] = 'e';
		return ret;
	}
	
	private static byte[] encodeMap(Map<String, Object> m)
	{
		List<Object> l = new ArrayList<>(2*m.size());
		for(Map.Entry<String, Object> e : m.entrySet()) {
			l.add(e.getKey());
			l.add(e.getValue());
		}
		
		byte[] ret = encodeList(l);
		ret[0] = 'd';
		return ret;
	}

	public static byte[] encode(Object o) 
	{
		if(o instanceof Long)
			return encodeLong((Long)o);
		if(o instanceof byte[])
			return encodeBytes((byte[])o);
		if(o instanceof String)
			return encodeString((String)o);
		if(o instanceof List)
			return encodeList((List<Object>)o);
		if(o instanceof Map)
			return encodeMap((Map<String, Object>)o);
		
		return null;
	}
}