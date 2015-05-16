package btclient;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentLinkedQueue;

import btclient.Pieces.PieceFrag;
import btclient.Pieces.PieceSelector;


public class Peer {
	private Torrent tor;
	private InetSocketAddress addr;
	
	private Pieces pieces;
	private int piecesCount;
	private volatile Pieces.PieceSelector pieceSelector;
	
	private BitSet bs;
	
	private boolean amChoking;
	private boolean amInterested;
	private boolean peerChoking;
	private boolean peerInterested;
	
	private volatile boolean closed;
	
	private boolean acceptedSocket; // created through listenSocket.accept()
	
	private Socket sock;
	private DataInputStream in;
	private DataOutputStream out;
	
	private List<Pieces.PieceFrag> requestedFrags;
	private List<Pieces.PieceFrag> canceledRequests; 
	
	private boolean trusted;
	
	private boolean streaming;
	
	private Thread sender;
	
	private List<Pieces.PeerFrag> peerRequestedFrags;
	
	private long downloadCount;
	
	private long lastMessageTime;
	
	private boolean oneDownloaded;
	
	private static final int CONNECT_TIMEOUT_MS = 1000;
	private static final int HANDSHAKE_TIMEOUT = 3000;
	
	// TODO probably should experiment with changing this value
	public static final int MAX_REQUESTED_FRAGS = 6;
	
	private Peer(Torrent tor)
	{
		this.tor = tor;
		
		pieces = tor.getPieces();
		piecesCount = pieces.getCount();
		pieceSelector = (streaming ? getStreamingSelector() : getRandomSelector());
		
		bs = new BitSet(piecesCount);
		
		amChoking = true;
		amInterested = false;
		peerChoking = true;
		peerInterested = false;
		
		
		requestedFrags = Collections.synchronizedList(new ArrayList<Pieces.PieceFrag>(MAX_REQUESTED_FRAGS));
		canceledRequests = Collections.synchronizedList(new ArrayList<Pieces.PieceFrag>(2*MAX_REQUESTED_FRAGS));
		peerRequestedFrags = Collections.synchronizedList(new ArrayList<Pieces.PeerFrag>());
		
		closed = false;
		
		trusted = false;
		streaming = false;
		
		downloadCount = 0;
	}
	
	public Peer(Torrent tor, InetSocketAddress addr)
	{
		this(tor);
		this.addr = addr;
		acceptedSocket = false;
	}
	
	public Peer(Torrent tor, Socket sock) 
	{
		this(tor);
		this.sock = sock;
		this.addr = new InetSocketAddress(sock.getInetAddress(), sock.getPort());
		acceptedSocket = true;
	}
	
	public void start()
	{
		new Thread(new Runnable() {

			@Override
			public void run() 
			{
				boolean inCount = false;
				
				try {					
					if(!acceptedSocket) {
						sock = null;
						sock = new Socket();
						sock.connect(addr, CONNECT_TIMEOUT_MS);
					}
					in = new DataInputStream(new BufferedInputStream(sock.getInputStream()));
					out = new DataOutputStream(new BufferedOutputStream(sock.getOutputStream()));
					
					if(!acceptedSocket) {
						sendHandshake();
						receiveHandshake();
					} else {
						receiveHandshake();
						sendHandshake();
					}
					
					inCount = true;
					tor.incrementPeersCount();
										
					sender = new Thread(new Runnable() {

						@Override
						public void run() 
						{
							sendMessages();
						}
					});
					sender.start();
					receiveMessages();
				
				} catch(IOException e) {
					/*if(downloadCount > 0) {
						System.err.println("(" + downloadCount + ") " + getInetAddress());
						e.printStackTrace();
					}*/
					closeConnection();
					synchronized(requestedFrags) {
						pieces.pieceReceiveFailed(requestedFrags);
					}
				} finally {
					if(inCount)
						tor.decrementPeersCount();
				}
			}
			
		}).start();
	}
	
	private void sendHandshake() throws IOException
	{
		byte pstrlen = 19;
		byte[] pstr = "BitTorrent protocol".getBytes();
		byte[] reserved = new byte[8];
		out.writeByte(pstrlen);
		out.write(pstr);
		out.write(reserved);
		out.write(tor.getInfoHash());
		out.write(tor.getPeerId());
		out.flush();
	}
	
