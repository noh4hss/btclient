package btclient;

import java.util.List;

public class Announcer implements TorrentWorker {
	private Torrent tor;
	private List<Tracker> trackers;
	
	private Thread mainThread;
	
	public Announcer(Torrent tor, List<Tracker> trackers)
	{
		this.tor = tor;
		this.trackers = trackers;
	}
	
	private int trackerIndex;
	private static final int MAX_ANNOUNCE_THREADS = 5;
	
	@Override
	public void start() 
	{
		mainThread = new Thread(new Runnable() {
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
								tor.addPeers(tr.announceNone());
							}
						}
						
					}).start();
				}
				
				
				while(!Thread.currentThread().isInterrupted()) {
					for(Tracker tr : trackers) { 
						if(Thread.currentThread().isInterrupted())
							break;
						tor.addPeers(tr.announceNone());
					}
				
					try {
						Thread.sleep(60 * 1000);
					} catch(InterruptedException e) {
						break;
					}
				}
				
				for(Tracker tr : trackers) {
					if(Thread.currentThread() != mainThread)
						break;
					tr.announceStopped();
					tr.announceNone();
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
}
