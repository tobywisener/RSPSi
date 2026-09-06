package com.rspsi;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.jagex.Client;
import com.jagex.cache.def.ObjectDefinition;
import com.jagex.cache.loader.object.ObjectDefinitionLoader;
import com.jagex.chunk.Chunk;
import com.jagex.io.Buffer;
import com.rspsi.tools.BiomeProfilePreviewGenerator;
import com.rspsi.tools.CacheBiomeProfileMiner;
import com.rspsi.util.FXDialogs;
import com.rspsi.util.FilterMode;
import com.rspsi.util.RetentionFileChooser;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.image.ImageView;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.io.File;
import java.io.FileReader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public final class BiomeProfileReportDialog {

    private static final DecimalFormat DECIMAL = new DecimalFormat("0.000");
    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String DEFAULT_PROFILE_LABEL_PREFIX = "profile_09";
    private static final Set<String> TOKEN_STOPWORDS = Set.of(
            "the", "and", "for", "with", "from", "into", "over", "under", "null", "object",
            "open", "closed", "large", "small", "broken", "dead", "old", "new",
            "north", "south", "east", "west", "upper", "lower", "left", "right",
            "part", "pair", "entrance", "exit", "very", "plain", "set"
    );
    private static final Set<Integer> WATER_OVERLAY_IDS = Set.of(6, 7, 29, 41, 42, 72, 85, 95, 104, 128, 130, 133, 151, 156, 158, 161, 181, 245, 246);
    private static final Set<Integer> WATER_UNDERLAY_IDS = Set.of(54, 133, 134);

    public Optional<PreviewRequest> showAndWait(Stage owner) {
        return showAndWait(owner, null);
    }

    public Optional<PreviewRequest> showAndWait(Stage owner, PreviewRequest initialRequest) {
        Dialog<PreviewRequest> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.setTitle("Browse Biome Profiles");
        dialog.setHeaderText("Load a mined biome profile report and inspect the discovered options.");
        dialog.setResizable(true);
        ButtonType generateButtonType = new ButtonType("Generate Preview", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(generateButtonType, new ButtonType("Close", ButtonBar.ButtonData.CANCEL_CLOSE));

        TextField pathField = new TextField(initialRequest != null && initialRequest.reportPath() != null && !initialRequest.reportPath().isBlank()
                ? initialRequest.reportPath()
                : defaultReportPath().toString());
        Button browseButton = new Button("Browse");
        Button loadButton = new Button("Load");
        Button addObjectsButton = new Button("Add Objects");
        Button addSettlementsButton = new Button("Add Settlements");
        Spinner<Integer> widthSpinner = spinner(1, 16, initialRequest == null ? 1 : initialRequest.width());
        Spinner<Integer> lengthSpinner = spinner(1, 16, initialRequest == null ? 1 : initialRequest.length());
        TextField seedField = new TextField(Long.toString(initialRequest == null ? randomSeed() : initialRequest.seed()));
        Button randomizeSeedButton = new Button("Randomize");
        Button previewUpButton = new Button("Up");
        Button previewDownButton = new Button("Down");
        Button previewLeftButton = new Button("Left");
        Button previewRightButton = new Button("Right");
        Label previewOffsetLabel = new Label();
        CacheBiomeProfileMiner.MiningReport[] reportState = new CacheBiomeProfileMiner.MiningReport[1];
        int[] originRegionXState = new int[] {initialRequest == null ? centeredOriginRegion(widthSpinner.getValue()) : initialRequest.originRegionX()};
        int[] originRegionYState = new int[] {initialRequest == null ? centeredOriginRegion(lengthSpinner.getValue()) : initialRequest.originRegionY()};

        TableView<CacheBiomeProfileMiner.BiomeProfile> profileTable = new TableView<>();
        profileTable.setPlaceholder(new Label("Load a biome profile report."));
        profileTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        profileTable.setPrefHeight(220.0);

        TableColumn<CacheBiomeProfileMiner.BiomeProfile, String> labelColumn = new TableColumn<>("Profile");
        labelColumn.setCellValueFactory(data -> new ReadOnlyStringWrapper(data.getValue().label));
        labelColumn.setPrefWidth(240);

        TableColumn<CacheBiomeProfileMiner.BiomeProfile, String> sizeColumn = new TableColumn<>("Regions");
        sizeColumn.setCellValueFactory(data -> new ReadOnlyStringWrapper(Integer.toString(data.getValue().regionCount)));
        sizeColumn.setPrefWidth(90);

        TableColumn<CacheBiomeProfileMiner.BiomeProfile, String> sampleColumn = new TableColumn<>("Examples");
        sampleColumn.setCellValueFactory(data -> new ReadOnlyStringWrapper(
                data.getValue().exampleRegions == null || data.getValue().exampleRegions.isEmpty()
                        ? ""
                        : Integer.toString(data.getValue().exampleRegions.get(0).hash)
        ));
        sampleColumn.setPrefWidth(110);

        profileTable.getColumns().addAll(labelColumn, sizeColumn, sampleColumn);

        TextArea detailArea = new TextArea();
        detailArea.setEditable(false);
        detailArea.setWrapText(false);
        detailArea.setPrefRowCount(12);
        detailArea.setPrefHeight(300.0);
        ImageView previewView = new ImageView();
        previewView.setPreserveRatio(true);
        previewView.setFitWidth(300.0);
        previewView.setFitHeight(300.0);
        previewView.setSmooth(false);
        Label previewPlaceholder = new Label("Load and select a biome profile to preview.");
        previewPlaceholder.setMouseTransparent(true);
        StackPane previewPane = new StackPane(previewView, previewPlaceholder);
        previewPane.setMinHeight(300.0);
        previewPane.setPrefHeight(300.0);
        previewPane.setStyle("-fx-background-color: #1e1e1e; -fx-border-color: #4c4c4c; -fx-border-width: 1;");
        previewUpButton.setMaxWidth(Double.MAX_VALUE);
        previewDownButton.setMaxWidth(Double.MAX_VALUE);
        previewLeftButton.setMaxHeight(Double.MAX_VALUE);
        previewRightButton.setMaxHeight(Double.MAX_VALUE);
        BorderPane previewControlsPane = new BorderPane(previewPane);
        previewControlsPane.setTop(previewUpButton);
        previewControlsPane.setBottom(previewDownButton);
        previewControlsPane.setLeft(previewLeftButton);
        previewControlsPane.setRight(previewRightButton);
        BorderPane.setAlignment(previewUpButton, Pos.CENTER);
        BorderPane.setAlignment(previewDownButton, Pos.CENTER);
        BorderPane.setAlignment(previewLeftButton, Pos.CENTER);
        BorderPane.setAlignment(previewRightButton, Pos.CENTER);
        BorderPane.setMargin(previewUpButton, new Insets(0.0, 56.0, 6.0, 56.0));
        BorderPane.setMargin(previewDownButton, new Insets(6.0, 56.0, 0.0, 56.0));
        BorderPane.setMargin(previewLeftButton, new Insets(28.0, 6.0, 28.0, 0.0));
        BorderPane.setMargin(previewRightButton, new Insets(28.0, 0.0, 28.0, 6.0));
        previewOffsetLabel.setText(formatPreviewOffset(originRegionXState[0], originRegionYState[0]));
        CacheBiomeProfileMiner.BiomeProfile[] selectedProfileState = new CacheBiomeProfileMiner.BiomeProfile[1];

        profileTable.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            selectedProfileState[0] = newValue;
            if (newValue == null) {
                detailArea.clear();
            } else {
                detailArea.setText(describeProfile(newValue));
            }
            refreshPreview(previewView, previewPlaceholder, newValue, widthSpinner.getValue(), lengthSpinner.getValue(), seedField.getText(), originRegionXState[0], originRegionYState[0]);
        });

        browseButton.setOnAction(event -> {
            File file = RetentionFileChooser.showOpenDialog("Open biome profile report", owner, FilterMode.JSON);
            if (file != null) {
                pathField.setText(file.toPath().toString());
                loadReport(owner, Paths.get(pathField.getText().trim()), profileTable, detailArea, reportState);
                updateActionState(dialog, generateButtonType, addObjectsButton, addSettlementsButton, reportState[0], profileTable.getSelectionModel().getSelectedItem());
                refreshPreview(previewView, previewPlaceholder, profileTable.getSelectionModel().getSelectedItem(), widthSpinner.getValue(), lengthSpinner.getValue(), seedField.getText(), originRegionXState[0], originRegionYState[0]);
            }
        });

        loadButton.setOnAction(event -> {
            loadReport(owner, Paths.get(pathField.getText().trim()), profileTable, detailArea, reportState);
            updateActionState(dialog, generateButtonType, addObjectsButton, addSettlementsButton, reportState[0], profileTable.getSelectionModel().getSelectedItem());
            refreshPreview(previewView, previewPlaceholder, profileTable.getSelectionModel().getSelectedItem(), widthSpinner.getValue(), lengthSpinner.getValue(), seedField.getText(), originRegionXState[0], originRegionYState[0]);
        });

        addObjectsButton.setOnAction(event -> {
            CacheBiomeProfileMiner.BiomeProfile selectedProfile = profileTable.getSelectionModel().getSelectedItem();
            replaceObjectsFromActiveRegion(
                    owner,
                    Paths.get(pathField.getText().trim()),
                    reportState[0],
                    selectedProfile,
                    profileTable,
                    detailArea,
                    previewView,
                    previewPlaceholder,
                    widthSpinner.getValue(),
                    lengthSpinner.getValue(),
                    seedField.getText(),
                    originRegionXState[0],
                    originRegionYState[0]
            );
        });

        addSettlementsButton.setOnAction(event -> {
            CacheBiomeProfileMiner.BiomeProfile selectedProfile = profileTable.getSelectionModel().getSelectedItem();
            replaceSettlementFromActiveRegion(
                    owner,
                    Paths.get(pathField.getText().trim()),
                    reportState[0],
                    selectedProfile,
                    profileTable,
                    detailArea,
                    previewView,
                    previewPlaceholder,
                    widthSpinner.getValue(),
                    lengthSpinner.getValue(),
                    seedField.getText(),
                    originRegionXState[0],
                    originRegionYState[0]
            );
        });

        randomizeSeedButton.setOnAction(event -> {
            seedField.setText(Long.toString(randomSeed()));
            refreshPreview(previewView, previewPlaceholder, selectedProfileState[0], widthSpinner.getValue(), lengthSpinner.getValue(), seedField.getText(), originRegionXState[0], originRegionYState[0]);
        });

        previewUpButton.setOnAction(event -> {
            originRegionYState[0] -= 1;
            previewOffsetLabel.setText(formatPreviewOffset(originRegionXState[0], originRegionYState[0]));
            refreshPreview(previewView, previewPlaceholder, selectedProfileState[0], widthSpinner.getValue(), lengthSpinner.getValue(), seedField.getText(), originRegionXState[0], originRegionYState[0]);
        });
        previewDownButton.setOnAction(event -> {
            originRegionYState[0] += 1;
            previewOffsetLabel.setText(formatPreviewOffset(originRegionXState[0], originRegionYState[0]));
            refreshPreview(previewView, previewPlaceholder, selectedProfileState[0], widthSpinner.getValue(), lengthSpinner.getValue(), seedField.getText(), originRegionXState[0], originRegionYState[0]);
        });
        previewLeftButton.setOnAction(event -> {
            originRegionXState[0] -= 1;
            previewOffsetLabel.setText(formatPreviewOffset(originRegionXState[0], originRegionYState[0]));
            refreshPreview(previewView, previewPlaceholder, selectedProfileState[0], widthSpinner.getValue(), lengthSpinner.getValue(), seedField.getText(), originRegionXState[0], originRegionYState[0]);
        });
        previewRightButton.setOnAction(event -> {
            originRegionXState[0] += 1;
            previewOffsetLabel.setText(formatPreviewOffset(originRegionXState[0], originRegionYState[0]));
            refreshPreview(previewView, previewPlaceholder, selectedProfileState[0], widthSpinner.getValue(), lengthSpinner.getValue(), seedField.getText(), originRegionXState[0], originRegionYState[0]);
        });

        HBox pathRow = new HBox(8.0, pathField, browseButton, loadButton);
        HBox.setHgrow(pathField, Priority.ALWAYS);

        HBox previewRow = new HBox(
                8.0,
                new Label("Width"),
                widthSpinner,
                new Label("Length"),
                lengthSpinner,
                new Label("Seed"),
                seedField,
                randomizeSeedButton,
                addObjectsButton,
                addSettlementsButton
        );
        HBox.setHgrow(seedField, Priority.ALWAYS);

        VBox detailPane = new VBox(8.0, new Label("Preview"), previewOffsetLabel, previewControlsPane, detailArea);
        VBox.setVgrow(detailArea, Priority.ALWAYS);

        SplitPane splitPane = new SplitPane(profileTable, detailPane);
        splitPane.setOrientation(Orientation.VERTICAL);
        splitPane.setDividerPositions(0.38);
        splitPane.setPrefHeight(560.0);

        VBox content = new VBox(10.0);
        content.setPadding(new Insets(12.0));
        content.getChildren().addAll(
                new Label("Profile Report"),
                pathRow,
                previewRow,
                splitPane
        );
        VBox.setVgrow(splitPane, Priority.ALWAYS);

        dialog.getDialogPane().setContent(content);
        Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
        dialog.getDialogPane().setPrefSize(
                Math.min(980.0, bounds.getWidth() * 0.82),
                Math.min(760.0, bounds.getHeight() * 0.82)
        );
        dialog.getDialogPane().setMinHeight(520.0);
        profileTable.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) ->
                updateActionState(dialog, generateButtonType, addObjectsButton, addSettlementsButton, reportState[0], newValue));
        widthSpinner.valueProperty().addListener((observable, oldValue, newValue) ->
                refreshPreview(previewView, previewPlaceholder, selectedProfileState[0], widthSpinner.getValue(), lengthSpinner.getValue(), seedField.getText(), originRegionXState[0], originRegionYState[0]));
        lengthSpinner.valueProperty().addListener((observable, oldValue, newValue) ->
                refreshPreview(previewView, previewPlaceholder, selectedProfileState[0], widthSpinner.getValue(), lengthSpinner.getValue(), seedField.getText(), originRegionXState[0], originRegionYState[0]));
        seedField.setOnAction(event ->
                refreshPreview(previewView, previewPlaceholder, selectedProfileState[0], widthSpinner.getValue(), lengthSpinner.getValue(), seedField.getText(), originRegionXState[0], originRegionYState[0]));
        seedField.focusedProperty().addListener((observable, oldValue, focused) -> {
            if (!focused) {
                refreshPreview(previewView, previewPlaceholder, selectedProfileState[0], widthSpinner.getValue(), lengthSpinner.getValue(), seedField.getText(), originRegionXState[0], originRegionYState[0]);
            }
        });

        if (initialRequest != null && initialRequest.report() != null && initialRequest.profile() != null) {
            reportState[0] = initialRequest.report();
            profileTable.setItems(FXCollections.observableArrayList(initialRequest.report().profiles));
            CacheBiomeProfileMiner.BiomeProfile initialProfile = findMatchingProfile(initialRequest.report(), initialRequest.profile());
            if (initialProfile != null) {
                profileTable.getSelectionModel().select(initialProfile);
            } else if (!initialRequest.report().profiles.isEmpty()) {
                selectDefaultProfile(profileTable, initialRequest.report());
            }
            if (profileTable.getSelectionModel().getSelectedItem() != null) {
                detailArea.setText(describeProfile(profileTable.getSelectionModel().getSelectedItem()));
            }
        } else {
            loadReport(owner, Paths.get(pathField.getText().trim()), profileTable, detailArea, reportState);
        }
        updateActionState(dialog, generateButtonType, addObjectsButton, addSettlementsButton, reportState[0], profileTable.getSelectionModel().getSelectedItem());
        refreshPreview(previewView, previewPlaceholder, profileTable.getSelectionModel().getSelectedItem(), widthSpinner.getValue(), lengthSpinner.getValue(), seedField.getText(), originRegionXState[0], originRegionYState[0]);

        dialog.setResultConverter(buttonType -> {
            if (buttonType != generateButtonType) {
                return null;
            }
            CacheBiomeProfileMiner.BiomeProfile selectedProfile = profileTable.getSelectionModel().getSelectedItem();
            if (reportState[0] == null || selectedProfile == null) {
                return null;
            }
            return new PreviewRequest(
                    reportState[0],
                    selectedProfile,
                    widthSpinner.getValue(),
                    lengthSpinner.getValue(),
                    parseSeed(seedField.getText()),
                    originRegionXState[0],
                    originRegionYState[0],
                    pathField.getText().trim()
            );
        });
        return dialog.showAndWait();
    }

    private static CacheBiomeProfileMiner.BiomeProfile findMatchingProfile(
            CacheBiomeProfileMiner.MiningReport report,
            CacheBiomeProfileMiner.BiomeProfile selectedProfile
    ) {
        if (report == null || report.profiles == null || selectedProfile == null) {
            return null;
        }
        for (CacheBiomeProfileMiner.BiomeProfile profile : report.profiles) {
            if (profile == null) {
                continue;
            }
            if (profile.profileId == selectedProfile.profileId) {
                return profile;
            }
            if (profile.label != null && profile.label.equals(selectedProfile.label)) {
                return profile;
            }
        }
        return null;
    }

    private static void loadReport(
            Stage owner,
            Path path,
            TableView<CacheBiomeProfileMiner.BiomeProfile> profileTable,
            TextArea detailArea,
            CacheBiomeProfileMiner.MiningReport[] reportState
    ) {
        if (path == null || path.toString().isBlank()) {
            return;
        }
        if (!Files.exists(path)) {
            profileTable.setItems(FXCollections.observableArrayList());
            detailArea.setText("Report not found:\n" + path);
            reportState[0] = null;
            return;
        }

        try (FileReader reader = new FileReader(path.toFile())) {
            CacheBiomeProfileMiner.MiningReport report = new Gson().fromJson(reader, CacheBiomeProfileMiner.MiningReport.class);
            if (report == null || report.profiles == null) {
                throw new JsonParseException("Report contained no profiles.");
            }
            CacheBiomeProfileMiner.applyBuiltInSettlementProfiles(report);
            BiomeProfilePreviewGenerator.invalidatePreviewCache();
            reportState[0] = report;
            profileTable.setItems(FXCollections.observableArrayList(report.profiles));
            if (!report.profiles.isEmpty()) {
                selectDefaultProfile(profileTable, report);
            } else {
                detailArea.setText("No profiles were present in the report.");
            }
        } catch (Exception ex) {
            reportState[0] = null;
            profileTable.setItems(FXCollections.observableArrayList());
            detailArea.clear();
            FXDialogs.showException(owner, "Failed to load biome profile report",
                    "The selected JSON report could not be loaded.", ex instanceof Exception ? (Exception) ex : new RuntimeException(ex));
        }
    }

    private static void selectDefaultProfile(
            TableView<CacheBiomeProfileMiner.BiomeProfile> profileTable,
            CacheBiomeProfileMiner.MiningReport report
    ) {
        if (profileTable == null || report == null || report.profiles == null || report.profiles.isEmpty()) {
            return;
        }
        for (CacheBiomeProfileMiner.BiomeProfile profile : report.profiles) {
            if (profile == null || profile.label == null) {
                continue;
            }
            if (profile.label.toLowerCase(Locale.ROOT).startsWith(DEFAULT_PROFILE_LABEL_PREFIX)) {
                profileTable.getSelectionModel().select(profile);
                return;
            }
        }
        profileTable.getSelectionModel().select(0);
    }

    private static void updateActionState(
            Dialog<PreviewRequest> dialog,
            ButtonType generateButtonType,
            Button addObjectsButton,
            Button addSettlementsButton,
            CacheBiomeProfileMiner.MiningReport report,
            CacheBiomeProfileMiner.BiomeProfile selectedProfile
    ) {
        boolean disabled = report == null || selectedProfile == null;
        Button generateButton = (Button) dialog.getDialogPane().lookupButton(generateButtonType);
        if (generateButton != null) {
            generateButton.setDisable(disabled);
        }
        if (addObjectsButton != null) {
            addObjectsButton.setDisable(disabled);
        }
        if (addSettlementsButton != null) {
            addSettlementsButton.setDisable(disabled);
        }
    }

    private static void replaceObjectsFromActiveRegion(
            Stage owner,
            Path reportPath,
            CacheBiomeProfileMiner.MiningReport report,
            CacheBiomeProfileMiner.BiomeProfile selectedProfile,
            TableView<CacheBiomeProfileMiner.BiomeProfile> profileTable,
            TextArea detailArea,
            ImageView previewView,
            Label previewPlaceholder,
            int width,
            int length,
            String rawSeed,
            int originRegionX,
            int originRegionY
    ) {
        if (report == null || selectedProfile == null) {
            FXDialogs.showWarning(owner, "No biome selected", "Load a report and select a biome profile first.");
            return;
        }

        Client client = Client.getSingleton();
        if (client == null) {
            FXDialogs.showError(owner, "No active map", "RSPSi has no active client instance.");
            return;
        }

        Chunk chunk = client.getCurrentChunk();
        if (chunk == null) {
            FXDialogs.showError(owner, "No active region", "Load a region by hash before importing objects into a biome profile.");
            return;
        }

        byte[] objectData = chunk.scenegraph != null ? chunk.scenegraph.saveObjects(chunk) : chunk.objectMapData;
        ActiveRegionObjectImport imported = importObjectsFromChunk(chunk, objectData);

        selectedProfile.topObjects = imported.topObjects();
        selectedProfile.topTokens = imported.topTokens();
        selectedProfile.objectPlacement = imported.objectPlacement();
        if (selectedProfile.metrics == null) {
            selectedProfile.metrics = new CacheBiomeProfileMiner.ClusterMetrics();
        }
        selectedProfile.metrics.namedObjectDensity = imported.namedObjectDensity();

        if (report.featureTokens == null || report.featureTokens.isEmpty()) {
            report.featureTokens = imported.topTokens().stream().map(token -> token.token).toList();
        }
        report.generatedAtUtc = Instant.now().toString();

        if (!saveReport(owner, reportPath, report,
                "Failed to save biome profile report",
                "The selected profile was updated in memory, but the report JSON could not be written.")) {
            return;
        }

        BiomeProfilePreviewGenerator.invalidatePreviewCache();
        profileTable.refresh();
        detailArea.setText(describeProfile(selectedProfile));
        refreshPreview(previewView, previewPlaceholder, selectedProfile, width, length, rawSeed, originRegionX, originRegionY);
        FXDialogs.showInformation(owner, "Biome objects replaced",
                "Replaced object lists for " + selectedProfile.label + " using active region " + chunk.regionHash + ".");
    }

    private static void replaceSettlementFromActiveRegion(
            Stage owner,
            Path reportPath,
            CacheBiomeProfileMiner.MiningReport report,
            CacheBiomeProfileMiner.BiomeProfile selectedProfile,
            TableView<CacheBiomeProfileMiner.BiomeProfile> profileTable,
            TextArea detailArea,
            ImageView previewView,
            Label previewPlaceholder,
            int width,
            int length,
            String rawSeed,
            int originRegionX,
            int originRegionY
    ) {
        if (report == null || selectedProfile == null) {
            FXDialogs.showWarning(owner, "No biome selected", "Load a report and select a biome profile first.");
            return;
        }

        Client client = Client.getSingleton();
        if (client == null) {
            FXDialogs.showError(owner, "No active map", "RSPSi has no active client instance.");
            return;
        }

        Chunk chunk = client.getCurrentChunk();
        if (chunk == null) {
            FXDialogs.showError(owner, "No active region", "Load a region by hash before importing a settlement profile.");
            return;
        }

        byte[] objectData = chunk.objectMapData != null && chunk.objectMapData.length > 0
                ? chunk.objectMapData
                : chunk.scenegraph != null ? chunk.scenegraph.saveObjects(chunk) : null;
        ActiveRegionSettlementImport imported = importSettlementFromChunk(chunk, objectData);
        if (imported.profile() == null) {
            FXDialogs.showWarning(owner, "No settlement found",
                    "No settlement buildings could be mined from the active region " + chunk.regionHash + ".");
            return;
        }

        selectedProfile.settlementProfile = imported.profile();
        report.generatedAtUtc = Instant.now().toString();
        if (!saveReport(owner, reportPath, report,
                "Failed to save biome profile report",
                "The selected profile was updated in memory, but the report JSON could not be written.")) {
            return;
        }

        BiomeProfilePreviewGenerator.invalidatePreviewCache();
        profileTable.refresh();
        detailArea.setText(describeProfile(selectedProfile));
        refreshPreview(previewView, previewPlaceholder, selectedProfile, width, length, rawSeed, originRegionX, originRegionY);
        FXDialogs.showInformation(owner, "Settlement imported",
                "Imported " + imported.profile().sampledBuildingCount + " settlement buildings from active region "
                        + chunk.regionHash + " into " + selectedProfile.label + ".");
    }

    private static boolean saveReport(
            Stage owner,
            Path reportPath,
            CacheBiomeProfileMiner.MiningReport report,
            String title,
            String message
    ) {
        try {
            if (reportPath.getParent() != null) {
                Files.createDirectories(reportPath.getParent());
            }
            try (Writer writer = Files.newBufferedWriter(reportPath)) {
                PRETTY_GSON.toJson(report, writer);
            }
            return true;
        } catch (Exception ex) {
            FXDialogs.showException(owner, title, message,
                    ex instanceof Exception ? (Exception) ex : new RuntimeException(ex));
            return false;
        }
    }

    private static ActiveRegionSettlementImport importSettlementFromChunk(Chunk chunk, byte[] objectData) {
        if (chunk == null || objectData == null || objectData.length == 0) {
            return new ActiveRegionSettlementImport(null);
        }

        List<ActiveRegionObject> objects = decodeActiveRegionObjects(objectData);
        if (objects.isEmpty()) {
            return new ActiveRegionSettlementImport(null);
        }

        List<SettlementBuildingSample> buildings = mineSettlementBuildings(chunk, objects);
        if (buildings.isEmpty()) {
            return new ActiveRegionSettlementImport(null);
        }

        return new ActiveRegionSettlementImport(buildSettlementProfile(chunk, objects, buildings));
    }

    private static List<SettlementBuildingSample> mineSettlementBuildings(Chunk chunk, List<ActiveRegionObject> objects) {
        boolean[][] structureMask = new boolean[64][64];
        Map<Integer, List<ActiveRegionObject>> objectsByTile = new HashMap<>();
        for (ActiveRegionObject object : objects) {
            if (!isLikelySettlementStructure(object)) {
                continue;
            }
            structureMask[object.x()][object.y()] = true;
            objectsByTile.computeIfAbsent(tileKey(object.x(), object.y()), ignored -> new ArrayList<>()).add(object);
        }

        boolean[][] visited = new boolean[64][64];
        List<SettlementBuildingSample> buildings = new ArrayList<>();
        for (int startX = 0; startX < 64; startX++) {
            for (int startY = 0; startY < 64; startY++) {
                if (!structureMask[startX][startY] || visited[startX][startY]) {
                    continue;
                }
                List<TilePoint> tiles = new ArrayList<>();
                ArrayList<TilePoint> queue = new ArrayList<>();
                queue.add(new TilePoint(startX, startY));
                visited[startX][startY] = true;
                for (int index = 0; index < queue.size(); index++) {
                    TilePoint tile = queue.get(index);
                    tiles.add(tile);
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dy = -1; dy <= 1; dy++) {
                            if (dx == 0 && dy == 0) {
                                continue;
                            }
                            int nextX = tile.x() + dx;
                            int nextY = tile.y() + dy;
                            if (nextX < 0 || nextX >= 64 || nextY < 0 || nextY >= 64
                                    || visited[nextX][nextY] || !structureMask[nextX][nextY]) {
                                continue;
                            }
                            visited[nextX][nextY] = true;
                            queue.add(new TilePoint(nextX, nextY));
                        }
                    }
                }
                SettlementBuildingSample sample = buildSettlementBuildingSample(chunk, objects, objectsByTile, tiles);
                if (sample != null) {
                    buildings.add(sample);
                }
            }
        }
        buildings.sort(Comparator.comparingInt(SettlementBuildingSample::area).reversed());
        return buildings;
    }

    private static SettlementBuildingSample buildSettlementBuildingSample(
            Chunk chunk,
            List<ActiveRegionObject> objects,
            Map<Integer, List<ActiveRegionObject>> objectsByTile,
            List<TilePoint> componentTiles
    ) {
        if (componentTiles.isEmpty()) {
            return null;
        }
        int minX = componentTiles.stream().mapToInt(TilePoint::x).min().orElse(0);
        int minY = componentTiles.stream().mapToInt(TilePoint::y).min().orElse(0);
        int maxX = componentTiles.stream().mapToInt(TilePoint::x).max().orElse(0);
        int maxY = componentTiles.stream().mapToInt(TilePoint::y).max().orElse(0);
        int width = maxX - minX + 1;
        int height = maxY - minY + 1;
        if (width < 4 || height < 4) {
            return null;
        }

        int expandedMinX = Math.max(0, minX - 1);
        int expandedMinY = Math.max(0, minY - 1);
        int expandedMaxX = Math.min(63, maxX + 1);
        int expandedMaxY = Math.min(63, maxY + 1);
        List<ActiveRegionObject> nearbyObjects = objects.stream()
                .filter(object -> object.x() >= expandedMinX
                        && object.x() <= expandedMaxX
                        && object.y() >= expandedMinY
                        && object.y() <= expandedMaxY)
                .toList();
        List<ActiveRegionObject> groundObjects = nearbyObjects.stream()
                .filter(object -> object.plane() == 0)
                .toList();
        long wallLikeCount = groundObjects.stream()
                .filter(object -> object.type() == 0 || object.type() == 1 || object.type() == 2 || object.type() == 3 || object.type() == 4)
                .count();
        if (wallLikeCount < 6) {
            return null;
        }
        int boundaryCompleteness = boundaryCompletenessScore(componentTiles, minX, minY, maxX, maxY);
        if (boundaryCompleteness <= 0) {
            return null;
        }

        int roofPlane = selectRoofPlane(chunk, nearbyObjects, minX, minY, maxX, maxY, expandedMinX, expandedMinY, expandedMaxX, expandedMaxY);
        int highestPlane = highestTemplatePlane(chunk, nearbyObjects, minX, minY, maxX, maxY);
        List<ActiveRegionObject> roofObjects = nearbyObjects.stream()
                .filter(object -> object.plane() == roofPlane && roofPlane > 0)
                .toList();

        List<ActiveRegionObject> upperWallObjects = nearbyObjects.stream()
                .filter(object -> object.plane() > 0
                        && object.plane() <= highestPlane
                        && object.plane() != roofPlane)
                .toList();

        int padHeightRange = buildingPadHeightRange(chunk, minX, minY, maxX, maxY);
        double waterDistance = distanceToNearestWater(chunk, minX, minY, maxX, maxY, 16);
        return new SettlementBuildingSample(
                minX,
                minY,
                maxX,
                maxY,
                Math.max(1, highestPlane),
                Math.max(1, roofPlane == 0 ? highestPlane : roofPlane),
                groundObjects,
                upperWallObjects,
                roofObjects,
                padHeightRange,
                waterDistance
        );
    }

    private static CacheBiomeProfileMiner.SettlementProfile buildSettlementProfile(
            Chunk chunk,
            List<ActiveRegionObject> objects,
            List<SettlementBuildingSample> buildings
    ) {
        CacheBiomeProfileMiner.SettlementProfile profile = new CacheBiomeProfileMiner.SettlementProfile();
        profile.id = "imported_settlement_" + chunk.regionHash;
        profile.sourceRegionHash = chunk.regionHash;
        profile.sampledBuildingCount = buildings.size();
        profile.layoutStyle = averageWaterDistance(buildings) <= 5.0 ? "riverside_compound" : "clustered_rectilinear";
        profile.perimeterTiles = 1;

        Bounds settlementBounds = unionBounds(buildings);
        TerrainPalette palette = mineSettlementTerrainPalette(chunk, settlementBounds);
        profile.primaryUnderlayId = palette.primaryUnderlayId();
        profile.secondaryUnderlayId = palette.secondaryUnderlayId();
        profile.accentOverlayId = palette.accentOverlayId();
        profile.waterOverlayId = com.rspsi.tools.MapDataExemplarLibrary.REAL_WATER_OVERLAY_ID;

        Map<Integer, Integer> wallCounts = new HashMap<>();
        Map<Integer, Integer> upperWallCounts = new HashMap<>();
        Map<Integer, Integer> cornerCounts = new HashMap<>();
        Map<Integer, Integer> doorCounts = new HashMap<>();
        Map<Integer, Integer> wallDecorCounts = new HashMap<>();
        Map<Integer, Integer> roofEdgeCounts = new HashMap<>();
        Map<Integer, Integer> roofCornerCounts = new HashMap<>();
        Map<Integer, Integer> roofInsetCounts = new HashMap<>();
        Map<Integer, Integer> roofFlatCounts = new HashMap<>();
        Map<Integer, Integer> roofAnyCounts = new HashMap<>();
        Map<FootprintKey, Integer> footprintCounts = new HashMap<>();
        Map<FootprintKey, BuildingTemplateCandidate> buildingTemplateCandidates = new LinkedHashMap<>();
        Map<FootprintKey, RoofTemplateCandidate> roofTemplateCandidates = new LinkedHashMap<>();

        for (SettlementBuildingSample building : buildings) {
            FootprintKey footprintKey = new FootprintKey(
                    Math.min(building.width(), building.height()),
                    Math.max(building.width(), building.height()),
                    Math.max(1, building.floors())
            );
            footprintCounts.merge(footprintKey, 1, Integer::sum);

            int packedWall = packedWallPlacement(building.groundObjects());
            if (packedWall != 0) {
                wallCounts.merge(packedWall, 1, Integer::sum);
            }
            int packedUpperWall = packedUpperWallPlacement(building.upperWallObjects());
            if (packedUpperWall != 0) {
                upperWallCounts.merge(packedUpperWall, 1, Integer::sum);
            }
            int packedCorner = packedCornerPlacement(building.groundObjects());
            if (packedCorner != 0) {
                cornerCounts.merge(packedCorner, 1, Integer::sum);
            }
            int packedDoor = packedDoorPlacement(building.groundObjects(), packedWall, packedCorner);
            if (packedDoor != 0) {
                doorCounts.merge(packedDoor, 1, Integer::sum);
            }
            int packedWallDecor = packedWallDecorPlacement(building.groundObjects());
            if (packedWallDecor != 0) {
                wallDecorCounts.merge(packedWallDecor, 1, Integer::sum);
            }

            CacheBiomeProfileMiner.SettlementBuildingTemplate buildingTemplate = buildBuildingTemplate(chunk, building);
            if (buildingTemplate != null) {
                int completenessScore = templateCompletenessScore(buildingTemplate);
                if (completenessScore <= 0) {
                    buildingTemplate = null;
                }
            }
            if (buildingTemplate != null) {
                int score = (buildingTemplate.objects == null ? 0 : buildingTemplate.objects.size() * 4)
                        + (buildingTemplate.tiles == null ? 0 : buildingTemplate.tiles.size() * 2)
                        + templateCompletenessScore(buildingTemplate) * 8;
                BuildingTemplateCandidate existing = buildingTemplateCandidates.get(footprintKey);
                if (existing == null || score > existing.score()) {
                    buildingTemplateCandidates.put(footprintKey, new BuildingTemplateCandidate(buildingTemplate, score));
                }
            }

            CacheBiomeProfileMiner.SettlementRoofTemplate roofTemplate = buildRoofTemplate(chunk, building);
            if (roofTemplate != null) {
                int score = roofTemplate.objects == null ? 0 : roofTemplate.objects.size() * 4;
                score += roofTemplate.tiles == null ? 0 : roofTemplate.tiles.size() * 2;
                RoofTemplateCandidate existing = roofTemplateCandidates.get(footprintKey);
                if (existing == null || score > existing.score()) {
                    roofTemplateCandidates.put(footprintKey, new RoofTemplateCandidate(roofTemplate, score));
                }
            }

            RoofPlacements roofPlacements = classifyRoofPlacements(roofTemplate);
            mergePlacementCounts(roofEdgeCounts, roofPlacements.edgeCounts());
            mergePlacementCounts(roofCornerCounts, roofPlacements.cornerCounts());
            mergePlacementCounts(roofInsetCounts, roofPlacements.insetCornerCounts());
            mergePlacementCounts(roofFlatCounts, roofPlacements.flatCounts());
            mergePlacementCounts(roofAnyCounts, roofPlacements.allCounts());
        }

        int wallPlacement = topPlacement(wallCounts, 1415, 0);
        int upperWallPlacement = topPlacement(upperWallCounts, unpackPlacementId(wallPlacement), unpackPlacementType(wallPlacement));
        int cornerPlacement = topPlacement(cornerCounts, 6248, 3);
        int doorPlacement = topPlacement(doorCounts, 1534, 0);
        int wallDecorPlacement = topPlacement(wallDecorCounts, 6247, 4);
        int roofBasePlacement = topPlacement(roofAnyCounts, 1416, 10);
        int roofEdgePlacement = topPlacement(roofEdgeCounts, unpackPlacementId(roofBasePlacement), unpackPlacementType(roofBasePlacement));
        int roofCornerPlacement = topPlacement(roofCornerCounts, unpackPlacementId(roofEdgePlacement), unpackPlacementType(roofEdgePlacement));
        int roofInsetPlacement = topPlacement(roofInsetCounts, unpackPlacementId(roofEdgePlacement), unpackPlacementType(roofEdgePlacement));
        int roofFlatPlacement = topPlacement(roofFlatCounts, unpackPlacementId(roofBasePlacement), unpackPlacementType(roofBasePlacement));

        profile.wallId = unpackPlacementId(wallPlacement);
        profile.wallType = unpackPlacementType(wallPlacement);
        profile.upperWallId = unpackPlacementId(upperWallPlacement);
        profile.upperWallType = unpackPlacementType(upperWallPlacement);
        profile.cornerId = unpackPlacementId(cornerPlacement);
        profile.cornerType = unpackPlacementType(cornerPlacement);
        profile.doorId = unpackPlacementId(doorPlacement);
        profile.doorType = unpackPlacementType(doorPlacement);
        profile.wallDecorId = unpackPlacementId(wallDecorPlacement);
        profile.wallDecorType = unpackPlacementType(wallDecorPlacement);
        profile.roofSlopeId = unpackPlacementId(roofEdgePlacement);
        profile.roofEdgeId = unpackPlacementId(roofEdgePlacement);
        profile.roofEdgeType = unpackPlacementType(roofEdgePlacement);
        profile.roofCornerId = unpackPlacementId(roofCornerPlacement);
        profile.roofCornerType = unpackPlacementType(roofCornerPlacement);
        profile.roofInsetCornerId = unpackPlacementId(roofInsetPlacement);
        profile.roofInsetCornerType = unpackPlacementType(roofInsetPlacement);
        profile.roofFlatId = unpackPlacementId(roofFlatPlacement);
        profile.roofFlatType = unpackPlacementType(roofFlatPlacement);

        List<WeightedFootprint> sortedFootprints = buildWeightedFootprints(footprintCounts);
        profile.anchorFootprints = anchorFootprints(sortedFootprints);
        profile.satelliteFootprints = satelliteFootprints(sortedFootprints);
        profile.buildingTemplates = buildWeightedBuildingTemplates(sortedFootprints, buildingTemplateCandidates);
        profile.roofTemplates = roofTemplateCandidates.values().stream()
                .map(RoofTemplateCandidate::template)
                .sorted((left, right) -> Integer.compare((right.width * right.height), (left.width * left.height)))
                .toList();
        profile.minAnchors = 1;
        profile.maxAnchors = Math.max(1, Math.min(2, profile.anchorFootprints.size()));
        int satelliteDefault = Math.max(1, Math.min(6, Math.max(1, buildings.size() - profile.maxAnchors)));
        profile.minSatellites = Math.min(satelliteDefault, 3);
        profile.maxSatellites = satelliteDefault;
        profile.props = mineSettlementProps(chunk, objects, settlementBounds);
        return profile;
    }

    private static ActiveRegionObjectImport importObjectsFromChunk(Chunk chunk, byte[] objectData) {
        if (chunk == null || objectData == null || objectData.length == 0) {
            return new ActiveRegionObjectImport(List.of(), List.of(), 0.0, new CacheBiomeProfileMiner.ObjectPlacementProfile());
        }

        int landTileCount = countLandTiles(chunk);
        int waterTileCount = Math.max(0, 4096 - landTileCount);
        Map<Integer, Integer> objectCounts = new HashMap<>();
        Map<String, Integer> tokenCounts = new LinkedHashMap<>();
        CategoryAccumulator trees = new CategoryAccumulator();
        CategoryAccumulator shrubs = new CategoryAccumulator();
        CategoryAccumulator decor = new CategoryAccumulator();
        int namedObjectCount = 0;

        Buffer buffer = new Buffer(objectData);
        int objectId = -1;
        while (true) {
            int idOffset = buffer.readUSmartInt();
            if (idOffset == 0) {
                break;
            }
            objectId += idOffset;
            int position = 0;
            while (true) {
                int offset = buffer.readUSmartInt();
                if (offset == 0) {
                    break;
                }
                position += offset - 1;
                int localY = position & 0x3f;
                int localX = position >> 6 & 0x3f;
                int plane = position >> 12;
                int config = buffer.readUByte();
                if (plane != 0) {
                    continue;
                }
                if (isWaterTile(chunk, localX, localY)) {
                    continue;
                }

                int type = config >> 2;
                int orientation = config & 3;

                objectCounts.merge(objectId, 1, Integer::sum);
                ObjectDefinition definition = ObjectDefinitionLoader.lookup(objectId);
                if (definition == null) {
                    continue;
                }
                String name = safeName(definition.getName());
                if (name.isEmpty() || "null".equals(name)) {
                    continue;
                }
                namedObjectCount++;
                for (String token : tokenize(name)) {
                    tokenCounts.merge(token, 1, Integer::sum);
                }

                CategoryAccumulator category = selectCategoryAccumulator(name, type, trees, shrubs, decor);
                if (category != null) {
                    category.record(objectId, name, type, orientation, localX, localY);
                }
            }
        }

        CacheBiomeProfileMiner.ObjectPlacementProfile objectPlacement = new CacheBiomeProfileMiner.ObjectPlacementProfile();
        objectPlacement.landTileCount = landTileCount;
        objectPlacement.waterTileCount = waterTileCount;
        objectPlacement.totalNamedObjectDensity = roundTo(namedObjectCount / (double) Math.max(1, landTileCount), 6);
        objectPlacement.trees = trees.toPlacementProfile(landTileCount);
        objectPlacement.shrubs = shrubs.toPlacementProfile(landTileCount);
        objectPlacement.decor = decor.toPlacementProfile(landTileCount);

        return new ActiveRegionObjectImport(
                buildRankedObjects(objectCounts),
                buildRankedTokens(tokenCounts),
                objectPlacement.totalNamedObjectDensity,
                objectPlacement
        );
    }

    private static List<CacheBiomeProfileMiner.RankedObject> buildRankedObjects(Map<Integer, Integer> counts) {
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        if (total == 0) {
            return List.of();
        }
        return counts.entrySet().stream()
                .sorted((left, right) -> Integer.compare(right.getValue(), left.getValue()))
                .map(entry -> {
                    ObjectDefinition definition = ObjectDefinitionLoader.lookup(entry.getKey());
                    String name = definition == null ? "" : safeName(definition.getName());
                    return new CacheBiomeProfileMiner.RankedObject(entry.getKey(), name, entry.getValue() / (double) total);
                })
                .toList();
    }

    private static List<CacheBiomeProfileMiner.RankedToken> buildRankedTokens(Map<String, Integer> counts) {
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        if (total == 0) {
            return List.of();
        }
        return counts.entrySet().stream()
                .sorted((left, right) -> Integer.compare(right.getValue(), left.getValue()))
                .limit(32)
                .map(entry -> new CacheBiomeProfileMiner.RankedToken(entry.getKey(), entry.getValue() / (double) total))
                .toList();
    }

    private static String safeName(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }

    private static Set<String> tokenize(String value) {
        if (value == null || value.isBlank()) {
            return Collections.emptySet();
        }
        String normalized = safeName(value).replaceAll("[^a-z0-9]+", " ").trim();
        if (normalized.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : normalized.split("\\s+")) {
            if (token.length() < 3 || TOKEN_STOPWORDS.contains(token) || token.chars().allMatch(Character::isDigit)) {
                continue;
            }
            tokens.add(token);
        }
        return tokens;
    }

    private static List<ActiveRegionObject> decodeActiveRegionObjects(byte[] objectData) {
        if (objectData == null || objectData.length == 0) {
            return List.of();
        }
        List<ActiveRegionObject> objects = new ArrayList<>();
        Buffer buffer = new Buffer(objectData);
        int objectId = -1;
        while (true) {
            int idOffset = buffer.readUSmartInt();
            if (idOffset == 0) {
                break;
            }
            objectId += idOffset;
            int position = 0;
            while (true) {
                int offset = buffer.readUSmartInt();
                if (offset == 0) {
                    break;
                }
                position += offset - 1;
                int localY = position & 0x3f;
                int localX = position >> 6 & 0x3f;
                int plane = position >> 12;
                int config = buffer.readUByte();
                int type = config >> 2;
                int orientation = config & 3;
                ObjectDefinition definition = ObjectDefinitionLoader.lookup(objectId);
                String name = definition == null ? "" : safeName(definition.getName());
                objects.add(new ActiveRegionObject(objectId, type, orientation, localX, localY, plane, name));
            }
        }
        return objects;
    }

    private static boolean isLikelySettlementStructure(ActiveRegionObject object) {
        if (object == null || object.plane() != 0) {
            return false;
        }
        if (containsAny(object.name(), "fence", "railing") && !containsAny(object.name(), "curtain", "door")) {
            return false;
        }
        if (containsAny(object.name(), "wall", "door", "curtain", "window", "shutter", "house", "building")) {
            return true;
        }
        return object.type() == 0 || object.type() == 1 || object.type() == 2 || object.type() == 3 || object.type() == 4;
    }

    private static boolean isLikelyRoofObject(ActiveRegionObject object) {
        if (object == null || object.plane() <= 0) {
            return false;
        }
        if (containsAny(object.name(), "roof", "awning", "tent")) {
            return true;
        }
        return object.type() >= 12 && object.type() <= 21;
    }

    private static int selectRoofPlane(
            Chunk chunk,
            List<ActiveRegionObject> nearbyObjects,
            int buildingMinX,
            int buildingMinY,
            int buildingMaxX,
            int buildingMaxY,
            int minX,
            int minY,
            int maxX,
            int maxY
    ) {
        Map<Integer, Integer> scores = new HashMap<>();
        for (int plane = 1; plane < 4; plane++) {
            int tileScore = 0;
            for (int localX = Math.max(0, buildingMinX); localX <= Math.min(63, buildingMaxX); localX++) {
                for (int localY = Math.max(0, buildingMinY); localY <= Math.min(63, buildingMaxY); localY++) {
                    TileLookup lookup = resolveTileLookup(chunk, localX, localY);
                    if (lookup == null || !isValidPlaneLookup(chunk, plane, lookup)) {
                        continue;
                    }
                    int underlayId = chunk.mapRegion.underlays[plane][lookup.x()][lookup.y()] & 0xffff;
                    int overlayId = chunk.mapRegion.overlays[plane][lookup.x()][lookup.y()] & 0xffff;
                    int tileFlags = chunk.mapRegion.tileFlags[plane][lookup.x()][lookup.y()] & 0xff;
                    if (overlayId > 0) {
                        tileScore += 3;
                    } else if (underlayId > 0) {
                        tileScore += 2;
                    } else if (tileFlags != 0) {
                        tileScore += 1;
                    }
                }
            }
            if (tileScore > 0) {
                scores.merge(plane, tileScore, Integer::sum);
            }
        }
        for (ActiveRegionObject object : nearbyObjects) {
            if (object.plane() <= 0) {
                continue;
            }
            int weight = 1;
            if (isLikelyRoofObject(object)) {
                weight += 4;
            }
            boolean edge = object.x() == minX || object.x() == maxX || object.y() == minY || object.y() == maxY;
            if (edge) {
                weight += 1;
            }
            scores.merge(object.plane(), weight, Integer::sum);
        }
        return scores.entrySet().stream()
                .sorted((left, right) -> {
                    int scoreCompare = Integer.compare(right.getValue(), left.getValue());
                    if (scoreCompare != 0) {
                        return scoreCompare;
                    }
                    return Integer.compare(right.getKey(), left.getKey());
                })
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(0);
    }

    private static int highestTemplatePlane(
            Chunk chunk,
            List<ActiveRegionObject> nearbyObjects,
            int minX,
            int minY,
            int maxX,
            int maxY
    ) {
        int highest = nearbyObjects.stream()
                .filter(object -> object.x() >= minX && object.x() <= maxX && object.y() >= minY && object.y() <= maxY)
                .mapToInt(ActiveRegionObject::plane)
                .max()
                .orElse(0);
        for (int plane = 1; plane < 4; plane++) {
            for (int localX = minX; localX <= maxX; localX++) {
                for (int localY = minY; localY <= maxY; localY++) {
                    TileLookup lookup = resolveTileLookup(chunk, localX, localY);
                    if (lookup == null || !isValidPlaneLookup(chunk, plane, lookup)) {
                        continue;
                    }
                    int underlayId = chunk.mapRegion.underlays[plane][lookup.x()][lookup.y()] & 0xffff;
                    int overlayId = chunk.mapRegion.overlays[plane][lookup.x()][lookup.y()] & 0xffff;
                    int tileFlags = chunk.mapRegion.tileFlags[plane][lookup.x()][lookup.y()] & 0xff;
                    if (underlayId > 0 || overlayId > 0 || tileFlags > 0) {
                        highest = Math.max(highest, plane);
                    }
                }
            }
        }
        return highest;
    }

    private static int dominantPlane(List<ActiveRegionObject> objects) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (ActiveRegionObject object : objects) {
            counts.merge(object.plane(), 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(1);
    }

    private static int buildingPadHeightRange(Chunk chunk, int minX, int minY, int maxX, int maxY) {
        if (chunk == null || chunk.mapRegion == null || chunk.mapRegion.tileHeights == null) {
            return 0;
        }
        int minHeight = Integer.MAX_VALUE;
        int maxHeight = Integer.MIN_VALUE;
        for (int localX = Math.max(0, minX); localX <= Math.min(63, maxX); localX++) {
            for (int localY = Math.max(0, minY); localY <= Math.min(63, maxY); localY++) {
                TileLookup lookup = resolveTileLookup(chunk, localX, localY);
                if (lookup == null) {
                    continue;
                }
                int height = -chunk.mapRegion.tileHeights[0][lookup.x()][lookup.y()];
                minHeight = Math.min(minHeight, height);
                maxHeight = Math.max(maxHeight, height);
            }
        }
        if (minHeight == Integer.MAX_VALUE) {
            return 0;
        }
        return maxHeight - minHeight;
    }

    private static double distanceToNearestWater(Chunk chunk, int minX, int minY, int maxX, int maxY, int radius) {
        double best = Double.MAX_VALUE;
        for (int localX = Math.max(0, minX - radius); localX <= Math.min(63, maxX + radius); localX++) {
            for (int localY = Math.max(0, minY - radius); localY <= Math.min(63, maxY + radius); localY++) {
                if (!isWaterTile(chunk, localX, localY)) {
                    continue;
                }
                double dx = 0.0;
                if (localX < minX) {
                    dx = minX - localX;
                } else if (localX > maxX) {
                    dx = localX - maxX;
                }
                double dy = 0.0;
                if (localY < minY) {
                    dy = minY - localY;
                } else if (localY > maxY) {
                    dy = localY - maxY;
                }
                best = Math.min(best, Math.sqrt(dx * dx + dy * dy));
            }
        }
        return best == Double.MAX_VALUE ? 99.0 : best;
    }

    private static double averageWaterDistance(List<SettlementBuildingSample> buildings) {
        if (buildings == null || buildings.isEmpty()) {
            return 99.0;
        }
        return buildings.stream().mapToDouble(SettlementBuildingSample::waterDistance).average().orElse(99.0);
    }

    private static Bounds unionBounds(List<SettlementBuildingSample> buildings) {
        int minX = buildings.stream().mapToInt(SettlementBuildingSample::minX).min().orElse(0);
        int minY = buildings.stream().mapToInt(SettlementBuildingSample::minY).min().orElse(0);
        int maxX = buildings.stream().mapToInt(SettlementBuildingSample::maxX).max().orElse(63);
        int maxY = buildings.stream().mapToInt(SettlementBuildingSample::maxY).max().orElse(63);
        return new Bounds(minX, minY, maxX, maxY);
    }

    private static TerrainPalette mineSettlementTerrainPalette(Chunk chunk, Bounds bounds) {
        Map<Integer, Integer> underlays = new HashMap<>();
        Map<Integer, Integer> overlays = new HashMap<>();
        Map<Integer, Integer> waterOverlays = new HashMap<>();
        for (int localX = Math.max(0, bounds.minX() - 3); localX <= Math.min(63, bounds.maxX() + 3); localX++) {
            for (int localY = Math.max(0, bounds.minY() - 3); localY <= Math.min(63, bounds.maxY() + 3); localY++) {
                if (chunk.mapRegion == null || chunk.mapRegion.underlays == null || chunk.mapRegion.overlays == null) {
                    continue;
                }
                TileLookup lookup = resolveTileLookup(chunk, localX, localY);
                if (lookup == null) {
                    continue;
                }
                int underlayId = chunk.mapRegion.underlays[0][lookup.x()][lookup.y()] & 0xffff;
                int overlayId = chunk.mapRegion.overlays[0][lookup.x()][lookup.y()] & 0xffff;
                if (underlayId > 0 && !isWaterTile(chunk, localX, localY)) {
                    underlays.merge(underlayId, 1, Integer::sum);
                }
                if (overlayId > 0) {
                    overlays.merge(overlayId, 1, Integer::sum);
                    if (WATER_OVERLAY_IDS.contains(overlayId)) {
                        waterOverlays.merge(overlayId, 1, Integer::sum);
                    }
                }
            }
        }
        int primaryUnderlay = topInt(underlays, 1);
        int secondaryUnderlay = secondInt(underlays, primaryUnderlay);
        int accentOverlay = topNonWaterOverlay(overlays);
        int waterOverlay = topInt(waterOverlays, 6);
        return new TerrainPalette(
                primaryUnderlay,
                secondaryUnderlay <= 0 ? primaryUnderlay : secondaryUnderlay,
                accentOverlay,
                waterOverlay
        );
    }

    private static int packedWallPlacement(List<ActiveRegionObject> objects) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (ActiveRegionObject object : objects) {
            if (object.plane() == 0 && (object.type() == 0 || object.type() == 1 || object.type() == 2)
                    && !containsAny(object.name(), "door", "curtain")) {
                counts.merge(packPlacement(object.id(), object.type()), 1, Integer::sum);
            }
        }
        return topPlacement(counts, 0, 0);
    }

    private static int packedUpperWallPlacement(List<ActiveRegionObject> objects) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (ActiveRegionObject object : objects) {
            if (object.type() == 0 || object.type() == 1 || object.type() == 2) {
                counts.merge(packPlacement(object.id(), object.type()), 1, Integer::sum);
            }
        }
        return topPlacement(counts, 0, 0);
    }

    private static int packedCornerPlacement(List<ActiveRegionObject> objects) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (ActiveRegionObject object : objects) {
            if (object.plane() == 0 && object.type() == 3) {
                counts.merge(packPlacement(object.id(), object.type()), 1, Integer::sum);
            }
        }
        return topPlacement(counts, 0, 0);
    }

    private static int packedDoorPlacement(List<ActiveRegionObject> objects, int wallPlacement, int cornerPlacement) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (ActiveRegionObject object : objects) {
            if (object.plane() != 0) {
                continue;
            }
            int packed = packPlacement(object.id(), object.type());
            if (containsAny(object.name(), "door", "curtain")) {
                counts.merge(packed, 3, Integer::sum);
                continue;
            }
            if ((object.type() == 0 || object.type() == 1 || object.type() == 2)
                    && packed != wallPlacement
                    && packed != cornerPlacement) {
                counts.merge(packed, 1, Integer::sum);
            }
        }
        return topPlacement(counts, 0, 0);
    }

    private static int packedWallDecorPlacement(List<ActiveRegionObject> objects) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (ActiveRegionObject object : objects) {
            if (object.plane() == 0 && object.type() == 4) {
                counts.merge(packPlacement(object.id(), object.type()), 1, Integer::sum);
            }
        }
        return topPlacement(counts, 0, 0);
    }

    private static CacheBiomeProfileMiner.SettlementRoofTemplate buildRoofTemplate(
            Chunk chunk,
            SettlementBuildingSample building
    ) {
        List<ActiveRegionObject> roofObjects = building.roofObjects();
        int roofPlane = Math.max(1, building.roofPlane());
        List<CacheBiomeProfileMiner.SettlementRoofObject> templateObjects = new ArrayList<>();
        if (roofObjects != null) {
            for (ActiveRegionObject object : roofObjects) {
                if (belongsToRoofTemplate(building, object)) {
                    templateObjects.add(new CacheBiomeProfileMiner.SettlementRoofObject(
                            object.x() - building.minX(),
                            object.y() - building.minY(),
                            object.id(),
                            object.type(),
                            object.orientation()
                    ));
                }
            }
        }

        List<CacheBiomeProfileMiner.SettlementRoofTile> templateTiles = new ArrayList<>();
        for (int localX = building.minX(); localX <= building.maxX(); localX++) {
            for (int localY = building.minY(); localY <= building.maxY(); localY++) {
                TileLookup lookup = resolveTileLookup(chunk, localX, localY);
                if (lookup == null || !isValidPlaneLookup(chunk, roofPlane, lookup)) {
                    continue;
                }
                int underlayId = chunk.mapRegion.underlays[roofPlane][lookup.x()][lookup.y()] & 0xffff;
                int overlayId = chunk.mapRegion.overlays[roofPlane][lookup.x()][lookup.y()] & 0xffff;
                int shape = chunk.mapRegion.overlayShapes[roofPlane][lookup.x()][lookup.y()] & 0xff;
                int orientation = chunk.mapRegion.overlayOrientations[roofPlane][lookup.x()][lookup.y()] & 0xff;
                int tileFlags = chunk.mapRegion.tileFlags[roofPlane][lookup.x()][lookup.y()] & 0xff;
                if (underlayId == 0 && overlayId == 0 && tileFlags == 0) {
                    continue;
                }
                templateTiles.add(new CacheBiomeProfileMiner.SettlementRoofTile(
                        localX - building.minX(),
                        localY - building.minY(),
                        underlayId,
                        overlayId,
                        shape,
                        orientation,
                        tileFlags
                ));
            }
        }

        if (templateObjects.isEmpty() && templateTiles.isEmpty()) {
            return null;
        }
        return new CacheBiomeProfileMiner.SettlementRoofTemplate(
                building.width(),
                building.height(),
                Math.max(1, building.floors()),
                templateObjects,
                templateTiles
        );
    }

    private static CacheBiomeProfileMiner.SettlementBuildingTemplate buildBuildingTemplate(
            Chunk chunk,
            SettlementBuildingSample building
    ) {
        int topPlane = Math.max(1, building.floors());
        List<CacheBiomeProfileMiner.SettlementTemplateObject> templateObjects = new ArrayList<>();
        for (ActiveRegionObject object : allTemplateObjects(building)) {
            if (belongsToBuildingTemplate(building, object, topPlane)) {
                templateObjects.add(new CacheBiomeProfileMiner.SettlementTemplateObject(
                        object.plane(),
                        object.x() - building.minX(),
                        object.y() - building.minY(),
                        object.id(),
                        object.type(),
                        object.orientation()
                ));
            }
        }

        List<CacheBiomeProfileMiner.SettlementTemplateTile> templateTiles = new ArrayList<>();
        for (int plane = 0; plane <= topPlane && plane < 4; plane++) {
            for (int localX = building.minX(); localX <= building.maxX(); localX++) {
                for (int localY = building.minY(); localY <= building.maxY(); localY++) {
                    TileLookup lookup = resolveTileLookup(chunk, localX, localY);
                    if (lookup == null || !isValidPlaneLookup(chunk, plane, lookup)) {
                        continue;
                    }
                    int underlayId = chunk.mapRegion.underlays[plane][lookup.x()][lookup.y()] & 0xffff;
                    int overlayId = chunk.mapRegion.overlays[plane][lookup.x()][lookup.y()] & 0xffff;
                    int shape = chunk.mapRegion.overlayShapes[plane][lookup.x()][lookup.y()] & 0xff;
                    int orientation = chunk.mapRegion.overlayOrientations[plane][lookup.x()][lookup.y()] & 0xff;
                    int tileFlags = chunk.mapRegion.tileFlags[plane][lookup.x()][lookup.y()] & 0xff;
                    if (underlayId == 0 && overlayId == 0 && tileFlags == 0) {
                        continue;
                    }
                    templateTiles.add(new CacheBiomeProfileMiner.SettlementTemplateTile(
                            plane,
                            localX - building.minX(),
                            localY - building.minY(),
                            underlayId,
                            overlayId,
                            shape,
                            orientation,
                            tileFlags
                    ));
                }
            }
        }

        if (templateObjects.isEmpty() && templateTiles.isEmpty()) {
            return null;
        }
        return new CacheBiomeProfileMiner.SettlementBuildingTemplate(
                building.width(),
                building.height(),
                topPlane,
                1.0,
                templateObjects,
                templateTiles
        );
    }

    private static int boundaryCompletenessScore(
            List<TilePoint> componentTiles,
            int minX,
            int minY,
            int maxX,
            int maxY
    ) {
        if (componentTiles == null || componentTiles.isEmpty()) {
            return 0;
        }
        Set<Integer> north = new HashSet<>();
        Set<Integer> south = new HashSet<>();
        Set<Integer> west = new HashSet<>();
        Set<Integer> east = new HashSet<>();
        int cornerCount = 0;
        for (TilePoint tile : componentTiles) {
            if (tile.y() == minY) {
                north.add(tile.x());
            }
            if (tile.y() == maxY) {
                south.add(tile.x());
            }
            if (tile.x() == minX) {
                west.add(tile.y());
            }
            if (tile.x() == maxX) {
                east.add(tile.y());
            }
            boolean corner = (tile.x() == minX || tile.x() == maxX) && (tile.y() == minY || tile.y() == maxY);
            if (corner) {
                cornerCount++;
            }
        }

        int width = maxX - minX + 1;
        int height = maxY - minY + 1;
        int horizontalThreshold = Math.max(2, Math.min(4, width / 3));
        int verticalThreshold = Math.max(2, Math.min(4, height / 3));
        int strongSides = 0;
        strongSides += north.size() >= horizontalThreshold ? 1 : 0;
        strongSides += south.size() >= horizontalThreshold ? 1 : 0;
        strongSides += west.size() >= verticalThreshold ? 1 : 0;
        strongSides += east.size() >= verticalThreshold ? 1 : 0;

        int uniqueBoundaryTiles = north.size() + south.size() + west.size() + east.size() - cornerCount;
        int perimeter = Math.max(1, 2 * (width + height) - 4);
        double boundaryRatio = uniqueBoundaryTiles / (double) perimeter;
        if (strongSides < 2) {
            return 0;
        }
        if (strongSides == 2 && cornerCount < 2) {
            return 0;
        }
        if (boundaryRatio < 0.24) {
            return 0;
        }
        return strongSides * 100 + cornerCount * 8 + (int) Math.round(boundaryRatio * 100.0);
    }

    private static int templateCompletenessScore(CacheBiomeProfileMiner.SettlementBuildingTemplate template) {
        if (template == null || template.objects == null || template.objects.isEmpty()) {
            return 0;
        }
        Set<Integer> north = new HashSet<>();
        Set<Integer> south = new HashSet<>();
        Set<Integer> west = new HashSet<>();
        Set<Integer> east = new HashSet<>();
        int cornerCount = 0;
        int minX = 0;
        int minY = 0;
        int maxX = Math.max(0, template.width - 1);
        int maxY = Math.max(0, template.height - 1);

        for (CacheBiomeProfileMiner.SettlementTemplateObject object : template.objects) {
            if (object == null || object.plane != 0 || object.type > 4) {
                continue;
            }
            if (object.x == minX) {
                west.add(object.y);
            }
            if (object.x == maxX) {
                east.add(object.y);
            }
            if (object.y == minY) {
                north.add(object.x);
            }
            if (object.y == maxY) {
                south.add(object.x);
            }
            boolean corner = (object.x == minX || object.x == maxX) && (object.y == minY || object.y == maxY);
            if (corner) {
                cornerCount++;
            }
        }

        int horizontalThreshold = Math.max(2, Math.min(4, Math.max(1, template.width / 3)));
        int verticalThreshold = Math.max(2, Math.min(4, Math.max(1, template.height / 3)));
        int strongSides = 0;
        strongSides += north.size() >= horizontalThreshold ? 1 : 0;
        strongSides += south.size() >= horizontalThreshold ? 1 : 0;
        strongSides += west.size() >= verticalThreshold ? 1 : 0;
        strongSides += east.size() >= verticalThreshold ? 1 : 0;

        int uniqueBoundaryTiles = north.size() + south.size() + west.size() + east.size() - cornerCount;
        int perimeter = Math.max(1, 2 * (template.width + template.height) - 4);
        double boundaryRatio = uniqueBoundaryTiles / (double) perimeter;
        if (strongSides < 2) {
            return 0;
        }
        if (strongSides == 2 && cornerCount < 2) {
            return 0;
        }
        if (boundaryRatio < 0.24) {
            return 0;
        }
        return strongSides * 100 + cornerCount * 8 + (int) Math.round(boundaryRatio * 100.0);
    }

    private static List<ActiveRegionObject> allTemplateObjects(SettlementBuildingSample building) {
        List<ActiveRegionObject> objects = new ArrayList<>();
        objects.addAll(building.groundObjects());
        objects.addAll(building.upperWallObjects());
        objects.addAll(building.roofObjects());
        return objects;
    }

    private static boolean belongsToRoofTemplate(SettlementBuildingSample building, ActiveRegionObject object) {
        if (object == null || object.plane() != Math.max(1, building.roofPlane())) {
            return false;
        }
        int relX = object.x() - building.minX();
        int relY = object.y() - building.minY();
        boolean inside = relX >= 0 && relX < building.width() && relY >= 0 && relY < building.height();
        if (!inside) {
            return false;
        }
        return object.type() <= 4 || isLikelyRoofObject(object) || object.type() == 9 || object.type() == 10 || object.type() == 11 || object.type() == 22;
    }

    private static boolean belongsToBuildingTemplate(SettlementBuildingSample building, ActiveRegionObject object, int topPlane) {
        if (object == null || object.plane() < 0 || object.plane() > topPlane) {
            return false;
        }
        int relX = object.x() - building.minX();
        int relY = object.y() - building.minY();
        boolean inside = relX >= 0 && relX < building.width() && relY >= 0 && relY < building.height();
        if (!inside) {
            return false;
        }
        return true;
    }

    private static RoofPlacements classifyRoofPlacements(CacheBiomeProfileMiner.SettlementRoofTemplate roofTemplate) {
        if (roofTemplate == null || roofTemplate.objects == null || roofTemplate.objects.isEmpty()) {
            return new RoofPlacements(Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        }
        Map<Integer, Integer> edgeCounts = new HashMap<>();
        Map<Integer, Integer> cornerCounts = new HashMap<>();
        Map<Integer, Integer> insetCounts = new HashMap<>();
        Map<Integer, Integer> flatCounts = new HashMap<>();
        Map<Integer, Integer> allCounts = new HashMap<>();
        int minX = 0;
        int minY = 0;
        int maxX = Math.max(0, roofTemplate.width - 1);
        int maxY = Math.max(0, roofTemplate.height - 1);
        for (CacheBiomeProfileMiner.SettlementRoofObject object : roofTemplate.objects) {
            int packed = packPlacement(object.id, object.type);
            allCounts.merge(packed, 1, Integer::sum);
            boolean outerCorner = (object.x == minX || object.x == maxX) && (object.y == minY || object.y == maxY);
            boolean insetCorner = (object.x == minX + 1 || object.x == maxX - 1)
                    && (object.y == minY + 1 || object.y == maxY - 1)
                    && maxX - minX >= 2
                    && maxY - minY >= 2;
            boolean edge = object.x == minX || object.x == maxX || object.y == minY || object.y == maxY;
            if (outerCorner) {
                cornerCounts.merge(packed, 1, Integer::sum);
            } else if (insetCorner) {
                insetCounts.merge(packed, 1, Integer::sum);
            } else if (edge) {
                edgeCounts.merge(packed, 1, Integer::sum);
            } else {
                flatCounts.merge(packed, 1, Integer::sum);
            }
        }
        return new RoofPlacements(edgeCounts, cornerCounts, insetCounts, flatCounts, allCounts);
    }

    private static void mergePlacementCounts(Map<Integer, Integer> target, Map<Integer, Integer> source) {
        if (source == null || source.isEmpty()) {
            return;
        }
        source.forEach((key, value) -> target.merge(key, value, Integer::sum));
    }

    private static List<WeightedFootprint> buildWeightedFootprints(Map<FootprintKey, Integer> footprintCounts) {
        int total = footprintCounts.values().stream().mapToInt(Integer::intValue).sum();
        if (total <= 0) {
            return List.of();
        }
        return footprintCounts.entrySet().stream()
                .sorted((left, right) -> {
                    int areaCompare = Integer.compare(right.getKey().area(), left.getKey().area());
                    if (areaCompare != 0) {
                        return areaCompare;
                    }
                    return Integer.compare(right.getValue(), left.getValue());
                })
                .map(entry -> new WeightedFootprint(
                        entry.getKey().width(),
                        entry.getKey().height(),
                        entry.getKey().floors(),
                        entry.getValue(),
                        entry.getValue() / (double) total
                ))
                .toList();
    }

    private static List<CacheBiomeProfileMiner.SettlementFootprint> anchorFootprints(List<WeightedFootprint> footprints) {
        if (footprints.isEmpty()) {
            return List.of(new CacheBiomeProfileMiner.SettlementFootprint(9, 9, 1.0, 1));
        }
        int count = Math.min(2, footprints.size());
        double total = footprints.subList(0, count).stream().mapToDouble(WeightedFootprint::weight).sum();
        List<CacheBiomeProfileMiner.SettlementFootprint> result = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            WeightedFootprint footprint = footprints.get(index);
            result.add(new CacheBiomeProfileMiner.SettlementFootprint(
                    footprint.width(),
                    footprint.height(),
                    footprint.weight() / Math.max(0.0001, total),
                    footprint.floors()
            ));
        }
        return result;
    }

    private static List<CacheBiomeProfileMiner.SettlementFootprint> satelliteFootprints(List<WeightedFootprint> footprints) {
        if (footprints.isEmpty()) {
            return List.of(new CacheBiomeProfileMiner.SettlementFootprint(7, 6, 1.0, 1));
        }
        List<WeightedFootprint> source = footprints.size() > 1 ? footprints.subList(1, footprints.size()) : footprints;
        double total = source.stream().mapToDouble(WeightedFootprint::weight).sum();
        List<CacheBiomeProfileMiner.SettlementFootprint> result = new ArrayList<>();
        for (WeightedFootprint footprint : source) {
            result.add(new CacheBiomeProfileMiner.SettlementFootprint(
                    footprint.width(),
                    footprint.height(),
                    footprint.weight() / Math.max(0.0001, total),
                    footprint.floors()
            ));
        }
        return result;
    }

    private static List<CacheBiomeProfileMiner.SettlementBuildingTemplate> buildWeightedBuildingTemplates(
            List<WeightedFootprint> footprints,
            Map<FootprintKey, BuildingTemplateCandidate> templateCandidates
    ) {
        if (footprints.isEmpty() || templateCandidates == null || templateCandidates.isEmpty()) {
            return List.of();
        }
        Map<FootprintKey, Double> weightByKey = new HashMap<>();
        for (WeightedFootprint footprint : footprints) {
            weightByKey.put(new FootprintKey(footprint.width(), footprint.height(), footprint.floors()), footprint.weight());
        }
        List<CacheBiomeProfileMiner.SettlementBuildingTemplate> templates = new ArrayList<>();
        for (Map.Entry<FootprintKey, BuildingTemplateCandidate> entry : templateCandidates.entrySet()) {
            CacheBiomeProfileMiner.SettlementBuildingTemplate template = entry.getValue().template();
            templates.add(new CacheBiomeProfileMiner.SettlementBuildingTemplate(
                    template.width,
                    template.height,
                    template.floors,
                    weightByKey.getOrDefault(entry.getKey(), 1.0 / Math.max(1, templateCandidates.size())),
                    template.objects,
                    template.tiles
            ));
        }
        templates.sort((left, right) -> Integer.compare((right.width * right.height), (left.width * left.height)));
        return templates;
    }

    private static List<CacheBiomeProfileMiner.SettlementProp> mineSettlementProps(
            Chunk chunk,
            List<ActiveRegionObject> objects,
            Bounds bounds
    ) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (ActiveRegionObject object : objects) {
            if (object.plane() != 0
                    || object.x() < Math.max(0, bounds.minX() - 4)
                    || object.x() > Math.min(63, bounds.maxX() + 4)
                    || object.y() < Math.max(0, bounds.minY() - 4)
                    || object.y() > Math.min(63, bounds.maxY() + 4)
                    || isLikelySettlementStructure(object)
                    || isLikelyRoofObject(object)
                    || isWaterTile(chunk, object.x(), object.y())) {
                continue;
            }
            if (!(object.type() == 10 || object.type() == 22 || object.type() == 4)) {
                continue;
            }
            counts.merge(packPlacement(object.id(), object.type()), 1, Integer::sum);
        }
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        if (total == 0) {
            return List.of();
        }
        return counts.entrySet().stream()
                .sorted((left, right) -> Integer.compare(right.getValue(), left.getValue()))
                .limit(10)
                .map(entry -> new CacheBiomeProfileMiner.SettlementProp(
                        unpackPlacementId(entry.getKey()),
                        unpackPlacementType(entry.getKey()),
                        roundTo(entry.getValue() / (double) total, 6)
                ))
                .toList();
    }

    private static int topPlacement(Map<Integer, Integer> counts, int fallbackId, int fallbackType) {
        if (counts == null || counts.isEmpty()) {
            return packPlacement(fallbackId, fallbackType);
        }
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(packPlacement(fallbackId, fallbackType));
    }

    private static int topInt(Map<Integer, Integer> counts, int fallback) {
        if (counts == null || counts.isEmpty()) {
            return fallback;
        }
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(fallback);
    }

    private static int secondInt(Map<Integer, Integer> counts, int primary) {
        if (counts == null || counts.isEmpty()) {
            return primary;
        }
        return counts.entrySet().stream()
                .filter(entry -> entry.getKey() != primary)
                .sorted((left, right) -> Integer.compare(right.getValue(), left.getValue()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(primary);
    }

    private static int topNonWaterOverlay(Map<Integer, Integer> counts) {
        if (counts == null || counts.isEmpty()) {
            return 0;
        }
        return counts.entrySet().stream()
                .filter(entry -> !WATER_OVERLAY_IDS.contains(entry.getKey()))
                .sorted((left, right) -> Integer.compare(right.getValue(), left.getValue()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(0);
    }

    private static int packPlacement(int id, int type) {
        return (id << 5) | (type & 0x1f);
    }

    private static int unpackPlacementId(int packed) {
        return packed >>> 5;
    }

    private static int unpackPlacementType(int packed) {
        return packed & 0x1f;
    }

    private static int tileKey(int x, int y) {
        return (x << 6) | y;
    }

    private static TileLookup resolveTileLookup(Chunk chunk, int localX, int localY) {
        if (chunk == null || chunk.mapRegion == null || chunk.mapRegion.overlays == null || chunk.mapRegion.overlays.length == 0) {
            return null;
        }
        int absoluteX = chunk.offsetX + localX;
        int absoluteY = chunk.offsetY + localY;
        if (absoluteX >= 0 && absoluteY >= 0
                && absoluteX < chunk.mapRegion.overlays[0].length
                && absoluteY < chunk.mapRegion.overlays[0][absoluteX].length) {
            return new TileLookup(absoluteX, absoluteY);
        }
        if (localX >= 0 && localY >= 0
                && localX < chunk.mapRegion.overlays[0].length
                && localY < chunk.mapRegion.overlays[0][localX].length) {
            return new TileLookup(localX, localY);
        }
        return null;
    }

    private static boolean isValidPlaneLookup(Chunk chunk, int plane, TileLookup lookup) {
        return chunk != null
                && chunk.mapRegion != null
                && lookup != null
                && plane >= 0
                && plane < chunk.mapRegion.overlays.length
                && lookup.x() >= 0
                && lookup.y() >= 0
                && lookup.x() < chunk.mapRegion.overlays[plane].length
                && lookup.y() < chunk.mapRegion.overlays[plane][lookup.x()].length;
    }

    private static void refreshPreview(
            ImageView previewView,
            Label previewPlaceholder,
            CacheBiomeProfileMiner.BiomeProfile profile,
            int width,
            int length,
            String rawSeed,
            int originRegionX,
            int originRegionY
    ) {
        if (profile == null) {
            previewView.setImage(null);
            previewPlaceholder.setVisible(true);
            previewPlaceholder.setText("Load and select a biome profile to preview.");
            return;
        }

        try {
            PreviewImageSize previewImageSize = previewImageSize(width, length, 300);
            previewView.setImage(BiomeProfilePreviewGenerator.renderThumbnail(
                    profile,
                    width,
                    length,
                    previewSeed(rawSeed),
                    previewImageSize.width(),
                    previewImageSize.height(),
                    originRegionX,
                    originRegionY
            ));
            previewPlaceholder.setVisible(false);
        } catch (Exception settlementFailure) {
            settlementFailure.printStackTrace();
            try {
                PreviewImageSize previewImageSize = previewImageSize(width, length, 300);
                previewView.setImage(BiomeProfilePreviewGenerator.renderThumbnail(
                        profile,
                        width,
                        length,
                        previewSeed(rawSeed),
                        previewImageSize.width(),
                        previewImageSize.height(),
                        originRegionX,
                        originRegionY,
                        false
                ));
                previewPlaceholder.setVisible(false);
            } catch (Exception fallbackFailure) {
                fallbackFailure.addSuppressed(settlementFailure);
                fallbackFailure.printStackTrace();
                previewView.setImage(null);
                previewPlaceholder.setVisible(true);
                previewPlaceholder.setText("Preview failed for this biome profile.");
            }
        }
    }

    private static String describeProfile(CacheBiomeProfileMiner.BiomeProfile profile) {
        StringBuilder builder = new StringBuilder();
        builder.append("Profile: ").append(profile.label).append('\n');
        builder.append("Profile ID: ").append(profile.profileId).append('\n');
        builder.append("Region Count: ").append(profile.regionCount).append('\n');
        if (profile.exampleRegions != null && !profile.exampleRegions.isEmpty()) {
            builder.append("Example Regions: ")
                    .append(profile.exampleRegions.stream().map(region -> Integer.toString(region.hash)).toList())
                    .append('\n');
        }

        if (profile.metrics != null) {
            builder.append('\n').append("Metrics").append('\n');
            builder.append("waterRatio=").append(DECIMAL.format(profile.metrics.waterRatio)).append('\n');
            builder.append("roadRatio=").append(DECIMAL.format(profile.metrics.roadRatio)).append('\n');
            builder.append("textureRatio=").append(DECIMAL.format(profile.metrics.textureRatio)).append('\n');
            builder.append("heightStdDev=").append(DECIMAL.format(profile.metrics.heightStdDev)).append('\n');
            builder.append("meanSlope=").append(DECIMAL.format(profile.metrics.meanSlope)).append('\n');
            builder.append("namedObjectDensity=").append(DECIMAL.format(profile.metrics.namedObjectDensity)).append('\n');
            builder.append("underlayRgb=").append(rgb(profile.metrics.underlayRed, profile.metrics.underlayGreen, profile.metrics.underlayBlue)).append('\n');
            builder.append("overlayRgb=").append(rgb(profile.metrics.overlayRed, profile.metrics.overlayGreen, profile.metrics.overlayBlue)).append('\n');
        }

        builder.append('\n').append("Top Underlays").append('\n');
        appendMaterials(builder, profile.topUnderlays);

        builder.append('\n').append("Top Overlays").append('\n');
        appendMaterials(builder, profile.topOverlays);

        builder.append('\n').append("Top Objects").append('\n');
        if (profile.topObjects == null || profile.topObjects.isEmpty()) {
            builder.append("(none)\n");
        } else {
            profile.topObjects.forEach(object -> builder
                    .append(object.id)
                    .append(" ")
                    .append(object.name == null ? "" : object.name)
                    .append(" share=")
                    .append(DECIMAL.format(object.share))
                    .append('\n'));
        }

        builder.append('\n').append("Top Tokens").append('\n');
        if (profile.topTokens == null || profile.topTokens.isEmpty()) {
            builder.append("(none)\n");
        } else {
            profile.topTokens.forEach(token -> builder
                    .append(token.token)
                    .append(" share=")
                    .append(DECIMAL.format(token.share))
                    .append('\n'));
        }

        appendObjectPlacement(builder, profile.objectPlacement);
        appendSettlementProfile(builder, CacheBiomeProfileMiner.resolveSettlementProfile(profile));

        return builder.toString();
    }

    private static void appendSettlementProfile(StringBuilder builder, CacheBiomeProfileMiner.SettlementProfile settlement) {
        if (settlement == null) {
            return;
        }
        builder.append('\n').append("Settlement").append('\n');
        builder.append("id=").append(settlement.id).append('\n');
        builder.append("sourceRegionHash=").append(settlement.sourceRegionHash).append('\n');
        builder.append("layoutStyle=").append(settlement.layoutStyle).append('\n');
        builder.append("sampledBuildingCount=").append(settlement.sampledBuildingCount).append('\n');
        builder.append("wall=").append(settlement.wallId).append(':').append(settlement.wallType).append('\n');
        builder.append("upperWall=").append(settlement.upperWallId).append(':').append(settlement.upperWallType).append('\n');
        builder.append("corner=").append(settlement.cornerId).append(':').append(settlement.cornerType).append('\n');
        builder.append("door=").append(settlement.doorId).append(':').append(settlement.doorType).append('\n');
        builder.append("wallDecor=").append(settlement.wallDecorId).append(':').append(settlement.wallDecorType).append('\n');
        builder.append("roofEdge=").append(settlement.roofEdgeId).append(':').append(settlement.roofEdgeType).append('\n');
        builder.append("roofCorner=").append(settlement.roofCornerId).append(':').append(settlement.roofCornerType).append('\n');
        builder.append("roofInsetCorner=").append(settlement.roofInsetCornerId).append(':').append(settlement.roofInsetCornerType).append('\n');
        builder.append("roofFlat=").append(settlement.roofFlatId).append(':').append(settlement.roofFlatType).append('\n');
        builder.append("roofTemplates=")
                .append(settlement.roofTemplates == null ? List.of() : settlement.roofTemplates.stream()
                        .map(template -> template.width + "x" + template.height + "f" + template.floors
                                + " objs=" + (template.objects == null ? 0 : template.objects.size())
                                + " tiles=" + (template.tiles == null ? 0 : template.tiles.size()))
                        .toList())
                .append('\n');
        builder.append("buildingTemplates=")
                .append(settlement.buildingTemplates == null ? List.of() : settlement.buildingTemplates.stream()
                        .map(template -> template.width + "x" + template.height + "f" + template.floors
                                + " w=" + DECIMAL.format(template.weight)
                                + " objs=" + (template.objects == null ? 0 : template.objects.size())
                                + " tiles=" + (template.tiles == null ? 0 : template.tiles.size()))
                        .toList())
                .append('\n');
        builder.append("anchorFootprints=")
                .append(settlement.anchorFootprints == null ? List.of() : settlement.anchorFootprints.stream().map(fp -> fp.width + "x" + fp.height + "@" + DECIMAL.format(fp.weight)).toList())
                .append('\n');
        builder.append("satelliteFootprints=")
                .append(settlement.satelliteFootprints == null ? List.of() : settlement.satelliteFootprints.stream().map(fp -> fp.width + "x" + fp.height + "@" + DECIMAL.format(fp.weight)).toList())
                .append('\n');
    }

    private static void appendObjectPlacement(StringBuilder builder, CacheBiomeProfileMiner.ObjectPlacementProfile placement) {
        if (placement == null) {
            return;
        }
        builder.append('\n').append("Object Placement").append('\n');
        builder.append("landTileCount=").append(placement.landTileCount).append('\n');
        builder.append("waterTileCount=").append(placement.waterTileCount).append('\n');
        builder.append("totalNamedObjectDensity=").append(DECIMAL.format(placement.totalNamedObjectDensity)).append('\n');
        appendCategoryPlacement(builder, "Trees", placement.trees);
        appendCategoryPlacement(builder, "Shrubs", placement.shrubs);
        appendCategoryPlacement(builder, "Decor", placement.decor);
    }

    private static void appendCategoryPlacement(
            StringBuilder builder,
            String label,
            CacheBiomeProfileMiner.ObjectCategoryPlacement placement
    ) {
        builder.append('\n').append(label).append('\n');
        if (placement == null || placement.objectCount <= 0) {
            builder.append("(none)\n");
            return;
        }
        builder.append("layoutType=").append(placement.layoutType).append('\n');
        builder.append("tileDensity=").append(DECIMAL.format(placement.tileDensity)).append('\n');
        builder.append("objectDensity=").append(DECIMAL.format(placement.objectDensity)).append('\n');
        builder.append("adjacencyRatio=").append(DECIMAL.format(placement.adjacencyRatio)).append('\n');
        builder.append("meanNearestNeighborDistance=").append(DECIMAL.format(placement.meanNearestNeighborDistance)).append('\n');
        builder.append("occupiedTileCount=").append(placement.occupiedTileCount).append('\n');
        builder.append("objectCount=").append(placement.objectCount).append('\n');
        if (placement.objects == null || placement.objects.isEmpty()) {
            builder.append("(objects none)\n");
            return;
        }
        placement.objects.forEach(object -> builder
                .append(object.id)
                .append(" ")
                .append(object.name == null ? "" : object.name)
                .append(" objectShare=")
                .append(DECIMAL.format(object.objectShare))
                .append(" tileDensity=")
                .append(DECIMAL.format(object.tileDensity))
                .append(" type=")
                .append(object.dominantType)
                .append(" orientation=")
                .append(object.dominantOrientation)
                .append('\n'));
    }

    private static void appendMaterials(StringBuilder builder, java.util.List<CacheBiomeProfileMiner.RankedMaterial> materials) {
        if (materials == null || materials.isEmpty()) {
            builder.append("(none)\n");
            return;
        }
        materials.forEach(material -> builder
                .append(material.id)
                .append(" share=")
                .append(DECIMAL.format(material.share))
                .append('\n'));
    }

    private static String rgb(double red, double green, double blue) {
        return (int) Math.round(red) + "," + (int) Math.round(green) + "," + (int) Math.round(blue);
    }

    private static Path defaultReportPath() {
        Path directory = Paths.get(System.getProperty("user.home"), ".rspsi", "biome-profiles");
        if (!Files.isDirectory(directory)) {
            return directory.resolve("biome-profiles.json");
        }
        try {
            return Files.list(directory)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .max(Comparator.comparingLong(path -> path.toFile().lastModified()))
                    .orElse(directory.resolve("biome-profiles.json"));
        } catch (Exception ignored) {
            return directory.resolve("biome-profiles.json");
        }
    }

    private static Spinner<Integer> spinner(int min, int max, int value) {
        Spinner<Integer> spinner = new Spinner<>();
        spinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(min, max, value));
        spinner.setEditable(true);
        return spinner;
    }

    private static int centeredOriginRegion(int span) {
        return -Math.floorDiv(Math.max(1, span), 2);
    }

    private static int recenterOrigin(int currentOrigin, Integer oldSpan, Integer newSpan) {
        int safeOldSpan = Math.max(1, oldSpan == null ? 1 : oldSpan);
        int safeNewSpan = Math.max(1, newSpan == null ? 1 : newSpan);
        int centre = currentOrigin + Math.floorDiv(safeOldSpan, 2);
        return centre - Math.floorDiv(safeNewSpan, 2);
    }

    private static String formatPreviewOffset(int originRegionX, int originRegionY) {
        return "Offset: " + originRegionX + ", " + originRegionY;
    }

    private static PreviewImageSize previewImageSize(int widthRegions, int heightRegions, int maxPixels) {
        int safeWidthRegions = Math.max(1, widthRegions);
        int safeHeightRegions = Math.max(1, heightRegions);
        double scale = Math.min(
                maxPixels / (double) safeWidthRegions,
                maxPixels / (double) safeHeightRegions
        );
        int imageWidth = Math.max(64, (int) Math.round(safeWidthRegions * scale));
        int imageHeight = Math.max(64, (int) Math.round(safeHeightRegions * scale));
        return new PreviewImageSize(imageWidth, imageHeight);
    }

    private static long parseSeed(String rawValue) {
        try {
            return Long.parseLong(rawValue.trim());
        } catch (Exception ignored) {
            return randomSeed();
        }
    }

    private static long previewSeed(String rawValue) {
        try {
            return Long.parseLong(rawValue.trim());
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static long randomSeed() {
        long value = ThreadLocalRandom.current().nextLong();
        return value == Long.MIN_VALUE ? 0L : Math.abs(value);
    }

    private static int countLandTiles(Chunk chunk) {
        if (chunk == null || chunk.mapRegion == null) {
            return 4096;
        }
        int landTiles = 0;
        for (int localX = 0; localX < 64; localX++) {
            for (int localY = 0; localY < 64; localY++) {
                if (!isWaterTile(chunk, localX, localY)) {
                    landTiles++;
                }
            }
        }
        return Math.max(1, landTiles);
    }

    private static boolean isWaterTile(Chunk chunk, int localX, int localY) {
        if (chunk == null || chunk.mapRegion == null || chunk.mapRegion.overlays == null || chunk.mapRegion.underlays == null) {
            return false;
        }
        int x = chunk.offsetX + localX;
        int y = chunk.offsetY + localY;
        if (x < 0 || y < 0 || x >= chunk.mapRegion.overlays[0].length || y >= chunk.mapRegion.overlays[0][x].length) {
            return false;
        }
        int overlayId = chunk.mapRegion.overlays[0][x][y];
        int underlayId = chunk.mapRegion.underlays[0][x][y];
        return WATER_OVERLAY_IDS.contains(overlayId) || WATER_UNDERLAY_IDS.contains(underlayId);
    }

    private static CategoryAccumulator selectCategoryAccumulator(
            String name,
            int type,
            CategoryAccumulator trees,
            CategoryAccumulator shrubs,
            CategoryAccumulator decor
    ) {
        if (name.isBlank() || isStructuralObjectName(name)) {
            return null;
        }
        if (isTreeObjectName(name)) {
            return trees;
        }
        if (isShrubObjectName(name)) {
            return shrubs;
        }
        if (type == 22 || isGroundDecorObjectName(name)) {
            return decor;
        }
        return null;
    }

    private static boolean containsAny(String value, String... tokens) {
        for (String token : tokens) {
            if (value.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isStructuralObjectName(String name) {
        return containsAny(name,
                "wall", "door", "gate", "roof", "fence", "window", "building", "house",
                "table", "chair", "stool", "bed", "crate", "barrel", "chest", "ladder",
                "bookcase", "bookshelf", "bench", "sign", "stall", "counter");
    }

    private static boolean isTreeObjectName(String name) {
        return containsAny(name, "tree", "oak", "willow", "maple", "evergreen", "yew", "dead tree", "cactus", "palm", "jungle");
    }

    private static boolean isShrubObjectName(String name) {
        return containsAny(name, "bush", "shrub", "fern", "plant", "branch", "log", "stump", "root", "roots", "vine", "ivy", "reed");
    }

    private static boolean isGroundDecorObjectName(String name) {
        return containsAny(name, "flower", "flowers", "grass", "reed", "mushroom", "weed", "herb", "rock", "rocks", "stones", "boulder");
    }

    private static double roundTo(double value, int decimals) {
        double scale = Math.pow(10, decimals);
        return Math.round(value * scale) / scale;
    }

    private record ActiveRegionObjectImport(
            List<CacheBiomeProfileMiner.RankedObject> topObjects,
            List<CacheBiomeProfileMiner.RankedToken> topTokens,
            double namedObjectDensity,
            CacheBiomeProfileMiner.ObjectPlacementProfile objectPlacement
    ) {
    }

    private record ActiveRegionSettlementImport(
            CacheBiomeProfileMiner.SettlementProfile profile
    ) {
    }

    private record ActiveRegionObject(
            int id,
            int type,
            int orientation,
            int x,
            int y,
            int plane,
            String name
    ) {
    }

    private record SettlementBuildingSample(
            int minX,
            int minY,
            int maxX,
            int maxY,
            int floors,
            int roofPlane,
            List<ActiveRegionObject> groundObjects,
            List<ActiveRegionObject> upperWallObjects,
            List<ActiveRegionObject> roofObjects,
            int padHeightRange,
            double waterDistance
    ) {
        int width() {
            return maxX - minX + 1;
        }

        int height() {
            return maxY - minY + 1;
        }

        int area() {
            return width() * height();
        }
    }

    private record Bounds(int minX, int minY, int maxX, int maxY) {
    }

    private record TerrainPalette(
            int primaryUnderlayId,
            int secondaryUnderlayId,
            int accentOverlayId,
            int waterOverlayId
    ) {
    }

    private record FootprintKey(int width, int height, int floors) {
        int area() {
            return width * height;
        }
    }

    private record WeightedFootprint(
            int width,
            int height,
            int floors,
            int count,
            double weight
    ) {
    }

    private record RoofPlacements(
            Map<Integer, Integer> edgeCounts,
            Map<Integer, Integer> cornerCounts,
            Map<Integer, Integer> insetCornerCounts,
            Map<Integer, Integer> flatCounts,
            Map<Integer, Integer> allCounts
    ) {
    }

    private record RoofTemplateCandidate(
            CacheBiomeProfileMiner.SettlementRoofTemplate template,
            int score
    ) {
    }

    private record BuildingTemplateCandidate(
            CacheBiomeProfileMiner.SettlementBuildingTemplate template,
            int score
    ) {
    }

    private record TileLookup(int x, int y) {
    }

    private static final class CategoryAccumulator {
        private final Map<Integer, Integer> objectCounts = new HashMap<>();
        private final Map<Integer, String> objectNames = new HashMap<>();
        private final Map<Integer, Set<Integer>> tileSetById = new HashMap<>();
        private final Map<String, Integer> spawnStats = new HashMap<>();
        private final Set<Integer> occupiedTiles = new HashSet<>();
        private final List<TilePoint> occupiedPoints = new ArrayList<>();

        private void record(int id, String name, int type, int orientation, int x, int y) {
            int tileKey = (x << 6) | y;
            objectCounts.merge(id, 1, Integer::sum);
            objectNames.putIfAbsent(id, name);
            tileSetById.computeIfAbsent(id, ignored -> new HashSet<>()).add(tileKey);
            spawnStats.merge(id + ":" + type + ":" + (orientation & 3), 1, Integer::sum);
            if (occupiedTiles.add(tileKey)) {
                occupiedPoints.add(new TilePoint(x, y));
            }
        }

        private CacheBiomeProfileMiner.ObjectCategoryPlacement toPlacementProfile(int landTileCount) {
            int safeLandTileCount = Math.max(1, landTileCount);
            int totalObjectCount = objectCounts.values().stream().mapToInt(Integer::intValue).sum();
            int occupiedTileCount = occupiedTiles.size();
            double tileDensity = roundTo(occupiedTileCount / (double) safeLandTileCount, 6);
            double objectDensity = roundTo(totalObjectCount / (double) safeLandTileCount, 6);
            double adjacencyRatio = roundTo(computeAdjacencyRatio(), 6);
            double meanNearestNeighborDistance = roundTo(computeMeanNearestNeighborDistance(), 3);
            String layoutType = classifyLayoutType(tileDensity, adjacencyRatio, meanNearestNeighborDistance, occupiedTileCount);
            List<CacheBiomeProfileMiner.ObjectPlacementEntry> objects = buildPlacementEntries(safeLandTileCount, totalObjectCount);
            return new CacheBiomeProfileMiner.ObjectCategoryPlacement(
                    totalObjectCount,
                    occupiedTileCount,
                    tileDensity,
                    objectDensity,
                    adjacencyRatio,
                    meanNearestNeighborDistance,
                    layoutType,
                    objects
            );
        }

        private List<CacheBiomeProfileMiner.ObjectPlacementEntry> buildPlacementEntries(int landTileCount, int totalObjectCount) {
            if (totalObjectCount <= 0) {
                return List.of();
            }
            return objectCounts.entrySet().stream()
                    .sorted((left, right) -> Integer.compare(right.getValue(), left.getValue()))
                    .map(entry -> {
                        int id = entry.getKey();
                        int count = entry.getValue();
                        int tileCount = tileSetById.getOrDefault(id, Collections.emptySet()).size();
                        DominantSpawn dominantSpawn = dominantSpawnFor(id);
                        return new CacheBiomeProfileMiner.ObjectPlacementEntry(
                                id,
                                objectNames.getOrDefault(id, ""),
                                count,
                                tileCount,
                                roundTo(count / (double) totalObjectCount, 6),
                                roundTo(tileCount / (double) landTileCount, 6),
                                dominantSpawn.type(),
                                dominantSpawn.orientation()
                        );
                    })
                    .toList();
        }

        private DominantSpawn dominantSpawnFor(int objectId) {
            DominantSpawn best = null;
            for (Map.Entry<String, Integer> entry : spawnStats.entrySet()) {
                String[] parts = entry.getKey().split(":");
                if (parts.length != 3 || Integer.parseInt(parts[0]) != objectId) {
                    continue;
                }
                DominantSpawn candidate = new DominantSpawn(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]) & 3, entry.getValue());
                if (best == null || candidate.count() > best.count()) {
                    best = candidate;
                }
            }
            return best == null ? new DominantSpawn(10, 0, 0) : best;
        }

        private double computeAdjacencyRatio() {
            if (occupiedTiles.isEmpty()) {
                return 0.0;
            }
            int adjacentTiles = 0;
            for (int tileKey : occupiedTiles) {
                int x = tileKey >> 6;
                int y = tileKey & 0x3f;
                if (hasNeighbor(x, y)) {
                    adjacentTiles++;
                }
            }
            return adjacentTiles / (double) occupiedTiles.size();
        }

        private boolean hasNeighbor(int x, int y) {
            for (int offsetX = -1; offsetX <= 1; offsetX++) {
                for (int offsetY = -1; offsetY <= 1; offsetY++) {
                    if (offsetX == 0 && offsetY == 0) {
                        continue;
                    }
                    int nextX = x + offsetX;
                    int nextY = y + offsetY;
                    if (nextX < 0 || nextX >= 64 || nextY < 0 || nextY >= 64) {
                        continue;
                    }
                    if (occupiedTiles.contains((nextX << 6) | nextY)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private double computeMeanNearestNeighborDistance() {
            if (occupiedPoints.size() <= 1) {
                return 99.0;
            }
            double total = 0.0;
            for (int index = 0; index < occupiedPoints.size(); index++) {
                TilePoint point = occupiedPoints.get(index);
                double bestDistance = Double.MAX_VALUE;
                for (int otherIndex = 0; otherIndex < occupiedPoints.size(); otherIndex++) {
                    if (index == otherIndex) {
                        continue;
                    }
                    TilePoint other = occupiedPoints.get(otherIndex);
                    double deltaX = point.x() - other.x();
                    double deltaY = point.y() - other.y();
                    double distance = Math.sqrt(deltaX * deltaX + deltaY * deltaY);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                    }
                }
                total += bestDistance == Double.MAX_VALUE ? 0.0 : bestDistance;
            }
            return total / occupiedPoints.size();
        }

        private String classifyLayoutType(double tileDensity, double adjacencyRatio, double meanNearestNeighborDistance, int occupiedTileCount) {
            if (occupiedTileCount <= 1 || tileDensity <= 0.01) {
                return "sparse";
            }
            if (adjacencyRatio >= 0.48 || meanNearestNeighborDistance <= 2.25) {
                return "clustered";
            }
            if (adjacencyRatio <= 0.18 && meanNearestNeighborDistance >= 4.5) {
                return "scattered";
            }
            return "mixed";
        }
    }

    private record TilePoint(int x, int y) {
    }

    private record DominantSpawn(int type, int orientation, int count) {
    }

    private record PreviewImageSize(int width, int height) {
    }

    public record PreviewRequest(
            CacheBiomeProfileMiner.MiningReport report,
            CacheBiomeProfileMiner.BiomeProfile profile,
            int width,
            int length,
            long seed,
            int originRegionX,
            int originRegionY,
            String reportPath
    ) {
    }
}
