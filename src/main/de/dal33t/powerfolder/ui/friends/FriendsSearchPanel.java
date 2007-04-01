package de.dal33t.powerfolder.ui.friends;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.Action;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.event.TableModelEvent;
import javax.swing.event.TableModelListener;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableColumn;
import javax.swing.table.TableModel;

import com.jgoodies.forms.builder.ButtonBarBuilder;
import com.jgoodies.forms.builder.PanelBuilder;
import com.jgoodies.forms.factories.Borders;
import com.jgoodies.forms.layout.CellConstraints;
import com.jgoodies.forms.layout.FormLayout;

import de.dal33t.powerfolder.Controller;
import de.dal33t.powerfolder.Member;
import de.dal33t.powerfolder.PFUIComponent;
import de.dal33t.powerfolder.PreferencesEntry;
import de.dal33t.powerfolder.net.NodeSearcher;
import de.dal33t.powerfolder.ui.Icons;
import de.dal33t.powerfolder.ui.action.BaseAction;
import de.dal33t.powerfolder.ui.action.ConnectAction;
import de.dal33t.powerfolder.ui.builder.ContentPanelBuilder;
import de.dal33t.powerfolder.ui.model.SearchNodeTableModel;
import de.dal33t.powerfolder.util.Translation;
import de.dal33t.powerfolder.util.ui.DoubleClickAction;
import de.dal33t.powerfolder.util.ui.HasUIPanel;
import de.dal33t.powerfolder.util.ui.PopupMenuOpener;
import de.dal33t.powerfolder.util.ui.SimpleComponentFactory;
import de.dal33t.powerfolder.util.ui.UIUtil;

/**
 * Search for members, use to "make friends".
 * 
 * @author <A HREF="mailto:schaatser@powerfolder.com">Jan van Oosterom</A>
 * @version $Revision: 1.7 $
 */
public class FriendsSearchPanel extends PFUIComponent implements HasUIPanel {

    /** input field for search text */
    private JTextField searchInput;
    /** the ui of the list of users that matches the search. */
    private JTable searchResult;

    /** The quick info panle for friends */
    private FriendsQuickInfoPanel quickinfo;

    private JScrollPane searchResultScroller;
    /** the table model holding the search results */
    private SearchNodeTableModel searchNodeTableModel;
    /** this panel */
    private JComponent panel;
    /** The button to search with */
    private JButton searchButton;
    /** search */
    private Action searchAction;
    /** add friend */
    private Action addFriendAction;
    /** chat with this user */
    private Action chatAction;
    /** manual connect */
    private Action connectAction;
    /** bottom toolbar */
    private JComponent toolbar;
    /** The Thread performing the search */
    private NodeSearcher searcher;
    /**
     * the toggle button that indicates if the offline users should be hidden or
     * not
     */
    private JCheckBox hideOffline;

    /** create a FriendsPanel */
    public FriendsSearchPanel(Controller controller) {
        super(controller);
    }

    public String getTitle() {
        return Translation.getTranslation("title.search.users");
    }

    /** returns this ui component, creates it if not available * */
    public Component getUIComponent() {
        if (panel == null) {
            initComponents();
            ContentPanelBuilder builder = new ContentPanelBuilder();
            builder.setQuickInfo(quickinfo.getUIComponent());
            builder.setToolbar(toolbar);
            builder.setContent(createContentPanel());
            panel = builder.getPanel();
        }
        return panel;
    }

    private void initComponents() {
        quickinfo = new FriendsQuickInfoPanel(getController(), Translation
            .getTranslation("quickinfo.friends.title"));

        searchInput = new JTextField(15);
        searchInput.setEditable(true);
        searchNodeTableModel = new SearchNodeTableModel(getController());
        searchNodeTableModel.addTableModelListener(new QuickInfoUpdater());
        searchResult = new JTable(searchNodeTableModel);
        searchResult.setRowHeight(Icons.NODE_NON_FRIEND_CONNECTED
            .getIconHeight() + 3);
        searchResult.setShowGrid(false);
        searchResult.setDefaultRenderer(Member.class,
            new MemberTableCellRenderer());
        searchResult.getSelectionModel().addListSelectionListener(
            new SearchResultSelectionListener());
        searchResult.getTableHeader().setReorderingAllowed(true);
        // add sorting
        searchResult.getTableHeader().addMouseListener(
            new TableHeaderMouseListener());

        addFriendAction = new AddFriendAction();
        addFriendAction.setEnabled(false);
        chatAction = new ChatAction();
        chatAction.setEnabled(false);
        connectAction = new ConnectAction(getController());
        
        toolbar = createToolBar();

        searchInput.addKeyListener(new SearchInputKeyListener());
        searchResult.addMouseListener(new PopupMenuOpener(createPopupMenu()));
        searchResult.addMouseListener(new DoubleClickAction(addFriendAction));

        searchResultScroller = new JScrollPane(searchResult);
        UIUtil.whiteStripTable(searchResult);
        UIUtil.removeBorder(searchResultScroller);
        UIUtil.setZeroHeight(searchResultScroller);

        setupColumns();
    }

