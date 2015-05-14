package btclient;

import javafx.application.Platform;
import javafx.beans.property.SimpleDoubleProperty;
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
import javafx.scene.control.TableView.TableViewSelectionModel;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.util.*;


//TODO selecting torrent in table
public class Controller {

	public static Serializer serializer;
	public TableColumn<TorrentEntry, Integer> colId;
	public TableColumn<TorrentEntry, String> colName;
	public TableColumn<TorrentEntry, Double> colProgress;
	public TableColumn<TorrentEntry, Integer> colSpeed;
	public TableColumn<TorrentEntry, Integer> colUploadSpeed;
	public TableColumn<TorrentEntry, String> colDownloaded;
	public TableColumn<TorrentEntry, Integer> colPeers;
	public MenuItem openItem;
	public MenuItem startItem;
	public MenuItem stopItem;
	public MenuItem deleteItem;
	public MenuItem closeItem;
	public MenuItem makeItem;
	@FXML
	private TableView<TorrentEntry> table;
	private ObservableList<TorrentEntry> data = FXCollections.observableArrayList();
	private List<Torrent> torrents;

	@FXML
	void initialize() {
		configureTable();

		torrents = Collections.synchronizedList(new ArrayList<Torrent>());
		serializer = new Serializer();
		Torrent.setSerializer(serializer);
		serializer.loadTorrents(torrents);
		serializer.start();
		for (Torrent tor : torrents) {
			long downloaded = tor.getVerifiedDownloadCount();
			System.err.println(downloaded);
			String downloadedText = downloaded / (1 << 20) + "." + downloaded / 1024 % 1024 * 10 / 1024 + "MB";
			data.add(new TorrentEntry(data.size() + 1, tor.getName(), 0, downloadedText, 0, 0, 0, tor));
		}

		new Timer().schedule(new TimerTask() {

			@Override
			public void run() {
				
				Platform.runLater(new Runnable() {

					@Override
					public void run() {

						for (TorrentEntry entry : data) {
							Torrent tor = entry.tor;
							long downloaded = tor.getVerifiedDownloadCount();
							long currentDownloadCount = tor.getDownloadCount();
							long currentUploadCount = tor.getUploadCount();
							
							long downloadSpeed = currentDownloadCount - entry.lastDownloadCount;
							long uploadSpeed = currentUploadCount - entry.lastUploadCount;
							
							//entry.setId();
							entry.setProgress((100 * downloaded / tor.getTotalSize())); //double
							entry.setName(tor.getName());
							entry.setSpeed((int)(downloadSpeed / 1000)); //Kb/s
							entry.setUploadSpeed((int)(uploadSpeed / 1000)); //Kb/s
							entry.setDownloaded(downloaded / (1 << 20) + "." + downloaded / 1024 % 1024 * 10 / 1024 + "MB");
							entry.setPeers(tor.getPeersCount());
							
							entry.lastDownloadCount = currentDownloadCount;
							entry.lastUploadCount = currentUploadCount;
						}
					}
				});

			}
		}, 0, 1000);
	}

	private void configureTable() {
		colId.setCellValueFactory(new PropertyValueFactory<TorrentEntry, Integer>("id"));
		colName.setCellValueFactory(new PropertyValueFactory<TorrentEntry, String>("name"));
		colProgress.setCellValueFactory(new PropertyValueFactory<TorrentEntry, Double>("progress"));
		colSpeed.setCellValueFactory(new PropertyValueFactory<TorrentEntry, Integer>("speed"));
		colDownloaded.setCellValueFactory(new PropertyValueFactory<TorrentEntry, String>("downloaded"));
		colPeers.setCellValueFactory(new PropertyValueFactory<TorrentEntry, Integer>("peers"));
		colUploadSpeed.setCellValueFactory(new PropertyValueFactory<TorrentEntry, Integer>("uploadSpeed"));
		table.setItems(data);
		table.getColumns().setAll(colId, colName, colProgress, colDownloaded, colSpeed, colPeers, colUploadSpeed);
		table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
	}


	public void startTorrent(ActionEvent actionEvent) {
		for (Torrent tor : torrents)
			tor.start();
	}
	
	public void startSelected(ActionEvent actionEvent)
	{
		for(TorrentEntry entry : table.getSelectionModel().getSelectedItems()) {
			entry.tor.start();
		}
	}
	
