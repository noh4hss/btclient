package btclient;

import java.io.File;
import java.io.IOException;
import java.util.Timer;
import java.util.TimerTask;

import javafx.application.Platform;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.stage.FileChooser;


public class Controller {
	private Serializer serializer;
	private ObservableList<TorrentEntry> torrents;
	
	
	public static class TorrentEntry {
		public final Torrent tor;
		
		private final SimpleStringProperty name;
		private final SimpleStringProperty progress;
		private final SimpleStringProperty downloaded;
		private final SimpleIntegerProperty peers;
		private final SimpleStringProperty downloadSpeed;
		private final SimpleStringProperty uploadSpeed;
		
		private long lastDownloadCount;
		private long lastUploadCount;

		public TorrentEntry(Torrent tor)
		{
			this.tor = tor;
			
			name = new SimpleStringProperty(tor.getName());
			progress = new SimpleStringProperty();
			downloaded = new SimpleStringProperty();
			peers = new SimpleIntegerProperty();
			downloadSpeed = new SimpleStringProperty();
			uploadSpeed = new SimpleStringProperty();
			updateProperties();
			
			lastDownloadCount = tor.getDownloadCount();
			lastUploadCount = tor.getUploadCount();
		}
		
		public void updateProperties()
		{
			long downloaded = tor.getVerifiedDownloadCount();
			long currentDownloadCount = tor.getDownloadCount();
			long currentUploadCount = tor.getUploadCount();
			
			long downloadSpeed = currentDownloadCount - lastDownloadCount;
			long uploadSpeed = currentUploadCount - lastUploadCount;
			
						
			double x = (double)downloaded / tor.getTotalSize();
			setProgress((int)(x*100) + "." + (int)(x*1000)%10 + "%");
			
			if(downloaded < (1 << 30)) {
				setDownloaded(downloaded / (1 << 20) + "." + downloaded / 1024 % 1024 * 10 / 1024 + "MB");
			} else {
				setDownloaded(downloaded / (1 << 30) + "." + downloaded / (1 << 20) % 1024 * 10 / 1024 + "GB");
			}
			setPeers(tor.getPeersCount());

			if(downloadSpeed < 1000 * 1000) {
				setDownloadSpeed(downloadSpeed/1000 + "Kb/s");
			} else {
				setDownloadSpeed(downloadSpeed/1000000 + "." + downloadSpeed%1000000/100000 + "Mb/s");
			}

			if(uploadSpeed < 1000 * 1000) {
				setUploadSpeed(uploadSpeed/1000 + "Kb/s");
			} else {
				setUploadSpeed(uploadSpeed/1000000 + "." + uploadSpeed%1000000/100000 + "Mb/s");
			}
			
			lastDownloadCount = currentDownloadCount;
			lastUploadCount = currentUploadCount;
		}

		public String getName() 
		{
			return name.get();
		}

		public void setName(String name) 
		{
			this.name.set(name);
		}

		public SimpleStringProperty nameProperty() 
		{
			return name;
		}

		public String getProgress() 
		{
			return progress.get();
		}

		public void setProgress(String progress) 
		{
			this.progress.set(progress);
		}

		public SimpleStringProperty progressProperty() 
		{
			return progress;
		}

		public String getDownloaded() 
		{
			return downloaded.get();
		}

		public void setDownloaded(String downloaded) 
		{
			this.downloaded.set(downloaded);
		}

		public SimpleStringProperty downloadedProperty() 
		{
			return downloaded;
		}
		
		public int getPeers() 
		{
			return peers.get();
		}

		public void setPeers(int peers) 
		{
			this.peers.set(peers);
		}

		public SimpleIntegerProperty peersProperty() 
		{
			return peers;
		}

		public String getDownloadSpeed() 
		{
			return downloadSpeed.get();
		}

		public void setDownloadSpeed(String downloadSpeed) 
		{
			this.downloadSpeed.set(downloadSpeed);
		}

		public SimpleStringProperty downloadSpeedProperty() 
		{
			return downloadSpeed;
		}

		public String getUploadSpeed() 
		{
			return uploadSpeed.get();
		}

		public void setUploadSpeed(String uploadSpeed) 
		{
			this.uploadSpeed.set(uploadSpeed);
		}

		public SimpleStringProperty uploadSpeedProperty() 
		{
			return uploadSpeed;
		}
	}
	
