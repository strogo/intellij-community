/*
 * Copyright 2000-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.ide;

import com.intellij.ide.impl.ProjectUtil;
import com.intellij.idea.StartupUtil;
import com.intellij.openapi.application.*;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.Extensions;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.fileEditor.impl.NonProjectFileWritingAccessProvider;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.openapi.wm.IdeFrame;
import com.intellij.platform.PlatformProjectOpenProcessor;
import com.intellij.project.ProjectKt;
import com.intellij.projectImport.ProjectOpenProcessor;
import com.intellij.util.ArrayUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.EnumSet;
import java.util.List;

/**
 * @author yole
 */
public class CommandLineProcessor {
  private static final Logger LOG = Logger.getInstance("#com.intellij.ide.CommandLineProcessor");

  private CommandLineProcessor() { }

  @Nullable
  private static Project doOpenFileOrProject(VirtualFile file, String path) {
    ProjectOpenProcessor provider = ProjectOpenProcessor.getImportProvider(file);
    if (provider instanceof PlatformProjectOpenProcessor && !file.isDirectory()) {
      // HACK: PlatformProjectOpenProcessor agrees to open anything
      provider = null;
    }
    if (provider != null || ProjectKt.isValidProjectPath(path)) {
      Project project = ProjectUtil.openOrImport(path, null, true);
      if (project == null) {
        Messages.showErrorDialog("Cannot open project '" + path + "'", "Cannot Open Project");
      }
      return project;
    }
    else {
      return doOpenFile(file, -1, false);
    }
  }

  @Nullable
  private static Project doOpenFile(VirtualFile file, int line, boolean tempProject) {
    Project[] projects = ProjectManager.getInstance().getOpenProjects();
    if (projects.length == 0 || tempProject) {
      PlatformProjectOpenProcessor processor = PlatformProjectOpenProcessor.getInstanceIfItExists();
      if (processor != null) {
        EnumSet<PlatformProjectOpenProcessor.Option> options = EnumSet.noneOf(PlatformProjectOpenProcessor.Option.class);
        if (tempProject) {
          options.add(PlatformProjectOpenProcessor.Option.TEMP_PROJECT);
          options.add(PlatformProjectOpenProcessor.Option.FORCE_NEW_FRAME);
        }
        return PlatformProjectOpenProcessor.doOpenProject(file, null, line, null, options);
      }
      Messages.showErrorDialog("No project found to open file in", "Cannot Open File");
      return null;
    }
    else {
      NonProjectFileWritingAccessProvider.allowWriting(file);
      Project project = findBestProject(file, projects);
      OpenFileDescriptor descriptor = line == -1 ? new OpenFileDescriptor(project, file) : new OpenFileDescriptor(project, file, line-1, 0);
      descriptor.navigate(true);
      return project;
    }
  }

  @NotNull
  private static Project findBestProject(VirtualFile file, Project[] projects) {
    for (Project aProject : projects) {
      if (ProjectRootManager.getInstance(aProject).getFileIndex().isInContent(file)) {
        return aProject;
      }
    }
    IdeFrame frame = IdeFocusManager.getGlobalInstance().getLastFocusedFrame();
    Project project = frame == null ? null : frame.getProject();
    return project != null ? project : projects[0];
  }

  @Nullable
  public static Project processExternalCommandLine(List<String> args, @Nullable String currentDirectory) {
    if (args.size() > 0) {
      LOG.info("External command line:");
      LOG.info("Dir: " + currentDirectory);
      for (String arg : args) {
        LOG.info(arg);
      }
    }
    LOG.info("-----");

    if (args.size() > 0) {
      final String command = args.get(0);
      for (ApplicationStarter starter : Extensions.getExtensions(ApplicationStarter.EP_NAME)) {
        if (command.equals(starter.getCommandName()) &&
            starter instanceof ApplicationStarterEx &&
            ((ApplicationStarterEx)starter).canProcessExternalCommandLine()) {
          LOG.info("Processing command with " + starter);
          ((ApplicationStarterEx)starter).processExternalCommandLine(ArrayUtil.toStringArray(args), currentDirectory);
          return null;
        }
      }

      if (command.startsWith(JetBrainsProtocolHandler.PROTOCOL)) {
        try {
          final String url = URLDecoder.decode(command, "UTF-8");
          JetBrainsProtocolHandler.processJetBrainsLauncherParameters(url);
          ApplicationManager.getApplication().invokeLater(() -> JBProtocolCommand.handleCurrentCommand());
        }
        catch (UnsupportedEncodingException e) {
          LOG.error(e);
        }

        return null;
      }
    }

    Project lastOpenedProject = null;
    int line = -1;
    boolean tempProject = false;

    for (int i = 0, argsSize = args.size(); i < argsSize; i++) {
      String arg = args.get(i);
      if (arg.equals(StartupUtil.NO_SPLASH)) {
        continue;
      }

      if (arg.equals("-l") || arg.equals("--line")) {
        //noinspection AssignmentToForLoopParameter
        i++;
        if (i == args.size()) {
          break;
        }
        try {
          line = Integer.parseInt(args.get(i));
        }
        catch (NumberFormatException e) {
          line = -1;
        }
        continue;
      }

      if (arg.equals("--temp-project")) {
        tempProject = true;
        continue;
      }

      if (StringUtil.isQuotedString(arg)) {
        arg = StringUtil.unquoteString(arg);
      }
      if (!new File(arg).isAbsolute()) {
        arg = currentDirectory != null ? new File(currentDirectory, arg).getAbsolutePath() : new File(arg).getAbsolutePath();
      }

      VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByPath(arg);
      if (line != -1 || tempProject) {
        if (file != null && !file.isDirectory()) {
          lastOpenedProject = doOpenFile(file, line, tempProject);
        }
        else {
          Messages.showErrorDialog("Cannot find file '" + arg + "'", "Cannot Find File");
        }
      }
      else {
        if (file != null) {
          lastOpenedProject = doOpenFileOrProject(file, arg);
        }
        else {
          Messages.showErrorDialog("Cannot find file '" + arg + "'", "Cannot Find File");
        }
      }

      line = -1;
      tempProject = false;
    }

    return lastOpenedProject;
  }
}