	public void stopSelected(ActionEvent actionEvent)
	{
		for(TorrentEntry entry : table.getSelectionModel().getSelectedItems()) {
			entry.tor.stop();
		}
	}

	public void stopTorrent(ActionEvent actionEvent) {
		for (Torrent tor : torrents)
			tor.stop();
	}

	public void deleteTorrent(ActionEvent actionEvent) {
		data.clear();
		for (Torrent tor : torrents) {
			tor.remove();
		}
	}

	public void closeProgram(ActionEvent actionEvent) {
		for (Torrent tor : torrents)
			tor.stop();
		Platform.exit();
	}

	public void makeTorrent(ActionEvent actionEvent) {
		//make Torrent
	}

	public void openTorrent(ActionEvent actionEvent) {
		FileChooser fileChooser = new FileChooser();
		fileChooser.setTitle("Choose Torrent File");
		fileChooser.getExtensionFilters().addAll(
				new FileChooser.ExtensionFilter("Torrent Files", "*.torrent"),
				new FileChooser.ExtensionFilter("All FILES", "*.*")
		);
		fileChooser.setInitialDirectory(new File(System.getProperty("user.home")));
		File file = fileChooser.showOpenDialog(null);
		if (file == null) {
			System.err.println("no file selected");
			return;
		}

		try {
			final Torrent tor = new Torrent(file);
			torrents.add(tor);

			addTorrent(tor);

		} catch (IOException e) {
			System.err.println(e.getMessage());
		}
	}

	private void addTorrent(final Torrent tor) {
		if (!tor.start()) {
			System.err.println("could not start torrent");
			return;
		}

		data.add(new TorrentEntry(data.size() + 1, tor.getName(), 0, "0.0MB", 0, 0, 0, tor));

	}

	public static class TorrentEntry {
		private final SimpleIntegerProperty id;
		private final SimpleStringProperty name;
		private final SimpleDoubleProperty progress;
		private final SimpleStringProperty downloaded;
		private final SimpleIntegerProperty speed;
		private final SimpleIntegerProperty uploadSpeed;
		private final SimpleIntegerProperty peers;
		private final Torrent tor;
		private long lastDownloadCount;
		private long lastUploadCount;

		TorrentEntry(int id, String name, double progress, String downloaded, int speed, int uploadSpeed, int peers, Torrent tor) {
			this.id = new SimpleIntegerProperty(id);
			this.name = new SimpleStringProperty(name);
			this.progress = new SimpleDoubleProperty(progress);
			this.downloaded = new SimpleStringProperty(downloaded);
			this.speed = new SimpleIntegerProperty(speed);
			this.uploadSpeed = new SimpleIntegerProperty(uploadSpeed);
			this.peers = new SimpleIntegerProperty(peers);
			this.tor = tor;
			lastDownloadCount = tor.getDownloadCount();
			lastUploadCount = tor.getUploadCount();
		}

		public int getId() {
			return id.get();
		}

		public void setId(int id) {
			this.id.set(id);
		}

		public SimpleIntegerProperty idProperty() {
			return id;
		}

		public String getName() {
			return name.get();
		}

		public void setName(String name) {
			this.name.set(name);
		}

		public SimpleStringProperty nameProperty() {
			return name;
		}

		public double getProgress() {
			return progress.get();
		}

		public void setProgress(double progress) {
			this.progress.set(progress);
		}

		public SimpleDoubleProperty progressProperty() {
			return progress;
		}

		public String getDownloaded() {
			return downloaded.get();
		}

		public void setDownloaded(String downloaded) {
			this.downloaded.set(downloaded);
		}

		public SimpleStringProperty downloadedProperty() {
			return downloaded;
		}

		public int getSpeed() {
			return speed.get();
		}

		public void setSpeed(int speed) {
			this.speed.set(speed);
		}

		public SimpleIntegerProperty speedProperty() {
			return speed;
		}

		public int getUploadSpeed() {
			return uploadSpeed.get();
		}

		public void setUploadSpeed(int speed) {
			System.err.println("upload speed: " + speed);
			this.uploadSpeed.set(speed);
		}

		public SimpleIntegerProperty uploadProperty() {
			return uploadSpeed;
		}

		public int getPeers() {
			return peers.get();
		}

		public void setPeers(int peers) {
			this.peers.set(peers);
		}

		public SimpleIntegerProperty peersProperty() {
			return peers;
		}
	}
}
