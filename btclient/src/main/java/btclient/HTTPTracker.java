package btclient;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.Socket;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import btclient.BeObject.ParsingError;



public class HTTPTracker extends Tracker {
	private URL url;
	private volatile HttpURLConnection conn;
	
	public HTTPTracker(Torrent tor, String urlString)
	{
		super(tor, urlString);
				
		try {
			url = new URL(urlString);
			if(!url.getProtocol().equals("http"))
				throw new IllegalArgumentException("could not parse tracker url: " + url);

		} catch(MalformedURLException e) {
			throw new IllegalArgumentException("could not parse tracker url: " + url);
		}
	}

	@Override
	public List<InetSocketAddress> announce() 
	{
		try {
			try {
				conn = (HttpURLConnection)(new URL(url + getCgiArgsString())).openConnection();
			} catch(MalformedURLException e) {
				e.printStackTrace();
				return null;
			}
			
			conn.setReadTimeout(5 * 1000);
			if(conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
				System.err.println(conn.getResponseCode());
				return null;
			}
			
			long len = conn.getContentLengthLong();
			byte[] buf = new byte[4096];
			if(len > buf.length)
				throw new IOException("tracker response too long");
			
			InputStream in = conn.getInputStream();
			int off = 0;
			while(off < len) {
				int n = in.read(buf, off, (int)len-off);
				if(n == -1)
					throw new IOException();
				off += n;
			}
			
			in.close();
			conn.disconnect();
			
			return parseResponse(buf);
			
		} catch(IOException e) {
			conn.disconnect();
			return null;
		}
	}

	private List<InetSocketAddress> parseResponse(byte[] b) 
	{
		try {
			Map<String, BeObject> root = BeObject.parse(b).getMap();
			if(root.containsKey("failure reason")) {
				System.err.println("tracker " + getURL() + " returned: " + root.get("failure reason").getString());
				return null;
			}
			
			if(root.containsKey("min interval"))
				updateNextAnnounceTime((int)root.get("min interval").getLong());
			
			if(root.containsKey("interval"))
				updateNextAnnounceTime((int)root.get("interval").getLong());
			
			
			BeObject peersObject = root.get("peers");
			try {
				b = peersObject.getBytes();
				DataInputStream in = new DataInputStream(new ByteArrayInputStream(b));
				
				int peersCount = b.length/6;
				List<InetSocketAddress> peers = new ArrayList<>(peersCount);
				for(int j = 0; j < peersCount; ++j) {
					byte[] ip = new byte[4];
					if(in.read(ip) != 4)
						throw new IOException();
					peers.add(new InetSocketAddress(InetAddress.getByAddress(ip), (int)in.readShort() & 0xFFFF));
				}
				return peers;
			} catch(ClassCastException e) {
				List<BeObject> l = peersObject.getList();
				List<InetSocketAddress> peers = new ArrayList<>(l.size());
				
				for(BeObject o : l) {
					Map<String, BeObject> m = o.getMap();
					InetSocketAddress addr = new InetSocketAddress(m.get("ip").getString(), (int)m.get("port").getLong());
					if(!addr.isUnresolved())
						peers.add(addr);
				}
				
				return peers;
			}
			
			
			
		} catch(ParsingError | Exception e) {
			e.printStackTrace();
			return null;
		}
		
	}

	private String getCgiArgsString() 
	{
		ByteBuffer buf = ByteBuffer.allocate(1000);
		buf.put((byte)'?');
		
		buf.put("info_hash=".getBytes());
		buf.put(Utils.urlencode(tor.getInfoHash()));
		
		buf.put("&peer_id=".getBytes());
		buf.put(Utils.urlencode(tor.getPeerId()));
		
		buf.put("&port=".getBytes());
		buf.put(Integer.toString(tor.getListenPort()).getBytes());
		
		buf.put("&uploaded=".getBytes());
		buf.put(Long.toString(getUploaded()).getBytes());
		
		buf.put("&downloaded=".getBytes());
		buf.put(Long.toString(getDownloaded()).getBytes());
		
		buf.put("&left=".getBytes());
		buf.put(Long.toString(getLeft()).getBytes());
		
		buf.put("&compact=1".getBytes());
		
		String eventString = getCurrentEventString();
		if(!eventString.equals("none")) {
			buf.put("&event=".getBytes());
			buf.put(eventString.getBytes());
		}
		
		buf.put("&numwant=".getBytes());
		buf.put(Integer.toString(getNumWant()).getBytes());
		
		buf.flip();
		
		byte[] arr = new byte[buf.limit()];
		buf.get(arr);
		
		return new String(arr);
	}

	@Override
	public void endAnnounce() 
	{
		if(conn != null) {
			conn.disconnect();
			try {
				conn.getOutputStream().close();
				conn.getInputStream().close();
			} catch (IOException e) {
			}
		}
	}
}