package org.lostcitymapeditor.ui;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.embed.swing.JFXPanel;
import javafx.embed.swing.SwingNode;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import org.lostcitymapeditor.Loaders.FileLoader;
import org.lostcitymapeditor.Loaders.MapDataLoader;
import org.lostcitymapeditor.Loaders.TextureLoader;
import org.lostcitymapeditor.OriginalCode.TileOverlay;
import org.lostcitymapeditor.Renderer.ModelViewer;
import org.lostcitymapeditor.Util.ModelViewerSelector;
import org.lostcitymapeditor.ipc.IpcProtocol;

import javax.swing.JFrame;
import java.util.*;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class ConfigUI {
    private static final int LEVELS = 4;

    private final String serverPath;
    private final EditorController controller;

    private final Map<String, Integer> underlayMap;
    private final Map<String, Object> overlayMap;
    private final Map<Integer, Image> shapeImages;
    private final Map<String, Object> npcMap;
    private final Map<String, Object> objMap;

    private Label currentMapLabel;
    private Label tilePositionLabel;
    private Label tileOverlayLabel;
    private Label tileUnderlayLabel;
    private Label tileHeightLabel;
    private Label tileFlagLabel;
    private Label tileShapeLabel;
    private Label tileRotationLabel;
    private Label tileTextureLabel;
    private Label locPositionLabel;
    private Label locDetailsLabel;
    private Label npcPositionLabel;
    private Label npcDetailsLabel;
    private Label objPositionLabel;
    private Label objDetailsLabel;

    private ModelViewer modelViewer;
    private ModelViewerSelector modelViewerSelector;
    private JFXPanel fxPanel;
    private JFrame frame;
    private CountDownLatch closeLatch;

    private String selectedLocName;
    private int selectedLocShape = 10;
    private int selectedLocRotation = 0;
    private int selectedNpcId = -1;
    private int selectedObjId = -1;
    private int selectedObjAmount = 1;

    private boolean displayLocs = true;
    private boolean displayNpcs = true;
    private boolean displayObjs = true;

    public ConfigUI(String serverPath, EditorController controller) {
        this.serverPath = serverPath;
        this.controller = controller;
        this.underlayMap = FileLoader.getUnderlayMap();
        this.overlayMap = FileLoader.getOverlayMap();
        this.shapeImages = FileLoader.getShapeImages();
        this.npcMap = FileLoader.getAllNpcMap();
        this.objMap = FileLoader.getAllObjMap();
    }

    public void setCloseLatch(CountDownLatch latch) {
        this.closeLatch = latch;
    }

    public void show() {
        modelViewer = new ModelViewer(300, 350);
        frame = new JFrame("LostCity Map Editor Config");
        fxPanel = new JFXPanel();
        frame.add(fxPanel);
        frame.setSize(1100, 900);
        frame.setAlwaysOnTop(true);
        frame.setVisible(true);
        frame.toFront();
        frame.requestFocus();
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                frame.dispose();
                if (closeLatch != null) {
                    closeLatch.countDown();
                }
                System.exit(0);
            }
        });
        javax.swing.Timer t = new javax.swing.Timer(500, e -> frame.setAlwaysOnTop(false));
        t.setRepeats(false);
        t.start();

        Platform.runLater(this::buildUI);
    }

    public JFrame getFrame() {
        return frame;
    }

    private void buildUI() {
        String currentMapFileName = "m50_50.jm2";

        ObservableList<String> mapFiles = MapDataLoader.getJM2Files(serverPath + "/maps/");
        ListView<String> mapListView = new ListView<>(mapFiles);
        mapListView.setPrefWidth(150);

        ObservableList<String> underlayNames = FXCollections.observableArrayList(underlayMap.keySet());
        underlayNames.add(0, "Original");
        underlayNames.add(1, "Clear");

        ObservableList<String> overlayNames = FXCollections.observableArrayList(overlayMap.keySet());
        overlayNames.add(0, "Original");
        overlayNames.add(1, "Clear");

        ListView<String> overlayListView = new ListView<>(overlayNames);
        overlayListView.setPrefHeight(50);
        overlayListView.setCellFactory(param -> new ListCell<>() {
            private final Rectangle rect = new Rectangle(20, 20);
            private final ImageView textureView = new ImageView();

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(item);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> overlayData = (Map<String, Object>) overlayMap.get(item);
                    if (overlayData != null) {
                        if (overlayData.containsKey("rgb")) {
                            Integer color = (Integer) overlayData.get("rgb");
                            if (color != null) {
                                rect.setFill(Color.rgb((color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF));
                                setGraphic(rect);
                            } else {
                                rect.setFill(Color.TRANSPARENT);
                                setGraphic(null);
                            }
                        } else if (overlayData.containsKey("texture")) {
                            String textureName = (String) overlayData.get("texture");
                            Image texture = TextureLoader.loadTextureImage(serverPath, textureName);
                            if (texture != null) {
                                textureView.setImage(texture);
                                textureView.setFitWidth(20);
                                textureView.setFitHeight(20);
                                setGraphic(textureView);
                            } else {
                                setGraphic(null);
                            }
                        } else {
                            setGraphic(null);
                        }
                    } else {
                        setGraphic(null);
                    }
                }
            }
        });

        overlayListView.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldValue, newValue) -> {
                    int id;
                    if (newValue == null || newValue.equals("Original")) {
                        id = 0;
                    } else if (newValue.equals("Clear")) {
                        id = -1;
                    } else {
                        Integer found = findOverlayOrUnderlayIdByName(newValue);
                        id = (found != null) ? found : 0;
                    }
                    controller.setOverlay(id);
                }
        );

        ObservableList<Integer> shapeValues = FXCollections.observableArrayList(
                IntStream.range(0, 12).boxed().collect(Collectors.toList()));
        ListView<Integer> shapeListView = new ListView<>(shapeValues);
        shapeListView.setPrefWidth(250);
        shapeListView.setPrefHeight(150);
        String[] shapeNames = TileOverlay.SHAPE_NAMES;

        shapeListView.setCellFactory(param -> new ListCell<>() {
            private final ImageView imageView = new ImageView();
            { imageView.setFitWidth(50); imageView.setFitHeight(50); }

            @Override
            protected void updateItem(Integer item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    Image image = shapeImages.get(item);
                    String shapeName = (item >= 0 && item < shapeNames.length) ? shapeNames[item] : "Unknown";
                    if (image != null) {
                        imageView.setImage(image);
                        setText(item + " - " + shapeName);
                        setGraphic(imageView);
                    } else {
                        setText(item + " - " + shapeName);
                        setGraphic(null);
                    }
                }
            }
        });

        shapeListView.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldValue, newValue) -> {
                    if (newValue != null) {
                        controller.setShape(newValue);
                    }
                });

        ObservableList<Integer> flagValues = FXCollections.observableArrayList(0, 1, 2, 4, 8, 16);
        ListView<Integer> flagListView = new ListView<>(flagValues);
        flagListView.setPrefWidth(250);
        flagListView.setPrefHeight(150);

        flagListView.setCellFactory(param -> new ListCell<>() {
            @Override
            protected void updateItem(Integer item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    String description = switch (item) {
                        case 0 -> "None";
                        case 1 -> "Unwalkable";
                        case 2 -> "Bridge";
                        case 4 -> "Remove roof";
                        case 8 -> "Render on level below";
                        case 16 -> "Don't draw on map";
                        default -> "";
                    };
                    setText(item + " - " + description);
                }
            }
        });

        flagListView.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldValue, newValue) -> {
                    if (newValue != null) {
                        controller.setFlag(newValue);
                    }
                });

        Label currentLevelLabel = new Label("Current Level:");
        ToggleGroup levelToggleGroup = new ToggleGroup();
        VBox levelRadioButtons = new VBox(5);
        for (int i = 0; i < LEVELS; i++) {
            RadioButton levelButton = new RadioButton("Level " + i);
            levelButton.setToggleGroup(levelToggleGroup);
            levelButton.setUserData(i);
            levelRadioButtons.getChildren().add(levelButton);

            int level = i;
            levelButton.setOnAction(e -> controller.setLevel(level));
        }
        ((RadioButton) levelRadioButtons.getChildren().get(0)).setSelected(true);

        Label displayLabel = new Label("Display:");
        CheckBox locCheckbox = new CheckBox("Locs");
        locCheckbox.setSelected(true);
        CheckBox npcCheckbox = new CheckBox("Npcs");
        npcCheckbox.setSelected(true);
        CheckBox objCheckbox = new CheckBox("Objs");
        objCheckbox.setSelected(true);

        locCheckbox.setOnAction(e -> {
            displayLocs = locCheckbox.isSelected();
            controller.setDisplay(displayLocs, displayNpcs, displayObjs);
        });
        npcCheckbox.setOnAction(e -> {
            displayNpcs = npcCheckbox.isSelected();
            controller.setDisplay(displayLocs, displayNpcs, displayObjs);
        });
        objCheckbox.setOnAction(e -> {
            displayObjs = objCheckbox.isSelected();
            controller.setDisplay(displayLocs, displayNpcs, displayObjs);
        });

        Button exportButton = new Button("Export Map");
        exportButton.setOnAction(e -> {
            javafx.stage.FileChooser fileChooser = new javafx.stage.FileChooser();
            fileChooser.setTitle("Save Map File");
            fileChooser.setInitialDirectory(new java.io.File(serverPath + "/maps/"));
            fileChooser.getExtensionFilters().add(
                    new javafx.stage.FileChooser.ExtensionFilter("JM2 Files", "*.jm2"));
            javafx.stage.Stage stage = new javafx.stage.Stage();
            java.io.File file = fileChooser.showSaveDialog(stage);
            if (file != null) {
                controller.exportMap(file.getAbsolutePath());
            }
        });

        // --- Loc inspector ---
        VBox locInspector = new VBox();
        locInspector.setPadding(new Insets(10));
        locInspector.setBorder(new Border(new BorderStroke(Color.BLACK, BorderStrokeStyle.SOLID, CornerRadii.EMPTY, BorderWidths.DEFAULT)));
        locInspector.setPrefWidth(250);

        Label newLocRotationLabel = new Label("New Loc Rotation");
        VBox locRotationBox = new VBox(5);
        ToggleGroup locRotationGroup = new ToggleGroup();

        Integer[] rotations = {0, 90, 180, 270};
        for (Integer rotation : rotations) {
            RadioButton rb = new RadioButton(rotation.toString());
            rb.setToggleGroup(locRotationGroup);
            rb.setUserData(rotation);
            locRotationBox.getChildren().add(rb);
        }

        locRotationGroup.selectedToggleProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null) {
                selectedLocRotation = (Integer) newValue.getUserData();
                sendLocUpdate();
            }
        });

        Label newLocShapeLabel = new Label("New Loc Shape");
        ObservableList<Integer> locShapeValues = FXCollections.observableArrayList();
        for (int i = 0; i <= 22; i++) {
            locShapeValues.add(i);
        }

        ListView<Integer> locShapeListView = new ListView<>(locShapeValues);
        locShapeListView.setPrefWidth(100);
        locShapeListView.setPrefHeight(100);

        String[] locShapeNames = new String[]{
                "Wall Straight", "Wall Diagonal Corner", "Wall L", "Wall Square Corner",
                "Wall Decor Straight No Offset", "Wall Decor Straight Offset", "Wall Decor Diagonal Offset",
                "Wall Decor Diagonal No Offset", "Wall Decor Diagonal Both", "Wall Diagonal",
                "Centrepiece Straight", "Centrepiece Diagonal", "Roof Straight",
                "Roof Diagonal With Roof Edge", "Roof Diagonal", "Roof L Concave", "Roof L Convex",
                "Roof Flat", "Roof Edge Straight", "Roof Edge Diagonal Corner", "Roof Edge L",
                "Roof Edge Square Corner", "Ground Decor"
        };

        locShapeListView.setCellFactory(param -> new ListCell<>() {
            @Override
            protected void updateItem(Integer item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    String shapeName = (item >= 0 && item < locShapeNames.length) ? locShapeNames[item] : "Unknown";
                    setText(item + " - " + shapeName);
                }
            }
        });

        locShapeListView.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldValue, newValue) -> {
                    if (newValue != null) {
                        selectedLocShape = newValue;
                        if (modelViewerSelector != null) {
                            modelViewerSelector.updateModel(selectedLocShape);
                        }
                        sendLocUpdate();
                    }
                });

        Label locInspectorTitle = new Label("--Locs--\nLeft Click to inspect\nL + click to update");
        locPositionLabel = new Label("Position: ");
        locDetailsLabel = new Label();
        Button clearLocsButton = new Button("Clear Tile Locs");
        clearLocsButton.setOnAction(e -> controller.clearLocs());

        locInspector.getChildren().addAll(locInspectorTitle, locPositionLabel, locDetailsLabel, clearLocsButton,
                newLocRotationLabel, locRotationBox, newLocShapeLabel, locShapeListView);

        // --- NPC inspector ---
        VBox npcInspector = new VBox();
        npcInspector.setPadding(new Insets(10));
        npcInspector.setBorder(new Border(new BorderStroke(Color.BLACK, BorderStrokeStyle.SOLID, CornerRadii.EMPTY, BorderWidths.DEFAULT)));
        npcInspector.setPrefWidth(250);

        Label npcInspectorTitle = new Label("--Npcs--\nLeft Click to inspect\nN + Click to update");
        npcPositionLabel = new Label("Position: ");
        npcDetailsLabel = new Label();
        Button clearNpcsButton = new Button("Clear Tile Npcs");
        clearNpcsButton.setOnAction(e -> controller.clearNpcs());

        Set<String> npcNameSet = npcMap.keySet();
        List<String> sortedNpcNames = new ArrayList<>(npcNameSet);
        Collections.sort(sortedNpcNames);
        ObservableList<String> npcNamesObservable = FXCollections.observableArrayList(sortedNpcNames);
        npcNamesObservable.add(0, "None");

        FilteredList<String> npcFilteredList = new FilteredList<>(npcNamesObservable, s -> true);

        TextField npcSearchField = new TextField();
        npcSearchField.setPromptText("Search NPCs...");
        npcSearchField.textProperty().addListener((obs, oldVal, newVal) -> {
            final String query = (newVal == null) ? "" : newVal.trim().toLowerCase();
            npcFilteredList.setPredicate(item -> {
                if (item == null) return false;
                if ("None".equals(item)) return true;
                if (query.isEmpty()) return true;
                return item.toLowerCase().contains(query);
            });
        });

        ListView<String> npcListView = new ListView<>(npcFilteredList);
        npcListView.setPrefHeight(150);
        npcListView.setCellFactory(param -> new ListCell<>() {
            @Override
            protected void updateItem(String npcName, boolean empty) {
                super.updateItem(npcName, empty);
                setText((empty || npcName == null) ? null : npcName);
                setGraphic(null);
            }
        });

        npcListView.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldVal, newVal) -> {
                    if (newVal == null || newVal.equals("None")) {
                        selectedNpcId = -1;
                    } else {
                        int npcId = -1;
                        for (Integer id : FileLoader.getNpcMap().keySet()) {
                            if (FileLoader.getNpcMap().get(id).equals(newVal)) {
                                npcId = id;
                                break;
                            }
                        }
                        selectedNpcId = npcId;
                    }
                    controller.setNpc(selectedNpcId);
                }
        );
        npcListView.getSelectionModel().select("None");

        npcInspector.getChildren().addAll(npcInspectorTitle, npcPositionLabel, npcDetailsLabel,
                clearNpcsButton, npcSearchField, npcListView);

        // --- OBJ inspector ---
        VBox objInspector = new VBox();
        objInspector.setPadding(new Insets(10));
        objInspector.setBorder(new Border(new BorderStroke(Color.BLACK, BorderStrokeStyle.SOLID, CornerRadii.EMPTY, BorderWidths.DEFAULT)));
        objInspector.setPrefWidth(250);

        Label objInspectorTitle = new Label("--Objs--\nLeft Click to inspect\nO + Click to update");
        objPositionLabel = new Label("Position: ");
        objDetailsLabel = new Label();
        Button clearObjsButton = new Button("Clear Tile Objs");
        clearObjsButton.setOnAction(e -> controller.clearObjs());

        Set<String> objNameSet = objMap.keySet();
        List<String> sortedObjNames = new ArrayList<>(objNameSet);
        Collections.sort(sortedObjNames);
        ObservableList<String> objNamesObservable = FXCollections.observableArrayList(sortedObjNames);
        objNamesObservable.add(0, "None");

        FilteredList<String> objFilteredList = new FilteredList<>(objNamesObservable, s -> true);

        TextField objSearchField = new TextField();
        objSearchField.setPromptText("Search OBJs...");
        objSearchField.textProperty().addListener((obs, oldVal, newVal) -> {
            final String query = (newVal == null) ? "" : newVal.trim().toLowerCase();
            objFilteredList.setPredicate(item -> {
                if (item == null) return false;
                if ("None".equals(item)) return true;
                if (query.isEmpty()) return true;
                return item.toLowerCase().contains(query);
            });
        });

        ListView<String> objListView = new ListView<>(objFilteredList);
        objListView.setPrefHeight(150);
        objListView.setCellFactory(param -> new ListCell<>() {
            @Override
            protected void updateItem(String objName, boolean empty) {
                super.updateItem(objName, empty);
                setText((empty || objName == null) ? null : objName);
                setGraphic(null);
            }
        });

        objListView.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldVal, newVal) -> {
                    if (newVal == null || newVal.equals("None")) {
                        selectedObjId = -1;
                    } else {
                        int objId = -1;
                        for (Integer id : FileLoader.getObjMap().keySet()) {
                            if (FileLoader.getObjMap().get(id).equals(newVal)) {
                                objId = id;
                                break;
                            }
                        }
                        selectedObjId = objId;
                    }
                    controller.setObj(selectedObjId, selectedObjAmount);
                }
        );
        objListView.getSelectionModel().select("None");

        Label objAmountLabel = new Label("Amount:");
        TextField objAmountTextField = new TextField();
        objAmountTextField.textProperty().addListener((obs, oldVal, newVal) -> {
            int amount = 1;
            if (newVal != null && !newVal.isEmpty()) {
                try { amount = Integer.parseInt(newVal); } catch (NumberFormatException ignored) {}
            }
            selectedObjAmount = Math.max(1, amount);
            controller.setObj(selectedObjId, selectedObjAmount);
        });

        objInspector.getChildren().addAll(objInspectorTitle, objPositionLabel, objDetailsLabel,
                clearObjsButton, objSearchField, objListView, objAmountLabel, objAmountTextField);

        // --- Tile inspector ---
        VBox tileInspector = new VBox();
        tileInspector.setPadding(new Insets(10));
        tileInspector.setBorder(new Border(new BorderStroke(Color.BLACK, BorderStrokeStyle.SOLID, CornerRadii.EMPTY, BorderWidths.DEFAULT)));
        tileInspector.setPrefWidth(250);

        Label tileInspectorTitle = new Label("--Tiles--\nLeft Click to inspect\nCtrl + Click to update");
        Label currentUnderlayLabel = new Label("New Underlay");
        ListView<String> underlayListView = new ListView<>(underlayNames);
        underlayListView.setPrefHeight(50);
        underlayListView.setCellFactory(param -> new ListCell<>() {
            private final Rectangle rect = new Rectangle(20, 20);
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(item);
                    Integer color = underlayMap.get(item);
                    if (color != null) {
                        rect.setFill(Color.rgb((color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF));
                        setGraphic(rect);
                    } else {
                        rect.setFill(Color.TRANSPARENT);
                        setGraphic(null);
                    }
                }
            }
        });

        underlayListView.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldVal, newVal) -> {
                    int id;
                    if (newVal == null || newVal.equals("Original")) {
                        id = 0;
                    } else if (newVal.equals("Clear")) {
                        id = -1;
                    } else {
                        Integer found = findOverlayOrUnderlayIdByName(newVal);
                        id = (found != null) ? found : 0;
                    }
                    controller.setUnderlay(id);
                }
        );

        Label currentOverlayLabel = new Label("New Overlay");
        Label newHeightLabel = new Label("New Height");
        Label newFlagLabel = new Label("New Flag");
        Label newShapeLabel = new Label("New Shape");
        Label newRotationLabel = new Label("New Rotation");
        VBox rotationBox = new VBox(5);
        ToggleGroup rotationGroup = new ToggleGroup();

        for (Integer rotation : rotations) {
            RadioButton rb = new RadioButton(rotation.toString());
            rb.setToggleGroup(rotationGroup);
            rb.setUserData(rotation);
            rotationBox.getChildren().add(rb);
        }

        rotationGroup.selectedToggleProperty().addListener((observable, oldVal, newVal) -> {
            if (newVal != null) {
                controller.setRotation((Integer) newVal.getUserData());
            }
        });

        TextField newHeightTextField = new TextField();
        newHeightTextField.textProperty().addListener((obs, oldVal, newVal) -> {
            controller.setHeight(newVal != null ? newVal : "");
        });

        tilePositionLabel = new Label("Position: ");
        tileOverlayLabel = new Label("Overlay ID: ");
        tileUnderlayLabel = new Label("Underlay ID: ");
        tileFlagLabel = new Label("Flag ID: ");
        tileHeightLabel = new Label("Height: ");
        tileShapeLabel = new Label("Shape ID: ");
        tileRotationLabel = new Label("Shape Rotation: ");
        tileTextureLabel = new Label("Texture Name: ");

        tileInspector.getChildren().addAll(
                tileInspectorTitle,
                tilePositionLabel,
                tileOverlayLabel,
                tileUnderlayLabel,
                tileFlagLabel,
                tileHeightLabel,
                tileShapeLabel,
                tileRotationLabel,
                tileTextureLabel,
                currentUnderlayLabel, underlayListView,
                currentOverlayLabel, overlayListView,
                newHeightLabel, newHeightTextField,
                newFlagLabel, flagListView,
                newRotationLabel, rotationBox,
                newShapeLabel, shapeListView
        );

        // --- Controls & sidebar ---
        VBox controlsBox = new VBox(
                currentLevelLabel, levelRadioButtons,
                displayLabel,
                locCheckbox,
                npcCheckbox,
                objCheckbox,
                exportButton
        );
        controlsBox.setPadding(new Insets(0));
        currentMapLabel = new Label("Current Map: " + currentMapFileName);
        VBox sideBar = new VBox(currentMapLabel, mapListView, controlsBox);
        BorderPane.setMargin(controlsBox, new Insets(10, 0, 10, 0));
        sideBar.setPadding(new Insets(10));
        sideBar.setBorder(new Border(new BorderStroke(Color.BLACK, BorderStrokeStyle.SOLID, CornerRadii.EMPTY, BorderWidths.DEFAULT)));

        // --- Model viewer ---
        modelViewerSelector = new ModelViewerSelector(modelViewer);

        modelViewerSelector.getModelListView().getSelectionModel().selectedItemProperty().addListener(
                (observable, oldVal, newVal) -> {
                    if (newVal != null) {
                        selectedLocName = newVal;
                        List<Integer> viable = FileLoader.getViableShapesForLoc(newVal);

                        Platform.runLater(() -> {
                            locShapeValues.clear();
                            locShapeValues.addAll(viable);

                            if (!viable.isEmpty()) {
                                if (viable.contains(10)) {
                                    locShapeListView.getSelectionModel().select(Integer.valueOf(10));
                                    selectedLocShape = 10;
                                } else {
                                    locShapeListView.getSelectionModel().select(0);
                                    selectedLocShape = viable.get(0);
                                }
                                modelViewerSelector.updateModel(selectedLocShape);
                            }
                            sendLocUpdate();
                        });
                    }
                }
        );

        SwingNode modelViewerSwingNode = new SwingNode();
        Platform.runLater(() -> modelViewerSwingNode.setContent(modelViewer));
        VBox.setVgrow(modelViewerSwingNode, Priority.ALWAYS);
        VBox modelViewerBox = new VBox();
        modelViewerBox.setPadding(new Insets(10));
        modelViewerBox.setAlignment(Pos.CENTER);
        modelViewerBox.setBorder(new Border(new BorderStroke(Color.BLACK, BorderStrokeStyle.SOLID, CornerRadii.EMPTY, BorderWidths.DEFAULT)));
        modelViewerBox.setPrefWidth(250);
        modelViewerBox.getChildren().addAll(modelViewerSelector, modelViewerSwingNode);

        HBox mainContent = new HBox(tileInspector, locInspector, modelViewerBox, npcInspector, objInspector);

        BorderPane rootPane = new BorderPane();
        rootPane.setLeft(sideBar);
        rootPane.setCenter(mainContent);

        Scene scene = new Scene(rootPane, 800, 600);
        fxPanel.setScene(scene);

        // --- Map list listener ---
        mapListView.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldVal, newVal) -> {
                    if (newVal != null) {
                        currentMapLabel.setText("Current Map: " + newVal);
                        controller.setMap(newVal);
                        clearInspectors();
                    }
                }
        );
    }

    private void sendLocUpdate() {
        if (selectedLocName != null) {
            controller.setLoc(selectedLocName, selectedLocShape, selectedLocRotation);
        }
    }

    private Integer findOverlayOrUnderlayIdByName(String name) {
        for (Map.Entry<Integer, String> entry : FileLoader.getFloMap().entrySet()) {
            if (entry.getValue().equals(name)) {
                return entry.getKey();
            }
        }
        return null;
    }

    // --- Inspector update methods (called from renderer or IPC events) ---

    public void updateMapLabel(String mapFileName) {
        Platform.runLater(() -> {
            if (currentMapLabel != null) {
                currentMapLabel.setText("Current Map: " + mapFileName);
            }
        });
    }

    public void updateTileDisplay(String pos, String overlay, String underlay,
                                  String height, String flag, String shape,
                                  String rotation, String texture) {
        Platform.runLater(() -> {
            if (tilePositionLabel != null) {
                tilePositionLabel.setText(pos);
                tileOverlayLabel.setText(overlay);
                tileUnderlayLabel.setText(underlay);
                tileHeightLabel.setText(height);
                tileFlagLabel.setText(flag);
                tileShapeLabel.setText(shape);
                tileRotationLabel.setText(rotation);
                tileTextureLabel.setText(texture);
            }
        });
    }

    public void updateLocDisplay(String pos, String details) {
        Platform.runLater(() -> {
            if (locPositionLabel != null) {
                locPositionLabel.setText(pos);
                locDetailsLabel.setText(details);
            }
        });
    }

    public void updateNpcDisplay(String pos, String details) {
        Platform.runLater(() -> {
            if (npcPositionLabel != null) {
                npcPositionLabel.setText(pos);
                npcDetailsLabel.setText(details);
            }
        });
    }

    public void updateObjDisplay(String pos, String details) {
        Platform.runLater(() -> {
            if (objPositionLabel != null) {
                objPositionLabel.setText(pos);
                objDetailsLabel.setText(details);
            }
        });
    }

    public void clearInspectors() {
        Platform.runLater(() -> {
            if (tilePositionLabel == null) return;
            tilePositionLabel.setText("Position: ");
            tileOverlayLabel.setText("Overlay ID: ");
            tileUnderlayLabel.setText("Underlay ID: ");
            tileHeightLabel.setText("Height: ");
            tileFlagLabel.setText("Flag ID: ");
            tileShapeLabel.setText("Shape ID: ");
            tileRotationLabel.setText("Shape Rotation: ");
            tileTextureLabel.setText("Texture Name: ");
            locPositionLabel.setText("Position: ");
            locDetailsLabel.setText("");
            npcPositionLabel.setText("Position: ");
            npcDetailsLabel.setText("");
            objPositionLabel.setText("Position: ");
            objDetailsLabel.setText("");
        });
    }

    public void handleIpcEvent(String event, String[] args) {
        switch (event) {
            case IpcProtocol.TILE -> {
                if (args.length >= 14) {
                    String pos = String.format("Position: X=%s, Z=%s Level=%s", args[0], args[1], args[2]);
                    String overlayId = args[3];
                    String overlayName = args[4];
                    String underlayId = args[5];
                    String underlayName = args[6];
                    String height = args[7];
                    String perlin = args[8];
                    String flag = args[9];
                    String shape = args[10];
                    String rotation = args[11];
                    String texture = args[12];

                    String overlayText = "Overlay ID: ";
                    if ("none".equals(overlayId)) {
                        overlayText += "None";
                    } else {
                        overlayText += overlayId;
                        if (!"none".equals(overlayName)) overlayText += " - " + overlayName;
                    }

                    String underlayText = "Underlay ID: ";
                    if ("none".equals(underlayId)) {
                        underlayText += "None";
                    } else {
                        underlayText += underlayId;
                        if (!"none".equals(underlayName)) underlayText += " - " + underlayName;
                    }

                    String heightText;
                    if ("true".equals(perlin)) {
                        heightText = "Height: Perlin Generated";
                    } else {
                        try {
                            int h = Integer.parseInt(height);
                            heightText = "Height: " + (h / -8);
                        } catch (NumberFormatException e) {
                            heightText = "Height: " + height;
                        }
                    }

                    String flagText = "Flag ID: " + ("none".equals(flag) ? "None" : flag);
                    String shapeText = "Shape ID: " + ("none".equals(shape) ? "0" : shape);
                    String rotText = "Shape Rotation: ";
                    if ("none".equals(rotation)) {
                        rotText += "0";
                    } else {
                        try {
                            rotText += Integer.parseInt(rotation) * 90;
                        } catch (NumberFormatException e) {
                            rotText += rotation;
                        }
                    }
                    String textureText = "Texture Name: " + ("none".equals(texture) ? "None" : texture);

                    updateTileDisplay(pos, overlayText, underlayText, heightText, flagText, shapeText, rotText, textureText);
                    updateLocDisplay(pos, args.length > 13 ? IpcProtocol.unescape(args[13]) : "");
                    updateNpcDisplay(pos, args.length > 14 ? IpcProtocol.unescape(args[14]) : "");
                    updateObjDisplay(pos, args.length > 15 ? IpcProtocol.unescape(args[15]) : "");
                }
            }
            case IpcProtocol.TILE_CLEAR -> clearInspectors();
            case IpcProtocol.OK -> {} // no-op for now
            case IpcProtocol.ERR -> {
                if (args.length > 0) {
                    System.err.println("[IPC] Server error: " + IpcProtocol.unescape(args[0]));
                }
            }
        }
    }
}
