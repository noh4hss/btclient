package btclient;



import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
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
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;



public class Torrent {
	private enum State {
		IDLE,
		RUNNING	
	}
	
	private List<Tracker> trackers;
	
	private String name;
	
	private Pieces pieces;
	
	private List<DiskIO.FileEntry> files;
	
	private byte[] infoHash;
	private String infoHashStr;
	private byte[] peerId;
	
	private long totalSize;
	private AtomicLong downloadCount;
	private long uploadCount;
	
	private ServerSocket listenSock;
	private int listenPort;
	
	// counting peers that received handshake
	private int maxPeers = 50;
	private AtomicInteger peersCount;
	
	private List<Peer> peers;
	private Set<InetSocketAddress> peersAddresses;
	private List<InetSocketAddress> candidatePeers;
	private Set<InetAddress> blacklist;
	
	private DiskIO diskDelegate;
	
	private String downloadDirectory;
	
	private State state;
	
	private Timer timer;
	private long downloadSpeed;
	private long uploadSpeed;
	
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
			Map<String, BeObject> info = root.get("info").getMap();
			initFilesInfo(info);
			totalSize = 0;
			for(DiskIO.FileEntry e : files)
				totalSize += e.length;
			pieces = new Pieces(this, info, totalSize);
			diskDelegate = new DiskIO(this);
			pieces.setDiskDelegate(diskDelegate);
			
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
			
			uploadCount = 0;
			downloadCount = new AtomicLong(0);
						
			state = State.IDLE;
			
			timer = new Timer();
			
			setDefaultDownloadDirectory();
		
