package mega.privacy.android.data.filewrapper;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.OpenableColumns;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local file access for the native SDK, which calls this class through JNI
 * (src/android/androidFileSystem.cpp) for every local file operation.
 *
 * A wrapper stands for a file or folder that existed when the wrapper was made. It is either a
 * plain filesystem path, handled with java.io.File, or a content:// URI of the Storage Access
 * Framework, handled with DocumentsContract. Instances are immutable, so the SDK threads can use
 * them concurrently.
 *
 * The native side finds these methods by name and JNI signature. They do not throw: a failure
 * gives null, false or a negative file descriptor.
 */
public final class FileWrapper {
    private static final String TAG = "FileWrapper";
    private static final String CONTENT_SCHEME = "content://";
    private static final String EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents";
    private static final String DOWNLOADS_AUTHORITY = "com.android.providers.downloads.documents";
    private static final String MIME_TYPE_FILE = "application/octet-stream";

    /** getFileDescriptor() result that the SDK reports as EACCES. Other negative values become ENOENT. */
    private static final int FD_ACCESS_DENIED = -2;
    private static final int FD_ERROR = -1;
    /**
     * Write access is read-write, creates the file if missing and never truncates: the SDK writes
     * at offsets with pwrite, resumes partial downloads and truncates by itself when it must.
     */
    private static final int MODE_WRITE = ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE;
    private static final String SAF_MODE_WRITE = "rw";
    private static final String SAF_MODE_READ = "r";

    private static final String[] DOCUMENT_COLUMNS = {Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE};
    private static final String[] OPENABLE_COLUMNS = {OpenableColumns.DISPLAY_NAME};
    private static final String[] CHILD_COLUMNS = {
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED};

    private static volatile Context appContext;
    /** Directory of each external storage root: "primary", "home" or a volume UUID. */
    private static final Map<String, String> rootDirectories = new ConcurrentHashMap<>();

    /** Value of getUri(): the path, or the content URI. */
    private final String uri;
    /** Set for plain paths, null for content URIs. The fields below are for content URIs only. */
    private final File file;
    private final Uri documentUri;
    /** The granted tree that holds the document, or null. */
    private final Uri treeUri;
    /** Null when the content URI is not a document, for example a MediaStore URI. */
    private final String documentId;
    private final String name;
    private final boolean folder;

    private FileWrapper(File file) {
        this.uri = file.getPath();
        this.file = file;
        this.documentUri = null;
        this.treeUri = null;
        this.documentId = null;
        this.name = null;
        this.folder = false;
    }

    private FileWrapper(String uri, Uri documentUri, Uri treeUri, String documentId, String name, boolean folder) {
        this.uri = uri;
        this.file = null;
        this.documentUri = documentUri;
        this.treeUri = treeUri;
        this.documentId = documentId;
        this.name = name;
        this.folder = folder;
    }

    /** Keeps the application context. Content URIs need it; plain paths work without it. */
    public static void init(Context context) {
        Context application = context.getApplicationContext();
        appContext = application != null ? application : context;
    }

    /** Returns a wrapper for an existing path or content URI, or null if it does not exist or is not readable. */
    public static FileWrapper getFromUri(String uriOrPath) {
        if (uriOrPath == null || uriOrPath.isEmpty()) {
            return null;
        }
        if (isPath(uriOrPath)) {
            File file = new File(uriOrPath);
            return file.exists() ? new FileWrapper(file) : null;
        }
        return fromContentUri(uriOrPath);
    }

    /** True for a plain filesystem path, false for a content:// URI. */
    public static boolean isPath(String uriOrPath) {
        return uriOrPath != null && !uriOrPath.startsWith(CONTENT_SCHEME);
    }

    public String getUri() {
        return uri;
    }

    public String getName() {
        return file != null ? file.getName() : name;
    }

    public boolean isFolder() {
        return file != null ? file.isDirectory() : folder;
    }

    /** Filesystem path of the element, or null when the storage provider does not expose one. */
    public String getPath() {
        if (file != null) {
            return file.getPath();
        }
        return documentId != null ? resolvePath(documentUri.getAuthority(), documentId) : null;
    }

