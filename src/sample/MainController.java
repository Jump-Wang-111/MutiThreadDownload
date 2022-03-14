package sample;

import Download.Download;
import Download.Protocol.Ftp;
import Download.Protocol.Http;
import Download.Protocol.Torrent.Torrent;
import Download.Protocol.Torrent.Torrentfile;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.AnchorPane;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

import javax.swing.*;
import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.logging.Logger;

public class MainController{

    Logger logger = Logger.getGlobal();

    @FXML
    AnchorPane root;
    @FXML
    Label infoLabel;
    @FXML
    Button OutputPathBtn;
    @FXML
    Button InputPathBtn;
    @FXML
    Button ClipBoardBtn;
    @FXML
    Button startDownload;
    @FXML
    TextField threadText;
    @FXML
    TextField savePos;
    @FXML
    TextField inputPos;
    @FXML
    TextArea urls;
    @FXML
    Label message;

    private Main main;
    private String outputDict;
    private String inputFile;
    private String inputTxt;
    private int threads;

    public void setMain(Main main) {
        this.main = main;
        outputDict = ".\\";
        inputFile = null;
        inputTxt = null;
        threads = 8;
    }


    @FXML
    public void onGetOutput() {
        DirectoryChooser chooser = new DirectoryChooser();
        File dict = chooser.showDialog(root.getScene().getWindow());
        if(dict != null) {
            outputDict = dict.getAbsolutePath();
        }

        logger.info("set output dictionary: " + outputDict);
        savePos.setText(outputDict);

    }

    @FXML
    public void onGetInput() {
        FileChooser chooser = new FileChooser();
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Txt", "*.txt"),
                new FileChooser.ExtensionFilter("Torrent", "*.torrent"));
        File file = chooser.showOpenDialog(root.getScene().getWindow());
        if(file != null) {
            inputFile = file.getAbsolutePath();
        }
        logger.info("set input path: " + inputFile);
        inputPos.setText(inputFile);

    }

    @FXML
    public void onGetClip() {
        urls.paste();
    }

    @FXML
    public void onStart() throws FileNotFoundException {

        if(threadText.getText().length() != 0) {
            threads = Integer.parseInt(threadText.getText());
        }

        if(inputPos.getText().length() != 0) {
            inputFile = inputPos.getText();
        }

        if(savePos.getText().length() != 0) {
            outputDict = savePos.getText();
        }


        // 处理torrent
        if(inputFile != null && inputFile.substring(inputFile.lastIndexOf(".") + 1).equals("torrent")) {
            try {
                Torrentfile torrent = new Torrentfile(
                        new File(inputFile),
                        new File(outputDict),
                        8);
                Download d = new Download(new Torrent(torrent), 8, null, null);
                message.setText("please wait...");
                d.start();
                message.setText("download over.");
            } catch (Exception e) {
                e.printStackTrace();
            }
            return;
        }

        // 处理其他链接
        List<String> urls;
        if(inputFile != null) {
            urls = main.parseFile(inputFile);
        }
        else {
            urls = main.parseText(this.urls.getText());
        }

        for(String url : urls) {

            if(url.substring(0, 4).equals("http")) {
                Download d = new Download(new Http(url), threads, outputDict);
                message.setText("please wait...");
                d.start();
                message.setText("download over.");
            }

            if(url.substring(0, 3).equals("ftp")) {
                Download d = new Download(new Ftp(url), threads, outputDict);
                message.setText("please wait...");
                d.start();
                message.setText("download over.");
            }

            if(url.substring(0, 6).equals("magnet")) {
                JOptionPane.showMessageDialog(null,
                        """
                                we deal with magnet by torrent
                                please change it into .torrent file
                                we recommend this site: http://www.magnet2torrent.com/""",
                        "Message", JOptionPane.PLAIN_MESSAGE);
            }

        }

    }
}