	@FXML
	private TableView<TorrentEntry> table;
	@FXML
	private TableColumn<TorrentEntry, String> colName;
	@FXML
	private TableColumn<TorrentEntry, String> colProgress;
	@FXML
	private TableColumn<TorrentEntry, String> colDownloaded;
	@FXML
	private TableColumn<TorrentEntry, Integer> colPeers;
	@FXML
	private TableColumn<TorrentEntry, String> colDownloadSpeed;
	@FXML
	private TableColumn<TorrentEntry, String> colUploadSpeed;
	
	@FXML
	private MenuItem openItem;
	@FXML
	private MenuItem newItem;
	
	private Timer timer;
	
	@FXML
	private void initialize() 
	{
		BitTorrentClient.setController(this);
		serializer = new Serializer();
		torrents = FXCollections.observableArrayList();
		Torrent.setSerializer(serializer);
		
		for(Torrent tor : serializer.loadTorrents())
			torrents.add(new TorrentEntry(tor));
		
		serializer.start();
		
		configureTable();

		timer = new Timer();
		timer.schedule(new TimerTask() {

			@Override
			public void run() 
			{
				
				Platform.runLater(new Runnable() {

					@Override
					public void run() 
					{
						for(TorrentEntry entry : torrents) {
							entry.updateProperties();
						}
					}
				});

			}
		}, 0, 1000);
		
		Platform.runLater(new Runnable() {

			@Override
			public void run() 
			{
				table.requestFocus();
				table.getSelectionModel().select(0);
		        table.getFocusModel().focus(0);
			}
		});
	}
	
	public void stop()
	{
		timer.cancel();
		for(Controller.TorrentEntry entry : torrents)
			entry.tor.stop();
		serializer.stop();
	}

	private void configureTable() 
	{
		colName.setCellValueFactory(new PropertyValueFactory<TorrentEntry, String>("name"));
		colProgress.setCellValueFactory(new PropertyValueFactory<TorrentEntry, String>("progress"));
		colDownloaded.setCellValueFactory(new PropertyValueFactory<TorrentEntry, String>("downloaded"));
		colPeers.setCellValueFactory(new PropertyValueFactory<TorrentEntry, Integer>("peers"));
		colDownloadSpeed.setCellValueFactory(new PropertyValueFactory<TorrentEntry, String>("downloadSpeed"));
		colUploadSpeed.setCellValueFactory(new PropertyValueFactory<TorrentEntry, String>("uploadSpeed"));
		table.getColumns().setAll(colName, colProgress, colDownloaded, colPeers, colDownloadSpeed, colUploadSpeed);
		table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
		table.setItems(torrents);

	}
	
	@FXML
	private void openTorrent(ActionEvent actionEvent) 
	{
		FileChooser fileChooser = new FileChooser();
		fileChooser.setTitle("Choose Torrent File");
		fileChooser.getExtensionFilters().addAll(
				new FileChooser.ExtensionFilter("Torrent Files", "*.torrent"),
				new FileChooser.ExtensionFilter("All Files", "*.*")
		);
		fileChooser.setInitialDirectory(new File(System.getProperty("user.home")));
		
		File file = fileChooser.showOpenDialog(null);
		if (file == null) {
			System.err.println("no file selected");
			return;
		}

		try {
			Torrent tor = new Torrent(file);
			torrents.add(new TorrentEntry(tor));
			tor.start();

		} catch (IOException e) {
			System.err.println(e.getMessage());
		}
	}
	
	@FXML
	private void newTorrent(ActionEvent actionEvent)
	{
		
	}
	
	@FXML
	private void startSelected(ActionEvent actionEvent)
	{
		for(TorrentEntry entry : table.getSelectionModel().getSelectedItems()) {
			entry.tor.start();
		}
		table.requestFocus();
	}
	
	@FXML
	private void stopSelected(ActionEvent actionEvent)
	{
		for(TorrentEntry entry : table.getSelectionModel().getSelectedItems()) {
			entry.tor.stop();
		}
		table.requestFocus();
	}
	
	@FXML
	public void deleteSelected(ActionEvent actionEvent)
	{
		for(TorrentEntry entry : table.getSelectionModel().getSelectedItems()) {
			entry.tor.remove();
			torrents.remove(entry);
		}
		table.requestFocus();
	}
}