	private void receiveHandshake() throws IOException
	{
		final int HANDSHAKE_LEN = 49+19;
		byte[] handshake = new byte[HANDSHAKE_LEN];
		int off = 0;
		sock.setSoTimeout(HANDSHAKE_TIMEOUT);
		
		while(off < handshake.length) {
			int ret = in.read(handshake, off, handshake.length-off);
			if(ret == -1)
				throw new IOException("receiveHandshake: stream ended");
			off += ret;
		}
		
		sock.setSoTimeout(0);
						
		if(!Arrays.equals(tor.getInfoHash(), Arrays.copyOfRange(handshake, 28, 48))) 
			throw new IOException("invalid info hash");
		
	}


	private static final int messageChoke = 0;
	private static final int messageUnchoke = 1;
	private static final int messageInterested = 2;
	private static final int messageNotInterested = 3;
	private static final int messageHave = 4;
	private static final int messageBitfield = 5;
	private static final int messageRequest = 6;
	private static final int messagePiece = 7;
	private static final int messageCancel = 8;
	private static final int messagePort = 9;
	
	private void receiveMessages() throws IOException
	{				
		while(!closed) {
			int len;
			try {
				sock.setSoTimeout(1000);
				len = in.readInt();
				sock.setSoTimeout(0);
			} catch(SocketTimeoutException e) {
				continue;
			}
			
			if(len < 0)
				throw new IOException("message with negative length");
			
			if(len == 0) {
				// keep-alive message
				continue;
			}
			
			int id = in.read();
						
			if(id == messageBitfield) { 
				receiveBitfield(len-1);
				continue;
			} 
			
			if(id == messagePiece) {
				receivePiece(len-1);
				continue;
			}
		
			switch(id) {
			case messageChoke:
				if(len != 1)
					throw new IOException("choke message invalid length (" + len + ")");
				peerChoking = true;
				break;
			case messageUnchoke:
				if(len != 1)
					throw new IOException("unchoke message invalid length");
				peerChoking = false;
				break;
			case messageInterested:
				if(len != 1)
					throw new IOException("intersted message invalid length");
				peerInterested = true;
				break;
			case messageNotInterested:
				if(len != 1)
					throw new IOException("not interested message invalid length");
				peerInterested = false;
				break;
			case messageHave:
				if(len != 5)
					throw new IOException("have message invalid length");
				int pieceIndex = in.readInt();
				try {
					bs.set(pieceIndex);
					pieceSelector.addPiece(pieceIndex);
				} catch(IndexOutOfBoundsException e) {
					throw new IOException("invalid pieced index in have message");
				}
				break;
			case messageRequest:
			{	
				if(len != 13)
					throw new IOException("request message invalid length");
				int index = in.readInt();
				int begin = in.readInt();
				int length = in.readInt();
				if(pieces.validFragToSend(index, begin, length)) {
					peerRequestedFrags.add(new Pieces.PeerFrag(index, begin, length));
				}
				break;
			}
			case messageCancel:
				if(len != 13)
					throw new IOException();
				int index = in.readInt();
				int begin = in.readInt();
				int length = in.readInt();
				peerRequestedFrags.remove(new Pieces.PeerFrag(index, begin, length));
				break;	
			default:
				System.err.println("received message with uknown id=" + id);
				// we just ignore this message
				if(in.read(new byte[len-1]) != len-1)
					throw new IOException();
				break;
			}
		}
	}
	
	private void sendMessages() 
	{
		try {
			lastMessageTime = System.currentTimeMillis();
			
			if(pieces.getPiecesVerifiedCount() > 0)
				sendBitfield();
			
			sendUnchoke();
			
			while(!closed) {
				if(tor.isSeeding() && isSeeder()) {
					closeConnection();
					return;
				}
				
				if(System.currentTimeMillis() - lastMessageTime > 60 * 1000) {
					sendKeepAlive();
				}
				
				
				if(requestedFrags.size() < MAX_REQUESTED_FRAGS) {
					sendRequests();
				} else {
					Thread.interrupted();
					try {
						Thread.sleep(100);
					} catch (InterruptedException e) {
					}
				}
				
				if(pieces.isEndGameOn())
					sendCancels();
			
				if(!verifiedPieces.isEmpty()) {
					int index = verifiedPieces.poll();
					sendHaveMsg(index);
				}
				
				Pieces.PeerFrag f = null;
				try {
					f = peerRequestedFrags.remove(0);
				} catch(IndexOutOfBoundsException e) {
				}
				if(f == null)
					continue;
				
				byte[] b = pieces.getFrag(f);	
				out.writeInt(9 + b.length);
				out.writeByte(messagePiece);
				out.writeInt(f.index);
				out.writeInt(f.begin);
				out.write(b);
				
				
				tor.increaseUploadCount(b.length);
			}
		} catch(IOException e) {
			closeConnection();
		}
	}
	
