package nz.mega.sdk;

/**
 * MegaApiJava with the Android graphics processor (thumbnails and previews for uploads).
 * Unlike MegaApiAndroid, listener callbacks run on the SDK thread and not on the main thread,
 * so the app can process them without blocking the UI.
 * Also exposes some MegaApi calls that MegaApiJava does not wrap.
 */
public class MegaGlassApi extends MegaApiJava {
    private final java.util.Map<MegaRequestListenerInterface, DelegateMegaRequestListener> syncListeners =
            new java.util.concurrent.ConcurrentHashMap<>();
    public MegaGlassApi(String appKey, String userAgent, String basePath) {
        super(appKey, userAgent, basePath, new AndroidGfxProcessor());
    }

    /** Parallel connections per transfer for one direction (MegaTransfer.TYPE_DOWNLOAD or TYPE_UPLOAD). */
    public void setMaxConnections(int direction, int connections) {
        megaApi.setMaxConnections(direction, connections);
    }

    /** Receives each sync that the SDK adds or loads. */
    public interface SyncAddedListener {
        void onSyncAdded(MegaSync sync);
    }

    /** The SDK keeps only a weak reference to a listener object. */
    private MegaListener syncAdded;

    /**
     * MegaApiJava does not forward onSyncAdded. The SDK loads the saved syncs when the nodes are
     * fetched and resumes them later, when the nodes are current; {@code listener} runs between
     * the two, on the SDK thread. The sync object is valid only during the call.
     */
    public void setSyncAddedListener(SyncAddedListener listener) {
        MegaListener forwarding = new MegaListener() {
            @Override
            public void onSyncAdded(MegaApi api, MegaSync sync) {
                if (sync == null) return;
                try {
                    listener.onSyncAdded(sync);
                } catch (RuntimeException e) {
                    android.util.Log.w("MegaGlassApi", "onSyncAdded failed: " + e.getClass().getSimpleName());
                }
            }
        };
        syncAdded = forwarding;
        megaApi.addListener(forwarding);
    }

    /** These SDK operations have no per-request listener overload in MegaApiJava. */
    public void setSyncState(long backupId, MegaSync.SyncRunningState state,
                             MegaRequestListenerInterface listener) {
        megaApi.setSyncRunState(backupId, state, requestListener(listener));
    }

    /** Same as above, without waiting for the result. */
    public void setSyncState(long backupId, MegaSync.SyncRunningState state) {
        megaApi.setSyncRunState(backupId, state);
    }

    public void removeSync(long backupId, MegaRequestListenerInterface listener) {
        megaApi.removeSync(backupId, requestListener(listener));
    }

    private MegaRequestListener requestListener(MegaRequestListenerInterface listener) {
        // The standard bridge copies JNI callback objects before delivering them.
        // MegaApiJava's registration helper is private, so retain these two overloads here.
        MegaRequestListenerInterface forwarding = new MegaRequestListenerInterface() {
            public void onRequestStart(MegaApiJava api, MegaRequest request) {
                listener.onRequestStart(api, request);
            }
            public void onRequestUpdate(MegaApiJava api, MegaRequest request) {
                listener.onRequestUpdate(api, request);
            }
            public void onRequestTemporaryError(MegaApiJava api, MegaRequest request, MegaError error) {
                listener.onRequestTemporaryError(api, request, error);
            }
            public void onRequestFinish(MegaApiJava api, MegaRequest request, MegaError error) {
                try {
                    listener.onRequestFinish(api, request, error);
                } finally {
                    syncListeners.remove(listener);
                }
            }
        };
        DelegateMegaRequestListener delegate = new DelegateMegaRequestListener(this, forwarding, true);
        syncListeners.put(listener, delegate);
        return delegate;
    }

    @Override
    public void removeRequestListener(MegaRequestListenerInterface listener) {
        super.removeRequestListener(listener);
        DelegateMegaRequestListener delegate = syncListeners.remove(listener);
        if (delegate != null) megaApi.removeRequestListener(delegate);
    }
}
