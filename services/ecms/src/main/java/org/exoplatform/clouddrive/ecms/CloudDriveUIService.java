/*
 * Copyright (C) 2003-2012 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.exoplatform.clouddrive.ecms;

import org.exoplatform.clouddrive.CloudDriveService;
import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.container.component.ComponentPlugin;
import org.exoplatform.services.cms.views.ManageViewService;
import org.exoplatform.services.jcr.RepositoryService;
import org.exoplatform.services.jcr.ext.common.SessionProvider;
import org.exoplatform.services.jcr.impl.RepositoryContainer;
import org.exoplatform.services.jcr.impl.WorkspaceContainer;
import org.exoplatform.services.jcr.impl.backup.ResumeException;
import org.exoplatform.services.jcr.impl.backup.SuspendException;
import org.exoplatform.services.jcr.impl.backup.Suspendable;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.webui.core.UIComponent;
import org.exoplatform.webui.ext.UIExtension;
import org.exoplatform.webui.ext.UIExtensionManager;
import org.picocontainer.Startable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

/**
 * Add ImportCloudDocument button in ListView. We're doing this by hack of already stored DMS
 * navigation.
 */
public class CloudDriveUIService implements Startable {

  /**
   * Instance of this class will be registered in ECMS's system workspace of JCR repository. It has maximum
   * priority and will be suspended on a repository stop first. It invokes ECMS menu restoration to remove CD
   * actions for a case of the extension uninstallation.
   */
  class ViewRestorer implements Suspendable {

    boolean suspended = false;

