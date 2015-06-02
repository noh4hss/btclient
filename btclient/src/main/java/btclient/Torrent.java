package btclient;



import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.*;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;



public class Torrent {
	private enum State {
		IDLE,
		RUNNING,
		SEEDING,
		DYING
	}
	
	private List<Tracker> trackers;
	
	private String name;
	
	private Pieces pieces;
	
	private List<FragmentSaver.FileEntry> files;
	
	private byte[] infoHash;
	private String infoHashStr;
	private byte[] peerId;
	
	private long totalSize;
	private AtomicLong downloadCount;
	private AtomicLong uploadCount;
	
	private ServerSocket listenSock;
	private int listenPort;
	
	// counting peers that received handshake
	private int maxPeers = 80;
	private AtomicInteger peersCount;
	
	private List<Peer> peers;
	private Set<InetSocketAddress> peersAddresses;
	private List<InetSocketAddress> candidatePeers;
	private Set<InetAddress> blacklist;
	
	private String downloadDirectory;
	
	private volatile State state;
	
	private Announcer announcer;
	private FragmentSaver fragmentSaver;

	private static Serializer serializer;
	
	private volatile boolean streaming;
	private volatile boolean completed;
	
	private boolean uploadOn = true;
	
	private Selector selector;
	
	public Torrent(File file) throws IOException
	{
		byte[] b = null;
		try(FileInputStream in = new FileInputStream(file)) {
			b = new byte[in.available()];
			in.read(b);
		}
		
		try {
			Map<String, BeObject> root = BeObject.parse(b).getMap();
			initTrackerInfo(root);
			announcer = new Announcer(this, trackers);
			Map<String, BeObject> info = root.get("info").getMap();
			initFilesInfo(info);
			
			setDefaultDownloadDirectory();
			
			totalSize = 0;
			for(FragmentSaver.FileEntry e : files)
				totalSize += e.length;
			pieces = new Pieces(this, info, totalSize);
			fragmentSaver = new FragmentSaver(this);
			pieces.setFragmentSaver(fragmentSaver);
			
			int startPos = root.get("info").getStartPosition();
			int endPos = root.get("info").getEndPosition();
			MessageDigest md = MessageDigest.getInstance("SHA-1");
			infoHash = md.digest(Arrays.copyOfRange(b, startPos, endPos));

			final char[] hexArray = "0123456789ABCDEF".toCharArray();
			char[] hexChars = new char[infoHash.length * 2];
			for ( int j = 0; j < infoHash.length; j++) {
				int v = infoHash[j] & 0xFF;
				hexChars[j * 2] = hexArray[v >>> 4];
				hexChars[j * 2 + 1] = hexArray[v & 0x0F];
			}
			infoHashStr = new String(hexChars);
			
			
			peerId = new byte[20];
			ByteArrayOutputStream out = new ByteArrayOutputStream(20);
			out.write("btclient".getBytes());
			int x = new Random().nextInt();
			out.write(x & 0xFF);
			out.write((x & 0xFF00) >>> 8);
			out.write((x & 0xFF0000) >>> 16);
			out.write((x & 0xFF000000) >>> 24);
			System.arraycopy(out.toByteArray(), 0, peerId, 0, out.size());
			
			uploadCount = new AtomicLong(0);
			downloadCount = new AtomicLong(0);
						
			state = State.IDLE;
			
			serializer.createFiles(this, b);
		
			streaming = false;
			completed = false;
			
			peers = Collections.synchronizedList(new LinkedList<Peer>());
			peersCount = new AtomicInteger(0);
		} catch(Exception e) {
			throw new IOException("invalid file format");
		}
	}

	private void initTrackerInfo(Map<String, BeObject> m)
	{
		trackers = new ArrayList<>();
		if(m.get("announce-list") == null) {
			try {
				trackers.add(Tracker.createTracker(this, m.get("announce").getString()));
			} catch(IllegalArgumentException e) {
				System.err.println(e.getMessage());
			}
			return;
		}
		
		for(BeObject tier : m.get("announce-list").getList())
			for(BeObject url : tier.getList()) {
				try {
					trackers.add(Tracker.createTracker(this, url.getString()));
				} catch(IllegalArgumentException e) {
					System.err.println(e.getMessage());
				}
			}
	}
	
	
	private void initFilesInfo(Map<String, BeObject> m)
	{
		name = m.get("name").getString();
		files = new ArrayList<>();
		
		if(m.get("files") == null) {
			List<String> path = new ArrayList<>();
			path.add(name);
			files.add( new FragmentSaver.FileEntry(path, m.get("length").getLong()) );
			return;
		}
		
		for(BeObject o : m.get("files").getList()) {
			Map<String, BeObject> fm = o.getMap();
			List<String> path = new ArrayList<>();
			path.add(name);
			for(BeObject str : fm.get("path").getList())
				path.add(str.getString());
			files.add( new FragmentSaver.FileEntry(path, fm.get("length").getLong()) );
		}
	}