			TorrentCache.createFiles(this, b);
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
			files.add( new DiskIO.FileEntry(path, m.get("length").getLong()) );
			return;
		}
		
		for(BeObject o : m.get("files").getList()) {
			Map<String, BeObject> fm = o.getMap();
			List<String> path = new ArrayList<>();
			path.add(name);
			for(BeObject str : fm.get("path").getList())
				path.add(str.getString());
			files.add( new DiskIO.FileEntry(path, fm.get("length").getLong()) );
		}
	}

	public boolean init(ObjectInputStream in) 
	{
		return pieces.init(in);
	}
	
	public boolean start()
	{
		if(state != State.IDLE)
			return true;
		
		if(connectThread != null && connectThread.isAlive())
			connectThread.interrupt();
		if(announceThread != null && announceThread.isAlive())
			announceThread.interrupt();
		if(listenThread != null && listenThread.isAlive())
			listenThread.interrupt();
		if(peersThread != null && peersThread.isAlive())
			peersThread.interrupt();
		if(diskDelegate != null)
			diskDelegate.forceClose();
		
		if(!initListenSocket())
			return false;
		state = State.RUNNING;
		
		peers = Collections.synchronizedList(new LinkedList<Peer>());
		peersAddresses = Collections.synchronizedSet(new HashSet<InetSocketAddress>());
		candidatePeers = Collections.synchronizedList(new LinkedList<InetSocketAddress>());
		blacklist = Collections.synchronizedSet(new HashSet<InetAddress>());
		
		peersCount = new AtomicInteger(0);
		
		diskDelegate.start();
		pieces.init();
		
		connectThread = new Thread(connectRunnable);
		connectThread.start();
		announceThread = new Thread(announceRunnable);
		announceThread.start();
		listenThread = new Thread(listenRunnable);
		listenThread.start();
		peersThread = new Thread(peersRunnable);
		peersThread.start();
		
		return true;
	}

	public void stop()
	{
		state = State.IDLE;
		synchronized(peers) {
			for(Peer peer : peers)
				peer.forceClose();
			peers.clear();
			peersCount.set(0);
		}
		diskDelegate.close();
		try {
			listenSock.close();
		} catch(IOException e) {		
		}
		
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
	
	
	private Thread connectThread;
	private Thread listenThread;
	private Thread announceThread;
	private Thread peersThread;
	
	private Runnable connectRunnable = new Runnable() {
		@Override
		public void run() 
		{
			while(isRunning()) {
				if(candidatePeers.isEmpty() || peersCount.get() >= maxPeers) {
					try {
						Thread.sleep(200);
					} catch(InterruptedException e) {
					}
					continue;
				}
				
				InetSocketAddress addr = candidatePeers.get(0);
				candidatePeers.remove(0);
				if(peersAddresses.contains(addr) || blacklist.contains(addr.getAddress()))
					continue;
				peersAddresses.add(addr);
				
				Peer peer = new Peer(Torrent.this, addr);
				synchronized(peers) {
					peers.add(peer);
				}
				peersCount.incrementAndGet();
				peer.start();
			}
		}
		
	};
	
	private Runnable listenRunnable = new Runnable() {

		@Override
		public void run() 
		{
			while(isRunning()) {
				if(getPeersCount() >= maxPeers) {
					try {
						Thread.sleep(1000);
					} catch(InterruptedException e) {
						
					}
					continue;
				}
				
				try {
					Socket sock = listenSock.accept();
					if(blacklist.contains(sock.getInetAddress())) {
						try {
							sock.close();
						} catch(IOException e) {
							
						}
					}
					
					Peer peer = new Peer(Torrent.this, sock);
					synchronized(peers) {
						peers.add(peer);
					}
					peersAddresses.add(new InetSocketAddress(sock.getInetAddress(), sock.getPort()));
					peer.start();
					
				} catch(IOException e) {
					e.printStackTrace();
					break;
				}
			}
		}
		
	};
	
	private int trackerIndex;
	private static final int MAX_ANNOUNCE_THREADS = 5;

	private Runnable announceRunnable = new Runnable() {
		@Override
		public void run() 
		{
			for(Tracker tr : trackers)
				tr.announceStarted();
			
			trackerIndex = 0;
			for(int i = 0; i < MAX_ANNOUNCE_THREADS; ++i) {
				new Thread(new Runnable() {
					@Override
					public void run() 
					{
						while(true) {
							Tracker tr;
							synchronized(trackers) {
								if(trackerIndex == trackers.size())
									break;
								tr = trackers.get(trackerIndex);
								++trackerIndex;
							}
							System.err.println("announce on " + tr.getURL());
							addPeers(tr.announceNone());
						}
					}
					
				}).start();
			}
			
			while(isRunning()) {
				for(Tracker tr : trackers) 
					addPeers(tr.announceNone());
			
				try {
					Thread.sleep(1000);
				} catch(InterruptedException e) {
					
				}
			}
			
			for(Tracker tr : trackers) {
				tr.announceStopped();
				tr.announceNone();
			}
		}
		
	};
	
	private Runnable peersRunnable = new Runnable() {
		@Override
		public void run() 
		{
			while(isRunning()) {
				try {
					Thread.sleep(500);
				} catch(InterruptedException e) {
				}
				
				if(!pieces.isEndGameOn() && pieces.getFreePiecesCount() == 0) 
					pieces.startEndGame();
				
				synchronized(peers) {
					Iterator<Peer> it = peers.iterator();
					while(it.hasNext()) {
						Peer peer = it.next();
						if(peer.isClosed()) {
							it.remove();
							peersAddresses.remove(peer.getAddress());
							peersCount.decrementAndGet();
						}
					}
					
					pieces.addCorruptedPieces(peers);
				}
			}
		}
		
	};

	
	
	private void addPeers(List<InetSocketAddress> newPeers)
	{
		if(newPeers == null)
			return;
		
		System.err.println("got list of " + newPeers.size() + " peers");
		candidatePeers.addAll(newPeers);
	}

	public void blacklistAddress(InetAddress addr)
	{
		synchronized(peers) {
			for(Peer peer : peers) {
				if(peer.getInetAddress().equals(addr))
					peer.forceClose();
			}
		}
		blacklist.add(addr);
	}
	
	public Pieces getPieces()
	{
		return pieces;
	}
	
	public DiskIO getDiskDelegate()
	{
		return diskDelegate;
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
	
	private void scheduleSpeedTask()
	{
		timer.schedule(new TimerTask() {
			long lastDownloaded = getDownloadCount();
			long lastUploaded = getUploadCount();
			
			@Override
			public void run() 
			{
				long currentDownloaded = getDownloadCount();
				long currentUploaded = getUploadCount();
				
				downloadSpeed = currentDownloaded - lastDownloaded;
				uploadSpeed = currentUploaded - lastUploaded;
				
				lastDownloaded = currentDownloaded;
				lastUploaded = currentUploaded;
			}
			
		}, 0, 1000);
	}
	
	public List<DiskIO.FileEntry> getFiles()
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
		return uploadCount;
	}
	
	public long getLeftCount()
	{
		return totalSize - getVerifiedDownloadCount();
	}
	
	public long getDownloadSpeed()
	{
		return downloadSpeed;
	}
	
	public long getUploadSpeed()
	{
		return uploadSpeed;
	}
	
	public boolean isRunning()
	{
		return state != State.IDLE;
	}

	public String getInfoHashStr() 
	{
		return infoHashStr;
	}

	public boolean save(ObjectOutputStream out) 
	{
		return pieces.save(out);
	}

	
}
