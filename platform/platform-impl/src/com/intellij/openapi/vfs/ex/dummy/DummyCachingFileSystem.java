/*
 * Copyright 2000-2014 JetBrains s.r.o.
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
package com.intellij.openapi.vfs.ex.dummy;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.project.ProjectManagerAdapter;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.containers.BidirectionalMap;
import com.intellij.util.containers.ConcurrentFactoryMap;
import com.intellij.util.containers.FactoryMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import java.io.IOException;
import java.util.Collection;
import java.util.List;

/**
 * @author gregsh
 */
public abstract class DummyCachingFileSystem<T extends VirtualFile> extends DummyFileSystem {
  private static final Logger LOG = Logger.getInstance("com.intellij.openapi.vfs.ex.dummy.DummyCachingFileSystem");

  private final String myProtocol;

  private final BidirectionalMap<Project, String> myProject2Id = new BidirectionalMap<Project, String>();

  private final FactoryMap<String, T> myCachedFiles = new ConcurrentFactoryMap<String, T>() {
    @Override
    protected T create(String key) {
      if (ApplicationManager.getApplication().isUnitTestMode()) {
        //noinspection TestOnlyProblems
        cleanup();
        initProjectMap();
      }
      return findFileByPathInner(key);
    }

    @Override
    public T get(Object key) {
      T file = super.get(key);
      if (file != null && !file.isValid()) {
        remove(key);
        return super.get(key);
      }
      return file;
    }
  };

  public DummyCachingFileSystem(String protocol) {
    myProtocol = protocol;

    final Application application = ApplicationManager.getApplication();
    application.getMessageBus().connect(application).subscribe(ProjectManager.TOPIC, new ProjectManagerAdapter() {
      @Override
      public void projectOpened(final Project project) {
        onProjectOpened(project);
      }

      @Override
      public void projectClosed(final Project project) {
        onProjectClosed(project);
      }
    });
    initProjectMap();
  }

  @NotNull
  @Override
  public final String getProtocol() {
    return myProtocol;
  }

  @Override
  @Nullable
  public final VirtualFile createRoot(String name) {
    return null;
  }

  @Override
  public final T findFileByPath(@NotNull String path) {
    return myCachedFiles.get(path);
  }

  @Override
  @NotNull
  public String extractPresentableUrl(@NotNull String path) {
    VirtualFile file = findFileByPath(path);
    return file != null ? file.getPresentableName() : super.extractPresentableUrl(path);
  }

  protected abstract T findFileByPathInner(@NotNull String path);

  protected void doRenameFile(VirtualFile vFile, String newName) {
    throw new UnsupportedOperationException("not implemented");
  }

  @Nullable
  public Project getProject(String projectId) {
    List<Project> list = myProject2Id.getKeysByValue(projectId);
    return list == null || list.size() > 1 ? null : list.get(0);
  }

  @NotNull
  public Collection<T> getCachedFiles() {
    return myCachedFiles.notNullValues();
  }

  public void onProjectClosed(Project project) {
    clearCache();
    myProject2Id.remove(project);
  }

  public void onProjectOpened(Project project) {
    clearCache();
    String projectId = project.getLocationHash();
    myProject2Id.put(project, projectId);

    List<Project> projects = myProject2Id.getKeysByValue(projectId);
    if (projects != null && projects.size() > 1) {
      LOG.error("project " + projectId + " already registered: " + projects);
    }
  }

  private void initProjectMap() {
    for (Project project : ProjectManager.getInstance().getOpenProjects()) {
      if (project.isOpen()) onProjectOpened(project);
    }
  }

  protected void clearCache() {
    myCachedFiles.clear();
  }

  @TestOnly
  public void cleanup() {
    clearCache();
    myProject2Id.clear();
  }

  @Override
  public void renameFile(Object requestor, @NotNull VirtualFile vFile, @NotNull String newName) throws IOException {
    String oldName = vFile.getName();
    beforeFileRename(vFile, requestor, oldName, newName);
    try {
      doRenameFile(vFile, newName);
    }
    finally {
      fileRenamed(vFile, requestor, oldName, newName);
    }
  }

  protected void beforeFileRename(@NotNull VirtualFile file, Object requestor, @NotNull String oldName, @NotNull String newName) {
    fireBeforePropertyChange(requestor, file, VirtualFile.PROP_NAME, oldName, newName);
    myCachedFiles.remove(file.getPath());
  }

  protected void fileRenamed(@NotNull VirtualFile file, Object requestor, String oldName, String newName) {
    //noinspection unchecked
    myCachedFiles.put(file.getPath(), (T)file);
    firePropertyChanged(requestor, file, VirtualFile.PROP_NAME, oldName, newName);
  }

  protected static String escapeSlash(String name) {
    return name == null ? "" : StringUtil.replace(name, "/", "&slash;");
  }

  protected static String unescapeSlash(String name) {
    return name == null ? "" : StringUtil.replace(name, "&slash;", "/");
  }
}
