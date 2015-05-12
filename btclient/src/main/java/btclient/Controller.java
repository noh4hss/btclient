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
import javafx.stage.FileChooser.ExtensionFilter;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Created by Jakub on 2015-05-12.
 */
//TODO selecting torrent in table
public class Controller {

	public TableColumn<entryTorrent,Integer> colId;
	public TableColumn<entryTorrent, String> colName;
	public TableColumn<entryTorrent,Double> colProgress;
	public TableColumn<entryTorrent,Integer> colSpeed;
	public TableColumn<entryTorrent, String> colDownloaded;
	public TableColumn<entryTorrent,Integer> colPeers;
	@FXML
	private TableView<entryTorrent> table;
	private ObservableList<entryTorrent> data = FXCollections.observableArrayList();
	private List<Torrent> torrents;
	public static Serializer serializer;



	public MenuItem openItem;
	public MenuItem startItem;
	public MenuItem stopItem;

	@FXML
	void initialize() {
		configureTable();

		torrents = Collections.synchronizedList(new ArrayList<Torrent>());
		serializer = new Serializer(torrents);
		Torrent.setSerializer(serializer);
		serializer.loadTorrents();
		serializer.start();
		for(Torrent tor : torrents) {
			addTorrent(tor);
		}

		new Timer().schedule(new TimerTask() {

			@Override
			public void run()
			{

				Platform.runLater(new Runnable() {

					@Override
					public void run() {

						for(entryTorrent myentry : data) {
							Torrent tor = myentry.tor;
							long downloaded = tor.getVerifiedDownloadCount();
							//myentry.setId();
							myentry.setProgress( (downloaded / tor.getTotalSize())); //double
							myentry.setName(tor.getName());
							myentry.setSpeed((int) (tor.getDownloadSpeed() / 1024)); //Kb/s
							myentry.setDownloaded(downloaded / (1 << 20) + "." + downloaded / 1024 % 1024 * 10 / 1024 + "MB");
							myentry.setPeers( tor.getPeersCount());
						}
					}
				});

			}
		}, 0, 1000);
	}

	private void configureTable() {
		colId.setCellValueFactory(new PropertyValueFactory<entryTorrent, Integer>("id"));
		colName.setCellValueFactory(new PropertyValueFactory<entryTorrent, String>("name"));
		colProgress.setCellValueFactory(new PropertyValueFactory<entryTorrent, Double>("progress"));
		colSpeed.setCellValueFactory(new PropertyValueFactory<entryTorrent, Integer>("speed"));
		colDownloaded.setCellValueFactory(new PropertyValueFactory<entryTorrent, String>("downloaded"));
		colPeers.setCellValueFactory(new PropertyValueFactory<entryTorrent, Integer>("peers"));

		table.setItems(data);
		table.getColumns().setAll(colId,colName,colProgress,colDownloaded,colSpeed,colPeers);

	}


	public void startTorrent(ActionEvent actionEvent) {
		for(Torrent tor : torrents)
			tor.start();
	}

	public void stopTorrent(ActionEvent actionEvent) {
		for(Torrent tor : torrents)
			tor.stop();
	}

	public void openTorrent(ActionEvent actionEvent) {
		FileChooser fileChooser = new FileChooser();
		fileChooser.setTitle("Choose Torrent File");
		fileChooser.getExtensionFilters().add(new ExtensionFilter("Torrent Files", "*.torrent"));
		fileChooser.setInitialDirectory(new File(System.getProperty("user.home")));
		File file = fileChooser.showOpenDialog(null);
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

	private void addTorrent(final Torrent tor)
	{
		if(!tor.start()) {
			System.err.println("could not start torrent");
			return;
		}

		data.add(new entryTorrent(data.size() + 1, tor.getName(),0, "0.0MB", 0, 0 , tor));

	}
	public class entryTorrent{
		private final SimpleIntegerProperty id;
		private final SimpleStringProperty name;
		private final SimpleDoubleProperty progress;
		private final SimpleStringProperty downloaded;
		private final SimpleIntegerProperty speed;
		private final SimpleIntegerProperty peers;
		private final Torrent tor;


		entryTorrent(int id, String name, double progress, String downloaded, int speed, int peers , Torrent tor) {
			this.id = new SimpleIntegerProperty(id);
			this.name = new SimpleStringProperty(name);
			this.progress = new SimpleDoubleProperty(progress);
			this.downloaded = new SimpleStringProperty(downloaded);
			this.speed = new SimpleIntegerProperty(speed);
			this.peers = new SimpleIntegerProperty(peers);
			this.tor = tor;
		}

		public int getId() {
			return id.get();
		}

		public SimpleIntegerProperty idProperty() {
			return id;
		}

		public void setId(int id) {
			this.id.set(id);
		}

		public String getName() {
			return name.get();
		}

		public SimpleStringProperty nameProperty() {
			return name;
		}

		public void setName(String name) {
			this.name.set(name);
		}

		public double getProgress() {
			return progress.get();
		}

		public SimpleDoubleProperty progressProperty() {
			return progress;
		}

		public void setProgress(double progress) {
			this.progress.set(progress);
		}

		public String getDownloaded() {
			return downloaded.get();
		}

		public SimpleStringProperty downloadedProperty() {
			return downloaded;
		}

		public void setDownloaded(String downloaded) {
			this.downloaded.set(downloaded);
		}

		public int getSpeed() {
			return speed.get();
		}

		public SimpleIntegerProperty speedProperty() {
			return speed;
		}

		public void setSpeed(int speed) {
			this.speed.set(speed);
		}

		public int getPeers() {
			return peers.get();
		}

		public SimpleIntegerProperty peersProperty() {
			return peers;
		}

		public void setPeers(int peers) {
			this.peers.set(peers);
		}
	}
}
