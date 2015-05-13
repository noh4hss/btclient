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
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Created by Jakub && Pyczek on ALWAYS :)
 */
//TODO selecting torrent in table
public class Controller {

	public static Serializer serializer;
	public TableColumn<entryTorrent, Integer> colId;
	public TableColumn<entryTorrent, String> colName;
	public TableColumn<entryTorrent, Double> colProgress;
	public TableColumn<entryTorrent, Integer> colSpeed;
	public TableColumn<entryTorrent, Integer> colUploadSpeed;
	public TableColumn<entryTorrent, String> colDownloaded;
	public TableColumn<entryTorrent, Integer> colPeers;
	public MenuItem openItem;
	public MenuItem startItem;
	public MenuItem stopItem;
	public MenuItem deleteItem;
	public MenuItem closeItem;
	public MenuItem makeItem;
	@FXML
	private TableView<entryTorrent> table;
	private ObservableList<entryTorrent> data = FXCollections.observableArrayList();
	private List<Torrent> torrents;

	@FXML
	void initialize() {
		configureTable();

		torrents = Collections.synchronizedList(new ArrayList<>());
		serializer = new Serializer();
		Torrent.setSerializer(serializer);
		serializer.loadTorrents(torrents);
		serializer.start();
		torrents.forEach(this::addTorrent);

		new Timer().schedule(new TimerTask() {

			@Override
			public void run() {

				Platform.runLater(new Runnable() {

					@Override
					public void run() {

						for (entryTorrent myEntry : data) {
							Torrent tor = myEntry.tor;
							long downloaded = tor.getVerifiedDownloadCount();
							//myEntry.setId();
							myEntry.setProgress((downloaded / tor.getTotalSize())); //double
							myEntry.setName(tor.getName());
							myEntry.setSpeed((int) (tor.getDownloadSpeed() / 1024)); //Kb/s
							myEntry.setUploadSpeed((int) (tor.getUploadSpeed() / 1024)); //Kb/s
							myEntry.setDownloaded(downloaded / (1 << 20) + "." + downloaded / 1024 % 1024 * 10 / 1024 + "MB");
							myEntry.setPeers(tor.getPeersCount());
						}
					}
				});

			}
		}, 0, 1000);
	}

	private void configureTable() {
		colId.setCellValueFactory(new PropertyValueFactory<>("id"));
		colName.setCellValueFactory(new PropertyValueFactory<>("name"));
		colProgress.setCellValueFactory(new PropertyValueFactory<>("progress"));
		colSpeed.setCellValueFactory(new PropertyValueFactory<>("speed"));
		colDownloaded.setCellValueFactory(new PropertyValueFactory<>("downloaded"));
		colPeers.setCellValueFactory(new PropertyValueFactory<>("peers"));
		colUploadSpeed.setCellValueFactory(new PropertyValueFactory<>("upload speed"));
		table.setItems(data);
		table.getColumns().setAll(colId, colName, colProgress, colDownloaded, colSpeed, colPeers, colUploadSpeed);

	}


	public void startTorrent(ActionEvent actionEvent) {
		torrents.forEach(btclient.Torrent::start);
	}

	public void stopTorrent(ActionEvent actionEvent) {
		torrents.forEach(btclient.Torrent::stop);
	}

	public void deleteTorrent(ActionEvent actionEvent) {
		torrents.forEach(btclient.Torrent::stop);
		//do something
	}

	public void closeProgram(ActionEvent actionEvent) {
		torrents.forEach(btclient.Torrent::stop);
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

		data.add(new entryTorrent(data.size() + 1, tor.getName(), 0, "0.0MB", 0, 0, 0, tor));

	}

	public class entryTorrent {
		private final SimpleIntegerProperty id;
		private final SimpleStringProperty name;
		private final SimpleDoubleProperty progress;
		private final SimpleStringProperty downloaded;
		private final SimpleIntegerProperty speed;
		private final SimpleIntegerProperty uploadSpeed;
		private final SimpleIntegerProperty peers;
		private final Torrent tor;


		entryTorrent(int id, String name, double progress, String downloaded, int speed, int uploadSpeed, int peers, Torrent tor) {
			this.id = new SimpleIntegerProperty(id);
			this.name = new SimpleStringProperty(name);
			this.progress = new SimpleDoubleProperty(progress);
			this.downloaded = new SimpleStringProperty(downloaded);
			this.speed = new SimpleIntegerProperty(speed);
			this.uploadSpeed = new SimpleIntegerProperty(uploadSpeed);
			this.peers = new SimpleIntegerProperty(peers);
			this.tor = tor;
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

		public int getUploadSpeed(){
			return uploadSpeed.get();
		}

		public void setSpeed(int speed) {
			this.speed.set(speed);
		}

		public void setUploadSpeed(int speed){
			this.uploadSpeed.set(speed);
		}

		public SimpleIntegerProperty speedProperty() {
			return speed;
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
