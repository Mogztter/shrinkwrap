/*
 * JBoss, Home of Professional Open Source
 * Copyright 2012, Red Hat Middleware LLC, and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.shrinkwrap.api.nio.file;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.DirectoryStream.Filter;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.spi.FileSystemProvider;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ArchivePath;
import org.jboss.shrinkwrap.api.ArchivePaths;
import org.jboss.shrinkwrap.api.GenericArchive;
import org.jboss.shrinkwrap.api.ShrinkWrap;

/**
 * {@link FileSystemProvider} implementation for ShrinkWrap {@link Archive}s.
 *
 * @author <a href="mailto:alr@jboss.org">Andrew Lee Rubinger</a>
 */
public class ShrinkWrapFileSystemProvider extends FileSystemProvider {

    /**
     * Logger
     */
    private static final Logger log = Logger.getLogger(ShrinkWrapFileSystemProvider.class.getName());

    /**
     * Scheme
     */
    private static final String SCHEME = "shrinkwrap";

    /**
     * Environment key for creating a new {@link FileSystem} denoting the archive
     */
    private static final String ENV_KEY_ARCHIVE = "archive";

    /**
     * Open file systems, keyed by the {@link Archive#getId()}
     */
    private final ConcurrentMap<String, ShrinkWrapFileSystem> createdFileSystems = new ConcurrentHashMap<>(1);

    /**
     * Lock for creation of a new filesystem and other tasks which should block until this op has completed
     */
    private final ReentrantLock createNewFsLock = new ReentrantLock();

    /**
     * {@inheritDoc}
     *
     * @see java.nio.file.spi.FileSystemProvider#getScheme()
     */
    @Override
    public String getScheme() {
        return SCHEME;
    }

    /**
     * {@inheritDoc}
     *
     * @see java.nio.file.spi.FileSystemProvider#newFileSystem(java.net.URI, java.util.Map)
     */
    @Override
    public FileSystem newFileSystem(final URI uri, final Map<String, ?> env) throws IOException {

        // Precondition checks
        if (uri == null) {
            throw new IllegalArgumentException("URI must be specified");
        }
        if (env == null) {
            throw new IllegalArgumentException("Environment must be specified");
        }

        // Scheme is correct?
        final String scheme = uri.getScheme();
        if (!scheme.equals(SCHEME)) {
            throw new IllegalArgumentException(ShrinkWrapFileSystem.class.getSimpleName()
                + " supports URI with scheme " + SCHEME + " only.");
        }

        // Lock
        createNewFsLock.lock();

        // Get ID of the archive
        final String id = uri.getHost();

        // Archive is provided?
        Archive<?> archive = null;
        final Object archiveArg = env.get(ENV_KEY_ARCHIVE);
        if (archiveArg != null) {
            try {
                archive = Archive.class.cast(archiveArg);
                // Ensure the name of the archive matches the host specified in the URI
                if (!archive.getId().equals(id)) {
                    throw new IllegalArgumentException("Specified archive " + archive.toString()
                        + " does not have name matching the host of specified URI: " + uri.toString());
                }
                if (log.isLoggable(Level.FINER)) {
                    log.finer("Found archive supplied by environment: " + archive.toString());
                }
            } catch (final ClassCastException cce) {
                // User specified the wrong type, translate and rethrow
                throw new IllegalArgumentException("Unexpected argument passed into environment under key "
                    + ENV_KEY_ARCHIVE + ": " + archiveArg);
            }
        }

        // Need to create a new archive?
        if (archive == null) {
            archive = ShrinkWrap.create(GenericArchive.class, id);
            if (log.isLoggable(Level.FINER)) {
                log.finer("Created new archive: " + archive.toString() + " for URI " + uri.toString());
            }
        }

        // Exists?
        final ShrinkWrapFileSystem existsFs = this.createdFileSystems.get(archive.getId());
        if (existsFs != null && existsFs.isOpen()) {
            throw new FileSystemAlreadyExistsException("File System for URI " + uri.toString() + " already exists: "
                + existsFs.toString());
        } else if (existsFs != null && !existsFs.isOpen()) {
            if (log.isLoggable(Level.FINE)) {
                log.fine("Attempting to create a file system for URI " + uri.toString()
                    + ", and one has been made but is closed; it will be replaced by a new one.");
            }
        }

        // Make a new FileSystem
        final ShrinkWrapFileSystem newFs = new ShrinkWrapFileSystem(this, archive);
        if (log.isLoggable(Level.FINE)) {
            log.fine("Created new filesystem: " + newFs.toString() + " for URI " + uri.toString());
        }
        this.createdFileSystems.put(archive.getId(), newFs);

        // Unlock
        createNewFsLock.unlock();

        // Return
        return newFs;
    }

