package btclient;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;


public class Pieces {
	private static class Piece {
		PieceState state;
		byte[] hash;
		BitSet requested; // downloaded or scheduled for download
		BitSet have;
		int writeCount; // how many frags downloaded
		Peer peer;
	}
	
	enum PieceState {
		FREE,
		DOWNLOADING,
		DOWNLOADED,
		VERIFIED
	}
	
	public static final int FRAG_LENGTH = 1<<14;
	
	public static class PieceFrag {
		int index;
		int frag;
		
		public PieceFrag(int index, int frag)
		{
			this.index = index;
			this.frag = frag;
		}
		
		@Override
		public boolean equals(Object o)
		{
			PieceFrag f = (PieceFrag)o;
			return index == f.index && frag == f.frag;
		}
	}
	
	
	private Torrent tor;	
	
	private int pieceLength;
	private int piecesCount;
	private int lastPieceLength;
	private int piecesDownloadedCount;
	private long verifiedDownloadCount;
	
	private PieceFrag lastFrag;
	private int lastFragLength;
	private int pieceFragCount;
	private int lastPieceFragCount;
	private int fragsCount;
	private AtomicInteger freePiecesCount;
	
	private Piece[] p;
	private FragmentSaver fragmentSaver;
	private ArrayList<Integer> corruptedPieces; 
	
	private boolean endGame;
	
	private BitSet have;
	private BitSet verified;
	
	private Random gen;
	
	public Pieces(Torrent tor, Map<String, BeObject> m, long totalSize) 
	{
		this.tor = tor;
		pieceLength = (int)m.get("piece length").getLong();
		byte[] b = m.get("pieces").getBytes();
		piecesCount = b.length / 20;
		lastPieceLength = (int)(totalSize % pieceLength);
		piecesDownloadedCount = 0;
		
		lastFrag = new PieceFrag(piecesCount-1, (lastPieceLength+FRAG_LENGTH-1)/FRAG_LENGTH-1);
		lastFragLength = lastPieceLength % FRAG_LENGTH;
		pieceFragCount = pieceLength / FRAG_LENGTH;
		lastPieceFragCount = lastFrag.frag+1;
		fragsCount = (piecesCount-1) * pieceFragCount + lastPieceFragCount;
		freePiecesCount = new AtomicInteger(piecesCount);
		
		p = new Piece[piecesCount];
		for(int i = 0; i < piecesCount; ++i) {
			p[i] = new Piece();
			p[i].state = PieceState.FREE;
			p[i].writeCount = 0;
			p[i].hash = Arrays.copyOfRange(b, i*20, (i+1)*20);
			p[i].requested = new BitSet(getPieceFragCount(i));
			p[i].have = new BitSet(getPieceFragCount(i));
		}			
		
		corruptedPieces = new ArrayList<>();
		
		have = new BitSet(fragsCount);
		verified = new BitSet(piecesCount);
		
		gen = new Random();
	}

	public boolean init(ObjectInputStream in) 
	{
		try {
			BitSet newHave = (BitSet)in.readObject();
			if(newHave.size() < fragsCount)
				throw new IOException();
			have = newHave;
			System.err.println("have cardinality " + have.cardinality());
			
			BitSet newVerified = (BitSet)in.readObject();
			if(newVerified.size() < piecesCount)
				throw new IOException();
			verified = newVerified;
			
			return true;
		} catch (ClassNotFoundException | IOException e) {
			e.printStackTrace();
			return false;
		}
	}
	
