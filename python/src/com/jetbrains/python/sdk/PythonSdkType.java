package com.jetbrains.python.sdk;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.facet.Facet;
import com.intellij.facet.FacetConfiguration;
import com.intellij.facet.FacetManager;
import com.intellij.ide.DataManager;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.*;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.OrderRootType;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.JarFileSystem;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.ArrayUtil;
import com.jetbrains.python.PythonFileType;
import com.jetbrains.python.PythonHelpersLocator;
import com.jetbrains.python.facet.PythonFacetSettings;
import com.jetbrains.python.psi.LanguageLevel;
import org.jdom.Element;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author yole
 */
public class PythonSdkType extends SdkType {
  private static final Logger LOG = Logger.getInstance("#" + PythonSdkType.class.getName());

  public static PythonSdkType getInstance() {
    return SdkType.findInstance(PythonSdkType.class);    
  }

  public PythonSdkType() {
    super("Python SDK");
  }

  public Icon getIcon() {
    return PythonFileType.INSTANCE.getIcon();
  }

  public Icon getIconForAddAction() {
    return PythonFileType.INSTANCE.getIcon();
  }

  /**
   * Name of directory where skeleton files (despite the value) are stored.
   */
  public static final String SKELETON_DIR_NAME = "python_stubs";

  /**
   * @return name of builtins skeleton file; for Python 2.x it is '{@code __builtins__.py}'.
   */
  @NotNull @NonNls
  public String getBuiltinsFileName() {
    return "__builtin__.py"; // TODO: for py3k, return the appropriate name
  }

  @NonNls
  @Nullable
  public String suggestHomePath() {
    @NonNls final String PYTHON_STR = "python";
    TreeSet<String> candidates = new TreeSet<String>();
    if (SystemInfo.isWindows) {
      VirtualFile rootDir = LocalFileSystem.getInstance().findFileByPath("C:\\");
      if (rootDir != null) {
        VirtualFile[] topLevelDirs = rootDir.getChildren();
        for(VirtualFile dir: topLevelDirs) {
          if (dir.isDirectory() && dir.getName().toLowerCase().startsWith(PYTHON_STR)) {
            candidates.add(dir.getPath());
          }
        }
      }
    }
    else if (SystemInfo.isLinux) {
      VirtualFile rootDir = LocalFileSystem.getInstance().findFileByPath("/usr/lib");
      if (rootDir != null) {
        VirtualFile[] suspect_dirs = rootDir.getChildren();
        for(VirtualFile dir: suspect_dirs) {
          if (dir.isDirectory() && dir.getName().startsWith(PYTHON_STR)) {
            candidates.add(dir.getPath());
          }
        }
      }
    }
    else if (SystemInfo.isMac) {
      return "/Library/Frameworks/Python.framework/Versions/Current/";
    }

    if (candidates.size() > 0) {
      // return latest version
      String[] candidateArray = ArrayUtil.toStringArray(candidates);
      return candidateArray [candidateArray.length-1];
    }
    return null;
  }

  public boolean isValidSdkHome(final String path) {
    return isPythonSdkHome(path) || isJythonSdkHome(path);
  }

  /**
   * Checks if the path is a valid home.
   * Valid CPython home must contain some standard libraries. Of them we look for re.py, __future__.py and site-packages/.
   * @param path path to check.
   * @return true if paths points to a valid home.
   */
  @NonNls
  private static boolean isPythonSdkHome(final String path) {
    if (SystemInfo.isLinux) {
      // on Linux, Python SDK home points to the /lib directory of a particular Python version
      File f_re = new File(path, "re.py");
      File f_future = new File(path, "__future__.py");
      File f_site = new File(path, "site-packages"); // 2.x
      File f_dist = new File(path, "dist-packages"); // 3.0
      return (
        f_re.exists() &&
        f_future.exists() &&
        (f_site.exists() &&  f_site.isDirectory()) || (f_dist.exists() &&  f_dist.isDirectory()) 
      );
    }
    else {
      File f = getPythonBinaryPath(path);
      return f != null && f.exists();
    }
  }

  private static boolean isJythonSdkHome(final String path) {
    File f = getJythonBinaryPath(path);
    return f != null && f.exists();
  }

