package btclient;


import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.stage.FileChooser;
import javafx.stage.FileChooser.ExtensionFilter;
import javafx.stage.Stage;



public class BitTorrentClient extends Application {
	private List<Torrent> torrents;
	private Serializer serializer;
	
	private Stage mainStage;
	private VBox root;
	
	@Override
	public void start(Stage stage) throws Exception 
	{
		torrents = Collections.synchronizedList(new ArrayList<>());
		serializer = new Serializer(torrents);
		Torrent.setSerializer(serializer);
		serializer.loadTorrents();
		serializer.start();
		// when applications closes one should call serializer.stop()
		
		
		// TODO gui code
		
		Stage mainStage = stage;
		mainStage.setTitle("BitTorrent Client");
		root = new VBox();
		root.setAlignment(Pos.TOP_CENTER);
		
		MenuBar menuBar = new MenuBar();
		Menu menuFile = new Menu("File");
		menuBar.getMenus().add(menuFile);
		root.getChildren().add(menuBar);
		MenuItem open = new MenuItem("Open");
		MenuItem stopAll = new MenuItem("Stop All");
		MenuItem startAll = new MenuItem("Start All");
		menuFile.getItems().addAll(open, stopAll, startAll);
		open.setOnAction(openTorrent);
		stopAll.setOnAction(new EventHandler<ActionEvent>() {
			@Override
			public void handle(ActionEvent event) 
			{
				for(Torrent tor : torrents)
					tor.stop();
			}
		});
		startAll.setOnAction(new EventHandler<ActionEvent>() {
			@Override
			public void handle(ActionEvent event) 
			{
				for(Torrent tor : torrents)
					tor.start();
			}
		});
		Scene scene = new Scene(root, 700, 600);
		mainStage.setScene(scene);
		
		mainStage.show();
		
		for(Torrent tor : torrents)
			addTorrent(tor);
	}
	
	EventHandler<ActionEvent> openTorrent = new EventHandler<ActionEvent>() {

		@Override
		public void handle(ActionEvent event) 
		{
			FileChooser fileChooser = new FileChooser();
			fileChooser.setTitle("Choose Torrent File");
			fileChooser.getExtensionFilters().add(new ExtensionFilter("Torrent Files", "*.torrent"));
			fileChooser.setInitialDirectory(new File(System.getProperty("user.home")));
			File file = fileChooser.showOpenDialog(mainStage);
			if(file == null) {
				System.err.println("no file selected");
				return;
			}
			 
			try {
				final Torrent tor = new Torrent(file);
				torrents.add(tor);

				addTorrent(tor);
				
			} catch(IOException e) {
				System.err.println(e.getMessage());
			}
		}
	};
	
	private void addTorrent(Torrent tor)
	{
		if(!tor.start()) {
			System.err.println("could not start torrent");
			return;
		}
	
		Text torrentName = new Text(tor.getName());
		
		final ProgressBar progressBar = new ProgressBar(0);
		progressBar.prefWidthProperty().bind(root.widthProperty().subtract(20));
		
		HBox stats = new HBox(30);
		final Text speedStat = new Text();
		final Text downloadedStat = new Text();
		final Text peersStat = new Text();
		stats.getChildren().addAll(speedStat, downloadedStat, peersStat);		
		root.getChildren().addAll(torrentName, progressBar, stats);
		
		new Timer().schedule(new TimerTask() {
			
			@Override
			public void run() 
			{
				
				Platform.runLater(new Runnable() {

					@Override
					public void run() {
						long downloaded = tor.getVerifiedDownloadCount();
						double progress = (double)downloaded / tor.getTotalSize();
						progressBar.setProgress(progress);
						speedStat.setText(" speed: " + tor.getDownloadSpeed() / 1024 + "KB/s");
						downloadedStat.setText("downloaded: " + downloaded/(1<<20) + "." + downloaded/1024%1024*10/1024 + "MB");
						peersStat.setText("peers: " + tor.getPeersCount());
					}
				});
				
			}
		}, 0, 1000);
	}
}