    /**
     * {@inheritDoc}
     */
    @Override
    public void suspend() throws SuspendException {
      try {
        restoreViews();
        LOG.info("Cloud Drive actions successfully disabled");
      } catch (Exception e) {
        LOG.error("Error disabling Cloud Drive actions: " + e.getMessage(), e);
      }
      suspended = true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void resume() throws ResumeException {
      suspended = true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isSuspended() {
      return suspended;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getPriority() {
      return Integer.MAX_VALUE; // it should be very first (sure it's not 100% this way)
    }
  }

  private static final Log           LOG                        = ExoLogger.getLogger(CloudDriveUIService.class);

  protected static final String      DMS_SYSTEM_WORKSPACE       = "dms-system";

  protected static final String      EXO_BUTTONS                = "exo:buttons";

  protected static final String      ECD_USER_BUTTONS           = "ecd:userButtons";

  protected static final String      ECD_BUTTONS                = "ecd:buttons";

  public static final String         CONNECT_CLOUD_DRIVE_ACTION = "add.connect.clouddrive.action";

  protected final RepositoryService  jcrService;

  protected final ManageViewService  manageView;

  protected final CloudDriveService  driveService;

  protected final UIExtensionManager uiExtensions;

  protected final Set<String>        defaultMenuActions         = new HashSet<String>();

  protected final List<String>       VIEWS                      = Arrays.asList("List/List",
                                                                                "Admin/Admin",
                                                                                "Web/Authoring",
                                                                                "Icons/Icons",
                                                                                "Categories/Collaboration");

  public CloudDriveUIService(RepositoryService repoService,
                             CloudDriveService driveService,
                             UIExtensionManager uiExtensions,
                             ManageViewService manageView) {
    this.jcrService = repoService;
    this.manageView = manageView;
    this.driveService = driveService;
    this.uiExtensions = uiExtensions;
  }

  public void addPlugin(ComponentPlugin plugin) {
    if (plugin instanceof CloudDriveUIExtension) {
      // default menu action to initialize
      CloudDriveUIExtension ext = (CloudDriveUIExtension) plugin;
      defaultMenuActions.addAll(ext.getDefaultActions());
    } else {
      LOG.warn("Cannot recognize component plugin for " + plugin.getName() + ": type " + plugin.getClass()
          + " not supported");
    }
  }

  /**
   * List of Cloud Drive actions configured to apper in menu by default.
   * 
   * @return List of Strings with action names
   * @throws Exception
   */
  protected List<String> getDefaultActions() throws Exception {
    // find all Cloud Drive actions configured by default for action bar
    List<String> cdActions = new ArrayList<String>();
    for (UIExtension ext : uiExtensions.getUIExtensions(ManageViewService.EXTENSION_TYPE)) {
      // Class<? extends UIComponent> extComp = ext.getComponent();
      String menuAction = ext.getName();
      if (defaultMenuActions.contains(menuAction)) {
        cdActions.add(menuAction);
      }
    }
    return cdActions;
  }

  /**
   * All Cloud Drive actions registered in the system.
   * 
   * @return List of Strings with action names
   * @throws Exception
   */
  protected List<String> getAllActions() throws Exception {
    // find all Cloud Drive actions configured by default for action bar
    List<String> cdActions = new ArrayList<String>();
    for (UIExtension ext : uiExtensions.getUIExtensions(ManageViewService.EXTENSION_TYPE)) {
      Class<? extends UIComponent> extComp = ext.getComponent();
      if (CloudDriveUIMenuAction.class.isAssignableFrom(extComp)) {
        cdActions.add(ext.getName());
      }
    }
    return cdActions;
  }

  /**
   * Read all buttons actions from given node, in buttons property, to given string builder.
   * 
   * @param node {@link Node}
   * @param buttons {@link String}
   * @param actionsStr {@link StringBuilder}
   * @throws RepositoryException
   */
  protected void readViewActions(Node node, String buttons, StringBuilder actionsStr) throws RepositoryException {
    if (node.hasProperty(buttons)) {
      String[] actions = node.getProperty(buttons).getString().split(";");
      for (int i = 0; i < actions.length; i++) {
        String a = actions[i].trim();
        if (actionsStr.indexOf(a) < 0) { // add only if not already exists
          if (actionsStr.length() > 0) {
            actionsStr.append(';');
            actionsStr.append(' ');
          }
          actionsStr.append(a);
        }
      }
    }
  }

  /**
   * Split buttons actions on Cloud Drive's and other from given node, in buttons property, to given string
   * builder.
   * 
   * @param node {@link Node}
   * @param buttons {@link String}
   * @param actionsStr {@link StringBuilder}
   * @throws RepositoryException
   */
  protected void splitViewActions(Node node,
                                  String buttons,
                                  StringBuilder cdActions,
                                  StringBuilder otherActions) throws RepositoryException {
    if (node.hasProperty(buttons)) {
      String[] actions = node.getProperty(buttons).getString().split(";");
      for (int i = 0; i < actions.length; i++) {
        String a = actions[i].trim();
        String aname = capitalize(a);
        UIExtension ae = uiExtensions.getUIExtension(ManageViewService.EXTENSION_TYPE, aname);
        if (ae != null) {
          if (CloudDriveUIMenuAction.class.isAssignableFrom(ae.getComponent())) {
            if (cdActions.indexOf(a) < 0) { // add only if not already exists
              if (cdActions.length() > 0) {
                cdActions.append(';');
                cdActions.append(' ');
              }
              cdActions.append(a);
            }
          } else if (otherActions.indexOf(a) < 0) { // add only if not already exists
            if (otherActions.length() > 0) {
              otherActions.append(';');
              otherActions.append(' ');
            }
            otherActions.append(a);
          }
        } else {
          LOG.warn("Cannot find UIExtension for action " + aname);
        }
      }
    }
  }

  //

  /**
   * Add Cloud Drive actions to ECMS actions menu if they are not already there. This method adds Cloud Drive
   * actions saved in previous container execution (saved on container stop, see {@link #restoreViews()} ). If
   * no saved actions, then defaults will be added from configuration.
   * 
   * @throws Exception
   */
  protected void prepareViews() throws Exception {
    SessionProvider jcrSessions = SessionProvider.createSystemProvider();
    try {
      Session session = jcrSessions.getSession(DMS_SYSTEM_WORKSPACE, jcrService.getCurrentRepository());
      for (String view : VIEWS) {
        Node viewNode = manageView.getViewByName(view, jcrSessions);
        StringBuilder newActions = new StringBuilder();

        // read all already existing actions
        readViewActions(viewNode, EXO_BUTTONS, newActions);
        int menuLength = newActions.length();
        // read all user actions saved for CD
        readViewActions(viewNode, ECD_BUTTONS, newActions);

        if (newActions.length() == menuLength) {
          // no CD actions saved previously - add defaults
          StringBuilder cdActions = new StringBuilder();
          cdActions.append(' ');

          for (String cda : getDefaultActions()) {
            // doing some trick to fix the string: make first char lowercase
            String acs = uncapitalize(cda);
            if (newActions.indexOf(acs) < 0) {
              newActions.append(';');
              newActions.append(' ');
              newActions.append(acs);
            }
            if (cdActions.indexOf(acs) < 0) {
              cdActions.append(acs);
              cdActions.append(';');
            }
          }

          // save CD actions as initial user actions
          if (!viewNode.isNodeType(ECD_USER_BUTTONS)) {
            viewNode.addMixin(ECD_USER_BUTTONS);
          }
          viewNode.setProperty(ECD_BUTTONS, cdActions.toString().trim());
        }

        if (LOG.isDebugEnabled()) {
          LOG.debug("New buttons: " + newActions.toString());
        }
        viewNode.setProperty(EXO_BUTTONS, newActions.toString());
      }
      session.save();
    } finally {
      jcrSessions.close();
    }
  }

  /**
   * Remove Cloud Drive actions from ECMS actions menu and store them in dedicated property. We remove actions
   * to make the add-on uninstallation safe (don't leave our menu actions in the content).
   * 
   * @throws Exception
   */
  protected void restoreViews() throws Exception {
    SessionProvider jcrSessions = SessionProvider.createSystemProvider();
    try {
      Session session = jcrSessions.getSession(DMS_SYSTEM_WORKSPACE, jcrService.getCurrentRepository());
      for (String view : VIEWS) {
        Node viewNode = manageView.getViewByName(view, jcrSessions);
        StringBuilder newActions = new StringBuilder();
        StringBuilder cdActions = new StringBuilder();
        // split current actions, including customized by user, to Cloud Drive's and others
        splitViewActions(viewNode, EXO_BUTTONS, cdActions, newActions);
        if (LOG.isDebugEnabled()) {
          LOG.debug("Stored user buttons: " + cdActions.toString());
        }
        viewNode.setProperty(EXO_BUTTONS, newActions.toString());
        viewNode.setProperty(ECD_BUTTONS, cdActions.toString());
      }
      session.save();
    } finally {
      jcrSessions.close();
    }
  }

  protected String capitalize(String text) {
    char[] tc = text.toCharArray();
    if (tc.length > 0) {
      tc[0] = Character.toUpperCase(tc[0]);
    }
    return new String(tc);
  }

  protected String uncapitalize(String text) {
    char[] tc = text.toCharArray();
    if (tc.length > 0) {
      tc[0] = Character.toLowerCase(tc[0]);
    }
    return new String(tc);
  }

  @Override
  public void start() {
    try {
      prepareViews();

      // also register menu restorer... 
      // XXX yeah, we do nasty things for this as Startable.stop() works later, when JCR already stopped.
      List<?> repoContainers = ExoContainerContext.getCurrentContainer()
                                                  .getComponentInstancesOfType(RepositoryContainer.class);
      for (Object rco : repoContainers) {
        RepositoryContainer rc = (RepositoryContainer) rco;
        WorkspaceContainer wc = rc.getWorkspaceContainer(DMS_SYSTEM_WORKSPACE);
        if (wc != null) {
          wc.registerComponentInstance(new ViewRestorer());
        }
      }

      LOG.info("Cloud Drive actions successfully enabled");
    } catch (Exception e) {
      LOG.error("Error enabling Cloud Drive actions: " + e.getMessage(), e);
    }
  }

  @Override
  public void stop() {
    // nothing, see ViewRestorer class above
  }
}
