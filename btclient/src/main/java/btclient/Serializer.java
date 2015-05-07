package btclient;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.List;

public class Serializer implements TorrentWorker {
	private List<Torrent> torrents;
	private Thread mainThread;
	
	private static final File defaultDirectory = new File(
			System.getProperty("user.home") + File.separator + ".btclient");

	private static final File torrentsDirectory = new File(
			defaultDirectory.getPath() + File.separator + "torrents");
	
	public Serializer(List<Torrent> torrents)
	{
		this.torrents = torrents;
		defaultDirectory.mkdir();
		torrentsDirectory.mkdir();
	}
	
	@Override
	public void start()
	{
		mainThread = new Thread(new Runnable() {

			@Override
			public void run() 
			{
				while(!Thread.currentThread().isInterrupted()) {
					try {
						Thread.sleep(1000);
					} catch(InterruptedException e) {
						break;
					}
				
					for(int i = 0; i < torrents.size(); ++i) {
						saveTorrent(torrents.get(i));		
					}
				}
				
				for(int i = 0; i < torrents.size(); ++i) {
					saveTorrent(torrents.get(i));
				}
			}
		});
		
		mainThread.start();
	}

	@Override
	public void stop() 
	{
		mainThread.interrupt();
	}
	

	public void loadTorrents()
	{
		for(File file : torrentsDirectory.listFiles()) {
			if(!file.isDirectory())
				continue;
			
			File torrentFile = new File(file.getPath() + File.separator + "name.torrent");
			if(!torrentFile.exists())
				continue;
			
			try {
				Torrent tor = new Torrent(torrentFile);
				torrents.add(tor);
				File dataFile = new File(file.getPath() + File.separator + "bindata");
				if(dataFile.exists()) {
					try(ObjectInputStream in = new ObjectInputStream(new BufferedInputStream(new FileInputStream(dataFile)))) {
						tor.init(in);
					}
				}
			} catch(IOException e) {
				e.printStackTrace();
			}
		}
	}
	
	
	public void createFiles(Torrent tor, byte[] b)
	{
		File torrentDirectory = new File(torrentsDirectory + File.separator + tor.getInfoHashStr());
		if(!torrentDirectory.mkdir())
			return;
		
		try {
			File torrentFile = new File(torrentDirectory + File.separator + "name.torrent");
			torrentFile.createNewFile();
			try(FileOutputStream out = new FileOutputStream(torrentFile)) {
				out.write(b, 0, b.length);
			}
			
			File dataFile = new File(torrentDirectory.getPath() + File.separator + "bindata");
			dataFile.createNewFile();
		} catch(IOException e) {
			e.printStackTrace();
		}
		
	}
	
	private void saveTorrent(Torrent tor)
	{
		File torrentDirectory = new File(torrentsDirectory.getPath() + File.separator + tor.getInfoHashStr());
		File dataFile = new File(torrentDirectory.getPath() + File.separator + "bindata");
		try(ObjectOutputStream out = new ObjectOutputStream(new BufferedOutputStream(new FileOutputStream(dataFile)))) {
			tor.save(out);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}