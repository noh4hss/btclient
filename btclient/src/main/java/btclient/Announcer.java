package btclient;

import java.util.List;

public class Announcer implements TorrentWorker {
	private Torrent tor;
	private List<Tracker> trackers;
	
	private Thread mainThread;
	private volatile boolean stopped;
	private volatile boolean joining;
	
	public Announcer(Torrent tor, List<Tracker> trackers)
	{
		this.tor = tor;
		this.trackers = trackers;
	}
		
	@Override
	public void start() 
	{
		forceStop();
		for(Tracker tr : trackers)
			tr.reset();
		
		stopped = false;
		mainThread = new Thread(new Runnable() {
			@Override
			public void run() 
			{				
				announceStarted();
				
				while(!stopped) {
					for(Tracker tr : trackers) { 
						if(stopped)
							break;
						tor.addPeers(tr.announceNone());
					}
				
					try {
						Thread.sleep(60 * 1000);
					} catch(InterruptedException e) {
					}
				}
												
				announceStopped();
			}
			
		}, tor + " announcer");
		
		mainThread.start();
	}

	private int trackerIndex;
	private static final int MAX_ANNOUNCE_THREADS = 4;
	
	private void announceStarted()
	{
		trackerIndex = 0;
		Thread[] announceThreads = new Thread[MAX_ANNOUNCE_THREADS];
		for(int i = 0; i < MAX_ANNOUNCE_THREADS; ++i) {
			announceThreads[i] = new Thread(new Runnable() {
				
				@Override
				public void run() 
				{
					while(!stopped) {
						Tracker tr;
						synchronized(trackers) {
							if(trackerIndex == trackers.size())
								break;
							tr = trackers.get(trackerIndex);
							++trackerIndex;
						}
						
						tor.addPeers(tr.announceStarted());
					}
				}
				
			});
			
			announceThreads[i].start();
		}
		

		for(int i = 0; i < MAX_ANNOUNCE_THREADS; ++i) {
			while(true) {
				try {
					announceThreads[i].join();
					break;
				} catch (InterruptedException e) {
				}
			}
		}
	}
	
	private void announceStopped()
	{
		trackerIndex = 0;
		Thread[] announceThreads = new Thread[MAX_ANNOUNCE_THREADS];
		for(int i = 0; i < MAX_ANNOUNCE_THREADS; ++i) {
			announceThreads[i] = new Thread(new Runnable() {
				
				@Override
				public void run() 
				{
					while(true) {
						if(joining)
							break;
						
						Tracker tr;
						synchronized(trackers) {
							if(trackerIndex == trackers.size())
								break;
							tr = trackers.get(trackerIndex);
							++trackerIndex;
						}
						
						tr.announceStopped();
					}
				}
				
			});
			
			announceThreads[i].start();
		}
		

		for(int i = 0; i < MAX_ANNOUNCE_THREADS; ++i) {
			while(true) {
				try {
					announceThreads[i].join();
					break;
				} catch (InterruptedException e) {
				}
			}
		}
	}
	
	public void announceCompleted()
	{
		for(Tracker tr : trackers)
			tr.announceCompleted();
	}
	
	@Override
	public void stop() 
	{
		stopped = true;
		mainThread.interrupt();
	}
	
	public void forceStop()
	{
		joining = true;
		if(mainThread != null) {
			while(mainThread.isAlive()) {
				for(Tracker tr : trackers)
					tr.endAnnounce();
				
				try {
					mainThread.join(50);
				} catch(InterruptedException e) {
				
				}
			}
			
			try {
				mainThread.join();
			} catch(InterruptedException e) {
			}
		}
		joining = false;
	}
}
