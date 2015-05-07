package btclient;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class FragmentSaver {
	public static class FileEntry {
		List<String> path;
		long length;
		String pathname;
		
		FileEntry(List<String> path, long length)
		{
			this.path = path;
			this.length = length;
		}
	}
	
	private static class WriteRequest {
		int index;
		int begin;
		byte[] block;
		
		WriteRequest(int index, int begin, byte[] block)
		{
			this.index = index;
			this.begin = begin;
			this.block = block;
		}
	}
	
	private Torrent tor;
	private Pieces pieces;
	private List<FileEntry> files;
	private Queue<WriteRequest> writeRequests;
	private Queue<Integer> readRequests;
	
	private Thread mainThread;
	
	public FragmentSaver(Torrent tor)
	{
		this.tor = tor;
		pieces = tor.getPieces();
		files = tor.getFiles();
		writeRequests = new ConcurrentLinkedQueue<>();
		readRequests = new ConcurrentLinkedQueue<>();
	
		createFiles();
	}
	
	public void start()
	{
		mainThread = new Thread(new Runnable() {
			@Override
			public void run() 
			{			
				while(true) {
					if(writeRequests.isEmpty() && readRequests.isEmpty()) {
						if(Thread.currentThread().isInterrupted())
							break;
						
						try {
							Thread.sleep(1000);
						} catch(InterruptedException e) {
							Thread.currentThread().interrupt();
						}
					}
					
					while(!writeRequests.isEmpty()) {
						WriteRequest req = writeRequests.poll();
					
						long begin = (long)pieces.getPieceLength() * req.index + req.begin;
						int off = 0;
						for(FileEntry e : files) {
							if(begin < e.length) {
								try(RandomAccessFile file = new RandomAccessFile(e.pathname, "rw")) {
									file.seek(begin);
									int len = (int)Math.min(req.block.length-off, e.length-begin);
									file.write(req.block, off, len);
									off += len;
									begin += len;
								} catch(Exception ee) {
									ee.printStackTrace();
								}
							}
							
							if(off == req.block.length)
								break;
							
							begin -= e.length;
						}
					
						pieces.writeFragmentCompleted(req.index, req.begin, req.block.length);
					}

					while(!readRequests.isEmpty()) {
						int index = readRequests.poll();
						
						byte[] piece = new byte[pieces.getPieceLength(index)];
						long begin = (long)pieces.getPieceLength() * index;
						int off = 0;
						
						for(FileEntry e : files) {
							if(begin < e.length) {
								try(RandomAccessFile file = new RandomAccessFile(e.pathname, "rw")) {
									file.seek(begin);
									int len = (int)Math.min(piece.length-off, e.length-begin);
									file.readFully(piece, off, len);
									off += len;
									begin += len;
								} catch(Exception ee) {
									ee.printStackTrace();
								}
							}
							
							if(off == piece.length)
								break;
							
							begin -= e.length;
						}
						
						pieces.readPieceCompleted(index, piece);
					}
				}
			}	
		});
		
		mainThread.start();
	}
	
	public void stop()
	{
		mainThread.interrupt();
		try {
			mainThread.join();
		} catch (InterruptedException e) {
			
		}
	}
	
	public void writePieceFragment(int index, int begin, byte[] block)
	{
		writeRequests.add(new WriteRequest(index, begin, block));
	}
	
	
	public void readPiece(int index)
	{
		readRequests.add(index);
	}
	
	
	private void createFiles()
	{
		for(FileEntry e : files) {
			e.pathname = tor.getDownloadDirectory();
			Iterator<String> it = e.path.iterator();
			while(true) {
				String name = it.next();
				e.pathname += File.separator + name;
				if(!it.hasNext()) {
					try(RandomAccessFile file = new RandomAccessFile(e.pathname, "rw")) {
						file.setLength(e.length);
					} catch(Exception ee) {
						ee.printStackTrace();
					}
					break;
				}
				
				new File(e.pathname).mkdir();
			}
		}
	}	
}
