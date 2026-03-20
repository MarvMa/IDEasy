package com.devonfw.tools.ide.tool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.devonfw.tools.ide.cli.CliException;
import com.devonfw.tools.ide.common.Tag;
import com.devonfw.tools.ide.context.IdeContext;
import com.devonfw.tools.ide.io.FileAccess;
import com.devonfw.tools.ide.io.FileCopyMode;
import com.devonfw.tools.ide.log.IdeLogLevel;
import com.devonfw.tools.ide.process.ProcessContext;
import com.devonfw.tools.ide.process.ProcessErrorHandling;
import com.devonfw.tools.ide.process.ProcessMode;
import com.devonfw.tools.ide.step.Step;
import com.devonfw.tools.ide.tool.repository.ToolRepository;
import com.devonfw.tools.ide.version.VersionIdentifier;

/**
 * {@link ToolCommandlet} that is installed globally.
 */
public abstract class GlobalToolCommandlet extends ToolCommandlet {

  private static final Logger LOG = LoggerFactory.getLogger(GlobalToolCommandlet.class);

  /**
   * The constructor.
   *
   * @param context the {@link IdeContext}.
   * @param tool the {@link #getName() tool name}.
   * @param tags the {@link #getTags() tags} classifying the tool. Should be created via {@link Set#of(Object) Set.of} method.
   */
  public GlobalToolCommandlet(IdeContext context, String tool, Set<Tag> tags) {

    super(context, tool, tags);
  }

  /**
   * Performs the installation or uninstallation of the {@link #getName() tool} via a package manager.
   *
   * @param silent {@code true} if called recursively to suppress verbose logging, {@code false} otherwise.
   * @param commandStrings commandStrings The package manager command strings to execute.
   * @return {@code true} if installation or uninstallation succeeds with any of the package manager commands, {@code false} otherwise.
   */
  protected boolean runWithPackageManager(boolean silent, String... commandStrings) {

    List<PackageManagerCommand> pmCommands = Arrays.stream(commandStrings).map(PackageManagerCommand::of).toList();
    return runWithPackageManager(silent, pmCommands);
  }

  /**
   * Performs the installation or uninstallation of the {@link #getName() tool} via a package manager.
   *
   * @param silent {@code true} if called recursively to suppress verbose logging, {@code false} otherwise.
   * @param pmCommands A list of {@link PackageManagerCommand} to be used for installation or uninstallation.
   * @return {@code true} if installation or uninstallation succeeds with any of the package manager commands, {@code false} otherwise.
   */
  protected boolean runWithPackageManager(boolean silent, List<PackageManagerCommand> pmCommands) {

    for (PackageManagerCommand pmCommand : pmCommands) {
      NativePackageManager packageManager = pmCommand.packageManager();
      Path packageManagerPath = this.context.getPath().findBinary(Path.of(packageManager.getBinaryName()));
      if (packageManagerPath == null || !Files.exists(packageManagerPath)) {
        LOG.debug("{} is not installed", packageManager);
        continue; // Skip to the next package manager command
      }

      if (executePackageManagerCommand(pmCommand, silent)) {
        return true; // Success
      }
    }
    return false; // None of the package manager commands were successful
  }

  private void logPackageManagerCommands(PackageManagerCommand pmCommand) {

    IdeLogLevel level = IdeLogLevel.INTERACTION;
    level.log(LOG, "We need to run the following privileged command(s):");
    for (String command : pmCommand.commands()) {
      level.log(LOG, command);
    }
    level.log(LOG, "This will require root permissions!");
  }

  /**
   * Executes the provided package manager command.
   *
   * @param pmCommand The {@link PackageManagerCommand} containing the commands to execute.
   * @param silent {@code true} if called recursively to suppress verbose logging, {@code false} otherwise.
   * @return {@code true} if the package manager commands execute successfully, {@code false} otherwise.
   */
  private boolean executePackageManagerCommand(PackageManagerCommand pmCommand, boolean silent) {

    String bashPath = this.context.findBashRequired().toString();
    logPackageManagerCommands(pmCommand);
    for (String command : pmCommand.commands()) {
      ProcessContext pc = this.context.newProcess().errorHandling(ProcessErrorHandling.LOG_WARNING).executable(bashPath).addArgs("-c", command);
      int exitCode = pc.run();
      if (exitCode != 0) {
        LOG.warn("{} command did not execute successfully", command);
        return false;
      }
    }

    if (!silent) {
      IdeLogLevel.SUCCESS.log(LOG, "Successfully installed {}", this.tool);
    }
    return true;
  }

  @Override
  protected boolean isExtract() {

    // for global tools we usually download installers and do not want to extract them (e.g. installer.msi file shall
    // not be extracted)
    return false;
  }

