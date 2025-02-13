/***
 * CERT Kaiju
 * Copyright 2021 Carnegie Mellon University.
 *
 * NO WARRANTY. THIS CARNEGIE MELLON UNIVERSITY AND SOFTWARE ENGINEERING
 * INSTITUTE MATERIAL IS FURNISHED ON AN "AS-IS" BASIS. CARNEGIE MELLON UNIVERSITY
 * MAKES NO WARRANTIES OF ANY KIND, EITHER EXPRESSED OR IMPLIED, AS TO ANY MATTER
 * INCLUDING, BUT NOT LIMITED TO, WARRANTY OF FITNESS FOR PURPOSE OR
 * MERCHANTABILITY, EXCLUSIVITY, OR RESULTS OBTAINED FROM USE OF THE MATERIAL.
 * CARNEGIE MELLON UNIVERSITY DOES NOT MAKE ANY WARRANTY OF ANY KIND WITH RESPECT
 * TO FREEDOM FROM PATENT, TRADEMARK, OR COPYRIGHT INFRINGEMENT.
 *
 * Released under a BSD (SEI)-style license, please see LICENSE.md or contact permission@sei.cmu.edu for full terms.
 *
 * [DISTRIBUTION STATEMENT A] This material has been approved for public release and unlimited distribution.
 * Please see Copyright notice for non-US Government use and distribution.
 *
 * Carnegie Mellon (R) and CERT (R) are registered in the U.S. Patent and Trademark Office by Carnegie Mellon University.
 *
 * This Software includes and/or makes use of the following Third-Party Software subject to its own license:
 * 1. OpenJDK (http://openjdk.java.net/legal/gplv2+ce.html) Copyright 2021 Oracle.
 * 2. Ghidra (https://github.com/NationalSecurityAgency/ghidra/blob/master/LICENSE) Copyright 2021 National Security Administration.
 * 3. GSON (https://github.com/google/gson/blob/master/LICENSE) Copyright 2020 Google.
 * 4. JUnit (https://github.com/junit-team/junit5/blob/main/LICENSE.md) Copyright 2020 JUnit Team.
 * 5. Gradle (https://github.com/gradle/gradle/blob/master/LICENSE) Copyright 2021 Gradle Inc.
 * 6. markdown-gradle-plugin (https://github.com/kordamp/markdown-gradle-plugin/blob/master/LICENSE.txt) Copyright 2020 Andres Almiray.
 * 7. Z3 (https://github.com/Z3Prover/z3/blob/master/LICENSE.txt) Copyright 2021 Microsoft Corporation.
 * 8. jopt-simple (https://github.com/jopt-simple/jopt-simple/blob/master/LICENSE.txt) Copyright 2021 Paul R. Holser, Jr.
 *
 * DM21-0792
 */

package kaiju.tools.ooanalyzer;

import java.awt.event.KeyEvent;
import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import javax.swing.JOptionPane;

import docking.ActionContext;
import docking.action.DockingAction;
import docking.action.KeyBindingData;
import docking.action.MenuData;
import ghidra.app.plugin.PluginCategoryNames;
import ghidra.app.plugin.ProgramPlugin;
import ghidra.app.script.AskDialog;
import ghidra.framework.cmd.BackgroundCommand;
import ghidra.framework.model.DomainObject;
import ghidra.framework.plugintool.PluginInfo;
import ghidra.framework.plugintool.PluginTool;
import ghidra.framework.plugintool.util.PluginStatus;
import ghidra.program.model.listing.Program;
import ghidra.util.Msg;
import ghidra.util.task.TaskMonitor;

import kaiju.common.*;
import kaiju.tools.ooanalyzer.jsontypes.OOAnalyzerClassType;
import kaiju.tools.ooanalyzer.jsontypes.OOAnalyzerJsonRoot;


//@formatter:off
@PluginInfo(
    status = PluginStatus.RELEASED,
    packageName = KaijuPluginPackage.NAME,
    category = PluginCategoryNames.ANALYSIS,
    shortDescription = "CERT OOAnalyzer JSON results importer.",
    description = "Import and apply CERT OOAnalyzer results to a Ghidra project."
)
//@formatter:on
/**
 * The main OOAnalyzer Plugin
 *
 */
public class OOAnalyzerGhidraPlugin extends ProgramPlugin {

  private static final String CERT_MENU = "&Kaiju";
  public static final String NAME = "OOAnalyzer Importer";
  private DockingAction ooaAction = null;

  /**
   * Setup the plugin
   */
  public OOAnalyzerGhidraPlugin(PluginTool tool) {
    super(tool, true, true);
    setupActions();
  }

  public void configureAndExecute() {
    configureAndExecute (null, null);
  }

  public ImportCommand configureAndExecute(File json, Boolean useOOAnalyzerNamespace) {
    ImportCommand bgcmd = new ImportCommand(json, useOOAnalyzerNamespace);

    tool.executeBackgroundCommand(bgcmd, currentProgram);

    return bgcmd;
  }


  class ImportCommand extends BackgroundCommand {

    File jsonFile;
    Boolean useOOAnalyzerNamespace;
    Boolean completed = false;
    Boolean testEnv = false;

    ImportCommand() {
      super("OOAnalyzer Import", true, true, false);
    }