	public void init()
	{
		// initializing only from have and verified bitsets
		
		freePiecesCount.set(0);
		piecesDownloadedCount = 0;
		verifiedDownloadCount = 0;
		for(int i = 0; i < piecesCount; ++i) {
			if(verified.get(i)) {
				p[i].state = PieceState.VERIFIED;
				++piecesDownloadedCount;
				verifiedDownloadCount += getPieceLength(i);
				continue;
			}
			
			p[i].have.clear();
			p[i].requested.clear();
			for(int frag = 0; frag < getPieceFragCount(i); ++frag) {
				if(have.get(toFragIndex(i, frag))) {
					p[i].have.set(frag);
					p[i].requested.set(frag);
				}
			}
			
			int card = p[i].have.cardinality();
			p[i].writeCount = card;
			if(card < getPieceFragCount(i)) {
				p[i].state = PieceState.FREE;
				freePiecesCount.incrementAndGet();
			} else {
				p[i].state = PieceState.DOWNLOADED;
				fragmentSaver.readPiece(i);
			}
		}
		
		endGame = false;
		corruptedPieces.clear();
	}
	
	private int toFragIndex(int index, int frag) 
	{
		return index*pieceFragCount + frag;
	}

	public int getPieceLength()
	{
		return pieceLength;
	}
	
	public int getCount()
	{
		return piecesCount;
	}
	
	public int getPieceLength(int index)
	{
		return index == piecesCount-1 ? lastPieceLength : pieceLength;
	}
	
	public int getFragLength(PieceFrag f) 
	{
		return f.equals(lastFrag) ? lastFragLength : FRAG_LENGTH;
	}
	
	public int getFragsCount()
	{
		return fragsCount;
	}
	
	public int getPieceFragCount(int index)
	{
		return index == piecesCount-1 ? lastPieceFragCount : pieceFragCount;
	}
	
	interface PieceSelector {
		public void addPiece(int index);
		public PieceFrag selectPiece(Peer peer);
	}
	