  @Override
  protected ToolInstallation doInstall(ToolInstallRequest request) {

    VersionIdentifier resolvedVersion = request.getRequested().getResolvedVersion();
    if (this.context.getSystemInfo().isLinux()) {
      // on Linux global tools are typically installed via the package manager of the OS
      // if a global tool implements this method to return at least one PackageManagerCommand, then we install this way.
      List<PackageManagerCommand> commands = getInstallPackageManagerCommands();
      if (!commands.isEmpty()) {
        boolean newInstallation = runWithPackageManager(request.isSilent(), commands);
        Path rootDir = getInstallationPath(getConfiguredEdition(), resolvedVersion);
        return createToolInstallation(rootDir, resolvedVersion, newInstallation, request.getProcessContext(), request.isAdditionalInstallation());
      }
    }

    ToolEdition toolEdition = getToolWithConfiguredEdition();
    Path installationPath = getInstallationPath(toolEdition.edition(), resolvedVersion);
    // if force mode is enabled, go through with the installation even if the tool is already installed
    if ((installationPath != null) && !this.context.isForceMode()) {
      return toolAlreadyInstalled(request);
    }
    String edition = toolEdition.edition();
    ToolRepository toolRepository = this.context.getDefaultToolRepository();
    resolvedVersion = cveCheck(request);
    // download and install the global tool
    FileAccess fileAccess = this.context.getFileAccess();
    Path target = toolRepository.download(this.tool, edition, resolvedVersion, this);
    Path executable = target;
    Path tmpDir = null;
    boolean extract = isExtract();
    if (extract) {
      tmpDir = fileAccess.createTempDir(getName());
      Path downloadBinaryPath = tmpDir.resolve(target.getFileName());
      fileAccess.extract(target, downloadBinaryPath);
      executable = fileAccess.findFirst(downloadBinaryPath, Files::isExecutable, false);
      if (this.context.getSystemInfo().isMac() && isMacAppBundlePath(executable)) {
        Path resolvedExecutable = resolveExecutableFromMacApp(downloadBinaryPath);
        if (resolvedExecutable != null) {
          executable = resolvedExecutable;
          LOG.info("Resolved executable path on MacOS: {}", executable);
        }
      }
      if (executable == null) {
        throw new CliException("Could not find executable file in extracted archive " + target + " for tool " + this.tool + "!");
      }
      if (this.context.getSystemInfo().isMac() && isMacAppExecutable(executable)) {
        Path managedInstallationPath = getManagedInstallationPath(edition, resolvedVersion);
        if (managedInstallationPath != null) {
          executable = stageMacAppBundle(executable, managedInstallationPath);
        }
      }
    }
    boolean keepExtractedMacAppBundle =
        (tmpDir != null) && this.context.getSystemInfo().isMac() && isMacAppExecutable(executable) && executable.startsWith(tmpDir);
    ProcessContext pc = this.context.newProcess().errorHandling(ProcessErrorHandling.LOG_WARNING).executable(executable);
    int exitCode = pc.run(ProcessMode.BACKGROUND).getExitCode();
    if (tmpDir != null) {
      if (keepExtractedMacAppBundle) {
        LOG.info("Keeping extracted MacOS app bundle at {} to avoid runtime framework resolution issues.", tmpDir);
      } else {
        fileAccess.delete(tmpDir);
      }
    }
    if (exitCode == 0) {
      IdeLogLevel.SUCCESS.log(LOG, "Installation process for {} in version {} has started", this.tool, resolvedVersion);
      Step step = request.getStep();
      if (step != null) {
        step.success(true);
      }
    } else {
      throw new CliException("Installation process for " + this.tool + " in version " + resolvedVersion + " failed with exit code " + exitCode + "!");
    }
    installationPath = getInstallationPath(toolEdition.edition(), resolvedVersion);
    if (installationPath == null && this.context.getSystemInfo().isMac()) {
      installationPath = resolveMacInstallationPathFromExecutable(executable);
      LOG.info("Resolved installation path on MacOS: {}", installationPath);
    }
    if (installationPath == null) {
      LOG.warn("Could not find binary {} on PATH after installation.", getBinaryName());
    }
    return createToolInstallation(installationPath, resolvedVersion, true, pc, false);
  }

  /**
   * @param edition the installed edition.
   * @param resolvedVersion the installed version.
   * @return managed installation path for global tools or {@code null} to keep default behavior.
   */
  protected Path getManagedInstallationPath(String edition, VersionIdentifier resolvedVersion) {

    return null;
  }

