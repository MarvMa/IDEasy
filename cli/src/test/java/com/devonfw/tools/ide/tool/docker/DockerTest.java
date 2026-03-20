package com.devonfw.tools.ide.tool.docker;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.devonfw.tools.ide.context.AbstractIdeContextTest;
import com.devonfw.tools.ide.context.IdeTestContext;
import com.devonfw.tools.ide.os.SystemInfoMock;
import com.devonfw.tools.ide.version.VersionIdentifier;

/**
 * Test of {@link Docker} class.
 */
class DockerTest extends AbstractIdeContextTest {

  @Test
  void testMacInstallationPathUsesManagedSoftwareFolder() {

    // arrange
    IdeTestContext context = newContext(PROJECT_BASIC);
    context.setSystemInfo(SystemInfoMock.MAC_ARM64);
    Docker docker = new Docker(context);
    VersionIdentifier version = VersionIdentifier.of("1.22.0");
    Path expectedManagedPath = context.getSoftwarePath().resolve("docker");

    // act + assert
    assertThat(docker.getManagedInstallationPath("rancher", version)).isEqualTo(expectedManagedPath);

    context.getFileAccess().mkdirs(expectedManagedPath);
    assertThat(docker.getInstallationPath("rancher", version)).isEqualTo(expectedManagedPath);
  }

  @Test
  void testManagedInstallationPathOnlyForMac() {

    // arrange
    IdeTestContext context = newContext(PROJECT_BASIC);
    context.setSystemInfo(SystemInfoMock.WINDOWS_X64);
    Docker docker = new Docker(context);

    // act + assert
    assertThat(docker.getManagedInstallationPath("rancher", VersionIdentifier.of("1.22.0"))).isNull();
  }
}

