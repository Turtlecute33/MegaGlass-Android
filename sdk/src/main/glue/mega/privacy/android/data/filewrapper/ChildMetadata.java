package mega.privacy.android.data.filewrapper;

/**
 * One child of a folder, as returned by {@link FileWrapper#getChildrenWithMetadata()}.
 * The native SDK reads these fields through JNI by name and type: do not rename them.
 */
public final class ChildMetadata {
    /** Absolute path or content URI of the child; FileWrapper.getFromUri() accepts it. */
    public final String uri;
    public final String name;
    public final boolean isFolder;
    /** Size in bytes, 0 for folders. */
    public final long size;
    /** Last modification time in milliseconds since the epoch, 0 when unknown. */
    public final long lastModified;
    /** Absolute filesystem path, or "" when the storage provider does not expose one. */
    public final String path;

    public ChildMetadata(String uri, String name, boolean isFolder, long size, long lastModified, String path) {
        this.uri = uri;
        this.name = name;
        this.isFolder = isFolder;
        this.size = size;
        this.lastModified = lastModified;
        this.path = path;
    }
}