	public PieceSelector getRandomSelector() 
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
					PieceFrag f = requestPieceFrag(currentIndex);
					if(f != null)
						return f;
				}
				
				while(!l.isEmpty()) {
					int index = l.get(l.size()-1);
					l.remove(l.size()-1);
					if(requestPiece(index, peer)) {
						currentIndex = index;
						PieceFrag f = requestPieceFrag(currentIndex);
						if(f != null)
							return f;
					}
				}
				
				return null;
			}
			
		};
	}
	
	public void setDiskDelegate(FragmentSaver diskDelagate)
	{
		this.fragmentSaver = diskDelagate;
	}
	
	
	public void receivedFragment(PieceFrag f, byte[] block)
	{
		Piece piece = p[f.index];
		synchronized(piece) {
			if(piece.have.get(f.frag))
				return;
			piece.have.set(f.frag);
		}
		fragmentSaver.writePieceFragment(f.index, f.frag*FRAG_LENGTH, block);
	}
	
	public void writeFragmentCompleted(int index, int begin, int length)
	{
		Piece piece = p[index];
		int frag = begin / FRAG_LENGTH;
		have.set(toFragIndex(index, frag));
		
		synchronized(piece) {
			++piece.writeCount;
			if(piece.writeCount == getPieceFragCount(index)) {
				piece.state = PieceState.DOWNLOADED;
				fragmentSaver.readPiece(index);
			}
		}
	}
	
	public void readPieceCompleted(int index, byte[] buf)
	{
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-1");
			byte[] calcHash = md.digest(buf);
			Piece piece = p[index];
			
			if(Arrays.equals(calcHash, piece.hash)) {
				verified.set(index);
				
				synchronized(piece) {
					piece.state = PieceState.VERIFIED;
				}
								
				++piecesDownloadedCount;
				verifiedDownloadCount += getPieceLength(index);
				
				if(piece.peer != null) {
					if(!piece.peer.isTrusted())
						piece.peer.setTrusted();
				}
				
				return;
			}
			
			
			System.err.println("piece " + index + " failed verification");
			if(piece.peer != null) {
				System.err.println("banning " + piece.peer.getInetAddress());
				tor.blacklistAddress(piece.peer.getInetAddress());
			}
			
			for(int frag = 0; frag < getPieceFragCount(index); ++frag)
				have.clear(toFragIndex(index, frag));
			synchronized(piece) {
				piece.writeCount = 0;
				piece.requested.clear();
				piece.have.clear();
				piece.state = PieceState.FREE;
			}
			freePiecesCount.incrementAndGet();
		
			synchronized(corruptedPieces) {
				corruptedPieces.add(index);
			}
		} catch(NoSuchAlgorithmException e) {
			
		}
	}


	public void pieceReceiveFailed(List<PieceFrag> l) 
	{
		for(PieceFrag f : l) {
			synchronized(p[f.index]) {
				p[f.index].requested.clear(f.frag);
			}
		}
		
		int prevIndex = -1;
		for(PieceFrag f : l) {
			if(f.index != prevIndex) {
				prevIndex = f.index;
				Piece piece = p[f.index];
				synchronized(piece) {
					piece.state = PieceState.FREE;
				}
				freePiecesCount.incrementAndGet();
				
				synchronized(corruptedPieces) {
					corruptedPieces.add(f.index);
				}
			}
		}
	}

	public void addCorruptedPieces(List<Peer> peers) 
	{		
		synchronized(corruptedPieces) {
			for(int index : corruptedPieces) {
				for(Peer peer : peers) 
					peer.addPiece(index);
			}
			
			corruptedPieces.clear();
		}
	}

	public boolean requestPiece(int index, Peer peer)
	{
		Piece piece = p[index];
		synchronized(piece) {
			if(piece.state != PieceState.FREE) {
				if(endGame) {
					return peer.isTrusted() && piece.state == PieceState.DOWNLOADING;
				}
				return false;
			}
	
			piece.state = PieceState.DOWNLOADING;
		
			if(piece.have.cardinality() == 0) {
				piece.peer = peer;
			} else {
				piece.peer = null;
			}
		}
		
		freePiecesCount.decrementAndGet();
		return true;
	}
	
	public PieceFrag requestPieceFrag(int index)
	{
		Piece piece = p[index];
		int count = getPieceFragCount(index);
		
		synchronized(piece) {
			if(piece.state != PieceState.DOWNLOADING)
				return null;
			
			int frag = 0;
			while(frag < count && piece.requested.get(frag))
				++frag;
			if(frag < count) {
				piece.requested.set(frag);
				return new PieceFrag(index, frag);
			}
			
			
			if(!endGame)
				return null;
				
			ArrayList<Integer> l = new ArrayList<>();
			for(frag = 0; frag < count; ++frag) {
				if(!piece.have.get(frag))
					l.add(frag);
			}
					
			if(l.isEmpty())
				return null;
					
			frag = l.get(gen.nextInt(l.size()));
			return new PieceFrag(index, frag);
		}
		
	}

	public boolean isEndGameOn() 
	{
		return endGame;
	}

	public boolean haveFrag(PieceFrag f) 
	{
		Piece piece = p[f.index];
		synchronized(piece) {
			return piece.have.get(f.frag);
		}
	}
	
	public int getPiecesVerifiedCount()
	{
		return piecesDownloadedCount;
	}
	
	public int getFreePiecesCount()
	{
		return freePiecesCount.get();
	}

	public void startEndGame() 
	{
		if(endGame)
			return;
		
		System.err.println("end-game on");
		endGame = true;
		synchronized(corruptedPieces) {
			for(int i = 0; i < piecesCount; ++i) {
				if(p[i].state == PieceState.DOWNLOADING)
					corruptedPieces.add(i);
			}
		}
	}

	public boolean save(ObjectOutputStream out) 
	{
		try {
			out.writeObject(have);
			out.writeObject(verified);
			return true;
		} catch(IOException e) {
			e.printStackTrace();
			return false;
		}
	}
	
	public long getVerifiedDownloadCount()
	{
		return verifiedDownloadCount;
	}

	public void setFragmentSaver(FragmentSaver fragmentSaver) 
	{
		this.fragmentSaver = fragmentSaver;
	}
}