	public boolean init(ObjectInputStream in) 
	{
		try {
			downloadDirectory = (String)in.readObject();
			return pieces.init(in);

		} catch (IOException | ClassNotFoundException e) {
			e.printStackTrace();
			return false;
		} 
	}
	
	public boolean start()
	{
		if(state != State.IDLE)
			return true;
		
		if(completed) {
			if(!uploadOn)
				return false;
			state = State.SEEDING;
		} else {
			state = State.RUNNING;
		}
		
		if(!initListenSocket()) {
			state = State.IDLE;
			return false;
		}
		
		peersAddresses = Collections.synchronizedSet(new HashSet<InetSocketAddress>());
		candidatePeers = Collections.synchronizedList(new LinkedList<InetSocketAddress>());
		blacklist = Collections.synchronizedSet(new HashSet<InetAddress>());
		
		peersCount.set(0);
		
		pieces.init();
		
		try {
			selector = Selector.open();
		} catch(IOException e) {
			System.err.println(this + "Selector.open() failed: " + e.getMessage());
			return false;
		}
		
		fragmentSaver.start();
		peerManager.start();
		announcer.start();
		
		return true;
	}

	public void stop()
	{
		if(state == State.IDLE)
			return;
		
		state = State.IDLE;
		
		announcer.stop();
		
		try {
			listenSock.close();
		} catch(IOException e) {		
		}
		
		peerManager.stop();
		fragmentSaver.stop();
	}
	
	public void forceStop()
	{
		stop();
		announcer.forceStop();
	}
	
	
	private boolean initListenSocket()
	{
		try {
			listenSock = new ServerSocket(0);
			listenPort = listenSock.getLocalPort();
			return true;
		} catch(IOException e) {
			e.printStackTrace();
			return false;
		}
	}
	
	
	private TorrentWorker peerManager = new TorrentWorker() {
		private Thread mainThread;
		
		@Override
		public void start() 
		{
			mainThread = new Thread(new Runnable() {
				
				@Override
				public void run() 
				{
					while(!Thread.currentThread().isInterrupted()) {
						try {
							selector.select(1000);
						} catch (IOException e) {
							System.err.println(Torrent.this + "select() failed: " + e.getMessage());
							break;
						}
						Set<SelectionKey> keys = selector.selectedKeys();
						
						Iterator<SelectionKey> it = keys.iterator();
						while(it.hasNext()) {
							SelectionKey key = it.next();
							if((key.interestOps() & SelectionKey.OP_CONNECT) != 0) {
								if(peers.size() < maxPeers) {
									peers.add(new Peer(Torrent.this, key));
								} else {
									try {
										((SocketChannel)key.channel()).close();
									} catch (IOException e) {
									}
								}
							} else {
								Peer peer = (Peer)key.attachment();
								peer.process();
							}
							
							it.remove();
						}
						
						removeUnconnectedPeers();
						
						while(peers.size() < maxPeers && !candidatePeers.isEmpty()) {
							InetSocketAddress addr = candidatePeers.get(0);
							candidatePeers.remove(0);
							if(peersAddresses.contains(addr) || blacklist.contains(addr.getAddress()))
								continue;
							peersAddresses.add(addr);
							
							try {
								SocketChannel channel = SocketChannel.open();
								channel.configureBlocking(false);
								channel.connect(addr);
								channel.register(selector, SelectionKey.OP_CONNECT);
							} catch (IOException e) {
								continue;
							}
							
							
						}
						
						if(!pieces.isEndGameOn() && pieces.getFreePiecesCount() == 0 && !completed) 
							pieces.startEndGame();
					}
					
					synchronized(peers) {
						for(Peer peer : peers)
							peer.endConnection();
						peers.clear();
					}
				}

				private void removeUnconnectedPeers() 
				{
					synchronized(peers) {
						Iterator<Peer> it = peers.iterator();
						while(it.hasNext()) {
							Peer peer = it.next();
							if(!peer.isConnected())
								it.remove();
						}
					}
				}
			});
			
			mainThread.start();
		}

		@Override
		public void stop() 
		{
			mainThread.interrupt();
			try {
				mainThread.join();
			} catch(InterruptedException e) {
				
			}
		}
		
	};
	
	void addPeers(List<InetSocketAddress> newPeers)
	{
		if(newPeers == null)
			return;
		
		candidatePeers.addAll(newPeers);
	}