    private JComponent createContentPanel() {
        FormLayout layout = new FormLayout("fill:pref:grow",
            "pref, pref, pref, fill:pref:grow");
        PanelBuilder builder = new PanelBuilder(layout);
        CellConstraints cc = new CellConstraints();

        builder.add(createSearchPanel(), cc.xy(1, 1));
        JLabel searchTitle = builder.addTitle(Translation
            .getTranslation("friendspanel.search_results"), cc.xy(1, 3));
        searchTitle.setBorder(Borders.createEmptyBorder("0, 4dlu, 4dlu, 0"));

        builder.add(searchResultScroller, cc.xy(1, 4));
        return builder.getPanel();
    }

    /**
     * Creates the popup menu
     */
    private JPopupMenu createPopupMenu() {
        JPopupMenu popupMenu = SimpleComponentFactory.createPopupMenu();
        popupMenu.add(addFriendAction);
        popupMenu.add(chatAction);
        return popupMenu;
    }

    private JPanel createSearchPanel() {
        searchAction = new SearchAction();
        searchAction.setEnabled(false);

        searchButton = new JButton(searchAction);
        ButtonBarBuilder bar = ButtonBarBuilder.createLeftToRightBuilder();
        bar.addGlue();
        bar.addFixed(searchButton);
        bar.addRelatedGap();
        hideOffline = new JCheckBox(new HideOfflineAction());
        bar.addGridded(hideOffline);
        hideOffline.setSelected(PreferencesEntry.FRIENDSEARCH_HIDEOFFLINE
            .getValueBoolean(getController()));
        hideOffline.addChangeListener(new ChangeListener() {
            public void stateChanged(ChangeEvent e) {
                // save to pref if hide off line checkbox clicked
                PreferencesEntry.FRIENDSEARCH_HIDEOFFLINE.setValue(
                    getController(), hideOffline.isSelected());
            }
        });
        FormLayout layout = new FormLayout("pref, 3dlu, pref, 7dlu, pref",
            "pref");
        PanelBuilder builder = new PanelBuilder(layout);
        CellConstraints cc = new CellConstraints();
        builder.add(new JLabel(Translation
            .getTranslation("friendspanel.search_for_user")), cc.xy(1, 1));
        builder.add(searchInput, cc.xy(3, 1));
        builder.add(bar.getPanel(), cc.xy(5, 1));

        builder.setBorder(Borders.DLU4_BORDER);
        return builder.getPanel();

    }

    /**
     * Creates the bottom toolbar
     * 
     * @return The bottom tool bar
     */
    private JComponent createToolBar() {
        // Create toolbar
        ButtonBarBuilder bar = ButtonBarBuilder.createLeftToRightBuilder();
        bar.addGridded(new JButton(addFriendAction));
        bar.addRelatedGap();
        bar.addGridded(new JButton(chatAction));
        bar.addUnrelatedGap();
        bar.addGridded(new JButton(connectAction));
        JPanel barPanel = bar.getPanel();
        barPanel.setBorder(Borders.DLU4_BORDER);
        return barPanel;
    }

    /**
     * Sets the column sizes of the table
     */
    private void setupColumns() {
        int totalWidth = searchResult.getWidth();
        // otherwise the table header may not be visible:
        searchResult.getTableHeader().setPreferredSize(
            new Dimension(totalWidth, 20));

        TableColumn column = searchResult.getColumn(searchResult
            .getColumnName(0));
        column.setPreferredWidth(140);
        column = searchResult.getColumn(searchResult.getColumnName(1));
        column.setPreferredWidth(100);
        // column = searchResult.getColumn(searchResult.getColumnName(2));
        // column.setPreferredWidth(220);
        column = searchResult.getColumn(searchResult.getColumnName(2));
        column.setPreferredWidth(100);
        column = searchResult.getColumn(searchResult.getColumnName(3));
        column.setPreferredWidth(50);
        column.setMaxWidth(50);
    }

    /** preform a search, interrupts a search if still running */
    private void search() {
        // Block until the search has been killed if there's one running
        if (searcher != null && searcher.isSearching()) {
            searcher.cancelSearch();
        }

        searcher = new NodeSearcher(getController(), searchInput.getText()
            .trim(), searchNodeTableModel.getListModel(), false, // ignore
            // friends,
            hideOffline.isSelected()); // hide offline
        searcher.start();
    }

    /** called if button addFriend clicked or if selected in popupmenu */
    private void addFriend() {
        synchronized (searchNodeTableModel) {
            int[] selectedIndexes = searchResult.getSelectedRows();
            for (int i = 0; i < selectedIndexes.length; i++) {
                int index = selectedIndexes[i];
                Object item = searchNodeTableModel.getDataAt(index);
                if (item instanceof Member) {
                    Member newFriend = (Member) item;
                    newFriend.setFriend(true);
                }
            }
        }
        // Update actions
        updateActions();
        // refresh search (removes the new friend)
        searchButton.doClick();
    }

