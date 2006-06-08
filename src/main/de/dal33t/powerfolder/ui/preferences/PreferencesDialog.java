package de.dal33t.powerfolder.ui.preferences;

import java.awt.Component;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.List;

import javax.swing.*;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JTabbedPane;

import org.apache.commons.lang.StringUtils;

import com.jgoodies.binding.value.ValueHolder;
import com.jgoodies.binding.value.ValueModel;
import com.jgoodies.forms.builder.PanelBuilder;
import com.jgoodies.forms.factories.Borders;
import com.jgoodies.forms.factories.ButtonBarFactory;
import com.jgoodies.forms.layout.CellConstraints;
import com.jgoodies.forms.layout.FormLayout;

import de.dal33t.powerfolder.Controller;
import de.dal33t.powerfolder.ui.Icons;
import de.dal33t.powerfolder.util.Translation;
import de.dal33t.powerfolder.util.ui.BaseDialog;

public class PreferencesDialog extends BaseDialog {

    private ValueModel mydnsndsModel;
    private JButton okButton;
    private JButton cancelButton;
    private List<PreferenceTab> preferenceTabs;
    private JTabbedPane tabbedPane;

    private DynDnsSettingsTab dynDnsSettingsTab;
    private AdvancedSettingsTab advangedSettingsTab;
    static final int GENERAL_TAB_INDEX = 0;
    private static final int DYNDNS_TAB_INDEX = 3;    
    private static final int ADVANGED_TAB_INDEX = 4;
    
    public PreferencesDialog(Controller controller) {
        super(controller, true, false);
        preferenceTabs = new ArrayList<PreferenceTab>();
    }

    public JDialog getDialog() {
        return getUIComponent();
    }

    public String getTitle() {
        return Translation.getTranslation("preferences.dialog.title");
    }

    protected Icon getIcon() {
        return Icons.PREFERENCES;
    }

    void enableTab(int index, boolean flag) {
        tabbedPane.setEnabledAt(index, flag);
    }

    void selectTab(int index) {
        tabbedPane.setSelectedIndex(index);
    }

    private void showTab(boolean enable, PreferenceTab tab, int tabindex) {
        if (enable) {
            preferenceTabs.add(tab);
            //calculate a valid insert index before inserting
            int currentNumberOfTabs = tabbedPane.getTabCount();
            int newTabindex = Math.min(tabindex, currentNumberOfTabs);
            tabbedPane.insertTab(tab.getTabName(), null, tab.getUIPanel(), null, newTabindex);
        } else {
            preferenceTabs.remove(tab);
            tabbedPane.remove(tab.getUIPanel());
        }
        rePack();
    }
        
    private void showAdvangedTab(boolean enable) {
        showTab(enable, advangedSettingsTab, ADVANGED_TAB_INDEX);
    }

    void showDynDNSTab(boolean enable) {
        showTab(enable, dynDnsSettingsTab, DYNDNS_TAB_INDEX);
    }

    public Component getContent() {
        initComponents();

        FormLayout layout = new FormLayout("pref", "pref");
        PanelBuilder builder = new PanelBuilder(layout);

        CellConstraints cc = new CellConstraints();
        builder.add(tabbedPane, cc.xy(1, 1));

        return builder.getPanel();
    }

