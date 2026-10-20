package io.github.hectorvent.floci.services.codebuild;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CodeBuildSourceCopyTest {

    @Test
    void retriesSourceUploadWithFreshTarAfterBrokenPipe(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("buildspec.yml"), "version: 0.2\n");
        DockerClient docker = mock(DockerClient.class);
        CopyArchiveToContainerCmd command = mock(CopyArchiveToContainerCmd.class, RETURNS_SELF);
        when(docker.copyArchiveToContainerCmd("build-container")).thenReturn(command);
        List<InputStream> streams = new ArrayList<>();
        AtomicReference<InputStream> currentStream = new AtomicReference<>();
        when(command.withTarInputStream(any())).thenAnswer(invocation -> {
            InputStream stream = invocation.getArgument(0);
            streams.add(stream);
            currentStream.set(stream);
            return command;
        });
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<String> uploadedSource = new AtomicReference<>();
        when(command.exec()).thenAnswer(invocation -> {
            if (attempts.incrementAndGet() == 1) {
                currentStream.get().readNBytes(8);
                throw new RuntimeException(new IOException("Broken pipe"));
            }
            byte[] archive = currentStream.get().readAllBytes();
            try (TarArchiveInputStream tar = new TarArchiveInputStream(new ByteArrayInputStream(archive))) {
                TarArchiveEntry entry = tar.getNextEntry();
                assertEquals("buildspec.yml", entry.getName());
                uploadedSource.set(new String(tar.readAllBytes(), StandardCharsets.UTF_8));
            }
            return null;
        });

        new CodeBuildRunner(docker, null, null, null, null, null, null, null, null, null, null)
                .copySourceToContainer("build-container", workspace, "/codebuild/output/src/src");

        assertEquals(2, attempts.get());
        assertEquals(2, streams.size());
        assertNotSame(streams.get(0), streams.get(1));
        assertEquals("version: 0.2\n", uploadedSource.get());
        verify(command, times(2)).withRemotePath("/codebuild/output/src/src");
    }

    @Test
    void propagatesFailureAfterTransientRetryBudgetIsExhausted(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("buildspec.yml"), "version: 0.2\n");
        DockerClient docker = mock(DockerClient.class);
        CopyArchiveToContainerCmd command = mock(CopyArchiveToContainerCmd.class, RETURNS_SELF);
        when(docker.copyArchiveToContainerCmd("build-container")).thenReturn(command);
        AtomicInteger attempts = new AtomicInteger();
        when(command.exec()).thenAnswer(invocation -> {
            attempts.incrementAndGet();
            throw new RuntimeException(new IOException("Broken pipe"));
        });

        RuntimeException failure = assertThrows(RuntimeException.class, () ->
                new CodeBuildRunner(docker, null, null, null, null, null, null, null, null, null, null)
                        .copySourceToContainer("build-container", workspace, "/codebuild/output/src/src"));

        assertEquals(6, attempts.get());
        assertTrue(failure.getMessage().contains("codebuild-source"));
    }

    @Test
    void propagatesSourceUploadFailureInsteadOfRunningWithoutSource(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("buildspec.yml"), "version: 0.2\n");
        DockerClient docker = mock(DockerClient.class);
        CopyArchiveToContainerCmd command = mock(CopyArchiveToContainerCmd.class, RETURNS_SELF);
        when(docker.copyArchiveToContainerCmd("build-container")).thenReturn(command);
        AtomicInteger attempts = new AtomicInteger();
        when(command.exec()).thenAnswer(invocation -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("No such container");
        });

        RuntimeException failure = assertThrows(RuntimeException.class, () ->
                new CodeBuildRunner(docker, null, null, null, null, null, null, null, null, null, null)
                        .copySourceToContainer("build-container", workspace, "/codebuild/output/src/src"));

        assertTrue(failure.getMessage().contains("No such container"));
        assertEquals(1, attempts.get());
    }

    @Test
    void skipsEmptyWorkspace(@TempDir Path workspace) {
        DockerClient docker = mock(DockerClient.class);

        new CodeBuildRunner(docker, null, null, null, null, null, null, null, null, null, null)
                .copySourceToContainer("build-container", workspace, "/codebuild/output/src/src");

        verifyNoInteractions(docker);
    }
}
