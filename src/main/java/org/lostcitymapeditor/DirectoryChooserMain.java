package org.lostcitymapeditor;

import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/**
 * Standalone entry point that only shows a directory chooser and prints the selected path to stdout.
 * Used on macOS: the main app runs with -XstartOnFirstThread (for GLFW), so it cannot show Swing
 * dialogs. This runs in a separate JVM without that flag, shows the chooser, then exits.
 */
public class DirectoryChooserMain {

    public static void main(String[] args) {
        final String[] result = new String[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                JOptionPane.showMessageDialog(null,
                    "Select the root directory containing your server's 'pack' and 'maps' folders.",
                    "Lost City Map Editor – Select Server Data",
                    JOptionPane.INFORMATION_MESSAGE);

                JFileChooser chooser = new JFileChooser();
                chooser.setDialogTitle("Select Server Data Source Directory");
                chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);

                if (chooser.showDialog(null, "Select Directory") == JFileChooser.APPROVE_OPTION) {
                    result[0] = chooser.getSelectedFile().getAbsolutePath();
                }
            });
        } catch (Exception e) {
            System.err.println("Chooser failed: " + e.getMessage());
        }
        if (result[0] != null) {
            System.out.println(result[0]);
        }
    }
}