    public void initComponents() {
        mydnsndsModel = new ValueHolder(getController().getConfig()
            .getProperty("mydyndns"));
        mydnsndsModel.addValueChangeListener(new PropertyChangeListener() {
            public void propertyChange(PropertyChangeEvent evt) {
                String dyndns = (String) evt.getNewValue();
                // show tab when dyndns host is set
                showDynDNSTab(!StringUtils.isBlank(dyndns));
            }
        });

        tabbedPane = new JTabbedPane(JTabbedPane.LEFT);

        GeneralSettingsTab generalSettingsTab = new GeneralSettingsTab(
            getController());
        preferenceTabs.add(generalSettingsTab);
        tabbedPane.addTab("     " + generalSettingsTab.getTabName() + "      ",
            null, generalSettingsTab.getUIPanel(), null);

        NetworkSettingsTab networkSettingsTab = new NetworkSettingsTab(
            getController(), mydnsndsModel);
        preferenceTabs.add(networkSettingsTab);
        tabbedPane.addTab(networkSettingsTab.getTabName(), null,
            networkSettingsTab.getUIPanel(), null);
  
        PluginSettingsTab pluginSettingsTab = new PluginSettingsTab(
            getController(), this);
        if (getController().getPluginManager().countPlugins() > 0) {
            preferenceTabs.add(pluginSettingsTab);
            tabbedPane.addTab(pluginSettingsTab.getTabName(), null,
                pluginSettingsTab.getUIPanel(), null);
        }
        
        dynDnsSettingsTab = new DynDnsSettingsTab(getController(),
            mydnsndsModel);
        if (!StringUtils.isBlank((String) mydnsndsModel.getValue())) {
            preferenceTabs.add(dynDnsSettingsTab);
            tabbedPane.addTab(dynDnsSettingsTab.getTabName(), null,
                dynDnsSettingsTab.getUIPanel(), null);
        }
        
        advangedSettingsTab = new AdvancedSettingsTab(getController());
        if ("true".equals(getController().getConfig().get(
            GeneralSettingsTab.SHOWADVANGEDSETTINGS)))
        {
            preferenceTabs.add(advangedSettingsTab);
            tabbedPane.addTab(advangedSettingsTab.getTabName(), null,
                advangedSettingsTab.getUIPanel(), null);
        }

        // Behavior for advanced settings panel
        generalSettingsTab.getShowAdvancedSettingsModel()
            .addValueChangeListener(new PropertyChangeListener() {
                public void propertyChange(PropertyChangeEvent evt) {
                    showAdvangedTab(Boolean.TRUE.equals(evt.getNewValue()));
                }
            });
        showAdvangedTab(Boolean.TRUE.equals(generalSettingsTab
            .getShowAdvancedSettingsModel().getValue()));

        tabbedPane.setSelectedIndex(0);
        tabbedPane.setBorder(Borders.createEmptyBorder("3dlu,0,0,3dlu"));

        // Buttons
        okButton = createOKButton();
        cancelButton = createCancelButton();
    }

    /**
     * Creates the okay button for the whole pref dialog
     */
    private JButton createOKButton() {
        JButton theButton = createOKButton(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                //
                // OK button event handler
                //
                // disable the OK button while we are in the
                // working thread (i.e. prohibit re-entrance)
                okButton.setEnabled(false);

                // since we are performing a validation
                // that could take some time we need to warn the user about it.
                // However updating the gui while the task is progressing,
                // requires us to run the validation in a new thread that will
                // give the chance of the swing thread to update the GUI
                new Thread("Preferences saver/validator") {
                    public void run() {

                        // validate the user input and check the result
                        boolean succes = validateSettings();
                        if (!succes) {
                            okButton.setEnabled(true);
                            return;
                        }

                        // Save settings
                        saveSettings();
                        if (needsRestart()) {
                            handleRestartRequest();
                        }
                        close();

                    } // end run
                }.start(); // start the working thread
            }
        });
        return theButton;
    }

    private JButton createCancelButton() {
        return createCancelButton(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                close();
                undoChanges();
            }
        });

    }

    /**
     * Asks user about restart and executes that if requested
     */
    private void handleRestartRequest() {
        int result = getUIController().showOKCancelDialog(null,
            Translation.getTranslation("preferences.dialog.restarttitle"),
            Translation.getTranslation("preferences.dialog.restarttext"));

        if (result == JOptionPane.OK_OPTION) {
            getController().shutdownAndRequestRestart();
        }
    }

    protected Component getButtonBar() {
        return ButtonBarFactory.buildCenteredBar(okButton, cancelButton);
    }

    private void saveSettings() {
        for (PreferenceTab tab : preferenceTabs) {
            tab.save();
        }
        getController().saveConfig();
    }

    /**
     * call undoChanges on all tabs, those changes that where done immediately
     * like theme change
     */
    private void undoChanges() {
        for (PreferenceTab tab : preferenceTabs) {
            tab.undoChanges();
        }
    }

    /**
     * Validates the settings before saving them persistantly
     */
    private boolean validateSettings() {
        for (PreferenceTab tab : preferenceTabs) {
            boolean succes = tab.validate();
            if (!succes) {
                return false;
            }
        }
        return true;
    }

    private boolean needsRestart() {
        for (PreferenceTab tab : preferenceTabs) {
            if (tab.needsRestart()) {
                return true;
            }
        }
        return false;
    }
}