  private Path stageMacAppBundle(Path executable, Path managedInstallationPath) {

    Path appBundle = findMacAppBundle(executable);
    if (appBundle == null) {
      return executable;
    }
    FileAccess fileAccess = this.context.getFileAccess();
    fileAccess.delete(managedInstallationPath);
    fileAccess.mkdirs(managedInstallationPath);
    Path targetAppBundle = managedInstallationPath.resolve(appBundle.getFileName());
    fileAccess.copy(appBundle, targetAppBundle, FileCopyMode.COPY_TREE_OVERRIDE_TREE);
    Path managedExecutable = resolveExecutableFromMacApp(managedInstallationPath);
    if (managedExecutable == null) {
      throw new CliException("Could not resolve executable in staged MacOS app bundle " + targetAppBundle + " for tool " + this.tool + "!");
    }
    LOG.info("Staged MacOS app bundle to {}", targetAppBundle);
    return managedExecutable;
  }

  private Path findMacAppBundle(Path executable) {

    Path current = executable;
    while (current != null) {
      Path fileName = current.getFileName();
      if ((fileName != null) && fileName.toString().endsWith(".app")) {
        return current;
      }
      current = current.getParent();
    }
    return null;
  }

  private Path resolveMacInstallationPathFromExecutable(Path executable) {

    Path appBundle = findMacAppBundle(executable);
    if (appBundle == null) {
      return (executable == null) ? null : executable.getParent();
    }
    Path parent = appBundle.getParent();
    if (parent != null) {
      return parent;
    }
    return appBundle;
  }

  private boolean isMacAppBundlePath(Path executable) {

    return (executable == null) || Files.isDirectory(executable) || ((executable.getFileName() != null) && executable.getFileName().toString()
        .endsWith(".app"));
  }

  private boolean isMacAppExecutable(Path executable) {

    if (executable == null) {
      return false;
    }
    Path current = executable;
    while (current != null) {
      Path fileName = current.getFileName();
      if ((fileName != null) && fileName.toString().endsWith(".app")) {
        return true;
      }
      current = current.getParent();
    }
    return false;
  }


  private Path resolveExecutableFromMacApp(Path extractedRoot) {

    Path appDir = getMacOsHelper().findAppDir(extractedRoot);
    if (appDir == null) {
      return null;
    }
    Path linkDir = getMacOsHelper().findLinkDir(appDir, getBinaryName());
    Path executable = this.context.getFileAccess().getBinPath(linkDir).resolve(getBinaryName());
    if (isExecutableFile(executable)) {
      return executable;
    }
    Path macOsDir = appDir.resolve(IdeContext.FOLDER_CONTENTS).resolve("MacOS");
    if (Files.isDirectory(macOsDir)) {
      Path binaryFromMacOsDir = this.context.getFileAccess().findFirst(macOsDir, this::isExecutableFile, false);
      if (binaryFromMacOsDir != null) {
        return binaryFromMacOsDir;
      }
      Path binaryFromMacOsDirWithoutExecFlag = this.context.getFileAccess().findFirst(macOsDir, Files::isRegularFile, false);
      if (binaryFromMacOsDirWithoutExecFlag != null) {
        // Ensure copied app binaries can be launched even if executable bits were dropped.
        this.context.getFileAccess().makeExecutable(binaryFromMacOsDirWithoutExecFlag);
        if (isExecutableFile(binaryFromMacOsDirWithoutExecFlag)) {
          return binaryFromMacOsDirWithoutExecFlag;
        }
      }
    }
    return this.context.getFileAccess().findFirst(appDir, this::isExecutableFile, true);
  }

  private boolean isExecutableFile(Path path) {

    return (path != null) && Files.isRegularFile(path) && Files.isExecutable(path);
  }

  /**
   * @return the {@link List} of {@link PackageManagerCommand}s to use on Linux to install this tool. If empty, no package manager installation will be
   *     triggered on Linux.
   */
  protected List<PackageManagerCommand> getInstallPackageManagerCommands() {
    return List.of();
  }

  @Override
  public VersionIdentifier getInstalledVersion() {
    //TODO: handle "get-version <globaltool>"
    LOG.error("Couldn't get installed version of " + this.getName());
    return null;
  }

  @Override
  public String getInstalledEdition() {
    //TODO: handle "get-edition <globaltool>"
    LOG.error("Couldn't get installed edition of " + this.getName());
    return null;
  }

  @Override
  protected Path getInstallationPath(String edition, VersionIdentifier resolvedVersion) {

    Path toolBinary = Path.of(getBinaryName());
    Path binaryPath = this.context.getPath().findBinary(toolBinary);
    if ((binaryPath == toolBinary) || !Files.exists(binaryPath)) {
      return null;
    }
    Path binPath = binaryPath.getParent();
    if (binPath == null) {
      return null;
    }
    return this.context.getFileAccess().getBinParentPath(binPath);
  }


  @Override
  public void uninstall() {
    //TODO: handle "uninstall <globaltool>"
    LOG.error("Couldn't uninstall " + this.getName());
  }
}