	private void sendKeepAlive() throws IOException
	{
		out.writeInt(0);
		out.flush();
		lastMessageTime = System.currentTimeMillis();
	}

	private void sendHaveMsg(int index) throws IOException
	{
		out.writeInt(5);
		out.writeByte(messageHave);
		out.writeInt(index);
		lastMessageTime = System.currentTimeMillis();
	}
	
	private void sendUnchoke() throws IOException
	{
		if(!amChoking)
			return;
		amChoking = false;
		
		out.writeInt(1);
		out.writeByte(messageUnchoke);
		lastMessageTime = System.currentTimeMillis();
	}
	
	private boolean bitfieldReceived = false;
	
	private void receiveBitfield(int len) throws IOException
	{
		if(bitfieldReceived) {
			// specification says bitfield should be received at most one time
			throw new IOException("bitfield was already received");
		}
		bitfieldReceived = true;
		
		if(len != (piecesCount+7)/8)
			throw new IOException("invalid bitfield size");

		byte[] bitfield = new byte[len];
		int off = 0;
		while(off < len) {
			int ret = in.read(bitfield, off, len-off);
			if(ret == -1)
				throw new IOException();
			off += ret;
		}

		for(int i = 0; i < piecesCount; ++i) {
			if(( bitfield[i/8] & (1 << (7-(i&7))) ) != 0) {
				bs.set(i);
				pieceSelector.addPiece(i);
			}
		}
		
		int piecesCountRound = (piecesCount+7)/8*8;
		for(int i = piecesCount; i < piecesCountRound; ++i) {
			if(( bitfield[i/8] & (1 << (7-(i&7))) ) != 0) {
				throw new IOException("spare bit in bitfield set");
			}
		}
	}
	
	private void sendBitfield() throws IOException
	{
		int count = pieces.getCount();
		byte[] bitfield = new byte[(count+7)/8];
		for(int i = 0; i < pieces.getCount(); ++i) {
			if(pieces.isVerified(i))
				bitfield[i/8] |= (1 << (7-(i&7)));
		}
		
		out.writeInt(1 + bitfield.length);
		out.writeByte(messageBitfield);
		out.write(bitfield);
		out.flush();
	}

	private void receivePiece(int len) throws IOException
	{
		int index = in.readInt();
		int begin = in.readInt();
		int length = len-8;
		Pieces.PieceFrag f = new Pieces.PieceFrag(index, begin/Pieces.FRAG_LENGTH);
		
		boolean canceledFrag = false;
		if(!requestedFrags.remove(f)) {
			if(!canceledRequests.remove(f)) {
				throw new IOException("didnt request such fragment");
			} else {
				canceledFrag = true;
			}
		}
				
		if(length != pieces.getFragLength(f))
			throw new IOException("invalid fragment length");
		
		byte[] block = new byte[length];
		int off = 0;
		
		while(off < length) {
			try {
				int ret = in.read(block, off, length-off);
				if(ret == -1)
					throw new IOException("receivePiece: stream ended");
				off += ret;
				if(!canceledFrag)
					tor.increaseDownloaded(ret);
			} catch(SocketTimeoutException e) {
			}
		}
	
		sock.setSoTimeout(0);
		pieces.receivedFragment(f, block);
		sender.interrupt();
		downloadCount += pieces.getFragLength(f);
		if(downloadCount >= pieces.getPieceLength())
			oneDownloaded = true;
	}
	
	private void sendRequests() throws IOException
	{
		if(!amInterested) {
			amInterested = true;
			out.writeInt(1);
			out.write(2);
		}
		
		while(requestedFrags.size() < MAX_REQUESTED_FRAGS) {
			Pieces.PieceFrag f = pieceSelector.selectPiece(this);
			if(f == null)
				break;
			
			out.writeInt(13);  					
			out.write(messageRequest);	   
			out.writeInt(f.index);
			out.writeInt(f.frag * Pieces.FRAG_LENGTH);
			out.writeInt(pieces.getFragLength(f));
			
			requestedFrags.add(f);
		}
				
		out.flush();
	}
	