    /**
     * Opens the file and returns a descriptor that the caller owns and closes.
     * Read-only, or read-write without truncation when {@code write} is true.
     * Returns -2 when access is denied and -1 on other errors.
     */
    public Integer getFileDescriptor(boolean write) {
        try {
            ParcelFileDescriptor descriptor;
            if (file != null) {
                descriptor = ParcelFileDescriptor.open(file, write ? MODE_WRITE : ParcelFileDescriptor.MODE_READ_ONLY);
            } else {
                ContentResolver resolver = resolver();
                if (resolver == null) {
                    return FD_ERROR;
                }
                descriptor = resolver.openFileDescriptor(documentUri, write ? SAF_MODE_WRITE : SAF_MODE_READ);
            }
            return descriptor != null ? descriptor.detachFd() : FD_ERROR;
        } catch (SecurityException e) {
            warn("getFileDescriptor", e);
            return FD_ACCESS_DENIED;
        } catch (FileNotFoundException e) {
            warn("getFileDescriptor", e);
            String message = e.getMessage();
            boolean denied = message != null && (message.contains("EACCES") || message.contains("EPERM"));
            return denied ? FD_ACCESS_DENIED : FD_ERROR;
        } catch (RuntimeException e) {
            warn("getFileDescriptor", e);
            return FD_ERROR;
        }
    }

    /** Paths or URIs of the children, or null if this is not a readable folder. */
    public List<String> getChildrenUris() {
        if (file != null) {
            String[] names = file.list();
            if (names == null) {
                return null;
            }
            List<String> uris = new ArrayList<>(names.length);
            for (String childName : names) {
                uris.add(new File(file, childName).getPath());
            }
            return uris;
        }
        List<ChildMetadata> children = queryChildren();
        if (children == null) {
            return null;
        }
        List<String> uris = new ArrayList<>(children.size());
        for (ChildMetadata child : children) {
            uris.add(child.uri);
        }
        return uris;
    }

    /** All children with their metadata, from one directory read or one provider query. Null on failure. */
    public List<ChildMetadata> getChildrenWithMetadata() {
        return file != null ? listFiles() : queryChildren();
    }

    /** Path or URI of the child with this exact name, or null if there is none. */
    public String getChildByName(String childName) {
        if (!isValidName(childName)) {
            return null;
        }
        FileWrapper child = findChild(childName);
        return child != null ? child.uri : null;
    }

    /**
     * Creates a child file or folder and returns it. An existing child of the same type is
     * returned as it is. Returns null on failure.
     */
    public FileWrapper createChildFile(String childName, boolean isFolder) {
        if (!isValidName(childName)) {
            return null;
        }
        if (file == null) {
            // The provider would create "name (1)" next to an existing child.
            FileWrapper existing = findChild(childName);
            if (existing != null) {
                return existing.folder == isFolder ? existing : null;
            }
        }
        return createChild(childName, isFolder);
    }

    /**
     * Walks down the names from this folder and returns the path or URI of the last element.
     * When {@code create} is true, missing elements are created: folders, except the last one,
     * which is a folder only when {@code lastIsFolder} is true. When {@code create} is false,
     * a missing element gives null.
     */
    public String createNestedPath(List<String> names, boolean create, boolean lastIsFolder) {
        if (names == null) {
            return null;
        }
        FileWrapper current = this;
        for (int i = 0; i < names.size(); i++) {
            String childName = names.get(i);
            if (!isValidName(childName)) {
                return null;
            }
            FileWrapper next = current.findChild(childName);
            if (next == null) {
                if (!create) {
                    return null;
                }
                next = current.createChild(childName, i < names.size() - 1 || lastIsFolder);
                if (next == null) {
                    return null;
                }
            }
            current = next;
        }
        return current.uri;
    }

