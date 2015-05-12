package btclient;


import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;



public class BitTorrentClient extends Application {
	public static void main(String[] args) {
		launch(args);
	}
	@Override
	public void start(Stage stage) throws Exception 
	{
		// when applications closes one should call serializer.stop()
		Parent root = FXMLLoader.load(getClass().getResource("torrentFX.fxml"));
		stage.setTitle("BitTorrent Client");
		stage.setScene(new Scene(root));
		stage.show();

	}

	@Override
	public void stop() throws Exception {
		Controller.serializer.stop(); //?
		super.stop();
	}
}