    /**
     * {@inheritDoc}
     *
     * @see java.nio.file.spi.FileSystemProvider#getFileSystem(java.net.URI)
     */
    @Override
    public FileSystem getFileSystem(final URI uri) {

        // Get specified archive by name

        // Let an in-flight creation finish
        createNewFsLock.lock();

        // Get open FS
        final FileSystem fs = this.createdFileSystems.get(uri.getHost());

        // No longer need locking
        createNewFsLock.unlock();

        // If not already created
        if (fs == null) {
            throw new FileSystemNotFoundException("Could not find an open file system with URI: " + uri.toString()
                + "; try creating a new file system?");
        }

        // Return
        return fs;
    }

    /**
     * {@inheritDoc}
     *
     * @see java.nio.file.spi.FileSystemProvider#getPath(java.net.URI)
     */
    @Override
    public Path getPath(final URI uri) {

        // Precondition checks
        if (uri == null) {
            throw new IllegalArgumentException("URI must be specified");
        }

        // ID exists? We're referencing a previously-opened archive?
        final String id = uri.getHost();
        ShrinkWrapFileSystem fs = null;
        if (id != null && id.length() > 0) {
            fs = this.createdFileSystems.get(id);
        }

        // Check that the file system exists
        if (fs == null) {
            throw new FileSystemNotFoundException("Could not find a previously-created filesystem with URI: "
                + uri.toString());
        }
        // Check FS is open
        if (!fs.isOpen()) {
            throw new FileSystemNotFoundException("File System for URI: " + uri.toString()
                + " is closed; create a new one to re-mount.");
        }

        final String pathComponent = uri.getPath();
        final ArchivePath archivePath = ArchivePaths.create(pathComponent);
        final Path path = new ShrinkWrapPath(archivePath, fs);

        // Return
        return path;
    }

