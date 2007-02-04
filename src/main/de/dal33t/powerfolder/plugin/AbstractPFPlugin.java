package de.dal33t.powerfolder.plugin;

import de.dal33t.powerfolder.Controller;
import de.dal33t.powerfolder.PFComponent;
import de.dal33t.powerfolder.ui.preferences.PreferencesDialog;

/**
 * For your convenience an implementation of the plugin interface that does not
 * have a options dialog and is an PFComponent. PFComoment gives you access to
 * the Controller. The Controller is the access point for all main program
 * elelements. Overwrite the hasOptionsDialog() and showOptionsDialog(JDialog
 * parent) if you do have an options dialog.
 */
public abstract class AbstractPFPlugin extends PFComponent implements Plugin {

    /**
     * this contructor will be called always, even if disabled. disabled means
     * that start will not be called, so when overwiting this contructor make
     * sure not to do much in there move all code to start().
     */
    public AbstractPFPlugin(Controller controller) {
        super(controller);
    }

    /** overwrite this to return true if you implement showOptionsDialog */
    public boolean hasOptionsDialog() {
        return false;
    }

    public void showOptionsDialog(PreferencesDialog prefDialog) {
        throw new IllegalStateException(
            "default no options dialog, overwrite this method");
    }

    public String toString() {
        return getName();
    }
}
