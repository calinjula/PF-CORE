package de.dal33t.powerfolder.plugin;

import javax.swing.JDialog;

import de.dal33t.powerfolder.ui.preferences.PreferencesDialog;

/**
 * Plugin Interface to PowerFolder. We recomment extending AbstractPFPlugin.<BR>
 * add plugins by changing the plugin= setting in the config file. This should
 * be a comma ',' seperated list of classname in the classpath.<BR>
 * In the preferences dialog of PowerFolder the currently installed plugins will
 * be listed.<BR>
 * If you plugins has a settings dialog hasOptionsDialog should return true.
 * Then this options dialog will be available by selecting the plugin in the
 * preferences dialog and clicking the settings button.<BR>
 * Your plugin should take care of its own settings, the best way to do that is
 * access the configfile like this:
 * <code>Properties config = getController().getConfig();</code>
 * 
 * @author <A HREF="mailto:schaatser@powerfolder.com">Jan van Oosterom</A>
 */
public interface Plugin {
    /** the name of the plugin */
    public String getName();

    /** the description of the plugin */
    public String getDescription();

    /** called to (re) start the plugin. */
    public void start();

    /** called to stop the plugin (e.g. on program exit) */
    public void stop();

    /** does this plugin has an options dialog? */
    public boolean hasOptionsDialog();

    /**
     * should show an options dialog
     * 
     * @param prefDialog
     *            the preferences dialog
     */
    public void showOptionsDialog(PreferencesDialog prefDialog);
}