    /** The parent folder, or null at the filesystem root or at the top of the granted tree. */
    public FileWrapper getParentFile() {
        if (file != null) {
            File parent = file.getAbsoluteFile().getParentFile();
            return parent != null && parent.exists() ? new FileWrapper(parent) : null;
        }
        if (treeUri == null || documentId.equals(DocumentsContract.getTreeDocumentId(treeUri))) {
            return null;
        }
        ContentResolver resolver = resolver();
        if (resolver == null) {
            return null;
        }
        try {
            DocumentsContract.Path path = DocumentsContract.findDocumentPath(resolver, documentUri);
            List<String> ids = path != null ? path.getPath() : null;
            if (ids == null || ids.size() < 2) {
                return null;
            }
            return fromContentUri(DocumentsContract.buildDocumentUriUsingTree(treeUri, ids.get(ids.size() - 2)).toString());
        } catch (FileNotFoundException | RuntimeException e) {
            warn("getParentFile", e);
            return null;
        }
    }

    /** Deletes a file. Returns false for folders. */
    public boolean deleteFile() {
        if (file != null) {
            return !file.isDirectory() && file.delete();
        }
        return !folder && deleteDocument();
    }

    /** Deletes the folder only if it is empty. Returns false for files and for folders with content. */
    public boolean deleteFolderIfEmpty() {
        if (file != null) {
            return file.isDirectory() && file.delete();
        }
        if (!folder) {
            return false;
        }
        List<ChildMetadata> children = queryChildren();
        return children != null && children.isEmpty() && deleteDocument();
    }

    /**
     * Renames the element inside its folder and returns the renamed element, or null on failure.
     * {@code parentUri} is the folder that holds it. An existing element named {@code newName}
     * makes the rename fail, unless {@code overwrite} is true and that element is a file.
     */
    public FileWrapper renameOverwrite(String parentUri, String newName, boolean overwrite) {
        if (!isValidName(newName)) {
            return null;
        }
        if (file != null) {
            File parent = file.getAbsoluteFile().getParentFile();
            if (parent == null) {
                return null;
            }
            File target = new File(parent, newName);
            if (!overwrite && target.exists() && !target.equals(file)) {
                return null;
            }
            if (file.renameTo(target)) {
                return new FileWrapper(target);
            }
            Log.w(TAG, "renameOverwrite failed");
            return null;
        }
        if (documentId == null) {
            return null;
        }
        if (newName.equals(name)) {
            return this;
        }
        FileWrapper parent = getFromUri(parentUri);
        if (parent == null) {
            return null;
        }
        FileWrapper existing = parent.findChild(newName);
        // A provider cannot replace a document in one step, so the old file goes first.
        if (existing != null && (!overwrite || existing.folder || !existing.deleteDocument())) {
            return null;
        }
        ContentResolver resolver = resolver();
        if (resolver == null) {
            return null;
        }
        try {
            Uri renamed = DocumentsContract.renameDocument(resolver, documentUri, newName);
            return renamed != null ? fromContentUri(renamed.toString()) : null;
        } catch (FileNotFoundException | RuntimeException e) {
            warn("renameOverwrite", e);
            return null;
        }
    }

    /**
     * Moves the element, keeping its name, from the folder {@code sourceParentUri} to the folder
     * {@code targetParentUri}. Returns the moved element, or null on failure or if the target
     * folder already holds that name.
     */
    public FileWrapper moveDocument(String sourceParentUri, String targetParentUri) {
        if (file != null) {
            if (!isPath(targetParentUri)) {
                return null;
            }
            File target = new File(targetParentUri, file.getName());
            if (target.exists()) {
                return null;
            }
            return file.renameTo(target) ? new FileWrapper(target) : null;
        }
        Uri sourceParent = toDocumentUri(sourceParentUri);
        Uri targetParent = toDocumentUri(targetParentUri);
        ContentResolver resolver = resolver();
        if (documentId == null || sourceParent == null || targetParent == null || resolver == null) {
            return null;
        }
        try {
            Uri moved = DocumentsContract.moveDocument(resolver, documentUri, sourceParent, targetParent);
            return moved != null ? fromContentUri(moved.toString()) : null;
        } catch (FileNotFoundException | RuntimeException e) {
            warn("moveDocument", e);
            return null;
        }
    }

    private FileWrapper findChild(String childName) {
        if (file != null) {
            File child = new File(file, childName);
            return child.exists() ? new FileWrapper(child) : null;
        }
        List<ChildMetadata> children = queryChildren();
        if (children == null) {
            return null;
        }
        for (ChildMetadata child : children) {
            if (child.name.equals(childName)) {
                Uri childUri = Uri.parse(child.uri);
                return new FileWrapper(child.uri, childUri, treeUri, DocumentsContract.getDocumentId(childUri),
                        child.name, child.isFolder);
            }
        }
        return null;
    }

