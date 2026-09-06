package com.rspsi;

import com.rspsi.tools.CacheBiomeProfileMiner;
import com.rspsi.util.FilterMode;
import com.rspsi.util.RetentionFileChooser;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

public final class BiomeProfileMiningDialog {

    public Optional<CacheBiomeProfileMiner.MiningConfig> showAndWait(Stage owner) {
        Dialog<CacheBiomeProfileMiner.MiningConfig> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.setTitle("Mine Biome Profiles");
        dialog.setHeaderText("Cluster cache regions into discovered biome profiles.");

        ButtonType mineButton = new ButtonType("Mine Profiles", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(mineButton, ButtonType.CANCEL);

        Spinner<Integer> clusterSpinner = spinner(2, 32, 16);
        Spinner<Integer> sampleSpinner = spinner(100, 10000, 3000);
        Spinner<Integer> tokenSpinner = spinner(4, 48, 20);
        TextField outputField = new TextField(defaultOutputPath().toString());

        javafx.scene.control.Button browseButton = new javafx.scene.control.Button("Browse");
        browseButton.setOnAction(event -> {
            File file = RetentionFileChooser.showSaveDialog(
                    "Save biome profile report",
                    owner,
                    defaultOutputPath().getFileName().toString(),
                    FilterMode.JSON
            );
            if (file != null) {
                outputField.setText(file.toPath().toString());
            }
        });

        HBox outputBox = new HBox(8.0, outputField, browseButton);
        HBox.setHgrow(outputField, Priority.ALWAYS);

        GridPane grid = new GridPane();
        grid.setHgap(10.0);
        grid.setVgap(10.0);
        grid.setPadding(new Insets(12.0));

        grid.add(new Label("Clusters"), 0, 0);
        grid.add(clusterSpinner, 1, 0);
        grid.add(new Label("Max Regions"), 0, 1);
        grid.add(sampleSpinner, 1, 1);
        grid.add(new Label("Token Features"), 0, 2);
        grid.add(tokenSpinner, 1, 2);
        grid.add(new Label("Output JSON"), 0, 3);
        grid.add(outputBox, 1, 3);

        dialog.getDialogPane().setContent(grid);
        dialog.setResultConverter(buttonType -> {
            if (buttonType != mineButton) {
                return null;
            }
            return new CacheBiomeProfileMiner.MiningConfig(
                    clusterSpinner.getValue(),
                    sampleSpinner.getValue(),
                    tokenSpinner.getValue(),
                    0x51eedL,
                    Paths.get(outputField.getText().trim())
            );
        });

        return dialog.showAndWait();
    }

    private static Spinner<Integer> spinner(int min, int max, int value) {
        Spinner<Integer> spinner = new Spinner<>();
        spinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(min, max, value));
        spinner.setEditable(true);
        return spinner;
    }

    private static Path defaultOutputPath() {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        return Paths.get(System.getProperty("user.home"), ".rspsi", "biome-profiles", "biome-profiles-" + timestamp + ".json");
    }
}
