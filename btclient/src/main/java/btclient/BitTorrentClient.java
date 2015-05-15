package btclient;


import java.util.List;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;



public class BitTorrentClient extends Application {
	static List<Controller.TorrentEntry> torrents;
	static Serializer serializer;
	
	public static void main(String[] args) 
	{
		launch(args);
	}
	
	@Override
	public void start(Stage stage) throws Exception 
	{
		Parent root = FXMLLoader.load(getClass().getResource("torrentFX.fxml"));
		stage.setTitle("BitTorrent Client");
		stage.setScene(new Scene(root));
		stage.show();

	}

	@Override
	public void stop() throws Exception 
	{
		for(Controller.TorrentEntry entry : torrents)
			entry.tor.stop();
		serializer.stop();
		super.stop();
	}
	
	public static void setTorrents(List<Controller.TorrentEntry> torrents) 
	{
		BitTorrentClient.torrents = torrents;
	}
	
	public static void setSerializer(Serializer serializer)
	{
		BitTorrentClient.serializer = serializer;
	}
}