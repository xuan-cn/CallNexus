package org.dromara.license;

import org.dromara.common.core.exception.ServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

@Component
class DeploymentIdentityStore {

    private static final String FILE_NAME = "deployment.identity";
    private static final long MAX_FILE_SIZE = 4096;

    private final String directory;

    DeploymentIdentityStore(@Value("${callnexus.license.identity-dir:${CALLNEXUS_LICENSE_IDENTITY_DIR:}}") String directory) {
        this.directory = directory;
    }

    void requireConfigured() {
        if (directory.isBlank() || !Path.of(directory).isAbsolute()) {
            throw new ServiceException("请先在授权管理实例上配置持久化身份目录的绝对路径");
        }
    }

    LocalIdentity read() throws IOException {
        Path root = requirePrivateDirectory();
        Path file = root.resolve(FILE_NAME);
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            return null;
        }
        if (!attributes.isRegularFile() || attributes.size() > MAX_FILE_SIZE) {
            throw new ServiceException("部署身份文件无效，请检查持久化目录或恢复备份");
        }
        if (supportsPosix(root) && !Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS)
            .equals(PosixFilePermissions.fromString("rw-------"))) {
            throw new ServiceException("部署身份文件权限不安全，请设置为 0600");
        }
        List<String> fields = Files.readAllLines(file, StandardCharsets.US_ASCII);
        if (fields.size() != 3 || fields.stream().anyMatch(String::isBlank)) {
            throw new ServiceException("部署身份文件不完整，请恢复备份");
        }
        syncDirectory(root);
        return new LocalIdentity(fields.get(0), fields.get(1), fields.get(2));
    }

    void write(LocalIdentity identity) throws IOException {
        Path root = requirePrivateDirectory();
        Path file = root.resolve(FILE_NAME);
        if (!Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new ServiceException("部署身份文件已存在或无法检查，拒绝覆盖");
        }
        Path temporary = supportsPosix(root)
            ? Files.createTempFile(root, ".identity-", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            : Files.createTempFile(root, ".identity-", ".tmp");
        try {
            byte[] encoded = String.join("\n", identity.deploymentId(), identity.publicKey(), identity.privateKey()).getBytes(StandardCharsets.US_ASCII);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(encoded);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE);
            syncDirectory(root);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Path requirePrivateDirectory() throws IOException {
        Path root = Path.of(directory);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new ServiceException("部署身份目录不存在或不是实际目录，请先配置持久化挂载");
        }
        if (supportsPosix(root) && !Files.getPosixFilePermissions(root, LinkOption.NOFOLLOW_LINKS)
            .equals(PosixFilePermissions.fromString("rwx------"))) {
            throw new ServiceException("部署身份目录权限不安全，请设置为 0700");
        }
        return root;
    }

    private static boolean supportsPosix(Path directory) throws IOException {
        return Files.getFileStore(directory).supportsFileAttributeView("posix");
    }

    private static void syncDirectory(Path directory) throws IOException {
        if (supportsPosix(directory)) {
            try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
                channel.force(true);
            }
        }
    }

    record LocalIdentity(String deploymentId, String publicKey, String privateKey) {
        @Override
        public String toString() {
            return "LocalIdentity[redacted]";
        }
    }
}
