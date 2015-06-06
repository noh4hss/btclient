package btclient;

import java.net.InetSocketAddress;
import java.util.List;

public abstract class Tracker {	
	private boolean startedSent;
	private long nextAnnounceTime;
	
	private long downloadedStart;
	private long uploadedStart;
	
	protected Torrent tor;
	protected String url;
	
	protected Tracker(Torrent tor, String url) 
	{
		this.tor = tor;
		this.url = url;
	}
	
	public static Tracker createTracker(Torrent tor, String url) 
	{
		if(url.substring(0, 3).equals("udp"))
			return new UDPTracker(tor, url);
		if(url.substring(0, 4).equals("http"))
			return new HTTPTracker(tor, url);
		throw new IllegalArgumentException("could not parse tracker url: " + url);
	}
	
	
	private enum Event {
		NONE(0),
		COMPLETED(1),
		STARTED(2),
		STOPPED(3);
		
		private final int value;
		
		private Event(int value)
		{
			this.value = value;
		}
		
		public int getValue()
		{
			return value;
		}
	}
	
	private Event currentEvent;
	
	protected int getCurrentEvent()
	{
		return currentEvent.getValue();
	}
	
	protected String getCurrentEventString()
	{
		return currentEvent.toString().toLowerCase();
	}
	
	public void reset()
	{
		startedSent = false;
		currentEvent = Event.STARTED;
	}
	
	public abstract List<InetSocketAddress> announce();
	
	public abstract void endAnnounce();
	
	public synchronized List<InetSocketAddress> announceNone()
	{
		if(currentEvent == Event.NONE && System.currentTimeMillis() < nextAnnounceTime)
			return null;
		
		List<InetSocketAddress> ret = announce();
		if(ret != null) {
			if(currentEvent == Event.STARTED) {
				startedSent = true;
				downloadedStart = tor.getDownloadCount();
				uploadedStart = tor.getUploadCount();
			}
			currentEvent = Event.NONE;
		}
		return ret;
	}
		
	public synchronized List<InetSocketAddress> announceStarted()
	{
		currentEvent = Event.STARTED;
		List<InetSocketAddress> ret = announce();
		if(ret != null) {
			startedSent = true;
			downloadedStart = tor.getDownloadCount();
			uploadedStart = tor.getUploadCount();
			currentEvent = Event.NONE;
		}
		return ret;
	}
	
	public synchronized void announceStopped()
	{
		if(currentEvent == Event.STARTED)
			return;
		currentEvent = Event.STOPPED;
		announce();
	}
	
	public synchronized void announceCompleted()
	{
		if(currentEvent == Event.STARTED || currentEvent == Event.STOPPED)
			return;
		currentEvent = Event.COMPLETED;
	}
	
	protected long getDownloaded()
	{
		if(!startedSent)
			return 0;
		return tor.getDownloadCount() - downloadedStart;
	}
	
	protected long getUploaded()
	{
		if(!startedSent)
			return 0;
		return tor.getUploadCount() - uploadedStart;
	}
	
	protected long getLeft()
	{
		return tor.getLeftCount();
	}
	
	protected int getNumWant()
	{
		return 100;
	}

	// interval is in seconds
	protected void updateNextAnnounceTime(int interval)
	{
		nextAnnounceTime = System.currentTimeMillis() + 1000l*interval;
	}
	
	public String getURL()
	{
		return url;
	}
	
}


