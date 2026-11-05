// SPDX-License-Identifier: MIT
// Copyright (C) 2026 8796n <info@8796.jp>
package jp.nyan.spatialwall.probe;

import android.content.Context;
import android.os.Process;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileLock;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.util.HashSet;
import java.util.Set;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

// Fixed app-private store. RDP's per-use biometric key and AtomicFile backup policy are separate.
final class SpatialCredentialStorage implements SpatialCredentials.Storage {
    private static final String ALIAS = "spatial-credentials-v2";
    private static final Set<String> OWNERS = new HashSet<>();
    private final File directory, file, pending;
    private final String alias, ownerName;
    private FileDescriptor directoryFd;
    private FileOutputStream owner;
    private FileLock lock;
    private boolean expectFile, closed;
    private boolean reserved;

    static SpatialCredentials open(Context context) throws IOException {
        return new SpatialCredentials(new SpatialCredentialStorage(directory(context), ALIAS));
    }

    private static File directory(Context context) throws IOException {
        File root = context.getNoBackupFilesDir();
        if (root == null || !root.isAbsolute()) throw new IOException("Private storage is unavailable");
        return new File(root, "spatial-credentials-v2");
    }

    // Only after explicit user confirmation and after all credential/channel owners were closed.
    static SpatialCredentials reset(Context context) throws IOException {
        return reset(new SpatialCredentialStorage(directory(context), ALIAS));
    }

    static SpatialCredentials reset(SpatialCredentialStorage storage) throws IOException {
        try {
            storage.requireOpen();
            keyStore().deleteEntry(storage.alias); // Invalidate old ciphertext even if cleanup fails.
            for (File target : new File[]{storage.file, storage.pending, new File(storage.directory, "state.enc.bak")}) {
                StructStat info = stat(target);
                if (info == null) continue;
                if (info.st_uid != Process.myUid() || !(OsConstants.S_ISREG(info.st_mode) || OsConstants.S_ISLNK(info.st_mode)))
                    throw new IOException("Unexpected credential reset target");
                Os.remove(target.getAbsolutePath()); // lstat + unlink never follow a symlink.
            }
            Os.fsync(storage.directoryFd);
            storage.expectFile = false;
            return new SpatialCredentials(storage);
        } catch (GeneralSecurityException | ErrnoException | IOException | RuntimeException failure) {
            storage.close();
            throw new IOException("Could not reset Spatial Wall credentials", failure);
        }
    }

    // Package-private injection permits isolated instrumentation files/alias, never a product option.
    SpatialCredentialStorage(File directory, String alias) throws IOException {
        if (directory == null || !directory.isAbsolute() || alias == null || alias.isEmpty()) throw new IllegalArgumentException();
        this.directory = directory; this.alias = alias;
        ownerName = directory.getCanonicalPath();
        file = new File(directory, "state.enc"); pending = new File(directory, "state.pending");
        try {
            // Closing a second descriptor can release process-wide POSIX record locks.
            // Reject another in-process owner before opening the lock inode at all.
            synchronized (OWNERS) {
                if (!OWNERS.add(ownerName)) throw new IOException("Credential store already has an owner");
                reserved = true;
            }
            try { Os.mkdir(directory.getAbsolutePath(), 0700); }
            catch (ErrnoException failure) { if (failure.errno != OsConstants.EEXIST) throw failure; }
            directoryFd = Os.open(directory.getAbsolutePath(),
                    OsConstants.O_RDONLY | OsConstants.O_NONBLOCK | OsConstants.O_NOFOLLOW | OsConstants.O_CLOEXEC, 0);
            StructStat info = Os.fstat(directoryFd);
            if (!OsConstants.S_ISDIR(info.st_mode) || info.st_uid != Process.myUid() || (info.st_mode & 0777) != 0700)
                throw new IOException("Credential directory is not private");
            FileDescriptor descriptor = Os.open(new File(directory, "owner.lock").getAbsolutePath(),
                    OsConstants.O_RDWR | OsConstants.O_CREAT | OsConstants.O_NOFOLLOW | OsConstants.O_CLOEXEC, 0600);
            owner = new FileOutputStream(descriptor);
            privateFile(Os.fstat(descriptor));
            lock = owner.getChannel().tryLock();
            if (lock == null) throw new IOException("Credential store already has an owner");
        } catch (ErrnoException | IOException | RuntimeException failure) {
            close();
            throw new IOException("Could not own Spatial Wall credential storage", failure);
        }
    }