    private FileWrapper createChild(String childName, boolean isFolder) {
        if (file != null) {
            File child = new File(file, childName);
            try {
                boolean created = isFolder
                        ? child.mkdir() || child.isDirectory()
                        : child.createNewFile() || child.isFile();
                if (created) {
                    return new FileWrapper(child);
                }
                Log.w(TAG, "createChildFile failed");
            } catch (IOException | RuntimeException e) {
                warn("createChildFile", e);
            }
            return null;
        }
        ContentResolver resolver = resolver();
        if (resolver == null || documentId == null) {
            return null;
        }
        try {
            Uri created = DocumentsContract.createDocument(resolver, documentUri,
                    isFolder ? Document.MIME_TYPE_DIR : MIME_TYPE_FILE, childName);
            if (created == null) {
                Log.w(TAG, "createChildFile failed");
                return null;
            }
            FileWrapper child = fromContentUri(created.toString());
            if (child != null && !childName.equals(child.name)) {
                Log.w(TAG, "createChildFile: the provider changed the name");
            }
            return child;
        } catch (FileNotFoundException | RuntimeException e) {
            warn("createChildFile", e);
            return null;
        }
    }

    private List<ChildMetadata> listFiles() {
        String[] names = file.list();
        if (names == null) {
            return null;
        }
        List<ChildMetadata> children = new ArrayList<>(names.length);
        for (String childName : names) {
            File child = new File(file, childName);
            String path = child.getPath();
            try {
                // One stat call per child.
                BasicFileAttributes attributes = Files.readAttributes(child.toPath(), BasicFileAttributes.class);
                boolean isFolder = attributes.isDirectory();
                children.add(new ChildMetadata(path, childName, isFolder, isFolder ? 0 : attributes.size(),
                        attributes.lastModifiedTime().toMillis(), path));
            } catch (IOException | RuntimeException e) {
                // Dangling link, or removed since the listing.
                children.add(new ChildMetadata(path, childName, false, 0, 0, path));
            }
        }
        return children;
    }