	private void sendCancels() throws IOException
	{
		Iterator<Pieces.PieceFrag> it = requestedFrags.iterator();
		while(it.hasNext()) {
			Pieces.PieceFrag f = it.next();
			if(pieces.haveFrag(f)) {
				it.remove();
				canceledRequests.add(f);
				out.writeInt(13);
				out.writeByte(8);
				out.writeInt(f.index);
				out.writeInt(f.frag*Pieces.FRAG_LENGTH);
				out.writeInt(pieces.getFragLength(f));
			}
		}
		
		out.flush();
	}
	
	public InetSocketAddress getAddress()
	{
		return addr;
	}

	public InetAddress getInetAddress()
	{
		return addr.getAddress();
	}
	
	public boolean isClosed()
	{
		return closed;
	}


	public void addPiece(int index) 
	{
		// TODO add lock on bs but i dont think using
		// it unsynchronized will do any harm
		if(bs.get(index))
			pieceSelector.addPiece(index);
	}

	void closeConnection()
	{
		closed = true;
		try {
			if(sock != null)
				sock.close();
		} catch(IOException e) {
			
		}
	}
	
	private PieceSelector getRandomSelector() 
	{
		return new PieceSelector() {
			ArrayList<Integer> l = new ArrayList<>();
			int currentIndex = -1;
			Random gen = new Random();
			
			@Override
			public synchronized void addPiece(int index) 
			{
				l.add(index);
				int i = gen.nextInt(l.size());
				index = l.set(i, index);
				l.set(l.size()-1, index);
			}

			@Override
			public synchronized PieceFrag selectPiece(Peer peer) 
			{
				if(currentIndex != -1) {
					PieceFrag f = pieces.requestPieceFrag(currentIndex);
					if(f != null)
						return f;
				}
				
				while(!l.isEmpty()) {
					int index = l.get(l.size()-1);
					l.remove(l.size()-1);
					if(pieces.requestPiece(index, peer)) {
						currentIndex = index;
						PieceFrag f = pieces.requestPieceFrag(currentIndex);
						if(f != null)
							return f;
					}
				}
				
				return null;
			}

			@Override
			public synchronized void movePiecesTo(PieceSelector ps) 
			{
				for(int index : l)
					ps.addPiece(index);
			}
			
		};
	}
	
	private PieceSelector getStreamingSelector()
	{
		return new PieceSelector() {
			private TreeSet<Integer> set = new TreeSet<>();
			int currentIndex = -1;
			
			@Override
			public synchronized void addPiece(int index) 
			{
				set.add(index);
			}

			@Override
			public synchronized PieceFrag selectPiece(Peer peer) 
			{
				if(currentIndex != -1) {
					PieceFrag f = pieces.requestPieceFrag(currentIndex);
					if(f != null)
						return f;
				}
				
				while(!set.isEmpty()) {
					int index;
					if(oneDownloaded) {
						index = set.pollFirst();
					} else {
						index = set.pollLast();
						if(index < 0.9*piecesCount) {
							set.add(index);
							return null;
						}
					}
					if(pieces.requestPiece(index, peer)) {
						currentIndex = index;
						PieceFrag f = pieces.requestPieceFrag(currentIndex);
						if(f != null)
							return f;
					}
				}
				
				return null;
			}
			
			@Override
			public synchronized void movePiecesTo(PieceSelector ps) 
			{
				for(int index : set)
					ps.addPiece(index);
			}
		};
	}
	
	public boolean isTrusted()
	{
		return trusted;
	}
	
	public void setTrusted()
	{
		trusted = true;
	}

	public void enableStreaming() 
	{
		if(streaming)
			return;
		streaming = true;
		
		Pieces.PieceSelector newPieceSelector = getStreamingSelector();
		pieceSelector.movePiecesTo(newPieceSelector);
		pieceSelector = newPieceSelector;
		oneDownloaded = false;
	}
	
	public void disableStreaming() 
	{
		if(!streaming)
			return;
		streaming = false;
		
		Pieces.PieceSelector newPieceSelector = getRandomSelector();
		pieceSelector.movePiecesTo(newPieceSelector);
		pieceSelector = newPieceSelector;
	}
	
	private Queue<Integer> verifiedPieces = new ConcurrentLinkedQueue<>();
	
	public void addVerifiedPiece(int index) 
	{
		if(!bs.get(index))
			verifiedPieces.add(index);
	}
	
	public boolean isSeeder()
	{
		return bs.cardinality() == pieces.getCount();
	}
}