  @Nullable
  private static File getJythonBinaryPath(final String path) {
    if (SystemInfo.isWindows) {
      return new File(path, "jython.bat");
    }
    else if (SystemInfo.isMac) {
      return new File(new File(path, "bin"), "jython"); // TODO: maybe use a smarter way
    }
    else if (SystemInfo.isLinux) {
      File jy_binary = new File(path, "jython"); // probably /usr/bin/jython
      if (jy_binary.exists()) {
        return jy_binary;
      }
    }
    return null;
  }

  @Nullable
  @NonNls
  private static File getPythonBinaryPath(final String path) {
    if (SystemInfo.isWindows) {
      return new File(path, "python.exe");
    }
    else if (SystemInfo.isMac) {
      return new File(new File(path, "bin"), "python");
    }
    else if (SystemInfo.isLinux) {
      // most probably path is like "/usr/lib/pythonX.Y"; executable is most likely /usr/bin/pythonX.Y
      Matcher m = Pattern.compile(".*/(python\\d\\.\\d)").matcher(path);
      File py_binary;
      if (m.matches()) {
        String py_name = m.group(1); // $1
        py_binary = new File("/usr/bin/"+py_name); // XXX broken logic! can't match the lib to the bin
        if (py_binary.exists()) {
          return py_binary;
        }
      } else {
        py_binary = new File("/usr/bin/python"); // TODO: search in $PATH
      }
      if (py_binary.exists()) {
        return py_binary;
      }
    }
    return null;
  }

  public String suggestSdkName(final String currentSdkName, final String sdkHome) {
    String name = getVersionString(sdkHome);
    if (name == null) name = "Unknown at " + sdkHome; // last resort
    return name;
  }

  public AdditionalDataConfigurable createAdditionalDataConfigurable(final SdkModel sdkModel, final SdkModificator sdkModificator) {
    return null;
  }

  public void saveAdditionalData(final SdkAdditionalData additionalData, final Element additional) {
  }

  @Override
  public SdkAdditionalData loadAdditionalData(final Sdk currentSdk, final Element additional) {
    final String[] urls = currentSdk.getRootProvider().getUrls(OrderRootType.SOURCES);
    for (String url : urls) {
      if (url.contains(SKELETON_DIR_NAME)) {
        final String path = VfsUtil.urlToPath(url);
        File stubs_dir = new File(path);
        if (!stubs_dir.exists()) {
          generateBuiltinStubs(currentSdk.getHomePath(), path);
        }
        generateBinaryStubs(currentSdk.getHomePath(), path, null); // TODO: add a nice progress indicator somehow 
        break;
      }
    }
    return null;
  }

  
  @NonNls
  public String getPresentableName() {
    return "Python SDK";
  }
  
  public void setupSdkPaths(final Sdk sdk) {
    final Ref<SdkModificator> sdkModificatorRef = new Ref<SdkModificator>();
    final ProgressManager progman = ProgressManager.getInstance();
    final Project project = PlatformDataKeys.PROJECT.getData(DataManager.getInstance().getDataContext());
    final Task.Modal setup_task = new Task.Modal(project, "Setting up library files", false) {

      public void run(@NotNull final ProgressIndicator indicator) {
        sdkModificatorRef.set(setupSdkPathsUnderProgress(sdk, indicator));
      }

    };
    progman.run(setup_task);
    if (sdkModificatorRef.get() != null) sdkModificatorRef.get().commitChanges(); // commit in dispatch thread, not task's
  }


  protected SdkModificator setupSdkPathsUnderProgress(final Sdk sdk, @Nullable ProgressIndicator indicator) {
    final SdkModificator sdkModificator = sdk.getSdkModificator();
    setupSdkPaths(sdkModificator, indicator);

    return sdkModificator;
    //sdkModificator.commitChanges() must happen outside, and probably in a different thread.
  }

  /**
   * In which root type built-in skeletons are put.
   */
  public static final OrderRootType BUITLIN_ROOT_TYPE = OrderRootType.CLASSES;

