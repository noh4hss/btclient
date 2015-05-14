package btclient;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


public class TorrentMaker {
	
	
	public static Torrent createTorrent(String path, String dst)
	{
		File file = new File(path);
		if(!file.exists()) {
			System.err.println(file.getAbsolutePath() + "does not exist");
			return null;
		}
		
		Map<String, Object> root = new HashMap<>();
		root.put("info", createInfoDictionary(file));
		root.put("announce-list", createAnnounceList());
		
		byte[] bencoding = BeObject.encode(root);
		try(FileOutputStream out = new FileOutputStream(dst)) {
			out.write(bencoding);
		} catch(IOException e) {
			e.printStackTrace();
			return null;
		}
		
		try {
			return new Torrent(new File(dst));
		} catch(IOException e) {
			e.printStackTrace();
			return null;
		}
	}

	private static Map<String, Object> createInfoDictionary(File src) 
	{
		Map<String, Object> info = new HashMap<>();
		info.put("name", src.getName());
		
		List<File> filesList = new ArrayList<>();
		if(!src.isDirectory()) {
			info.put("length", src.length());
			filesList.add(src);

		} else {
			List<Object> files = new ArrayList<>();
			createFiles(src, new ArrayList<>(), files, filesList);
			info.put("files", files);
		}
		
		long totalSize = 0;
		for(File f : filesList)
			totalSize += f.length();
		int pieceLength = choosePieceLength(totalSize);
		info.put("piece length", (long)pieceLength);
		int piecesCount = (int)((totalSize+pieceLength-1) / pieceLength);
		
		
		byte[] pieces = new byte[piecesCount * 20];
		for(int i = 0; i < piecesCount; ++i) {
			int length = i == piecesCount-1 ? (int)(totalSize % pieceLength) : pieceLength;					
			byte[] piece = new byte[length];
			long begin = pieceLength * i;
			int off = 0;
			
			for(File f : filesList) {
				if(begin < f.length()) {
					try(RandomAccessFile file = new RandomAccessFile(f, "rw")) {
						file.seek(begin);
						int len = (int)Math.min(piece.length-off, f.length()-begin);
						file.readFully(piece, off, len);
						off += len;
						begin += len;
					} catch(Exception ee) {
						ee.printStackTrace();
					}
				}
				
				if(off == piece.length)
					break;
				
				begin -= f.length();
			}
			
			
			try {
				MessageDigest md = MessageDigest.getInstance("SHA-1");
				byte[] hash = md.digest(piece);
				System.arraycopy(hash, 0, pieces, i*20, hash.length);
			} catch (NoSuchAlgorithmException e) {
			}
		}
		
		info.put("pieces", pieces);
		return info;
	}
	
	private static int choosePieceLength(long totalSize) 
	{
		if(totalSize <= 200 * 1024 * 1024) 
			return 32 * 1024;
		if(totalSize <= 2l * 1024 * 1024 * 1024) 
			return 256 * 1024;
		return 1024 * 1024; 
	}

	private static void createFiles(File file, List<Object> path, List<Object> files, List<File> filesList) 
	{
		path.add(file.getName());
		if(file.isDirectory()) {
			for(File subFile : file.listFiles())
				createFiles(subFile, path, files, filesList);
		} else {
			Map<String, Object> fileDict = new HashMap<>();
			fileDict.put("length", file.length());
			fileDict.put("path", path);
			files.add(fileDict);
			filesList.add(file);
		}
		path.remove(path.size()-1);
	}

	private static final String[] trackersUrl = { "udp://open.demonii.com:1337",
											   "udp://tracker.coppersurfer.tk:6969",
											   "udp://tracker.leechers-paradise.org:6969",
											   "udp://tracker.openbittorrent.com:80"
	};
	
	private static List<Object> createAnnounceList() 
	{
		List<Object> announceList = new ArrayList<>();
		for(String url : trackersUrl) {
			List<Object> tier = new ArrayList<>(1);
			tier.add(url);
			announceList.addAll(tier);
		}
		return announceList;
	}
}