    ImportCommand(File jsonFile_, Boolean useOOAnalyzerNamespace_) {
      super("OOAnalyzer Import", true, true, false);
      jsonFile = jsonFile_;
      useOOAnalyzerNamespace = useOOAnalyzerNamespace_;
      testEnv = jsonFile != null && useOOAnalyzerNamespace != null;
    }

    @Override
    public void taskCompleted () {
      Msg.debug (this, "Task completed!");
      completed = true;
    }

    public boolean getCompleted () {
      return completed;
    }

    @Override
    public boolean applyTo(DomainObject obj, TaskMonitor monitor) {
      cmdConfigureAndExecute(monitor);
      return true;
    }

    /**
     * Run the script
     */
    private void cmdConfigureAndExecute(TaskMonitor monitor) {

      // Refuse to continue unless program has been analyzed
      if (!currentProgram.getOptions(Program.PROGRAM_INFO).getBoolean(Program.ANALYZED, false)) {
        Msg.showError(this, null, "Error", "Please run auto analysis before using the OOAnalyzer Ghidra Plugin");
        return;
      }

      if (!testEnv) {
        OOAnalyzerDialog ooaDialog = new OOAnalyzerDialog("OOAnalyzer Settings");
        OOAnalyzerGhidraPlugin.this.tool.showDialog(ooaDialog);
        jsonFile = ooaDialog.getJsonFile();
        useOOAnalyzerNamespace = ooaDialog.useOOAnalyzerNamespace ();

        if (ooaDialog.isCancelled()) {
          return;
        } else if (jsonFile == null) {
          Msg.showError(this, null, "Error", "Invalid JSON file");
          return;
        }
      }

      // String baseJsonName = jsonFile.getName().split("\\.(?=[^\\.]+$)")[0];
      // String baseProgName = currentProgram.getName().split("\\.(?=[^\\.]+$)")[0];
      // if (baseJsonName.equalsIgnoreCase(baseProgName) == false) {
      //   if (0 != JOptionPane.showConfirmDialog(null, "JSON file name mismatch",
      //                                          "The selected JSON name does not match the executable, continue?", JOptionPane.YES_NO_OPTION,
      //                                          JOptionPane.WARNING_MESSAGE)) {
      //     Msg.info(null, "OOAnalyzer cancelled");
      //     return;
      //   }
      // }

      Optional<OOAnalyzerJsonRoot> optJson = OOAnalyzer.parseJsonFile(jsonFile);
      if (optJson.isEmpty()) {
        Msg.showError(this, null, "Error", "Could not load/parse JSON file " + jsonFile.getName());
        return;
      }

      String jsonMd5 = optJson.get ().getMd5 ();
      String ghidraMd5 = currentProgram.getExecutableMD5 ();

      if (jsonMd5 != null && !jsonMd5.equalsIgnoreCase (ghidraMd5)) {
        if (0 /* yes */  != JOptionPane.showConfirmDialog(null,
                                                          String.format ("There was a hash mismatch. This JSON may be for a different file '%s'.  Do you want to continue?", optJson.get ().getFilename ()),
                                                          "MD5 mismatch",
                                                          JOptionPane.YES_NO_OPTION,
                                                          JOptionPane.WARNING_MESSAGE)) {
          Msg.info (null, "OOAnalyzer import canceled by user after MD5 mismatch");
          return;
        }
      }

      // Actually run the plugin
      int result = -1;
      int tid = OOAnalyzerGhidraPlugin.this.currentProgram.startTransaction("OOA");
      try {
        OOAnalyzer ooa = new OOAnalyzer (currentProgram,
                                         useOOAnalyzerNamespace);
        ooa.setMonitor (monitor);
        result = ooa.analyzeClasses(optJson.get().getStructures ());
        if (monitor.isCancelled()) {
          // Do nothing
        } else if (result < 0) {
          Msg.showError(this, null, "Error", "No current program for OOAnalyzer");
          if (testEnv)
            throw new IllegalStateException("No current program for OOAnalyzer");
        } else if (result > 0) {
          Msg.showInfo(this, null, "Results", "OOAnalyzer loaded " + result + " classes");
        } else {
          Msg.showInfo(this, null, "Results", "OOAnalyzer could not find any classes");
          if (testEnv)
            throw new IllegalStateException("OOAnalyzer could not find any classes");
        }
      } finally {
        OOAnalyzerGhidraPlugin.this.currentProgram.endTransaction(tid, result > 0);
      }
    }
  }

  private void setupActions() {

    ooaAction = new DockingAction("OOAnalyzer", getName()) {

        @Override
        public void actionPerformed(ActionContext context) {
          configureAndExecute();
        }
      };

    final String ooaActionName = OOAnalyzerGhidraPlugin.NAME;
    final String ooaMenu = CERT_MENU;

    ooaAction.setMenuBarData(new MenuData(new String[] { ooaMenu, ooaActionName }, null,
                                          OOAnalyzerGhidraPlugin.NAME, MenuData.NO_MNEMONIC, null));

    ooaAction.setKeyBindingData(new KeyBindingData(KeyEvent.VK_F12, 0));
    ooaAction.setEnabled(false);
    ooaAction.markHelpUnnecessary();

    tool.addAction(ooaAction);
  }

  /**
   * Called when program activated
   */
  @Override
  protected void programActivated(Program activatedProgram) {
    ooaAction.setEnabled(true);
  }

}
