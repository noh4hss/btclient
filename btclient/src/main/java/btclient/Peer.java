package btclient;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

import btclient.Pieces.PeerFrag;
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
	
	private List<Pieces.PieceFrag> requestedFrags;
	private List<Pieces.PieceFrag> canceledRequests; 
	
	private boolean trusted;
	
	private boolean streaming;
	
	private List<Pieces.PeerFrag> peerRequestedFrags;
	
	private long downloadCount;
	
	private long lastMessageTime;
	
	private boolean oneDownloaded;
	
	private SelectionKey key;
	private SocketChannel channel;
	
	private ByteBuffer sendBuffer;
	private ByteBuffer recvBuffer;
	
	private static final int BUFFER_SIZE = (1<<14) + 100;
	
	private static final int HANDSHAKE_LEN = 68;
	private boolean receivedHandshake;
	
	private boolean connected;
	
	private int currentRecvMessage;
	
	public static final int MAX_REQUESTED_FRAGS = 8;
	
	public Peer(Torrent tor, SelectionKey key) 
	{
		this.tor = tor;
		connected = false;
		this.key = key;
		channel = (SocketChannel)key.channel();
		try {
			addr = (InetSocketAddress)channel.getRemoteAddress();
		} catch(IOException e) {
			return;
		}
		
		try {
			if(!channel.finishConnect()) {
				// should not happen
				throw new IOException("channel.finishConnect() returned false");
			}
		} catch(IOException e) {
			//System.err.println(this + "connect(): " + e.getMessage());
			return;
		}
		
		//System.err.println(this + "connect successful");
		
		connected = true;
		key.interestOps(SelectionKey.OP_READ);
		
		pieces = tor.getPieces();
		piecesCount = pieces.getCount();
		streaming = tor.isStreaming();
		pieceSelector = (streaming ? getStreamingSelector() : getRandomSelector());
		bs = new BitSet(piecesCount);
		
		amChoking = true;
		amInterested = false;
		peerChoking = true;
		peerInterested = false;
		
		sendBuffer = ByteBuffer.allocate(BUFFER_SIZE);
		recvBuffer = ByteBuffer.allocate(BUFFER_SIZE);
	
		requestedFrags = new ArrayList<Pieces.PieceFrag>(MAX_REQUESTED_FRAGS);
		canceledRequests = new ArrayList<Pieces.PieceFrag>(2*MAX_REQUESTED_FRAGS);
		peerRequestedFrags = new ArrayList<Pieces.PeerFrag>();
		
		trusted = false;
		receivedHandshake = false;
		
		downloadCount = 0;
		
		sendHandshake();
		recvBuffer.clear();
		recvBuffer.limit(HANDSHAKE_LEN);
	
		key.attach(this);
		
		currentRecvMessage = -1;
	}
	
	private void sendHandshake()
	{
		sendBuffer.clear();
		sendBuffer.put((byte)19);
		sendBuffer.put("BitTorrent protocol".getBytes());
		sendBuffer.put(new byte[8]);
		sendBuffer.put(tor.getInfoHash());
		sendBuffer.put(tor.getPeerId());
		sendBuffer.flip();
		try {
			if(channel.write(sendBuffer) != HANDSHAKE_LEN)
				throw new IOException("non-full write");
		} catch(IOException e) {
			//System.err.println(this + "sending handshake: " + e.getMessage());
			endConnection();
			return;
		}
	}
	
	public void process()
	{
		if(!connected)
			return;
		
		try {
			if(key.isReadable()) {
				receiveMessages();
			}
			if(key.isWritable()) {
				sendMessages();
			}
		} catch(IOException e) {
			if(!(e instanceof ClosedByInterruptException)) {
				//System.err.println(this + e.getMessage());
			}
			endConnection();
		}
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
		if(!receivedHandshake) {
			int nread = channel.read(recvBuffer);
			if(nread == -1)
				throw new IOException("peer ended connection");
			if(recvBuffer.hasRemaining())
				return;
			
			verifyHandshake();
			receivedHandshake = true;
			recvBuffer.clear();
			recvBuffer.limit(0);
			
			tor.incrementPeersCount();
			
			//System.err.println(this + "received handshake");
			
			sendBitfield();
			
			key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
		}
		
		while(!tor.maxDownloadSpeedExceeded()) {
			if(recvBuffer.limit() == 0)
				recvBuffer.limit(4);
			
			int nread = channel.read(recvBuffer);
			if(nread == -1) {
				throw new IOException("peer ended connection");
			}
			
			if(recvBuffer.position() < 4)
				return;
			
			int curPosition = recvBuffer.position();
			recvBuffer.position(0);
			int len = recvBuffer.getInt();
			if(recvBuffer.hasRemaining()) {
				currentRecvMessage = recvBuffer.get();
			}
			recvBuffer.position(curPosition);
			recvBuffer.limit(len+4);
			
			if(currentRecvMessage == messagePiece)
				tor.increaseDownloaded(nread);
			
			if(recvBuffer.hasRemaining())
				return;
			
			parseMessage();		
			recvBuffer.clear();
			recvBuffer.limit(0);
			currentRecvMessage = -1;
		}
	}
	
	void parseMessage() throws IOException
	{
		int len = recvBuffer.limit()-4;
		if(len == 0) {
			//System.err.println(this + "received keep-alive");
			return;
		}
		
		recvBuffer.position(4);
		int id = recvBuffer.get();
		switch(id) {
		case messageChoke:
			//System.err.println(this + "received choke");
			if(len != 1)
				throw new IOException("choke message invalid length");
			receivedChoke();
			break;
		case messageUnchoke:
			//System.err.println(this + "received unchoke");
			if(len != 1)
				throw new IOException("unchoke message invalid length");
			receivedUnchoke();
			break;
		case messageInterested:
			//System.err.println(this + "received interested");
			if(len != 1)
				throw new IOException("intersted message invalid length");
			receivedInterested();
			break;
		case messageNotInterested:
			//System.err.println(this + "received not interested");
			if(len != 1)
				throw new IOException("not interested message invalid length");
			receivedNotInterested();
			break;
		case messageHave:
			if(len != 5)
				throw new IOException("have message invalid length");
			receivedHave();
			break;
		case messageBitfield:
			receivedBitfield(len-1);
			break;
		case messagePiece:
			receivedPiece(len-1);
			break;
		case messageRequest:
			if(len != 13)
				throw new IOException("request message invalid length");
			receivedRequest();
			break;
		case messageCancel:
			if(len != 13)
				throw new IOException("cancel message invalid length");
			receivedCancel();
			break;	
		default:
			//System.err.println(this + "received message with uknown id=" + id);
			// we just ignore this message
			recvBuffer.get(new byte[len-1]);
			break;
		}
	}
	
	private void verifyHandshake() throws IOException
	{
		byte[] handshake = new byte[HANDSHAKE_LEN];
		recvBuffer.position(0);
		recvBuffer.get(handshake);
						
		if(!Arrays.equals(tor.getInfoHash(), Arrays.copyOfRange(handshake, 28, 48))) 
			throw new IOException("invalid info hash in handshake");
	}
	
	private void receivedChoke()
	{
		peerChoking = true;
		canceledRequests.addAll(requestedFrags);
		requestedFrags.clear();
	}
	
	private void receivedUnchoke()
	{
		peerChoking = false;
		key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
	}
	
	private void receivedInterested()
	{
		peerInterested = true;
		key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
	}
	
	private void receivedNotInterested()
	{
		peerInterested = false;
	}
	
	private void receivedHave() throws IOException
	{
		int pieceIndex = recvBuffer.getInt();
		try {
			bs.set(pieceIndex);
			pieceSelector.addPiece(pieceIndex);
		} catch(IndexOutOfBoundsException e) {
			throw new IOException("have message invalid piece index");
		}
		//System.err.println(this + "received have " + pieceIndex);
		
		if(!pieces.isVerified(pieceIndex))
			key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
	}
	
	private boolean bitfieldReceived = false;
	
	private void receivedBitfield(int len) throws IOException
	{
		if(bitfieldReceived) {
			throw new IOException("bitfield was already received");
		}
		bitfieldReceived = true;
		
		if(len != (piecesCount+7)/8)
			throw new IOException("invalid bitfield size");

		byte[] bitfield = new byte[len];
		recvBuffer.get(bitfield);

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
		
		//System.err.println(this + "bitfield received");
	
		key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
	}
	
	private void receivedPiece(int len) throws IOException
	{
		int index = recvBuffer.getInt();
		int begin = recvBuffer.getInt();
		int length = len-8;
		Pieces.PieceFrag f = new Pieces.PieceFrag(index, begin/Pieces.FRAG_LENGTH);
		
		if(!requestedFrags.remove(f)) {
			if(!canceledRequests.remove(f)) {
				throw new IOException("didnt request such fragment");
			}
		}
				
		if(length != pieces.getFragLength(f))
			throw new IOException("invalid fragment length");
		
		byte[] block = new byte[length];
		recvBuffer.get(block);
		
		if(!pieces.haveFrag(f)) {
			pieces.receivedFragment(f, block);
			downloadCount += pieces.getFragLength(f);
			if(downloadCount >= pieces.getPieceLength())
				oneDownloaded = true;
			
			//System.err.println(this + "received piece " + f.index + "," + f.frag);
		}
		
		key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
	}
	
	private void receivedRequest()
	{
		int index = recvBuffer.getInt();
		int begin = recvBuffer.getInt();
		int length = recvBuffer.getInt();
		if(pieces.validFragToSend(index, begin, length)) {
			peerRequestedFrags.add(new Pieces.PeerFrag(index, begin, length));
		}
		//System.err.println(this + "received request " + index + "," + begin/Pieces.FRAG_LENGTH);
		
		key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
	}
	
	private void receivedCancel()
	{
		int index = recvBuffer.getInt();
		int begin = recvBuffer.getInt();
		int length = recvBuffer.getInt();
		peerRequestedFrags.remove(new Pieces.PeerFrag(index, begin, length));
		//System.err.println(this + "received cancel " + index + "," + begin/Pieces.FRAG_LENGTH);
	}
	
	private void sendMessages() throws IOException 
	{	
		channel.write(sendBuffer);
		if(sendBuffer.hasRemaining())
			return;
		
		if(!amInterested) {
			if(!sendInterested())
				return;
		}
			
		if(peerInterested && amChoking) {
			if(!sendUnchoke())
				return;
		}
		
		if(!peerChoking && requestedFrags.size() < MAX_REQUESTED_FRAGS && !sendRequests())
			return;
			
		if(pieces.isEndGameOn() && !sendCancels())
			return;
		
		
		if(!sendHaveMessages())
			return;
		
		while(true) {
			Pieces.PeerFrag f = null;
			try {
				f = peerRequestedFrags.remove(0);
			} catch(IndexOutOfBoundsException e) {
			}
			if(f == null)
				break;
		
			if(!sendFragment(f))
				return;
		}
		
		key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
	}
	
	
	private boolean sendInterested() throws IOException
	{
		if(amInterested)
			return true;
		amInterested = true;
		
		sendBuffer.clear();
		sendBuffer.putInt(1);
		sendBuffer.put((byte)messageInterested);
		sendBuffer.flip();
		channel.write(sendBuffer);
		//System.err.println(this + "sent interested");
		return !sendBuffer.hasRemaining();
	}

	private boolean sendHaveMessages() throws IOException
	{
		sendBuffer.clear();
		while(!verifiedPieces.isEmpty() && sendBuffer.remaining() >= 9) {
			int index = verifiedPieces.poll();
			sendBuffer.putInt(5);
			sendBuffer.put((byte)messageHave);
			sendBuffer.putInt(index);
			
			//System.err.println(this + "sent have " + index);
		}
		
		sendBuffer.flip();
		channel.write(sendBuffer);
		return !sendBuffer.hasRemaining();
	}

	private void sendBitfield()
	{
		int count = pieces.getCount();
		byte[] bitfield = new byte[(count+7)/8];
		for(int i = 0; i < pieces.getCount(); ++i) {
			if(pieces.isVerified(i))
				bitfield[i/8] |= (1 << (7-(i&7)));
		}
		
		sendBuffer.clear();
		sendBuffer.putInt(1 + bitfield.length);
		sendBuffer.put((byte)messageBitfield);
		sendBuffer.put(bitfield);
		sendBuffer.flip();
		
		//System.err.println(this + "sent bitfield");
	}
	
	private boolean sendFragment(PeerFrag f) throws IOException
	{
		byte[] b = pieces.getFrag(f);	
		sendBuffer.clear();
		sendBuffer.putInt(9 + b.length);
		sendBuffer.put((byte)messagePiece);
		sendBuffer.putInt(f.index);
		sendBuffer.putInt(f.begin);
		sendBuffer.put(b);
		sendBuffer.flip();
		channel.write(sendBuffer);
		tor.increaseUploadCount(b.length);
		//System.err.println(this + "sent fragment " + f.index + "," + f.begin/Pieces.FRAG_LENGTH);
		return !sendBuffer.hasRemaining();
	}

	
	private boolean sendKeepAlive() throws IOException
	{
		sendBuffer.clear();
		sendBuffer.putInt(0);
		sendBuffer.flip();
		channel.write(sendBuffer);
		return !sendBuffer.hasRemaining();
	}
	
	private boolean sendUnchoke() throws IOException
	{
		if(!amChoking)
			return true;
		amChoking = false;
		
		
		sendBuffer.clear();
		sendBuffer.putInt(1);
		sendBuffer.put((byte)messageUnchoke);
		sendBuffer.flip();
		channel.write(sendBuffer);
		//System.err.println(this + "sent unchoke");
		return !sendBuffer.hasRemaining();		
	}
	
	private boolean sendRequests() throws IOException
	{
		sendBuffer.clear();
		
		while(requestedFrags.size() < MAX_REQUESTED_FRAGS) {
			Pieces.PieceFrag f = pieceSelector.selectPiece(this);
			if(f == null)
				break;
			
			sendBuffer.putInt(13);  					
			sendBuffer.put((byte)messageRequest);	   
			sendBuffer.putInt(f.index);
			sendBuffer.putInt(f.frag * Pieces.FRAG_LENGTH);
			sendBuffer.putInt(pieces.getFragLength(f));
			
			requestedFrags.add(f);
			//System.err.println(this + "sent request " + f.index + "," + f.frag);
		}
				
		sendBuffer.flip();
		channel.write(sendBuffer);
		return !sendBuffer.hasRemaining();
	}
	
	private boolean sendCancels() throws IOException
	{
		sendBuffer.clear();
		
		Iterator<Pieces.PieceFrag> it = requestedFrags.iterator();
		while(it.hasNext()) {
			Pieces.PieceFrag f = it.next();
			if(pieces.haveFrag(f)) {
				it.remove();
				canceledRequests.add(f);
				sendBuffer.putInt(13);
				sendBuffer.put((byte)8);
				sendBuffer.putInt(f.index);
				sendBuffer.putInt(f.frag*Pieces.FRAG_LENGTH);
				sendBuffer.putInt(pieces.getFragLength(f));
				//System.err.println(this + "sent cancel " + f.index + "," + f.frag);
			}
		}
		
		sendBuffer.flip();
		channel.write(sendBuffer);
		return !sendBuffer.hasRemaining();
	}
	
	public InetSocketAddress getAddress()
	{
		return addr;
	}


	public void addPiece(int index) 
	{
		if(bs.get(index))
			pieceSelector.addPiece(index);
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
	
	@Override
	public String toString()
	{
		return "Peer(" + addr + ") ";
	}

	public InetAddress getInetAddress() 
	{
		return addr.getAddress();
	}

	public void endConnection()
	{
		if(!connected)
			return;
			
		if(receivedHandshake)
			tor.decrementPeersCount();
		
		connected = false;
		key.cancel();
		try {
			channel.close();
		} catch (IOException e) {
		}
	}
	
	public boolean isConnected() 
	{
		return connected;
	}
}