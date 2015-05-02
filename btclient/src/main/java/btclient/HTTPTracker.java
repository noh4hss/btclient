package btclient;

import java.net.InetSocketAddress;
import java.util.List;


public class HTTPTracker extends Tracker {
	HTTPTracker(Torrent tor, String url)
	{
		super(tor, url);
		throw new IllegalArgumentException("could not parse tracker url: " + url);
	}

	@Override
	public List<InetSocketAddress> announce() 
	{
		// TODO Auto-generated method stub
		return null;
	}
}