    /*
     * (non-Javadoc)
     *
     * @see java.nio.file.spi.FileSystemProvider#newByteChannel(java.nio.file.Path, java.util.Set,
     * java.nio.file.attribute.FileAttribute<?>[])
     */
    @Override
    public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs)
        throws IOException {

        // TODO Auto-generated method stub
        return null;
    }

    /*
     * (non-Javadoc)
     *
     * @see java.nio.file.spi.FileSystemProvider#newDirectoryStream(java.nio.file.Path,
     * java.nio.file.DirectoryStream.Filter)
     */
    @Override
    public DirectoryStream<Path> newDirectoryStream(Path dir, Filter<? super Path> filter) throws IOException {
        // TODO Auto-generated method stub
        return null;
    }

    /*
     * (non-Javadoc)
     *
     * @see java.nio.file.spi.FileSystemProvider#createDirectory(java.nio.file.Path,
     * java.nio.file.attribute.FileAttribute<?>[])
     */
    @Override
    public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
        // TODO Auto-generated method stub

    }

    /**
     * {@inheritDoc}
     *
     * @see java.nio.file.spi.FileSystemProvider#delete(java.nio.file.Path)
     */
    @Override
    public void delete(final Path path) throws IOException {

        assert path != null : "Path must be specified";
        final Archive<?> archive = this.getArchive(path);
        final String pathString = path.toString();
        if (!archive.contains(pathString)) {
            throw new NoSuchFileException(path + " does not exist in " + archive);
        }
        // TODO Check for directory. If so, empty? If not empty, DirectoryNotEmptyException
        archive.delete(pathString);
        if (log.isLoggable(Level.FINEST)) {
            log.finest("Deleted " + path.toString() + " from " + archive.toString());
        }
    }

    /**
     * Obtains the underlying archive associated with the specified Path
     *
     * @param path
     * @return
     */
    private Archive<?> getArchive(final Path path) {
        assert path != null : "Path must be specified";
        final FileSystem fs = path.getFileSystem();
        assert fs != null : "File system is null";
        // Could be user error in this case, passing in a Path from another provider
        if (!(fs instanceof ShrinkWrapFileSystem)) {
            throw new IllegalArgumentException("This path is not associated with a "
                + ShrinkWrapFileSystem.class.getSimpleName());
        }
        final ShrinkWrapFileSystem swfs = (ShrinkWrapFileSystem) fs;
        final Archive<?> archive = swfs.getArchive();
        assert archive != null : "No archive associated with file system";
        return archive;
    }

    /*
     * (non-Javadoc)
     *
     * @see java.nio.file.spi.FileSystemProvider#copy(java.nio.file.Path, java.nio.file.Path,
     * java.nio.file.CopyOption[])
     */
    @Override
    public void copy(Path source, Path target, CopyOption... options) throws IOException {
        // TODO Auto-generated method stub

    }

    /*
     * (non-Javadoc)
     *
     * @see java.nio.file.spi.FileSystemProvider#move(java.nio.file.Path, java.nio.file.Path,
     * java.nio.file.CopyOption[])
     */
    @Override
    public void move(Path source, Path target, CopyOption... options) throws IOException {
        // TODO Auto-generated method stub

    }

    /*
     * (non-Javadoc)
     *
     * @see java.nio.file.spi.FileSystemProvider#isSameFile(java.nio.file.Path, java.nio.file.Path)
     */
    @Override
    public boolean isSameFile(Path path, Path path2) throws IOException {
        // TODO Auto-generated method stub
        return false;
    }

    /*
     * (non-Javadoc)
     *
     * @see java.nio.file.spi.FileSystemProvider#isHidden(java.nio.file.Path)
     */
    @Override
    public boolean isHidden(Path path) throws IOException {
        // TODO Auto-generated method stub
        return false;
    }

    /*
     * (non-Javadoc)
     *
     * @see java.nio.file.spi.FileSystemProvider#getFileStore(java.nio.file.Path)
     */
    @Override
    public FileStore getFileStore(Path path) throws IOException {
        // TODO Auto-generated method stub
        return null;
    }

    /*
     * (non-Javadoc)
     *
     * @see java.nio.file.spi.FileSystemProvider#checkAccess(java.nio.file.Path, java.nio.file.AccessMode[])
     */
    @Override
    public void checkAccess(Path path, AccessMode... modes) throws IOException {
        // TODO Auto-generated method stub

    }

    /*
     * (non-Javadoc)
     *
     * @see java.nio.file.spi.FileSystemProvider#getFileAttributeView(java.nio.file.Path, java.lang.Class,
     * java.nio.file.LinkOption[])
     */
    @Override
    public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type, LinkOption... options) {
        // TODO Auto-generated method stub
        return null;
    }

    /*
     * (non-Javadoc)
     *
     * @see java.nio.file.spi.FileSystemProvider#readAttributes(java.nio.file.Path, java.lang.Class,
     * java.nio.file.LinkOption[])
     */
    @Override
    public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options)
        throws IOException {
        // TODO Auto-generated method stub
        return null;
    }

    /*
     * (non-Javadoc)
     *
     * @see java.nio.file.spi.FileSystemProvider#readAttributes(java.nio.file.Path, java.lang.String,
     * java.nio.file.LinkOption[])
     */
    @Override
    public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) throws IOException {
        // TODO Auto-generated method stub
        return null;
    }

    /*
     * (non-Javadoc)
     *
     * @see java.nio.file.spi.FileSystemProvider#setAttribute(java.nio.file.Path, java.lang.String, java.lang.Object,
     * java.nio.file.LinkOption[])
     */
    @Override
    public void setAttribute(Path path, String attribute, Object value, LinkOption... options) throws IOException {
        // TODO Auto-generated method stub

    }

}