    /** called if button chat clicked or if in popupmenu selected */
    private void chatWithSelected() {
        synchronized (searchNodeTableModel) {
            int[] selectedIndexes = searchResult.getSelectedRows();
            // only single selection for chat
            if (selectedIndexes.length != 1) {
                return;
            }
            int index = selectedIndexes[0];
            Member member = (Member) searchNodeTableModel.getDataAt(index);
            if (!getUIController().getNodeManagerModel().hasMemberNode(member))
            {
                getUIController().getNodeManagerModel().addChatMember(member);
            }
            if (member.isCompleteyConnected()) {
                getController().getUIController().getControlQuarter()
                    .setSelected(member);
            }
        }
    }

    // UI Helper code *********************************************************

    /**
     * Updates the state of all actions upon the current selection
     */
    private void updateActions() {
        chatAction.setEnabled(false);
        addFriendAction.setEnabled(false);
        int[] selectedIndexes = searchResult.getSelectedRows();

        // only single selection for chat and with connected members
        if (selectedIndexes.length == 1) {
            int index = selectedIndexes[0];
            Object item = searchNodeTableModel.getDataAt(index);
            if (item instanceof Member) {
                Member member = (Member) item;
                chatAction.setEnabled(member.isCompleteyConnected());
            }
        }

        // if at least one member selected
        for (int i = 0; i < selectedIndexes.length; i++) {
            int index = selectedIndexes[i];
            Object item = searchNodeTableModel.getDataAt(index);
            if (item instanceof Member) {
                Member user = (Member) item;
                addFriendAction.setEnabled(!user.isFriend());
                break;
            }
        }
    }

    /** The hide offline user to perform on click on checkbox */
    private class HideOfflineAction extends BaseAction {
        public HideOfflineAction() {
            super("hideoffline", FriendsSearchPanel.this.getController());
        }

        public void actionPerformed(ActionEvent e) {
            searchButton.doClick();
        }
    }

    /** The Chat action to perform for button and popup menu item */
    private class ChatAction extends BaseAction {
        public ChatAction() {
            super("openchat", FriendsSearchPanel.this.getController());
        }

        public void actionPerformed(ActionEvent e) {
            chatWithSelected();
        }
    }

    /** The Add friends action for button and popup menu Item */
    private class AddFriendAction extends BaseAction {
        public AddFriendAction() {
            super("addfriend", FriendsSearchPanel.this.getController());
        }

        public void actionPerformed(ActionEvent e) {
            addFriend();
        }
    }

    /** The action to preform if the search button is clicked */
    private class SearchAction extends BaseAction {
        public SearchAction() {
            super("searchfriends", FriendsSearchPanel.this.getController());
        }

        public void actionPerformed(ActionEvent e) {
            search();
        }
    }

    /**
     * listens to keys in the search input updates the searchAction state if
     * enough chars are available and preforms a search on enter key
     * 
     * @author <A HREF="mailto:schaatser@powerfolder.com">Jan van Oosterom</A>
     */
    private class SearchInputKeyListener implements KeyListener {
        public void keyPressed(KeyEvent e) {
        }

        public void keyReleased(KeyEvent e) {
            // minimal 3 chars for enabled search button
            searchAction.setEnabled(searchInput.getText().trim().length() >= 3);
        }

        public void keyTyped(KeyEvent e) {
            char keyTyped = e.getKeyChar();
            if (keyTyped == '\n') { // enter key = search
                // gives visual button press
                searchButton.doClick();
            }
        }
    }

    /**
     * Helper class which tracks the selections int the search Results and
     * updates the actions to the correct state
     * 
     * @author <A HREF="mailto:schaatser@powerfolder.com">Jan van Oosterom</A>
     */
    private class SearchResultSelectionListener implements
        ListSelectionListener
    {
        public void valueChanged(ListSelectionEvent e) {
            updateActions();
        }
    }

    /**
     * Updates the quickinfo from the table model.
     * 
     * @author <a href="mailto:sprajc@riege.com">Christian Sprajc</a>
     */
    private class QuickInfoUpdater implements TableModelListener {
        public void tableChanged(TableModelEvent e) {
            if (searchNodeTableModel.containsNoUsers()) {
                quickinfo.setUsersFound(0);
            } else {
                quickinfo.setUsersFound(searchNodeTableModel.getRowCount());
            }
        }
    }

    /**
     * Listner on table header, takes care about the sorting of table
     */
    private class TableHeaderMouseListener extends MouseAdapter {
        public final void mouseClicked(MouseEvent e) {
            if (SwingUtilities.isLeftMouseButton(e)) {
                JTableHeader tableHeader = (JTableHeader) e.getSource();
                int columnNo = tableHeader.columnAtPoint(e.getPoint());
                TableColumn column = tableHeader.getColumnModel().getColumn(
                    columnNo);
                int modelColumnNo = column.getModelIndex();
                TableModel model = tableHeader.getTable().getModel();
                if (model instanceof SearchNodeTableModel) {
                    SearchNodeTableModel searchNodeTableModel = (SearchNodeTableModel) model;
                    boolean freshSorted = searchNodeTableModel
                        .sortBy(modelColumnNo);
                    if (!freshSorted) {
                        // reverse list
                        searchNodeTableModel.reverseList();
                    }
                }
            }
        }
    }
}