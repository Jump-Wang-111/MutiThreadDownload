package sample;

import javafx.application.Application;
import javafx.stage.Stage;
import javafx.scene.Scene;
import javafx.scene.layout.AnchorPane;
import javafx.fxml.FXMLLoader;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.logging.Logger;

public class Main extends Application {

	private Stage primaryStage;

	@Override
	public void start(Stage primaryStage) {
		this.primaryStage = primaryStage;
		showMainWindow();
	}

	public void showMainWindow() {
		try {
			FXMLLoader loader=new FXMLLoader(getClass().getResource("MainView.fxml"));
			AnchorPane root = (AnchorPane)loader.load();

			MainController controller=loader.getController();
			controller.setMain(this);


			Scene scene = new Scene(root);
			scene.getStylesheets().add(getClass().getResource("application.css").toExternalForm());
			primaryStage.setScene(scene);
			primaryStage.setTitle("多线程下载器");
			primaryStage.show();
		} catch(Exception e) {
			e.printStackTrace();
		}
	}

	public List<String> parseFile(String filename) throws FileNotFoundException {
		Scanner scanner = new Scanner(new BufferedReader(
				new InputStreamReader(new FileInputStream(filename))));

		scanner.useDelimiter("\n");
		ArrayList<String> list = new ArrayList<>();
		while(scanner.hasNext()) {
			list.add(scanner.next());
		}
		scanner.close();
		return list;
	}

	public List<String> parseText(String text) {
		return List.of(text.split("\n"));
	}

	public static void main(String[] args) {
		launch(args);
	}
}
