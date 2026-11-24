package io.github.madaai.idlestow.app.ui;

import javafx.stage.Window;

import java.nio.file.Path;
import java.util.List;

/** Cross-view navigation and shared window services. */
public interface Navigator {
    void show(ViewId id);

    /** Opens History filtered by a path fragment. */
    void showHistory(String pathFilter);

    /** Opens Recovery and selects the record, when given. */
    void showRecovery(Long recoveryId);

    /** Opens Explorer at a folder (or the folder containing a file). */
    void showExplorer(Path path);

    /** Opens the Plan Preview dialog for the selected explorer items. */
    void openPlanPreview(List<Long> itemIds);

    /** Short, non-blocking message at the bottom of the window. */
    void toast(String message);

    Window window();
}
