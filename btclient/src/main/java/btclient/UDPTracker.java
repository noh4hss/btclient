package btclient;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;


public class UDPTracker extends Tracker {
	private String host;
	private int port;
	private volatile DatagramSocket sock;
	
	private static class LookupService {
        ExecutorService executor;

        private LookupService() {
            executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r);
                    t.setDaemon(true);
                    return t;
                }
            });
        }

        static LookupService create() {
            return new LookupService();   
        }

        Future<InetAddress> getByName(final String host) {
            FutureTask<InetAddress> future = new FutureTask<>(new Callable<InetAddress>() {
                public InetAddress call() throws UnknownHostException {
                    return InetAddress.getByName(host);
                }
            });
            executor.execute(future);
            return future;
        }
    }
	
	private static final int MAX_TRIES = 3;
	private static final int RECEIVE_TIMEOUT = 5000;
	
	public UDPTracker(Torrent tor, String url)
	{
		super(tor, url);
		try {
			int i = 6;
			while(url.charAt(i) != ':')
				++i;
			host = url.substring(6, i);
			++i;
			
			port = 0;
			while(i < url.length() && Character.isDigit(url.charAt(i))) {
				port = port*10 + url.charAt(i)-'0';
				++i;
			}
		} catch(IndexOutOfBoundsException e) {
			throw new IllegalArgumentException("could not parse tracker url: " + url);
		}
		
		
	}

	@Override
	public List<InetSocketAddress> announce()
	{
		try {
			sock = new DatagramSocket();
		} catch(SocketException e) {
			e.printStackTrace();
			return null;
		}
		
		LookupService service = LookupService.create();
        Future<InetAddress> future = service.getByName(host);
        InetAddress addr;
		try {
			addr = future.get(2L, TimeUnit.SECONDS);
		} catch (InterruptedException e1) {
			return null;
		} catch (ExecutionException e1) {
			return null;
		} catch (TimeoutException e1) {
			return null;
		} 
		
		
		sock.connect(addr, port);
		
		try {
			long connId = getConnectionId();
			return getPeerList(connId);
		} catch(IOException e) {
			//System.err.println("announce on tracker " + url + " " + e.getMessage());
			return null;
		}
	}
	
	private long getConnectionId() throws IOException
	{
		long requestConnectionId = 0x41727101980l;
		int requestAction = 0;
		int requestTransactionId = new Random().nextInt(); 
		ByteBuffer buf = ByteBuffer.allocate(16).putLong(requestConnectionId)
							   					.putInt(requestAction)
							   					.putInt(requestTransactionId);
		
		DatagramPacket request = new DatagramPacket(buf.array(), 16);
		DatagramPacket response = new DatagramPacket(new byte[1024], 1024);
		for(int i = 0; i < MAX_TRIES && !sock.isClosed(); ++i) {
			sock.send(request);
			try {
				sock.setSoTimeout(RECEIVE_TIMEOUT);
				sock.receive(response);
			} catch(SocketTimeoutException e) {
				continue;
			}
			
			int responseLen = response.getLength();
			if(responseLen < 16)
				continue;
			
			DataInputStream in = new DataInputStream(new ByteArrayInputStream(response.getData()));
			int responseAction = in.readInt();
			int responseTransactionID = in.readInt();
			long responseConnectionID = in.readLong();
		
			if(responseTransactionID != requestTransactionId)
				continue;
			
			if(responseAction == 3) {
				//TODO log error message
				throw new IOException("received error message");
			}
			
			if(responseAction == 0)
				return responseConnectionID;
		}
		
		throw new IOException("getting connection id timed out");
	}
	
	private List<InetSocketAddress> getPeerList(long connId) throws IOException
	{
		final int MAX_REQUEST_SIZE = 128;
		ByteBuffer buf = ByteBuffer.allocate(MAX_REQUEST_SIZE);
		buf.putLong(connId); 				// connection_id
		buf.putInt(1); 						// action
		buf.putInt(new Random().nextInt()); // transaction_id
		buf.put(tor.getInfoHash());
		buf.put(tor.getPeerId());
		buf.putLong(getDownloaded());
		buf.putLong(getLeft());
		buf.putLong(getUploaded());
		buf.putInt(getCurrentEvent());
		buf.putInt(0);						// ip
		buf.putInt(new Random().nextInt()); // key
		buf.putInt(200);				    // num_want
		buf.putShort((short)tor.getListenPort());
		buf.putShort((short)0);				// extensions;
	
		DatagramPacket request = new DatagramPacket(buf.array(), buf.position());
		DatagramPacket response = new DatagramPacket(new byte[1024], 1024);
		for(int i = 0; i < MAX_TRIES && !sock.isClosed(); ++i) {
			sock.send(request);
			try {
				sock.setSoTimeout(RECEIVE_TIMEOUT);
				sock.receive(response);
			} catch(SocketTimeoutException e) {
				continue;
			}
			
			int responseLen = response.getLength();
			if(responseLen < 20)
				continue;
			
			DataInputStream in = new DataInputStream(new ByteArrayInputStream(response.getData()));
			int responseAction = in.readInt();
			int responseTransactionId = in.readInt();
			int responseInterval = in.readInt();
			int responseLeechers = in.readInt();
			int responseSeeders = in.readInt();
			
			if(responseAction == 3) {
				//TODO log error message
				throw new IOException("received error message");
			}
			
			if(responseAction == 1) {
				updateNextAnnounceTime(responseInterval);
				
				int peersCount = (responseLen-20)/6;
				List<InetSocketAddress> peers = new ArrayList<>(peersCount);
				for(int j = 0; j < peersCount; ++j) {
					byte[] ip = new byte[4];
					if(in.read(ip) != 4)
						throw new IOException();
					peers.add(new InetSocketAddress(InetAddress.getByAddress(ip), (int)in.readShort() & 0xFFFF));
				}
				return peers;
			}
		}
		
		throw new IOException("getPeerList() timed out");
	}

	@Override
	public void endAnnounce() 
	{
		if(sock != null) {
			sock.close();
		}
	}
	
}