	public void blacklistAddress(InetAddress addr)
	{
		synchronized(peers) {
			for(Peer peer : peers) {
				if(peer.getInetAddress().equals(addr))
					peer.endConnection();
			}
		}
		blacklist.add(addr);
	}
	
	public Pieces getPieces()
	{
		return pieces;
	}
	
	public FragmentSaver getFragmentSaver()
	{
		return fragmentSaver;
	}

	public void increaseDownloaded(int length) 
	{
		downloadCount.getAndAdd(length);
	}
	
	public int getPeersCount()
	{
		return peersCount.get();
	}
	
	private void setDefaultDownloadDirectory()
	{
		String base = System.getProperty("user.home");
		
		downloadDirectory = base + File.separator + "Downloads";
		if(new File(downloadDirectory).isDirectory())
			return;
		
		downloadDirectory = base + File.separator + "Pobrane";
		if(new File(downloadDirectory).isDirectory())
			return;
		
		downloadDirectory = base;
	}
	
	public void setDownloadDirectory(String downloadDirectory)
	{
		if(new File(downloadDirectory).isDirectory())
			this.downloadDirectory = downloadDirectory;
	}
	
	public String getDownloadDirectory()
	{
		return downloadDirectory;
	}
	
	
	public List<FragmentSaver.FileEntry> getFiles()
	{
		return files;
	}
	

	public byte[] getInfoHash()
	{
		return infoHash;
	}
	
	public byte[] getPeerId()
	{
		return peerId;
	}
	
	public int getListenPort()
	{
		return listenPort;
	}
	
	public int getPiecesVerifiedCount()
	{
		return pieces.getPiecesVerifiedCount();
	}

	public int getPiecesCount() 
	{
		return pieces.getCount();
	}

	public long getTotalSize() 
	{
		return totalSize;
	}

	public long getVerifiedDownloadCount()
	{
		return pieces.getVerifiedDownloadCount();
	}

	public String getName() 
	{
		return name;
	}
	
	public long getDownloadCount()
	{
		return downloadCount.get();
	}
	
	public long getUploadCount()
	{
		return uploadCount.get();
	}
	
	public void increaseUploadCount(long delta)
	{
		uploadCount.addAndGet(delta);
	}
	
	public long getLeftCount()
	{
		return totalSize - getVerifiedDownloadCount();
	}

	public String getInfoHashStr() 
	{
		return infoHashStr;
	}

	public boolean save(ObjectOutputStream out) 
	{
		try {
			out.writeObject(downloadDirectory);
			return pieces.save(out);
		} catch(IOException e) {
			e.printStackTrace();
			return false;
		}
	}
	
	public static void setSerializer(Serializer serializer)
	{
		Torrent.serializer = serializer;
	}
	
	public void enableStreaming()
	{
		if(streaming)
			return;
		streaming = true;
		
		synchronized(peers) {
			for(Peer peer : peers)
				peer.enableStreaming();
		}
	}
	
	public void disableStreaming()
	{	
		if(!streaming)
			return;
		streaming = false;
		
		synchronized(peers) {
			for(Peer peer : peers)
				peer.disableStreaming();
		}
	}

	public void addPieceToPeers(int index) 
	{
		synchronized(peers) {
			for(Peer peer : peers)
				peer.addPiece(index);
		}
	}
	
	public void remove()
	{
		stop();
		state = State.DYING;
	}
	
	public void addVerifiedPieceToPeers(int index)
	{
		synchronized(peers) {
			for(Peer peer : peers) {
				peer.addVerifiedPiece(index);
			}
		}
	}
	
	public boolean isIdle()
	{
		return state == State.IDLE;
	}
	
	public boolean isRunning()
	{
		return state == State.RUNNING;
	}
	
	public boolean isSeeding()
	{
		return state == State.SEEDING;
	}

	public boolean isCompleted()
	{
		return completed;
	}
	
	public void setCompleted() 
	{
		completed = true;
		if(state != State.RUNNING)
			return;
		
		if(!uploadOn) {
			stop();
		} else {
			state = State.SEEDING;
		}
	}

	public void incrementPeersCount() 
	{
		peersCount.incrementAndGet();
	}

	public void decrementPeersCount() 
	{
		peersCount.decrementAndGet();
	}

	public boolean isUploadOn()
	{
		return uploadOn;
	}
	
	public boolean isDead()
	{
		return state == State.DYING;
	}

	public boolean isStreaming() 
	{
		return streaming;
	}

	public void verifyFromLocalData() 
	{
		pieces.verifyFromLocalData();	
	}
	
	@Override
	public String toString()
	{
		return "Torrent(" + getName() + "): ";
	}
}
