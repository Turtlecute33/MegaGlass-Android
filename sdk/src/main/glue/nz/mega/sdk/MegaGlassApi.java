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

    /** Transfer file data over HTTPS only. The data is end-to-end encrypted either way. */
    public void useHttpsOnly(boolean httpsOnly) {
        megaApi.useHttpsOnly(httpsOnly);
    }

    public boolean usingHttpsOnly() {
        return megaApi.usingHttpsOnly();
    }

    /** Parallel connections per transfer for one direction (MegaTransfer.TYPE_DOWNLOAD or TYPE_UPLOAD). */
    public void setMaxConnections(int direction, int connections) {
        megaApi.setMaxConnections(direction, connections);
    }

    /** These SDK operations have no per-request listener overload in MegaApiJava. */
    public void setSyncState(long backupId, MegaSync.SyncRunningState state,
                             MegaRequestListenerInterface listener) {
        megaApi.setSyncRunState(backupId, state, requestListener(listener));
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