  public static void setupSdkPaths(SdkModificator sdkModificator, ProgressIndicator indicator) {
    String sdk_path = sdkModificator.getHomePath();
    String bin_path = getInterpreterPath(sdk_path);
    @NonNls final String stubs_path =
        PathManager.getSystemPath() + File.separator + SKELETON_DIR_NAME + File.separator + sdk_path.hashCode() + File.separator;
    // we have a number of lib dirs, those listed in python's sys.path
    if (indicator != null) {
      indicator.setText("Adding library roots");
    }
    final List<String> paths = getSysPath(sdk_path, bin_path);
    if ((paths != null) && paths.size() > 0) {
      // add every path as root.
      for (String path: paths) {
        if (path.indexOf(File.separator) < 0) continue; // TODO: interpret possible 'special' paths reasonably
        if (indicator != null) {
          indicator.setText2(path);
        }
        VirtualFile child = LocalFileSystem.getInstance().findFileByPath(path);
        if (child != null) {
          @NonNls String suffix = child.getExtension();
          if (suffix != null) suffix = suffix.toLowerCase(); // Why on earth empty suffix is null and not ""?
          if ((!child.isDirectory()) && ("zip".equals(suffix) || "egg".equals(suffix))) {
            // a .zip / .egg file must have its root extracted first
            child = JarFileSystem.getInstance().getJarRootForLocalFile(child);
          }
          if (child != null) {
            sdkModificator.addRoot(child, OrderRootType.SOURCES);
            sdkModificator.addRoot(child, OrderRootType.CLASSES);
          }
        }
        else LOG.info("Bogus sys.path entry "+path);
      }
      if (indicator != null) {
        indicator.setText("Generating skeletons of __builtins__");
        indicator.setText2("");
      }
      generateBuiltinStubs(sdk_path, stubs_path);
      sdkModificator.addRoot(LocalFileSystem.getInstance().refreshAndFindFileByPath(stubs_path), BUITLIN_ROOT_TYPE);
    }
    generateBinaryStubs(sdk_path, stubs_path, indicator);
  }

  private static List<String> getSysPath(String sdk_path, String bin_path) {
    @NonNls String script = // a script printing sys.path
      "import sys\n"+
      "import os.path\n" +
      "for x in sys.path:\n"+
      "  if x != os.path.dirname(sys.argv [0]): sys.stdout.write(x+chr(10))";

    try {
      final File scriptFile = File.createTempFile("script", ".py");
      try {
        PrintStream out = new PrintStream(scriptFile);
        try {
          out.print(script);
        }
        finally {
          out.close();
        }

        return SdkUtil.getProcessOutput(sdk_path, new String[] {bin_path, scriptFile.getPath()}).getStdoutLines();
      }
      finally {
        FileUtil.delete(scriptFile);
      }
    }
    catch (IOException e) {
      LOG.info(e);
      return Collections.emptyList();
    }
  }

  @Nullable
  public String getVersionString(final String sdkHome) {
    final String binaryPath = getInterpreterPath(sdkHome);
    if (binaryPath == null){
      return null;
    }
    final boolean isJython = isJythonSdkHome(sdkHome);
    @NonNls String version_regexp, version_opt;
    if (isJython) {
      version_regexp = "(Jython \\S+) on .*";
      version_opt = "--version";
    }
    else { // CPython
      version_regexp = "(Python \\S+).*";
      version_opt = "-V";
    }
    Pattern pattern = Pattern.compile(version_regexp);
    String version = SdkUtil.getFirstMatch(SdkUtil.getProcessOutput(sdkHome, new String[] {binaryPath, version_opt}).getStderrLines(), pattern);
    return version;
  }

  @Nullable
  public static String getInterpreterPath(final String sdkHome) {
    final File file = isJythonSdkHome(sdkHome) ? getJythonBinaryPath(sdkHome) : getPythonBinaryPath(sdkHome);
    return file != null ? file.getPath() : null;
  }

  private final static String GENERATOR3 = "generator3.py";
  private final static String FIND_BINARIES = "find_binaries.py"; 

  public static void generateBuiltinStubs(String sdkPath, final String stubsRoot) {
    new File(stubsRoot).mkdirs();

    GeneralCommandLine commandLine = new GeneralCommandLine();
    commandLine.setExePath(getInterpreterPath(sdkPath));    // python
    commandLine.addParameter(PythonHelpersLocator.getHelperPath(GENERATOR3));

    commandLine.addParameter("-d");
    commandLine.addParameter(stubsRoot); // -d stubs_root
    commandLine.addParameter("-b"); // for builtins
    commandLine.addParameter("-u"); // for update-only mode
    try {
      final OSProcessHandler handler = new OSProcessHandler(commandLine.createProcess(), commandLine.getCommandLineString());
      handler.startNotify();
      handler.waitFor();
      handler.destroyProcess();
    }
    catch (ExecutionException e) {
      LOG.error(e);
    }
  }