    @Override public byte[] read() throws IOException {
        requireOpen();
        FileDescriptor descriptor;
        try {
            // Never let an older backup become a new authoritative credential snapshot.
            if (stat(new File(directory, "state.enc.bak")) != null)
                throw new IOException("Unexpected credential backup file");
            try {
                descriptor = Os.open(file.getAbsolutePath(),
                        OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW | OsConstants.O_NONBLOCK | OsConstants.O_CLOEXEC, 0);
            } catch (ErrnoException failure) {
                if (failure.errno == OsConstants.ENOENT) return null;
                throw failure;
            }
        } catch (ErrnoException failure) { throw new IOException("Could not read credential file", failure); }
        try (FileInputStream input = new FileInputStream(descriptor)) {
            StructStat info = Os.fstat(descriptor);
            privateFile(info);
            if (info.st_size < 37 || info.st_size > SpatialCredentials.MAX_ENCRYPTED)
                throw new IOException("Invalid encrypted credential file size");
            byte[] value = new byte[(int) info.st_size];
            boolean complete = false;
            try {
                for (int offset = 0; offset < value.length;) {
                    int count = input.read(value, offset, value.length - offset);
                    if (count <= 0) throw new IOException("Truncated credential file");
                    offset += count;
                }
                if (input.read() != -1) throw new IOException("Credential file changed while reading");
                expectFile = true;
                complete = true;
                return value;
            } finally { if (!complete) PairingClient.wipe(value); }
        } catch (ErrnoException failure) { throw new IOException("Could not inspect credential file", failure); }
    }

    @Override public void write(byte[] encrypted) throws IOException {
        requireOpen();
        if (encrypted == null || encrypted.length < 37 || encrypted.length > SpatialCredentials.MAX_ENCRYPTED)
            throw new IOException("Invalid encrypted credential file size");
        boolean created = false;
        try {
            StructStat current = stat(file);
            if (current != null) privateFile(current);
            else if (expectFile) throw new IOException("Credential file disappeared");
            StructStat previousPending = stat(pending);
            if (previousPending != null) {
                privateFile(previousPending);
                Os.remove(pending.getAbsolutePath()); // Only an abandoned ciphertext temp, never a recovery source.
            }
            FileDescriptor descriptor = Os.open(pending.getAbsolutePath(),
                    OsConstants.O_WRONLY | OsConstants.O_CREAT | OsConstants.O_EXCL
                            | OsConstants.O_NOFOLLOW | OsConstants.O_CLOEXEC, 0600);
            created = true;
            try (FileOutputStream output = new FileOutputStream(descriptor)) {
                privateFile(Os.fstat(descriptor));
                output.write(encrypted);
                output.flush();
                Os.fsync(descriptor);
            }
            Os.rename(pending.getAbsolutePath(), file.getAbsolutePath());
            created = false;
            expectFile = true;
            Os.fsync(directoryFd);
        } catch (ErrnoException failure) { throw new IOException("Could not replace credential file", failure); }
        finally {
            if (created) try { Os.remove(pending.getAbsolutePath()); } catch (ErrnoException ignored) {}
        }
    }

    @Override public SecretKey existingKey() throws GeneralSecurityException, IOException {
        requireOpen();
        KeyStore store = keyStore();
        Key key = store.getKey(alias, null);
        if (key == null && !store.containsAlias(alias)) return null;
        if (!(key instanceof SecretKey) || !"AES".equals(key.getAlgorithm()))
            throw new GeneralSecurityException("Saved credential key is unavailable");
        return (SecretKey) key;
    }
    @Override public SecretKey createKey() throws GeneralSecurityException, IOException {
        requireOpen();
        if (keyStore().containsAlias(alias)) throw new GeneralSecurityException("Credential key already exists");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false) // Preserve background reconnect while the screen is locked.
                .build());
        return generator.generateKey();
    }
    private static KeyStore keyStore() throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null); return store;
    }
    private static StructStat stat(File file) throws ErrnoException {
        try { return Os.lstat(file.getAbsolutePath()); }
        catch (ErrnoException failure) { if (failure.errno == OsConstants.ENOENT) return null; throw failure; }
    }
    private static void privateFile(StructStat info) throws IOException {
        if (!OsConstants.S_ISREG(info.st_mode) || info.st_uid != Process.myUid()
                || info.st_nlink != 1 || (info.st_mode & 0777) != 0600)
            throw new IOException("Credential file is not private");
    }
    private void requireOpen() throws IOException {
        if (closed) throw new IOException("Credential storage is closed");
    }
    @Override public void close() {
        if (closed) return;
        closed = true;
        if (lock != null) try { lock.release(); } catch (IOException ignored) {}
        if (owner != null) try { owner.close(); } catch (IOException ignored) {}
        if (directoryFd != null) try { Os.close(directoryFd); } catch (ErrnoException ignored) {}
        if (reserved) synchronized (OWNERS) { OWNERS.remove(ownerName); reserved = false; }
        // The lock inode remains, so another process cannot acquire a different lock file.
    }
}
