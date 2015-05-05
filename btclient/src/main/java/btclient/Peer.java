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
import java.util.Iterator;

public class Peer {
	private Torrent tor;
	private InetSocketAddress addr;
	
	private Pieces pieces;
	private int piecesCount;
	private Pieces.PieceSelector pieceSelector;
	
	private BitSet bs;
	
	private boolean amChoking;
	private boolean amInterested;
	private boolean peerChoking;
	private boolean peerInterested;
	
	private boolean closed;
	
	private boolean acceptedSocket; // created through listenSocket.accept()
	
	private Socket sock;
	private DataInputStream in;
	private DataOutputStream out;
	
	private ArrayList<Pieces.PieceFrag> requestedFrags;
	private ArrayList<Pieces.PieceFrag> canceledRequests; 
	
	private boolean trusted;
	
	private static final int CONNECT_TIMEOUT_MS = 1000;

	// TODO probably should experiment with changing this value
	public static final int MAX_REQUESTED_FRAGS = 5;
	
	private Peer(Torrent tor)
	{
		this.tor = tor;
		
		pieces = tor.getPieces();
		piecesCount = pieces.getCount();
		pieceSelector = pieces.getRandomSelector();
		
		bs = new BitSet(piecesCount);
		
		amChoking = true;
		amInterested = false;
		peerChoking = true;
		peerInterested = false;
		
		
		requestedFrags = new ArrayList<>(MAX_REQUESTED_FRAGS);
		canceledRequests = new ArrayList<>(2*MAX_REQUESTED_FRAGS);
		
		closed = false;
		
		trusted = false;
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
						
					receiveMessages();
				
				} catch(IOException e) {
					//e.printStackTrace();
					pieces.pieceReceiveFailed(requestedFrags);
					
					closed = true;
					
					if(sock != null) {
						try {
							sock.close();
						} catch(IOException ee) {
							
						}
					}
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
		
		while(off < handshake.length) {
			int ret = in.read(handshake, off, handshake.length-off);
			if(ret == -1)
				throw new IOException("receiveHandshake: stream ended");
			off += ret;
		}
						
		if(!Arrays.equals(tor.getInfoHash(), Arrays.copyOfRange(handshake, 28, 48))) 
			throw new IOException("invalid info hash");
	}

	// can we somehow use with enum switch in receiveMessage
	// or java sucks as usual ?
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
		while(true) {
			if(requestedFrags.size() < 3)
				sendRequests();
			
			if(pieces.isEndGameOn())
				sendCancels();
			
			int len;
			try {
				sock.setSoTimeout(1000);
				len = in.readInt();
			} catch(SocketTimeoutException e) {
				continue;
			}
			sock.setSoTimeout(0);
			
			if(len < 0)
				throw new IOException("message with negative length");
			
			if(len == 0) {
				// keep-alive message
				continue;
			}
			
			int id = in.read();
			
			//System.err.println("received message with id " + id);
			
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
					throw new IOException("choke message invalid length");
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
				if(len != 13)
					throw new IOException("request message invalid length");
				{
				int index = in.readInt();
				int begin = in.readInt();
				int length = in.readInt();
				}
				break;
			case messageCancel:
				if(len != 13)
					throw new IOException();
				int index = in.readInt();
				int begin = in.readInt();
				int length = in.readInt();
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
			int ret = in.read(block, off, length-off);
			if(ret == -1)
				throw new IOException("receivePiece: stream ended");
			off += ret;
			if(!canceledFrag)
				tor.increaseDownloaded(ret);
		}
	
		//System.err.println("received piece fragment " + index + " " + begin);
		pieces.receivedFragment(f, block);
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
			
			out.writeInt(13);  							// length
			out.write(6);	   							// request message id
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

	void forceClose()
	{
		try {
			sock.close();
		} catch(IOException e) {
			
		}
	}
	
	public boolean isTrusted()
	{
		return trusted;
	}
	
	public void setTrusted()
	{
		trusted = true;
	}
}