  /**
   * (Re-)generates skeletons for all binary python modules. Up-to-date stubs not regenerated.
   * Does one module at a time: slower, but avoids certain conflicts.
   * @param sdkPath where to find interpreter.
   * @param stubsRoot where to put results (expected to exist).
   * @param indicator ProgressIndicator to update, or null.
   */
  public static void generateBinaryStubs(final String sdkPath, final String stubsRoot, ProgressIndicator indicator) {
    if (!new File(stubsRoot).exists()) return;
    if (indicator != null) {
      indicator.setText("Generating skeletons of binary libs");
    }
    final int RUN_TIMEOUT = 10 * 1000; // 10 seconds per call is plenty enough; anything more is clearly wrong.
    final String bin_path = getInterpreterPath(sdkPath);

    final ProcessOutput run_result =
      SdkUtil.getProcessOutput(sdkPath, new String[]{bin_path, PythonHelpersLocator.getHelperPath(FIND_BINARIES)});

    if (run_result.getExitCode() == 0) {
      for (String line : run_result.getStdoutLines()) {
        // line = "mod_name path"
        int cutpos = line.indexOf(' ');
        String modname = line.substring(0, cutpos);
        String mod_fname = modname.replace(".", File.separator); // "a.b.c" -> "a/b/c", no ext
        String fname = line.substring(cutpos + 1);
        //String ext = fname.substring(fname.lastIndexOf('.')); // no way ext is absent
        // check if it's fresh
        File f_orig = new File(fname);
        File f_skel = new File(stubsRoot + File.separator + mod_fname + ".py");
        if (f_orig.lastModified() >= f_skel.lastModified()) {
          // stale skeleton, rebuild
          if (indicator != null) {
            indicator.setText2(modname);
          }
          LOG.info("Skeleton for " + modname);
          final ProcessOutput gen_result = SdkUtil
            .getProcessOutput(sdkPath, new String[]{bin_path, PythonHelpersLocator.getHelperPath(GENERATOR3), "-d", stubsRoot, modname},
                              RUN_TIMEOUT);
          if (gen_result.getExitCode() != 0) {
            StringBuffer sb = new StringBuffer("Skeleton for ");
            sb.append(modname).append(" failed. stderr: --");
            for (String err_line : gen_result.getStderrLines()) sb.append(err_line).append("\n");
            sb.append("--");
            LOG.warn(sb.toString());
          }
        }
      }
    }
    else {
      StringBuffer sb = new StringBuffer();
      for (String err_line : run_result.getStderrLines()) sb.append(err_line).append("\n");
      LOG.error("failed to run " + FIND_BINARIES + ", exit code " + run_result.getExitCode() + ", stderr '" + sb.toString() + "'");
    }
  }

  public static List<Sdk> getAllSdks() {
    return ProjectJdkTable.getInstance().getSdksOfType(getInstance());
  }

  @Nullable
  public static Sdk findPythonSdk(Module module) {
    if (module == null) return null;
    final Sdk sdk = ModuleRootManager.getInstance(module).getSdk();
    if (sdk != null && sdk.getSdkType() instanceof PythonSdkType) return sdk;
    final Facet[] facets = FacetManager.getInstance(module).getAllFacets();
    for (Facet facet : facets) {
      final FacetConfiguration configuration = facet.getConfiguration();
      if (configuration instanceof PythonFacetSettings) {
        return ((PythonFacetSettings) configuration).getSdk();
      }
    }
    return null;
  }

  public static LanguageLevel getLanguageLevelForSdk(@Nullable Sdk sdk) {
    if (sdk != null) {
      String version = sdk.getVersionString();
      if (version != null) {
        // HACK rewrite in some nicer way?
        if (version.startsWith("Python ") || version.startsWith("Jython ")) {
          String pythonVersion = version.substring("Python ".length());
          return LanguageLevel.fromPythonVersion(pythonVersion);
        }
      }
    }
    return LanguageLevel.getDefault();
  }
}
