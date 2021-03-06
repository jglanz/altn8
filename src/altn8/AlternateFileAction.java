/*
 * Copyright 2012 The AltN8-Team
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
package altn8;

import altn8.filechooser.AlternateFilePopupChooser;
import altn8.filechooser.FileHandler;
import altn8.filematcher.AlternateFileMatcher;
import altn8.filematcher.AlternateFreeRegexFileMatcher;
import altn8.filematcher.AlternateGenericRegexFileMatcher;
import com.intellij.codeInsight.hint.HintManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ContentIterator;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * Our main action
 */
public class AlternateFileAction extends AnAction {
    private static VirtualFile getCurrentFile(AnActionEvent e) {
        return PlatformDataKeys.VIRTUAL_FILE.getData(e.getDataContext());
    }

    private static Project getProject(AnActionEvent e) {
        return PlatformDataKeys.PROJECT.getData(e.getDataContext());
    }

    private static Module getModule(AnActionEvent e) {
        return LangDataKeys.MODULE.getData(e.getDataContext());
    }

    private static Editor getEditor(AnActionEvent e) {
        return PlatformDataKeys.EDITOR.getData(e.getDataContext());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void actionPerformed(AnActionEvent e) {
        VirtualFile currentFile = getCurrentFile(e);
        if (currentFile != null) {
            Project currentProject = getProject(e);
            // find these in project
            List<AlternateFileGroup> fileGroups = findFiles(currentFile, currentProject, getModule(e));
            if (fileGroups.isEmpty()) {
                // nothing found
                Editor editor = getEditor(e);
                if (editor != null) { // fix issue 9: can only display hint if there is a editor instance
                    HintManager.getInstance().showInformationHint(editor, "No corresponding file(s) found");
                }
            } else {
                // open these...
                AlternateFilePopupChooser.prompt("Select the file(s) to open", fileGroups, currentProject, new FileHandler() {
                    public void processFile(@NotNull PsiFile psiFile) {
                        psiFile.navigate(true);
                    }
                });
            }
        }
    }

    /**
     * Find all corresponding files.<br>
     * If we found at minimunm one file in module, only module-files are listet. Else project files.
     */
    private List<AlternateFileGroup> findFiles(final VirtualFile currentFile, final Project project, final Module module) {
        AlternateConfiguration configuration = AlternateConfiguration.getInstance();

        final Map<String, AlternateFileGroup> projectWorkMap = new HashMap<String, AlternateFileGroup>();
        final Map<String, AlternateFileGroup> moduleWorkMap = new HashMap<String, AlternateFileGroup>();
        final String currentFilename = currentFile.getName();

        // get all fileMatchers
        final List<AlternateFileMatcher> fileMatchers = getFileMatchers(configuration, currentFilename);
        if (!fileMatchers.isEmpty()) {
            // iterate thru files
            final ProjectFileIndex projectFileIndex = ProjectRootManager.getInstance(project).getFileIndex();
            projectFileIndex.iterateContent(new ContentIterator() {
                private PsiManager psiManager = PsiManager.getInstance(project);
                public boolean processFile(VirtualFile fileOrDir) {
                    // if not a directory
                    if (!fileOrDir.isDirectory()) {
                        // and not currentFile...
                        if (!currentFilename.equals(fileOrDir.getName()) || !currentFile.getPath().equals(fileOrDir.getPath())) {
                            // iterate thru matchers and test...
                            for (AlternateFileMatcher fileMatcher : fileMatchers) {
                                if (fileMatcher.matches(fileOrDir.getName())) {
                                    PsiFile psiFile = psiManager.findFile(fileOrDir);
                                    if (psiFile != null) {
                                        Map<String, AlternateFileGroup> workMap = module.equals(projectFileIndex.getModuleForFile(fileOrDir)) ? moduleWorkMap : projectWorkMap;
                                        // add to module or project group
                                        String baseFilename = fileMatcher.getBaseFilename(fileOrDir.getName());
                                        String groupId = groupId(baseFilename);
                                        AlternateFileGroup group = workMap.get(groupId);
                                        if (group == null) {
                                            group = new AlternateFileGroup(groupId);
                                            workMap.put(groupId, group);
                                        }
                                        group.addFile(baseFilename, psiFile);
                                    }
                                    break;
                                }
                            }
                        }
                    }
                    return true;
                }
            });
        }

        // put groups into lists and sort (by baseFilename)
        List<AlternateFileGroup> moduleWorkList = new ArrayList<AlternateFileGroup>(moduleWorkMap.values());
        Collections.sort(moduleWorkList);
        List<AlternateFileGroup> projectWorkList = new ArrayList<AlternateFileGroup>(projectWorkMap.values());
        Collections.sort(projectWorkList);

        // Enhancement 5: If (at least) one corresponding file is found in the same module, show only files from module
        List<AlternateFileGroup> result;
        if (configuration.onlyFromModule) {
            // if moduleItems are presented, only moduleItems will be added, else projectItems
            result = !moduleWorkList.isEmpty() ? moduleWorkList : projectWorkList;
        } else {
            // add moduleItems then projectItems
            result = new ArrayList<AlternateFileGroup>(moduleWorkList);
            result.addAll(projectWorkList);
        }

        // move current file's group to top
        String currentGroupId = null;
        for (AlternateFileMatcher fileMatcher : fileMatchers) {
            if (fileMatcher.matches(currentFilename)) {
                currentGroupId = groupId(fileMatcher.getBaseFilename(currentFilename));
                break;
            }
        }
        if (currentGroupId != null && currentGroupId.length() > 0) {
            for (int i = 0, resultSize = result.size(); i < resultSize; i++) {
                AlternateFileGroup fileGroup = result.get(i);
                if (fileGroup.getGroupId().equals(currentGroupId)) {
                    if (i > 0) {
                        result.add(0, result.remove(i));
                    }
                    break;
                }
            }
        }
        // move group with no id to bottom
        for (int i = 0, resultSize = result.size(); i < resultSize; i++) {
            AlternateFileGroup fileGroup = result.get(i);
            if (fileGroup.getGroupId().length() == 0) {
                if (i < result.size() - 1) {
                    result.add(result.remove(i));
                }
                break;
            }
        }

        return result;
    }

    @NotNull
    private static String groupId(@NotNull String baseFilename) {
        // group id is lowecase of basefilename
        return baseFilename.toLowerCase(Locale.ENGLISH);
    }

    /**
     * @return  List with currently active FileMatchers ()
     */
    private List<AlternateFileMatcher> getFileMatchers(AlternateConfiguration configuration, String currentFilename) {
        List<AlternateFileMatcher> result = new ArrayList<AlternateFileMatcher>();
        // genericRegexActive (before freeRegexItems, because generic groups)
        if (configuration.genericRegexActive) {
            AlternateGenericRegexFileMatcher fileMatcher = new AlternateGenericRegexFileMatcher(currentFilename, configuration);
            if (fileMatcher.canProcess()) {
                result.add(fileMatcher);
            }
        }
        // freeRegexItems
        if (configuration.freeRegexActive) {
            AlternateFreeRegexFileMatcher fileMatcher = new AlternateFreeRegexFileMatcher(currentFilename, configuration);
            if (fileMatcher.canProcess()) {
                result.add(fileMatcher);
            }
        }
        return result;
    }
}