    /** Children of a folder in a granted tree, from one provider query. Null on failure. */
    private List<ChildMetadata> queryChildren() {
        if (treeUri == null || !folder) {
            return null;
        }
        ContentResolver resolver = resolver();
        if (resolver == null) {
            return null;
        }
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId);
        String authority = treeUri.getAuthority();
        try (Cursor cursor = resolver.query(childrenUri, CHILD_COLUMNS, null, null, null)) {
            if (cursor == null) {
                Log.w(TAG, "getChildren: no result from the provider");
                return null;
            }
            List<ChildMetadata> children = new ArrayList<>(cursor.getCount());
            while (cursor.moveToNext()) {
                String id = cursor.getString(0);
                String childName = cursor.getString(1);
                if (id == null || childName == null || childName.isEmpty()) {
                    continue;
                }
                boolean isFolder = Document.MIME_TYPE_DIR.equals(cursor.getString(2));
                long size = isFolder || cursor.isNull(3) ? 0 : cursor.getLong(3);
                long lastModified = cursor.isNull(4) ? 0 : cursor.getLong(4);
                String path = resolvePath(authority, id);
                children.add(new ChildMetadata(DocumentsContract.buildDocumentUriUsingTree(treeUri, id).toString(),
                        childName, isFolder, size, lastModified, path != null ? path : ""));
            }
            return children;
        } catch (RuntimeException e) {
            warn("getChildren", e);
            return null;
        }
    }

    private boolean deleteDocument() {
        ContentResolver resolver = resolver();
        if (resolver == null || documentId == null) {
            return false;
        }
        try {
            return DocumentsContract.deleteDocument(resolver, documentUri);
        } catch (FileNotFoundException | RuntimeException e) {
            warn("delete", e);
            return false;
        }
    }

    /** Wrapper for a content URI that exists, after one provider query. */
    private static FileWrapper fromContentUri(String uriString) {
        Uri uri = Uri.parse(uriString);
        Uri documentUri = uri;
        Uri tree = null;
        String documentId = null;
        try {
            List<String> segments = uri.getPathSegments();
            if (DocumentsContract.isTreeUri(uri)) {
                String treeId = DocumentsContract.getTreeDocumentId(uri);
                tree = DocumentsContract.buildTreeDocumentUri(uri.getAuthority(), treeId);
                documentId = segments.size() > 2 ? DocumentsContract.getDocumentId(uri) : treeId;
                documentUri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId);
            } else if (segments.size() == 2 && "document".equals(segments.get(0))) {
                documentId = segments.get(1);
            }
        } catch (IllegalArgumentException e) {
            warn("getFromUri", e);
            return null;
        }
        ContentResolver resolver = resolver();
        if (resolver == null) {
            return null;
        }
        String[] columns = documentId != null ? DOCUMENT_COLUMNS : OPENABLE_COLUMNS;
        try (Cursor cursor = resolver.query(documentUri, columns, null, null, null)) {
            // The provider gives no row, or no cursor, for a document that does not exist.
            if (cursor == null || !cursor.moveToFirst()) {
                return null;
            }
            String displayName = cursor.getString(0);
            boolean isFolder = documentId != null && Document.MIME_TYPE_DIR.equals(cursor.getString(1));
            return new FileWrapper(uriString, documentUri, tree, documentId,
                    displayName != null ? displayName : uri.getLastPathSegment(), isFolder);
        } catch (RuntimeException e) {
            warn("getFromUri", e);
            return null;
        }
    }

    /** Document URI for a folder given as a document or tree URI, or null. */
    private static Uri toDocumentUri(String uriString) {
        if (uriString == null || isPath(uriString)) {
            return null;
        }
        Uri uri = Uri.parse(uriString);
        try {
            if (DocumentsContract.isTreeUri(uri) && uri.getPathSegments().size() == 2) {
                return DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri));
            }
            return uri;
        } catch (IllegalArgumentException e) {
            warn("toDocumentUri", e);
            return null;
        }
    }

    /** Filesystem path of a document of the local storage providers, or null. */
    private static String resolvePath(String authority, String documentId) {
        if (DOWNLOADS_AUTHORITY.equals(authority)) {
            return documentId.startsWith("raw:") ? documentId.substring(4) : null;
        }
        if (!EXTERNAL_STORAGE_AUTHORITY.equals(authority)) {
            return null;
        }
        // Document IDs are "<root>:<path relative to the root>".
        int colon = documentId.indexOf(':');
        if (colon < 0) {
            return null;
        }
        String root = rootDirectory(documentId.substring(0, colon));
        if (root == null) {
            return null;
        }
        String relative = documentId.substring(colon + 1);
        return relative.isEmpty() ? root : root + "/" + relative;
    }

    private static String rootDirectory(String rootId) {
        String directory = rootDirectories.get(rootId);
        if (directory != null) {
            return directory;
        }
        if ("primary".equals(rootId)) {
            directory = Environment.getExternalStorageDirectory().getPath();
        } else if ("home".equals(rootId)) {
            directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).getPath();
        } else {
            Context context = appContext;
            StorageManager storage = context != null ? context.getSystemService(StorageManager.class) : null;
            if (storage != null) {
                for (StorageVolume volume : storage.getStorageVolumes()) {
                    File volumeDirectory = volume.getDirectory();
                    if (rootId.equalsIgnoreCase(volume.getUuid()) && volumeDirectory != null) {
                        directory = volumeDirectory.getPath();
                        break;
                    }
                }
            }
        }
        if (directory != null) {
            rootDirectories.put(rootId, directory);
        }
        return directory;
    }

    private static boolean isValidName(String name) {
        return name != null && !name.isEmpty() && !name.equals(".") && !name.equals("..") && name.indexOf('/') < 0;
    }

    private static ContentResolver resolver() {
        Context context = appContext;
        if (context == null) {
            Log.w(TAG, "FileWrapper.init() was not called: content URIs are not available");
            return null;
        }
        return context.getContentResolver();
    }

    private static void warn(String operation, Exception e) {
        Log.w(TAG, operation + " failed: " + e);
    }
}
